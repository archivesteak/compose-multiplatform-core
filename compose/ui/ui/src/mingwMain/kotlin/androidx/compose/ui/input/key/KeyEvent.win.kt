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

import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.win32.Win32Event
import kotlinx.cinterop.ExperimentalForeignApi
import platform.windows.GetKeyState

private const val WM_KEYDOWN = 0x0100u
private const val WM_KEYUP = 0x0101u
private const val WM_SYSKEYDOWN = 0x0104u
private const val WM_SYSKEYUP = 0x0105u
private const val WM_CHAR = 0x0102u

private const val VK_SHIFT = 0x10
private const val VK_CONTROL = 0x11
private const val VK_MENU = 0x12
private const val VK_LWIN = 0x5B
private const val VK_RWIN = 0x5C

private fun isKeyDown(vk: Int) = GetKeyState(vk) < 0

/**
 * Builds a [KeyEvent] from a Win32 keyboard message (WM_KEYDOWN/WM_KEYUP/
 * WM_SYSKEYDOWN/WM_SYSKEYUP/WM_CHAR). [wParam] carries the virtual-key code
 * (or the UTF-16 code unit for WM_CHAR).
 */
internal fun win32KeyEvent(message: UInt, wParam: ULong, lParam: Long): KeyEvent {
    return KeyEvent(
        nativeKeyEvent = InternalKeyEvent(
            key = if (message == WM_CHAR) Key.Unknown else Key(wParam.toLong()),
            type = when (message) {
                WM_KEYDOWN, WM_SYSKEYDOWN, WM_CHAR -> KeyEventType.KeyDown
                WM_KEYUP, WM_SYSKEYUP -> KeyEventType.KeyUp
                else -> KeyEventType.Unknown
            },
            // TODO: Support surrogate pairs (WM_CHAR delivers UTF-16 units)
            codePoint = if (message == WM_CHAR) wParam.toInt() else 0,
            modifiers = PointerKeyboardModifiers(
                isAltPressed = isKeyDown(VK_MENU),
                isShiftPressed = isKeyDown(VK_SHIFT),
                isCtrlPressed = isKeyDown(VK_CONTROL),
                isMetaPressed = isKeyDown(VK_LWIN) || isKeyDown(VK_RWIN),
            ),
            nativeEvent = Win32Event(message, wParam, lParam)
        )
    )
}
