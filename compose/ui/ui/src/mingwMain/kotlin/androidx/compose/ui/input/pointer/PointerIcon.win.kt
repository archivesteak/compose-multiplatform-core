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

package androidx.compose.ui.input.pointer

import kotlinx.cinterop.CPointed
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toCPointer
import platform.windows.HCURSOR
import platform.windows.LoadCursorW

internal data class Win32Cursor(val cursor: HCURSOR?) : PointerIcon

// System cursor resource ids (IDC_*); loaded once from the system, not
// destroyed (shared cursors owned by the OS).
private fun systemCursor(id: Long): HCURSOR? = LoadCursorW(null, id.toCPointer<CPointed>())

private val arrowCursor by lazy { Win32Cursor(systemCursor(32512)) }      // IDC_ARROW
private val crosshairCursor by lazy { Win32Cursor(systemCursor(32515)) }  // IDC_CROSS
private val ibeamCursor by lazy { Win32Cursor(systemCursor(32513)) }      // IDC_IBEAM
private val handCursor by lazy { Win32Cursor(systemCursor(32649)) }       // IDC_HAND

internal actual val pointerIconDefault: PointerIcon get() = arrowCursor
internal actual val pointerIconCrosshair: PointerIcon get() = crosshairCursor
internal actual val pointerIconText: PointerIcon get() = ibeamCursor
internal actual val pointerIconHand: PointerIcon get() = handCursor
