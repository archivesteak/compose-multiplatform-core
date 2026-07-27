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

import androidx.compose.ui.input.pointer.PointerEvent

/**
 * Native Win32 message backing a Compose [PointerEvent] or
 * [androidx.compose.ui.input.key.KeyEvent].
 */
class Win32Event(
    val message: UInt,
    val wParam: ULong,
    val lParam: Long,
)

val PointerEvent.win32EventOrNull: Win32Event?
    get() = nativeEvent as? Win32Event
