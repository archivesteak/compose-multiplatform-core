/*
 * Copyright 2024 The Android Open Source Project
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

@file:OptIn(ExperimentalForeignApi::class)

package androidx.compose.ui.platform

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.win32.ensureProcessDpiAwareness
import androidx.compose.ui.win32.readBoundedNullTerminatedUtf16
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import platform.windows.CreateWindowExW
import platform.windows.CloseClipboard
import platform.windows.DestroyWindow
import platform.windows.EmptyClipboard
import platform.windows.GetClipboardData
import platform.windows.GetLastError
import platform.windows.GetModuleHandleW
import platform.windows.GlobalAlloc
import platform.windows.GlobalFree
import platform.windows.GlobalLock
import platform.windows.GlobalSize
import platform.windows.GlobalUnlock
import platform.windows.IsClipboardFormatAvailable
import platform.windows.OpenClipboard
import platform.windows.SetClipboardData
import platform.windows.WCHARVar
import platform.windows.WS_POPUP

private const val CF_UNICODETEXT = 13u
private const val CF_HDROP = 15u
private const val GMEM_MOVEABLE = 0x0002u

/**
 * Writes an owned handle and transfers it to another owner.
 *
 * The caller keeps ownership until [transfer] succeeds, so every earlier return and exception must
 * release the handle. Keeping that rule in a platform-independent state machine makes the Win32
 * ownership boundary directly testable without substituting native functions.
 */
internal fun <H, P : Any> writeAndTransferOwnedHandle(
    handle: H,
    lock: (H) -> P?,
    write: (P) -> Unit,
    unlock: (H) -> Unit,
    transfer: (H) -> Boolean,
    release: (H) -> Unit,
): Boolean {
    var transferred = false
    try {
        val pointer = lock(handle) ?: return false
        try {
            write(pointer)
        } finally {
            unlock(handle)
        }
        transferred = transfer(handle)
        return transferred
    } finally {
        if (!transferred) release(handle)
    }
}

/** Win32 system clipboard, accessed through the OpenClipboard sequence. */
actual class NativeClipboard {
    fun getText(): String? {
        if (OpenClipboard(null) == 0) return null
        try {
            val handle = GetClipboardData(CF_UNICODETEXT) ?: return null
            val byteSize = GlobalSize(handle).toLong()
            val characterSize = sizeOf<WCHARVar>()
            if (byteSize < characterSize || byteSize % characterSize != 0L) return null
            val ptr = GlobalLock(handle) ?: return null
            try {
                val maximum = minOf(
                    byteSize / characterSize,
                    Int.MAX_VALUE.toLong(),
                ).toInt()
                return ptr.reinterpret<WCHARVar>().readBoundedNullTerminatedUtf16(maximum)
            } finally {
                GlobalUnlock(handle)
            }
        } finally {
            CloseClipboard()
        }
    }

    fun setText(text: String?) {
        // Prepare the transferable block before EmptyClipboard. Allocation/locking/writing can all
        // fail without destroying the user's existing clipboard; only the final ownership transfer
        // is allowed to happen after it has been emptied.
        val handle = if (text == null) {
            null
        } else {
            val characters = text.length.toULong() + 1uL
            val byteCount = characters * sizeOf<WCHARVar>().toULong()
            GlobalAlloc(GMEM_MOVEABLE, byteCount) ?: return
        }

        // EmptyClipboard assigns ownership to the HWND supplied to OpenClipboard. Passing null
        // would leave the clipboard owner null, for which SetClipboardData is documented to fail.
        // A short-lived hidden window gives this operation a handle owned by our own thread; data
        // is rendered eagerly into HGLOBAL, so the HWND is no longer needed after CloseClipboard.
        ensureProcessDpiAwareness()
        val owner = CreateWindowExW(
            0u,
            "STATIC",
            "Compose clipboard owner",
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
        if (owner == null) {
            if (handle != null) GlobalFree(handle)
            return
        }
        try {
            if (text == null) {
                if (OpenClipboard(owner) == 0) return
                try {
                    EmptyClipboard()
                } finally {
                    CloseClipboard()
                }
            } else {
                writeAndTransferOwnedHandle(
                    handle = checkNotNull(handle),
                    lock = ::GlobalLock,
                    write = { pointer ->
                        val characters = pointer.reinterpret<WCHARVar>()
                        for (i in text.indices) characters[i] = text[i].code.toUShort()
                        characters[text.length] = 0u
                    },
                    unlock = { GlobalUnlock(it) },
                    // SetClipboardData owns the handle only when it returns a non-null value.
                    transfer = {
                        if (OpenClipboard(owner) == 0) {
                            false
                        } else {
                            try {
                                EmptyClipboard() != 0 && SetClipboardData(CF_UNICODETEXT, it) != null
                            } finally {
                                CloseClipboard()
                            }
                        }
                    },
                    release = { GlobalFree(it) },
                )
            }
        } finally {
            check(DestroyWindow(owner) != 0) {
                "DestroyWindow failed for the clipboard owner: ${GetLastError()}"
            }
        }
    }
}

@Suppress("DEPRECATION")
internal class Win32PlatformClipboard : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? {
        val str = nativeClipboard.getText()
        if (str.isNullOrEmpty()) return null
        return ClipEntry.withPlainText(str)
    }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        nativeClipboard.setText(clipEntry?.plainText)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override val nativeClipboard: NativeClipboard
        get() = NativeClipboard()
}

@Suppress("DEPRECATION")
private class Win32PlatformClipboardManager : ClipboardManager {
    private val clipboard = NativeClipboard()

    override fun getText(): AnnotatedString? = clipboard.getText()?.let(::AnnotatedString)

    override fun setText(annotatedString: AnnotatedString) {
        clipboard.setText(annotatedString.text)
    }

    override fun hasText(): Boolean = !clipboard.getText().isNullOrEmpty()

    override fun getClip(): ClipEntry? = clipboard.getText()?.let(ClipEntry::withPlainText)

    @Suppress("GetterSetterNames")
    override fun setClip(clipEntry: ClipEntry?) {
        clipboard.setText(clipEntry?.plainText)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override val nativeClipboard: NativeClipboard
        get() = clipboard
}

@Suppress("DEPRECATION")
internal actual fun createPlatformClipboardManager(): ClipboardManager {
    return Win32PlatformClipboardManager()
}

internal actual fun createPlatformClipboard(): Clipboard {
    return Win32PlatformClipboard()
}

actual class ClipEntry internal constructor() {
    /**
     * What the entry holds, without reading it. The formats come from the clipboard's own format
     * list via `IsClipboardFormatAvailable`, so asking costs nothing and reads nothing.
     */
    actual val clipMetadata: ClipMetadata
        get() {
            // A entry built in-process already knows what it carries.
            plainText?.let { return plainTextClipMetadata() }
            val mimeTypes = buildList {
                if (IsClipboardFormatAvailable(CF_UNICODETEXT) != 0) add(MimeTypeText)
                if (IsClipboardFormatAvailable(CF_HDROP) != 0) add(MimeTypeFileList)
            }
            return if (mimeTypes.isEmpty()) emptyClipMetadata() else ClipMetadata(mimeTypes)
        }

    internal var plainText: String? = null

    @ExperimentalComposeUiApi
    fun getPlainText(): String? = plainText

    companion object {
        @ExperimentalComposeUiApi
        fun withPlainText(text: String): ClipEntry = ClipEntry().apply {
            plainText = text
        }
    }
}
