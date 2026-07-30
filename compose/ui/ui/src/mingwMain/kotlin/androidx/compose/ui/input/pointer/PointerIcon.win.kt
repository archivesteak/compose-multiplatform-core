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

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.toCPointer
import platform.windows.HCURSOR
import platform.windows.LoadCursorW

internal data class Win32Cursor(val cursor: HCURSOR?) : PointerIcon

/**
 * Creates a [PointerIcon] from a Win32 cursor handle, the counterpart of the JVM desktop target's
 * `PointerIcon(java.awt.Cursor)`. Use it with [Win32Cursors] for the stock shapes, or with a
 * handle of your own from `LoadCursorW`/`LoadImageW`/`CreateIconIndirect`.
 *
 * The handle is not owned by Compose: shared system cursors must not be destroyed, and a custom
 * one has to outlive every frame that shows it.
 */
fun PointerIcon(cursor: HCURSOR): PointerIcon = Win32Cursor(cursor)

/**
 * The stock Windows cursors, loaded on first use. These are the shared `IDC_*` cursors owned by
 * the system, so they are never destroyed.
 */
object Win32Cursors {
    /** `IDC_ARROW` */
    val Arrow: PointerIcon by lazy { systemPointerIcon(IDC_ARROW) }
    /** `IDC_IBEAM` */
    val IBeam: PointerIcon by lazy { systemPointerIcon(IDC_IBEAM) }
    /** `IDC_CROSS` */
    val Crosshair: PointerIcon by lazy { systemPointerIcon(IDC_CROSS) }
    /** `IDC_HAND` */
    val Hand: PointerIcon by lazy { systemPointerIcon(IDC_HAND) }
    /** `IDC_WAIT` — the whole application is busy. */
    val Wait: PointerIcon by lazy { systemPointerIcon(IDC_WAIT) }
    /** `IDC_APPSTARTING` — busy, but the UI still accepts input. */
    val AppStarting: PointerIcon by lazy { systemPointerIcon(IDC_APPSTARTING) }
    /** `IDC_HELP` */
    val Help: PointerIcon by lazy { systemPointerIcon(IDC_HELP) }
    /** `IDC_NO` — the drop or action is not allowed here. */
    val No: PointerIcon by lazy { systemPointerIcon(IDC_NO) }
    /** `IDC_SIZEALL` — move, or pan in any direction. */
    val ResizeAll: PointerIcon by lazy { systemPointerIcon(IDC_SIZEALL) }
    /** `IDC_SIZENS` — resize vertically. */
    val ResizeNorthSouth: PointerIcon by lazy { systemPointerIcon(IDC_SIZENS) }
    /** `IDC_SIZEWE` — resize horizontally. */
    val ResizeWestEast: PointerIcon by lazy { systemPointerIcon(IDC_SIZEWE) }
    /** `IDC_SIZENESW` — resize along the ↗↙ diagonal. */
    val ResizeNorthEastSouthWest: PointerIcon by lazy { systemPointerIcon(IDC_SIZENESW) }
    /** `IDC_SIZENWSE` — resize along the ↖↘ diagonal. */
    val ResizeNorthWestSouthEast: PointerIcon by lazy { systemPointerIcon(IDC_SIZENWSE) }
    /** `IDC_UPARROW` */
    val UpArrow: PointerIcon by lazy { systemPointerIcon(IDC_UPARROW) }
}

// The IDC_* macros are integers passed where a name pointer is expected, the way MAKEINTRESOURCE
// does it. They are not in the Kotlin/Native Windows klib, so they are spelled out here.
private const val IDC_ARROW = 32512L
private const val IDC_IBEAM = 32513L
private const val IDC_WAIT = 32514L
private const val IDC_CROSS = 32515L
private const val IDC_UPARROW = 32516L
private const val IDC_SIZENWSE = 32642L
private const val IDC_SIZENESW = 32643L
private const val IDC_SIZEWE = 32644L
private const val IDC_SIZENS = 32645L
private const val IDC_SIZEALL = 32646L
private const val IDC_NO = 32648L
private const val IDC_HAND = 32649L
private const val IDC_APPSTARTING = 32650L
private const val IDC_HELP = 32651L

private fun systemPointerIcon(id: Long): PointerIcon =
    Win32Cursor(LoadCursorW(null, id.toCPointer<UShortVar>()))

/**
 * The handle to hand to `SetCursor`, falling back to the arrow for a [PointerIcon] that did not
 * come from this platform — passing null would hide the cursor instead.
 */
internal fun PointerIcon.win32CursorOrDefault(): HCURSOR? =
    (this as? Win32Cursor)?.cursor ?: (Win32Cursors.Arrow as Win32Cursor).cursor

internal actual val pointerIconDefault: PointerIcon get() = Win32Cursors.Arrow
internal actual val pointerIconCrosshair: PointerIcon get() = Win32Cursors.Crosshair
internal actual val pointerIconText: PointerIcon get() = Win32Cursors.IBeam
internal actual val pointerIconHand: PointerIcon get() = Win32Cursors.Hand
