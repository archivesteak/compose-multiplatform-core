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

package androidx.compose.ui.viewinterop

import androidx.compose.runtime.CompositeKeyHashCode
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.round
import kotlin.math.roundToInt
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.windows.CreateRectRgn
import platform.windows.DestroyWindow
import platform.windows.HWND
import platform.windows.RDW_ALLCHILDREN
import platform.windows.RDW_ERASE
import platform.windows.RDW_INVALIDATE
import platform.windows.RECT
import platform.windows.RedrawWindow
import platform.windows.SWP_NOACTIVATE
import platform.windows.SWP_NOREDRAW
import platform.windows.SWP_NOZORDER
import platform.windows.SW_HIDE
import platform.windows.SW_SHOWNA
import platform.windows.SetParent
import platform.windows.SetWindowPos
import platform.windows.SetWindowRgn
import platform.windows.ShowWindow

/**
 * Ties a native child window to a Compose layout node.
 *
 * Compose owns the geometry: whenever the node is positioned, the child is moved and resized to
 * match, in the physical pixels Win32 works in. Input is left to Windows — a child `HWND` is a
 * first-class window that receives its own messages — so [dispatchToView] has nothing to forward.
 */
internal class Win32InteropViewHolder(
    factory: () -> HWND,
    private val win32Container: Win32InteropContainer,
    compositeKeyHashCode: CompositeKeyHashCode,
) : TypedInteropViewHolder<HWND>(
    factory = factory,
    interopContainer = win32Container,
    group = win32Container.window,
    compositeKeyHashCode = compositeKeyHashCode,
) {
    /**
     * The child fills whatever space the modifier chain gives it, like `AndroidView` and
     * `UIKitView`: a native window has no Compose-visible intrinsic size to measure.
     */
    override val measurePolicy: MeasurePolicy = InteropMeasurePolicy

    override fun insertInteropView(root: InteropViewGroup, index: Int) {
        SetParent(interopView, root as HWND)
        // SW_SHOWNA shows without taking focus away from the Compose content.
        // Shown by the first layout pass, once its clip is known.
        super.insertInteropView(root, index)
    }

    override fun removeInteropView(root: InteropViewGroup) {
        ShowWindow(interopView, SW_HIDE)
        SetParent(interopView, null)
        super.removeInteropView(root)
    }

    override fun changeInteropViewIndex(root: InteropViewGroup, index: Int) {
        // Z-order is relative, so it is applied by the container in one pass over all children
        // rather than per-view here.
        win32Container.applyZOrder()
    }

    override fun dispatchToView(pointerEvent: PointerEvent) {
        // Windows routes input to a child window itself; nothing to forward.
    }

    override fun layoutAccordingTo(layoutCoordinates: LayoutCoordinates) {
        val position = layoutCoordinates.positionInRoot().round()
        val size = layoutCoordinates.size

        // A child window is composited by the window manager, so Compose cannot clip it the way it
        // clips its own drawing. Anything that scrolls out of a clipping parent has to be clipped —
        // or hidden outright — by hand, otherwise it keeps floating over the rest of the content.
        val visible = layoutCoordinates.boundsInWindow()
        if (visible.isEmpty) {
            if (isVisible) {
                isVisible = false
                ShowWindow(interopView, SW_HIDE)
            }
            return
        }

        // Position and clip describe one state but Win32 sets them separately, and whichever goes
        // first is briefly wrong: move first and the window is repainted at the new position still
        // wearing the old clip, which draws the control past the edge it is being clipped to — by
        // as much as its own height, right over whatever is above the scroll viewport.
        //
        // So neither is allowed to paint. SWP_NOREDRAW suppresses the move's repaint and
        // SetWindowRgn's redraw flag is off, then one invalidation below covers both the area the
        // window left and the one it now occupies. SWP_NOREDRAW alone is not enough: it also
        // suppresses invalidating the vacated area, which leaves the pixels the child last drew
        // sitting there, since WS_CLIPCHILDREN had been excluding that area from the parent.
        SetWindowPos(
            interopView,
            null,
            position.x,
            position.y,
            size.width,
            size.height,
            (SWP_NOZORDER or SWP_NOACTIVATE or SWP_NOREDRAW).toUInt(),
        )

        // The region is in the child's own coordinates, and SetWindowRgn takes ownership of it.
        val clip = CreateRectRgn(
            (visible.left.roundToInt() - position.x),
            (visible.top.roundToInt() - position.y),
            (visible.right.roundToInt() - position.x),
            (visible.bottom.roundToInt() - position.y),
        )
        SetWindowRgn(interopView, clip, 0)

        if (!isVisible) {
            isVisible = true
            ShowWindow(interopView, SW_SHOWNA)
        }

        // RDW_ALLCHILDREN brings the child into the same repaint as the parent, so the two come
        // back together rather than one trailing the other by a frame.
        memScoped {
            val dirty = alloc<RECT>()
            dirty.left = minOf(position.x, lastLeft)
            dirty.top = minOf(position.y, lastTop)
            dirty.right = maxOf(position.x + size.width, lastRight)
            dirty.bottom = maxOf(position.y + size.height, lastBottom)
            RedrawWindow(
                win32Container.window,
                dirty.ptr,
                null,
                (RDW_INVALIDATE or RDW_ERASE or RDW_ALLCHILDREN).toUInt(),
            )
        }
        lastLeft = position.x
        lastTop = position.y
        lastRight = position.x + size.width
        lastBottom = position.y + size.height
    }

    /** The rectangle the window last occupied, so a move can repaint what it leaves behind. */
    private var lastLeft = 0
    private var lastTop = 0
    private var lastRight = 0
    private var lastBottom = 0

    /** Tracked so the window is not shown or hidden on every layout pass. */
    private var isVisible = false

    override fun onRelease() {
        DestroyWindow(interopView)
        super.onRelease()
    }
}

/** Fills the constraints it is given, falling back to zero when unbounded. */
private val InteropMeasurePolicy = MeasurePolicy { _, constraints ->
    layout(
        width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth,
        height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight,
    ) {}
}
