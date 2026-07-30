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
internal fun win32KeyEvent(message: UInt, wParam: ULong, lParam: Long): KeyEvent {
    return KeyEvent(
        nativeKeyEvent = InternalKeyEvent(
            key = if (message == WM_CHAR) Key.Unknown else keyOf(wParam, lParam),
            type = when (message) {
                WM_KEYDOWN, WM_SYSKEYDOWN, WM_CHAR -> KeyEventType.KeyDown
                WM_KEYUP, WM_SYSKEYUP -> KeyEventType.KeyUp
                else -> KeyEventType.Unknown
            },
            // TODO: Support surrogate pairs (WM_CHAR delivers UTF-16 units)
            codePoint = if (message == WM_CHAR) wParam.toInt() else 0,
            modifiers = win32KeyboardModifiers(),
            nativeEvent = Win32Event(message, wParam, lParam)
        )
    )
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
