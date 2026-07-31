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

package androidx.compose.ui.input.key

import androidx.compose.ui.win32.Win32Event
import androidx.compose.ui.win32.win32KeyboardModifiers
import kotlinx.cinterop.ExperimentalForeignApi
import platform.windows.VK_DELETE
import platform.windows.VK_DOWN
import platform.windows.VK_END
import platform.windows.VK_HOME
import platform.windows.VK_INSERT
import platform.windows.VK_LEFT
import platform.windows.VK_NEXT
import platform.windows.VK_PRIOR
import platform.windows.VK_RIGHT
import platform.windows.VK_UP

private const val WM_KEYDOWN = 0x0100u
private const val WM_KEYUP = 0x0101u
private const val WM_SYSKEYDOWN = 0x0104u
private const val WM_SYSKEYUP = 0x0105u
private const val WM_CHAR = 0x0102u

/** `KF_EXTENDED`, in the high word of a keyboard message's `lParam`. */
private const val EXTENDED_KEY_MASK = 0x01000000L

/**
 * Builds a [KeyEvent] from a Win32 keyboard message (WM_KEYDOWN/WM_KEYUP/
 * WM_SYSKEYDOWN/WM_SYSKEYUP/WM_CHAR). [wParam] carries the virtual-key code
 * (or the UTF-16 code unit for WM_CHAR).
 */
internal fun win32KeyEvent(message: UInt, wParam: ULong, lParam: Long): KeyEvent? {
    var codePoint = 0
    if (message == WM_CHAR) {
        codePoint = codePointOf(wParam.toInt()) ?: return null
    }
    return KeyEvent(
        nativeKeyEvent = InternalKeyEvent(
            key = if (message == WM_CHAR) Key.Unknown else keyOf(wParam, lParam),
            type = when (message) {
                WM_KEYDOWN, WM_SYSKEYDOWN, WM_CHAR -> KeyEventType.KeyDown
                WM_KEYUP, WM_SYSKEYUP -> KeyEventType.KeyUp
                else -> KeyEventType.Unknown
            },
            codePoint = codePoint,
            modifiers = win32KeyboardModifiers(),
            nativeEvent = Win32Event(message, wParam, lParam)
        )
    )
}

/**
 * The high half of a surrogate pair, waiting for its low half.
 *
 * `WM_CHAR` carries UTF-16 code units, so anything outside the BMP — emoji, most of CJK Ext B,
 * historic scripts — arrives as two messages. Held between them; the window procedure is
 * single-threaded, so no synchronisation is needed.
 */
private var pendingHighSurrogate: Int? = null

/**
 * Turns a `WM_CHAR` code unit into a code point, or null when there is nothing to deliver yet.
 *
 * A lone high surrogate is stored and swallowed: emitting it on its own would hand Compose half a
 * character. The following low surrogate combines with it into the real code point. A low surrogate
 * without a stored high one is malformed input and is dropped rather than passed on.
 */
private fun codePointOf(unit: Int): Int? {
    val pending = pendingHighSurrogate
    if (unit in 0xD800..0xDBFF) {
        pendingHighSurrogate = unit
        return null
    }
    pendingHighSurrogate = null
    if (unit in 0xDC00..0xDFFF) {
        if (pending == null) return null
        return 0x10000 + ((pending - 0xD800) shl 10) + (unit - 0xDC00)
    }
    return unit
}

/**
 * With NumLock off the numeric keypad acts as a second navigation block, and Win32 reports it with
 * the virtual keys of the dedicated one. The extended-key flag is what tells them apart: it is set
 * for the dedicated block and clear for the keypad.
 */
private fun keyOf(wParam: ULong, lParam: Long): Key {
    val virtualKey = wParam.toLong()
    if (lParam and EXTENDED_KEY_MASK != 0L) return Key(virtualKey)
    return when (virtualKey.toInt()) {
        VK_UP -> Key.NumPadDirectionUp
        VK_DOWN -> Key.NumPadDirectionDown
        VK_LEFT -> Key.NumPadDirectionLeft
        VK_RIGHT -> Key.NumPadDirectionRight
        VK_HOME -> Key.NumPadMoveHome
        VK_END -> Key.NumPadMoveEnd
        VK_PRIOR -> Key.NumPadPageUp
        VK_NEXT -> Key.NumPadPageDown
        VK_INSERT -> Key.NumPadInsert
        VK_DELETE -> Key.NumPadDelete
        else -> Key(virtualKey)
    }
}
