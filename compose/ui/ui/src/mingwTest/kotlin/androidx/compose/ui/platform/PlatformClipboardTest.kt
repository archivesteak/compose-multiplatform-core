/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package androidx.compose.ui.platform

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.win32.isWindowPerMonitorAwareV2
import androidx.compose.ui.win32.readBoundedNullTerminatedUtf16
import androidx.compose.ui.window.OleApartment
import androidx.compose.ui.window.Window
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import platform.windows.CF_BITMAP
import platform.windows.CF_ENHMETAFILE
import platform.windows.CF_METAFILEPICT
import platform.windows.CF_PALETTE
import platform.windows.CloseClipboard
import platform.windows.CreateWindowExW
import platform.windows.DeleteEnhMetaFile
import platform.windows.DeleteMetaFile
import platform.windows.DeleteObject
import platform.windows.DestroyWindow
import platform.windows.EmptyClipboard
import platform.windows.EnumClipboardFormats
import platform.windows.GetClipboardData
import platform.windows.GetLastError
import platform.windows.GetModuleHandleW
import platform.windows.GlobalFree
import platform.windows.GlobalLock
import platform.windows.GlobalUnlock
import platform.windows.METAFILEPICT
import platform.windows.OleDuplicateData
import platform.windows.OpenClipboard
import platform.windows.PostMessageW
import platform.windows.SetClipboardData
import platform.windows.SetLastError
import platform.windows.WCHARVar
import platform.windows.WM_CLOSE
import platform.windows.WS_POPUP

class PlatformClipboardTest {
    @Test
    fun boundedUnicodeReadRequiresTerminatorInsideAllocation() = memScoped {
        val characters = allocArray<WCHARVar>(3)
        characters[0] = 'A'.code.toUShort()
        characters[1] = 'Б'.code.toUShort()
        characters[2] = 0u

        assertEquals("AБ", characters.readBoundedNullTerminatedUtf16(3))
        assertNull(characters.readBoundedNullTerminatedUtf16(2))
        assertNull(characters.readBoundedNullTerminatedUtf16(0))
    }

    @Test
    fun releasesHandleWhenLockFails() {
        val events = mutableListOf<String>()

        val transferred = writeAndTransferOwnedHandle<String, String>(
            handle = "handle",
            lock = { events += "lock"; null },
            write = { events += "write" },
            unlock = { events += "unlock" },
            transfer = { events += "transfer"; true },
            release = { events += "release" },
        )

        assertFalse(transferred)
        assertEquals(listOf("lock", "release"), events)
    }

    @Test
    fun releasesHandleAndUnlocksWhenWriteFails() {
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            writeAndTransferOwnedHandle(
                handle = "handle",
                lock = { events += "lock"; "pointer" },
                write = { events += "write"; error("write failed") },
                unlock = { events += "unlock" },
                transfer = { events += "transfer"; true },
                release = { events += "release" },
            )
        }

        assertEquals(listOf("lock", "write", "unlock", "release"), events)
    }

    @Test
    fun releasesHandleWhenTransferFails() {
        val events = mutableListOf<String>()

        val transferred = writeAndTransferOwnedHandle(
            handle = "handle",
            lock = { events += "lock"; "pointer" },
            write = { events += "write" },
            unlock = { events += "unlock" },
            transfer = { events += "transfer"; false },
            release = { events += "release" },
        )

        assertFalse(transferred)
        assertEquals(listOf("lock", "write", "unlock", "transfer", "release"), events)
    }

    @Test
    fun successfulTransferDoesNotReleaseHandle() {
        val events = mutableListOf<String>()

        val transferred = writeAndTransferOwnedHandle(
            handle = "handle",
            lock = { events += "lock"; "pointer" },
            write = { events += "write" },
            unlock = { events += "unlock" },
            transfer = { events += "transfer"; true },
            release = { events += "release" },
        )

        assertTrue(transferred)
        assertEquals(listOf("lock", "write", "unlock", "transfer"), events)
    }

    @Test
    fun nativeClipboardRoundTripsUnicodeAndRestoresEveryOriginalFormat() = memScoped {
        val apartment = OleApartment.initialize()
        assertTrue(apartment.supportsOle, "native clipboard test requires an OLE-compatible STA")

        // OleGetClipboard may return an RPC-forwarding proxy owned by another process. Reusing that
        // proxy after EmptyClipboard is neither an independent snapshot nor safe restoration.
        // Duplicate every concrete clipboard handle before the test mutates anything instead.
        val original = ClipboardSnapshot.capture()
        try {
            val clipboard = NativeClipboard()
            val expected = "Compose clipboard — Кириллица — 😀"

            clipboard.setText(expected)

            assertEquals(expected, clipboard.getText())

            // This method deliberately writes before creating its first Compose window. The
            // clipboard's hidden owner must therefore initialize process DPI awareness early
            // enough for the real window to retain PerMonitorV2 behavior.
            var awareness: Boolean? = null
            var closePosted = false
            Window(title = "Clipboard DPI test", size = DpSize(64.dp, 64.dp)) {
                val hwnd = window
                SideEffect {
                    awareness = isWindowPerMonitorAwareV2(hwnd)
                    if (!closePosted) {
                        closePosted = true
                        check(PostMessageW(hwnd, WM_CLOSE.toUInt(), 0u, 0) != 0)
                    }
                }
            }
            assertEquals(true, awareness, "Compose HWND must use PerMonitorV2 awareness")
        } finally {
            try {
                original.restore()
                assertEquals(
                    original.formats,
                    enumerateClipboardFormats(),
                    "Every original clipboard format must be restored",
                )
            } finally {
                original.close()
                apartment.close()
            }
        }
    }
}

private const val GMEM_MOVEABLE = 0x0002u

/** An independently owned duplicate of every format present before a destructive clipboard test. */
private class ClipboardSnapshot private constructor(
    private val copies: List<ClipboardFormatCopy>,
) {
    val formats: Set<UInt> = copies.mapTo(linkedSetOf()) { it.format }

    fun restore() {
        val owner = checkNotNull(
            CreateWindowExW(
                0u,
                "STATIC",
                "Compose clipboard test restore owner",
                WS_POPUP,
                0,
                0,
                0,
                0,
                null,
                null,
                GetModuleHandleW(null),
                null,
            )
        ) { "Unable to create the clipboard restoration owner: ${GetLastError()}" }

        var failure: Throwable? = null
        try {
            check(OpenClipboard(owner) != 0) {
                "Unable to open the clipboard for restoration: ${GetLastError()}"
            }
            try {
                check(EmptyClipboard() != 0) {
                    "Unable to empty the clipboard for restoration: ${GetLastError()}"
                }
                copies.forEach { copy ->
                    try {
                        check(SetClipboardData(copy.format, copy.handle) != null) {
                            "Unable to restore clipboard format ${copy.format}: ${GetLastError()}"
                        }
                        copy.transferred = true
                    } catch (throwable: Throwable) {
                        val first = failure
                        if (first == null) failure = throwable else first.addSuppressed(throwable)
                    }
                }
            } finally {
                if (CloseClipboard() == 0) {
                    val closeFailure = IllegalStateException(
                        "Unable to close the clipboard after restoration: ${GetLastError()}"
                    )
                    val first = failure
                    if (first == null) failure = closeFailure else first.addSuppressed(closeFailure)
                }
            }
        } catch (throwable: Throwable) {
            val first = failure
            if (first == null) failure = throwable else first.addSuppressed(throwable)
        } finally {
            if (DestroyWindow(owner) == 0) {
                val destroyFailure = IllegalStateException(
                    "Unable to destroy the clipboard restoration owner: ${GetLastError()}"
                )
                val first = failure
                if (first == null) failure = destroyFailure else first.addSuppressed(destroyFailure)
            }
        }
        failure?.let { throw it }
    }

    fun close() {
        copies.filterNot { it.transferred }.forEach(ClipboardFormatCopy::release)
    }

    companion object {
        fun capture(): ClipboardSnapshot {
            check(OpenClipboard(null) != 0) {
                "Unable to open the clipboard for snapshot: ${GetLastError()}"
            }
            val copies = mutableListOf<ClipboardFormatCopy>()
            var failure: Throwable? = null
            try {
                enumerateClipboardFormatsWhileOpen().forEach { format ->
                    val source = checkNotNull(GetClipboardData(format)) {
                        "Unable to read clipboard format $format: ${GetLastError()}"
                    }
                    val duplicate = checkNotNull(
                        OleDuplicateData(source, format.toUShort(), GMEM_MOVEABLE)
                    ) { "Unable to duplicate clipboard format $format: ${GetLastError()}" }
                    copies += ClipboardFormatCopy(format, duplicate)
                }
            } catch (throwable: Throwable) {
                failure = throwable
            } finally {
                if (CloseClipboard() == 0) {
                    val closeFailure = IllegalStateException(
                        "Unable to close the clipboard after snapshot: ${GetLastError()}"
                    )
                    val first = failure
                    if (first == null) failure = closeFailure else first.addSuppressed(closeFailure)
                }
            }
            failure?.let {
                copies.forEach(ClipboardFormatCopy::release)
                throw it
            }
            return ClipboardSnapshot(copies)
        }
    }
}

private class ClipboardFormatCopy(
    val format: UInt,
    val handle: COpaquePointer,
) {
    var transferred = false

    fun release() {
        when (format) {
            CF_BITMAP.toUInt(), CF_PALETTE.toUInt() -> DeleteObject(handle)
            CF_ENHMETAFILE.toUInt() -> DeleteEnhMetaFile(handle.reinterpret())
            CF_METAFILEPICT.toUInt() -> {
                val pointer = GlobalLock(handle)
                if (pointer != null) {
                    try {
                        val metafile = pointer.reinterpret<METAFILEPICT>().pointed.hMF
                        if (metafile != null) DeleteMetaFile(metafile)
                    } finally {
                        GlobalUnlock(handle)
                    }
                }
                GlobalFree(handle)
            }
            else -> GlobalFree(handle)
        }
    }
}

private fun enumerateClipboardFormats(): Set<UInt> {
    check(OpenClipboard(null) != 0) {
        "Unable to open the clipboard for enumeration: ${GetLastError()}"
    }
    return try {
        enumerateClipboardFormatsWhileOpen()
    } finally {
        check(CloseClipboard() != 0) {
            "Unable to close the clipboard after enumeration: ${GetLastError()}"
        }
    }
}

private fun enumerateClipboardFormatsWhileOpen(): Set<UInt> {
    val formats = linkedSetOf<UInt>()
    var previous = 0u
    while (true) {
        SetLastError(0u)
        val format = EnumClipboardFormats(previous)
        if (format == 0u) {
            check(GetLastError() == 0u) {
                "Unable to enumerate clipboard formats: ${GetLastError()}"
            }
            return formats
        }
        check(formats.add(format)) { "Clipboard format enumeration repeated $format" }
        previous = format
    }
}
