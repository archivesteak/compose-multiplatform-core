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
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.cinterop.value
import platform.windows.CloseClipboard
import platform.windows.EmptyClipboard
import platform.windows.GetClipboardData
import platform.windows.GlobalAlloc
import platform.windows.GlobalLock
import platform.windows.GlobalSize
import platform.windows.GlobalUnlock
import platform.windows.OpenClipboard
import platform.windows.SetClipboardData
import platform.windows.WCHARVar

private const val CF_UNICODETEXT = 13u
private const val GMEM_MOVEABLE = 0x2002u

/** Win32 system clipboard, accessed through the OpenClipboard sequence. */
actual class NativeClipboard {
    fun getText(): String? {
        if (OpenClipboard(null) == 0) return null
        try {
            val handle = GetClipboardData(CF_UNICODETEXT) ?: return null
            val ptr = GlobalLock(handle) ?: return null
            try {
                return (ptr as CPointer<WCHARVar>).toKStringFromUtf16()
            } finally {
                GlobalUnlock(handle)
            }
        } finally {
            CloseClipboard()
        }
    }

    fun setText(text: String?) {
        if (OpenClipboard(null) == 0) return
        try {
            EmptyClipboard()
            if (text == null) return
            val chars = text.length + 1
            val handle = GlobalAlloc(GMEM_MOVEABLE, (chars * 2).convert()) ?: return
            val ptr = GlobalLock(handle)
            if (ptr != null) {
                val wstr = ptr as CPointer<WCHARVar>
                for (i in text.indices) {
                    wstr[i] = text[i].code.toUShort()
                }
                wstr[text.length] = 0u
                GlobalUnlock(handle)
                // Ownership of `handle` passes to the system on success.
                if (SetClipboardData(CF_UNICODETEXT, handle) == null) {
                    // Failed: reclaim and free would go here; GlobalFree is
                    // omitted only if ownership transferred.
                }
            }
        } finally {
            CloseClipboard()
        }
    }
}

internal class Win32PlatformClipboard : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? {
        val str = nativeClipboard.getText()
        if (str.isNullOrEmpty()) return null
        return ClipEntry.withPlainText(str)
    }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        nativeClipboard.setText(clipEntry?.plainText)
    }

    override val nativeClipboard: NativeClipboard
        get() = NativeClipboard()
}

internal actual fun createPlatformClipboard(): Clipboard {
    return Win32PlatformClipboard()
}

actual class ClipEntry internal constructor() {
    // TODO https://youtrack.jetbrains.com/issue/CMP-1260/ClipboardManager.-Implement-getClip-getClipMetadata-setClip
    actual val clipMetadata: ClipMetadata
        get() = TODO("ClipMetadata is not implemented. Consider using nativeClipboard")

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
