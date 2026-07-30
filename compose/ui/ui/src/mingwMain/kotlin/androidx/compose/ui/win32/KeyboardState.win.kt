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

package androidx.compose.ui.win32

import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import platform.windows.GetKeyState
import platform.windows.VK_CAPITAL
import platform.windows.VK_CONTROL
import platform.windows.VK_LWIN
import platform.windows.VK_MENU
import platform.windows.VK_NUMLOCK
import platform.windows.VK_RWIN
import platform.windows.VK_SCROLL
import platform.windows.VK_SHIFT

/**
 * Win32 delivers modifier state out of band rather than on the message, so both keyboard and
 * pointer events read it from the thread's key state at the time the message is handled.
 */
internal fun win32KeyboardModifiers(): PointerKeyboardModifiers =
    PointerKeyboardModifiers(
        isCtrlPressed = isVirtualKeyDown(VK_CONTROL),
        isMetaPressed = isVirtualKeyDown(VK_LWIN) || isVirtualKeyDown(VK_RWIN),
        isAltPressed = isVirtualKeyDown(VK_MENU),
        isShiftPressed = isVirtualKeyDown(VK_SHIFT),
        isCapsLockOn = isVirtualKeyToggled(VK_CAPITAL),
        isScrollLockOn = isVirtualKeyToggled(VK_SCROLL),
        isNumLockOn = isVirtualKeyToggled(VK_NUMLOCK),
    )

/** `GetKeyState` reports "down" in the high-order bit, hence the sign test. */
internal fun isVirtualKeyDown(virtualKey: Int): Boolean = GetKeyState(virtualKey) < 0

/** Toggle state (caps/num/scroll lock) lives in the low-order bit. */
internal fun isVirtualKeyToggled(virtualKey: Int): Boolean =
    GetKeyState(virtualKey).toInt() and 1 != 0
