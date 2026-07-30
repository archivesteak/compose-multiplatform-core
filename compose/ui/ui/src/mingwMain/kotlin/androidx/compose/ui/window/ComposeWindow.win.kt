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

@file:OptIn(ExperimentalForeignApi::class, InternalComposeUiApi::class)

package androidx.compose.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.key.win32KeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.win32CursorOrDefault
import androidx.compose.ui.platform.DefaultArchitectureComponentsOwner
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.Win32TextInputService
import androidx.compose.ui.platform.WindowInfoImpl
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.SingleComposeSceneRenderingScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toDpSize
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.platform.PlatformDragAndDropManager
import androidx.compose.ui.platform.PlatformDragAndDropSource
import androidx.compose.ui.platform.Win32ScreenReader
import androidx.compose.ui.viewinterop.LocalInteropContainer
import androidx.compose.ui.viewinterop.TrackInteropPlacementContainer
import androidx.compose.ui.viewinterop.Win32InteropContainer
import androidx.compose.ui.win32.Win32DropTarget
import androidx.compose.ui.win32.startTextDrag
import androidx.compose.ui.win32.Win32Event
import androidx.compose.ui.win32.markProcessDpiAware
import androidx.compose.ui.win32.systemScale
import androidx.compose.ui.win32.windowScale
import androidx.compose.ui.win32.win32KeyboardModifiers
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.enableSavedStateHandles
import kotlin.math.roundToInt
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import kotlinx.cinterop.wcstr
import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.SkiaLayer
import platform.posix.fflush
import org.jetbrains.skiko.SkikoRenderDelegate
import platform.windows.AdjustWindowRect
import platform.windows.CREATESTRUCTW
import platform.windows.CS_HREDRAW
import platform.windows.CS_VREDRAW
import platform.windows.CW_USEDEFAULT
import platform.windows.CreateWindowExW
import platform.windows.DefWindowProcW
import platform.windows.DestroyWindow
import platform.windows.DispatchMessageW
import platform.windows.GetDC
import platform.windows.GetDeviceCaps
import platform.windows.GetLastError
import platform.windows.GetMessageW
import platform.windows.GetModuleHandleW
import platform.windows.GetWindowLongPtrW
import platform.windows.HCURSOR
import platform.windows.HTCLIENT
import platform.windows.HWND
import platform.windows.LOGPIXELSY
import platform.windows.LPARAM
import platform.windows.LRESULT
import platform.windows.MK_LBUTTON
import platform.windows.MK_MBUTTON
import platform.windows.MK_RBUTTON
import platform.windows.MK_XBUTTON1
import platform.windows.MK_XBUTTON2
import platform.windows.MSG
import platform.windows.OleInitialize
import platform.windows.POINT
import platform.windows.PostQuitMessage
import platform.windows.RECT
import platform.windows.RegisterClassExW
import platform.windows.ReleaseCapture
import platform.windows.ReleaseDC
import platform.windows.SWP_NOACTIVATE
import platform.windows.SWP_NOZORDER
import platform.windows.SW_SHOWNORMAL
import platform.windows.ScreenToClient
import platform.windows.SetCapture
import platform.windows.SetCursor
import platform.windows.SetProcessDPIAware
import platform.windows.SetWindowLongPtrW
import platform.windows.SetWindowPos
import platform.windows.ShowWindow
import platform.windows.TME_LEAVE
import platform.windows.TRACKMOUSEEVENT
import platform.windows.TrackMouseEvent
import platform.windows.TranslateMessage
import platform.windows.UINT
import platform.windows.UpdateWindow
import platform.windows.WHEEL_DELTA
import platform.windows.WM_CHAR
import platform.windows.WM_CLOSE
import platform.windows.WM_DESTROY
import platform.windows.WM_DPICHANGED
import platform.windows.WM_KEYDOWN
import platform.windows.WM_KEYUP
import platform.windows.WM_IME_COMPOSITION
import platform.windows.WM_IME_ENDCOMPOSITION
import platform.windows.WM_IME_STARTCOMPOSITION
import platform.windows.WM_KILLFOCUS
import platform.windows.WM_LBUTTONDOWN
import platform.windows.WM_LBUTTONUP
import platform.windows.WM_MBUTTONDOWN
import platform.windows.WM_MBUTTONUP
import platform.windows.WM_MOUSEHWHEEL
import platform.windows.WM_MOUSELEAVE
import platform.windows.WM_MOUSEMOVE
import platform.windows.WM_MOUSEWHEEL
import platform.windows.WM_NCCREATE
import platform.windows.WM_NCDESTROY
import platform.windows.WM_RBUTTONDOWN
import platform.windows.WM_RBUTTONUP
import platform.windows.WM_SETCURSOR
import platform.windows.WM_SETFOCUS
import platform.windows.WM_SYSKEYDOWN
import platform.windows.WM_SYSKEYUP
import platform.windows.WM_XBUTTONDOWN
import platform.windows.WM_XBUTTONUP
import platform.windows.WNDCLASSEXW
import platform.windows.WPARAM
import platform.windows.WS_CLIPCHILDREN
import platform.windows.WS_OVERLAPPEDWINDOW
import platform.windows.XBUTTON1
import platform.windows.XBUTTON2

/** Scope of the content hosted by [Window]. */
interface WindowScope {
    /** `HWND` of the window that was created by [androidx.compose.ui.window.Window]. */
    val window: HWND
}

/**
 * Runs a Compose application: opens whatever windows [content] creates, then pumps the Win32
 * message loop until the last of them closes.
 *
 * Unlike AppKit there is no framework-owned run loop to hand control to — the message loop belongs
 * to the application thread — so it runs here, and every window is torn down before returning.
 * Open more than one window by calling [Window] more than once:
 *
 * ```
 * fun main() = application {
 *     Window(title = "Editor") { EditorContent() }
 *     Window(title = "Inspector", size = DpSize(320.dp, 600.dp)) { InspectorContent() }
 * }
 * ```
 */
fun application(content: () -> Unit) {
    check(!ComposeWindows.isApplicationScope) { "application { } is already running" }
    ComposeWindows.isApplicationScope = true
    try {
        content()
        pumpMessages()
    } finally {
        ComposeWindows.isApplicationScope = false
        ComposeWindows.destroyAll()
    }
}

/**
 * Shows a window hosting [content].
 *
 * Inside [application] this returns as soon as the window is on screen, so further windows can be
 * opened. Called on its own it is the whole application: it pumps the message loop itself and
 * returns once the window is closed.
 *
 * @param title text shown in the window's title bar.
 * @param size initial size of the *client* area, in density-independent pixels.
 */
fun Window(
    title: String = "ComposeWindow",
    size: DpSize = DpSize(800.dp, 600.dp),
    content: @Composable WindowScope.() -> Unit,
) {
    val window = ComposeWindow(title = title, size = size, content = content)
    if (ComposeWindows.isApplicationScope) return
    try {
        pumpMessages()
    } finally {
        // A no-op on the normal path: closing the window already ran the teardown. This covers
        // leaving the loop through an exception thrown out of the content.
        window.destroy()
    }
}

/**
 * Every window this process has open, so the message loop knows when the last one has gone.
 *
 * Kotlin/Native runs Compose on the single thread that pumps messages, so this needs no locking.
 */
private object ComposeWindows {
    private val open = mutableSetOf<ComposeWindow>()

    /** True inside [application], where [Window] must return instead of pumping messages. */
    var isApplicationScope = false

    /** True while [pumpMessages] is running, so closing the last window can end it. */
    var isPumping = false

    fun register(window: ComposeWindow) {
        open.add(window)
    }

    fun unregister(window: ComposeWindow) {
        if (open.remove(window) && open.isEmpty() && isPumping) {
            // The last window has gone: let the message loop finish.
            PostQuitMessage(0)
        }
    }

    fun destroyAll() {
        // Copied first: destroying a window unregisters it.
        open.toList().forEach(ComposeWindow::destroy)
    }
}

/** Pumps messages until the last window closes. */
private fun pumpMessages() = memScoped {
    ComposeWindows.isPumping = true
    // Claim this thread for Compose's dispatcher, then run whatever was queued while the windows
    // were being constructed.
    Win32MainDispatcher.bindToCurrentThread()
    Win32MainDispatcher.drain()

    val msg = alloc<MSG>()
    // `GetMessageW` returns 0 on WM_QUIT and -1 on error; both end the loop.
    while (GetMessageW(msg.ptr, null, 0u, 0u) > 0) {
        // Thread messages have no window, so DispatchMessage would silently drop them.
        if (msg.hwnd == null && msg.message.toInt() == WM_COMPOSE_DISPATCH) {
            Win32MainDispatcher.drain()
        } else {
            TranslateMessage(msg.ptr)
            DispatchMessageW(msg.ptr)
        }
    }
    ComposeWindows.isPumping = false
}

private class ComposeWindow(
    title: String,
    size: DpSize,
    content: @Composable WindowScope.() -> Unit,
) : WindowScope {
    private var isDisposed = false

    private val textInputService = Win32TextInputService()
    private val _windowInfo = WindowInfoImpl().apply { isWindowFocused = true }
    private val archComponentsOwner = DefaultArchitectureComponentsOwner()

    // TODO: It must be shared between Compose instances.
    //  It's supposed to be stored in platform's root view or window.
    private val frameRecomposer = FrameRecomposer(Win32MainDispatcher) { skiaLayer.needRender() }

    // TODO: It cannot be used in case of shared [FrameRecomposer], replace this helper with calling
    //  - [frameRecomposer.performFrame] once per frame (across all instances)
    //  - [scene.measureAndLayout] during the layout phase
    //  - [scene.draw] during the drawing phase
    private val sceneRenderingScope = SingleComposeSceneRenderingScope { skiaLayer.needRender() }

    private val screenReader = Win32ScreenReader()

    /**
     * Starts shell drags for content that asks to be dragged out of the window. DoDragDrop runs a
     * modal loop of its own, so it is only entered from a pointer gesture, never during layout.
     */
    private val dragAndDropManager = object : PlatformDragAndDropManager {
        override val isRequestDragAndDropTransferRequired: Boolean get() = true

        override fun requestDragAndDropTransfer(
            source: PlatformDragAndDropSource,
            offset: Offset,
        ) {
            var payload: DragAndDropTransferData? = null
            val scope = object : PlatformDragAndDropSource.StartTransferScope {
                override fun startDragAndDropTransfer(
                    transferData: DragAndDropTransferData,
                    decorationSize: Size,
                    drawDragDecoration: DrawScope.() -> Unit,
                ): Boolean {
                    // The shell draws its own drag image, so the decoration is not used.
                    payload = transferData
                    return true
                }
            }
            with(source) { scope.startDragAndDropTransfer(offset) { payload != null } }
            payload?.text?.let(::startTextDrag)
        }
    }

    /** Hosts native child windows placed by [androidx.compose.ui.viewinterop.Win32View]. */
    private var interopContainer: Win32InteropContainer? = null

    /** Shell drop target, registered once the window exists. */
    private var dropTarget: Win32DropTarget? = null

    /** Last event seen from the shell, so leave/end can be reported with a position. */
    private var lastDragEvent: DragAndDropEvent? = null

    /** Cursor for the client area, applied on every `WM_SETCURSOR`. */
    private var cursor: HCURSOR? = null

    /** `WM_MOUSELEAVE` carries no coordinates, so the last known position is reused for `Exit`. */
    private var lastPointerPosition = Offset.Zero

    private var isPointerInside = false

    private val platformContext: PlatformContext =
        object : PlatformContext by PlatformContext.Empty() {
            override val windowInfo get() = _windowInfo
            override val architectureComponentsOwner get() = archComponentsOwner
            override val textInputService get() = this@ComposeWindow.textInputService
            override val screenReader get() = this@ComposeWindow.screenReader
            override val dragAndDropManager get() = this@ComposeWindow.dragAndDropManager

            override fun setPointerIcon(pointerIcon: PointerIcon) {
                cursor = pointerIcon.win32CursorOrDefault()
                // Apply immediately; `WM_SETCURSOR` only fires on the next mouse move.
                SetCursor(cursor)
            }
        }

    private val skiaLayer = SkiaLayer()

    private val scene = CanvasLayersComposeScene(
        frameRecomposer = frameRecomposer,
        platformContext = platformContext,
        // TODO: Route these to distinct Win32 invalidation paths: layout work should not force a
        //  repaint on its own, while draw work should only mark the client area dirty.
        invalidateLayout = sceneRenderingScope::onSceneInvalidation,
        invalidateDraw = sceneRenderingScope::onSceneInvalidation,
    )

    private val renderDelegate = object : SkikoRenderDelegate {
        override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
            // Rendering runs inside skiko's window procedure, which is a C callback: an exception
            // that escaped it would reach the Win32 dispatcher and Windows would kill the process
            // with STATUS_FATAL_USER_CALLBACK_EXCEPTION and no diagnostic at all.
            try {
                val sizeInPx = IntSize(width, height)
                _windowInfo.containerSize = sizeInPx
                _windowInfo.containerDpSize = sizeInPx.toSize().toDpSize(scene.density)
                scene.size = sizeInPx // TODO: Move it out from onRender to avoid extra invalidation
                with(sceneRenderingScope) {
                    scene.render(frameRecomposer, canvas.asComposeCanvas(), nanoTime)
                }
            } catch (throwable: Throwable) {
                reportRenderFailure(throwable)
            }
        }
    }

    /**
     * Handed to `CreateWindowExW` so the window procedure can find this instance from `WM_NCCREATE`
     * onwards. Freed on `WM_NCDESTROY`, the window's last message.
     */
    private val selfRef = StableRef.create(this)

    override val window: HWND = createWindow(title, size, selfRef)

    init {
        skiaLayer.renderDelegate = renderDelegate
        skiaLayer.attachTo(window) // Subclasses the window procedure, so create the window first.

        // IMM32 calls need the window that owns the input context.
        textInputService.window = window

        ComposeWindows.register(this)

        // Accept files and text dropped from the shell.
        OleInitialize(null)
        dropTarget = Win32DropTarget(
            hwnd = window,
            onDragEnter = { event ->
                lastDragEvent = event
                scene.rootDragAndDropNode.acceptDragAndDropTransfer(event).also { accepted ->
                    if (accepted) {
                        scene.rootDragAndDropNode.onStarted(event)
                        scene.rootDragAndDropNode.onEntered(event)
                    }
                }
            },
            onDragOver = { event ->
                lastDragEvent = event
                scene.rootDragAndDropNode.onMoved(event)
            },
            onDragLeave = {
                scene.rootDragAndDropNode.onExited(lastDragEvent ?: return@Win32DropTarget)
                scene.rootDragAndDropNode.onEnded(lastDragEvent ?: return@Win32DropTarget)
            },
            onDrop = { event ->
                scene.rootDragAndDropNode.onDrop(event).also {
                    scene.rootDragAndDropNode.onEnded(event)
                }
            },
        )

        scene.density = Density(windowScale(window))
        interopContainer = Win32InteropContainer(window).also { it.startObserving() }
        scene.setContent {
            CompositionLocalProvider(LocalInteropContainer provides interopContainer!!) {
                // Tracks where interop children sit in the draw order, so their z-order can follow.
                interopContainer!!.TrackInteropPlacementContainer {
                    content()
                }
            }
        }

        archComponentsOwner.enableSavedStateHandles()
        // TODO: Drive the remaining lifecycle events from window activation/minimization.
        archComponentsOwner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        ShowWindow(window, SW_SHOWNORMAL)
        UpdateWindow(window)
    }

    /**
     * Closes the window if it is still alive. Destruction is synchronous, so [dispose] has run by
     * the time this returns.
     */
    fun destroy() {
        if (!isDisposed) {
            DestroyWindow(window)
        }
    }

    /**
     * Returns the message result, or null to let `DefWindowProcW` handle the message.
     */
    fun handleMessage(message: UINT, wParam: WPARAM, lParam: LPARAM): LRESULT? {
        if (message.toInt() == WM_NCDESTROY) {
            // The last message this window receives: the safe point to unsubclass and release.
            dispose()
            return null
        }
        if (isDisposed) return null
        return when (message.toInt()) {
            WM_MOUSEMOVE -> {
                onMouseMove(wParam, lParam)
                0
            }
            WM_MOUSELEAVE -> {
                onMouseLeave()
                0
            }
            WM_LBUTTONDOWN -> onMouseButton(
                PointerEventType.Press, PointerButton.Primary, message, wParam, lParam
            )
            WM_LBUTTONUP -> onMouseButton(
                PointerEventType.Release, PointerButton.Primary, message, wParam, lParam
            )
            WM_RBUTTONDOWN -> onMouseButton(
                PointerEventType.Press, PointerButton.Secondary, message, wParam, lParam
            )
            WM_RBUTTONUP -> onMouseButton(
                PointerEventType.Release, PointerButton.Secondary, message, wParam, lParam
            )
            WM_MBUTTONDOWN -> onMouseButton(
                PointerEventType.Press, PointerButton.Tertiary, message, wParam, lParam
            )
            WM_MBUTTONUP -> onMouseButton(
                PointerEventType.Release, PointerButton.Tertiary, message, wParam, lParam
            )
            WM_XBUTTONDOWN -> {
                onMouseButton(PointerEventType.Press, xButton(wParam), message, wParam, lParam)
                // Documented contract for the X button messages.
                1
            }
            WM_XBUTTONUP -> {
                onMouseButton(PointerEventType.Release, xButton(wParam), message, wParam, lParam)
                1
            }
            WM_MOUSEWHEEL -> {
                onMouseWheel(message, wParam, lParam, isHorizontal = false)
                0
            }
            WM_MOUSEHWHEEL -> {
                onMouseWheel(message, wParam, lParam, isHorizontal = true)
                0
            }
            WM_KEYDOWN, WM_KEYUP, WM_SYSKEYDOWN, WM_SYSKEYUP, WM_CHAR -> {
                // Unconsumed keys fall through to the system, so Alt+F4, menu mnemonics and the
                // system keys keep working.
                if (scene.sendKeyEvent(win32KeyEvent(message, wParam, lParam))) 0 else null
            }
            WM_IME_STARTCOMPOSITION -> {
                if (textInputService.isInputActive) {
                    textInputService.onImeStartComposition()
                    // Consume it: Compose draws the composition inline, so the IME must not open
                    // its own composition window on top of the text field.
                    0
                } else {
                    null
                }
            }
            WM_IME_COMPOSITION -> {
                if (textInputService.onImeComposition(lParam)) 0 else null
            }
            WM_IME_ENDCOMPOSITION -> {
                if (textInputService.isInputActive) {
                    textInputService.onImeEndComposition()
                    0
                } else {
                    null
                }
            }
            WM_SETFOCUS -> {
                _windowInfo.isWindowFocused = true
                // Returning null lets the system perform its default focus bookkeeping.
                null
            }
            WM_KILLFOCUS -> {
                _windowInfo.isWindowFocused = false
                null
            }
            WM_SETCURSOR -> {
                // The hit-test code is in the low word; only the client area is ours to style.
                if (lParam.loWord() == HTCLIENT) {
                    SetCursor(cursor)
                    1
                } else {
                    null
                }
            }
            WM_DPICHANGED -> {
                onDpiChanged(lParam)
                0
            }
            WM_CLOSE -> {
                DestroyWindow(window)
                0
            }
            WM_DESTROY -> {
                // Not PostQuitMessage: with several windows open the loop must outlive this one.
                // ComposeWindows ends it when the last window is unregistered.
                0
            }
            else -> null
        }
    }

    private fun onMouseMove(wParam: WPARAM, lParam: LPARAM) {
        val position = clientPosition(lParam)
        if (!isPointerInside) {
            isPointerInside = true
            // Win32 reports no leave event unless it is requested, once, per entry.
            trackMouseLeave()
            sendPointerEvent(PointerEventType.Enter, position, WM_MOUSEMOVE.convert(), wParam, lParam)
        }
        sendPointerEvent(PointerEventType.Move, position, WM_MOUSEMOVE.convert(), wParam, lParam)
    }

    private fun onMouseLeave() {
        isPointerInside = false
        sendPointerEvent(
            eventType = PointerEventType.Exit,
            position = lastPointerPosition,
            message = WM_MOUSELEAVE.convert(),
            wParam = 0u,
            lParam = 0,
        )
    }

    private fun onMouseButton(
        eventType: PointerEventType,
        button: PointerButton?,
        message: UINT,
        wParam: WPARAM,
        lParam: LPARAM,
    ): LRESULT {
        // `wParam` holds the button state *after* the transition, so capture is held for exactly
        // as long as some button is down — that is what keeps a drag alive outside the window.
        if (eventType == PointerEventType.Press) {
            SetCapture(window)
        } else if (pressedButtonMask(wParam) == 0) {
            ReleaseCapture()
        }
        sendPointerEvent(eventType, clientPosition(lParam), message, wParam, lParam, button = button)
        return 0
    }

    private fun onMouseWheel(
        message: UINT,
        wParam: WPARAM,
        lParam: LPARAM,
        isHorizontal: Boolean,
    ) {
        // Notches away from the user (or to the right) are positive on Win32; Compose counts
        // scroll deltas in the direction the content moves, which is the opposite vertically.
        val notches = (wParam shr 16).toShort().toInt().toFloat() / WHEEL_DELTA
        val scrollDelta = if (isHorizontal) {
            Offset(x = notches, y = 0f)
        } else {
            Offset(x = 0f, y = -notches)
        }
        // Unlike the other mouse messages, the wheel messages carry screen coordinates.
        sendPointerEvent(
            eventType = PointerEventType.Scroll,
            position = screenToClientPosition(lParam),
            message = message,
            wParam = wParam,
            lParam = lParam,
            scrollDelta = scrollDelta,
        )
    }

    private fun onDpiChanged(lParam: LPARAM) {
        // The system supplies the rect the window should occupy on the monitor it moved to.
        lParam.toCPointer<RECT>()?.pointed?.let { suggested ->
            SetWindowPos(
                window,
                null,
                suggested.left,
                suggested.top,
                suggested.right - suggested.left,
                suggested.bottom - suggested.top,
                (SWP_NOZORDER or SWP_NOACTIVATE).convert(),
            )
        }
        // The window moved to a monitor with a different DPI, so the content rescales.
        scene.density = Density(windowScale(window))
    }

    private fun sendPointerEvent(
        eventType: PointerEventType,
        position: Offset,
        message: UINT,
        wParam: WPARAM,
        lParam: LPARAM,
        scrollDelta: Offset = Offset.Zero,
        button: PointerButton? = null,
    ) {
        lastPointerPosition = position
        scene.sendPointerEvent(
            eventType = eventType,
            position = position,
            scrollDelta = scrollDelta,
            buttons = pointerButtons(wParam),
            keyboardModifiers = win32KeyboardModifiers(),
            nativeEvent = Win32Event(message, wParam, lParam),
            button = button,
        )
    }

    private fun trackMouseLeave() = memScoped {
        val track = alloc<TRACKMOUSEEVENT>()
        track.cbSize = sizeOf<TRACKMOUSEEVENT>().convert()
        track.dwFlags = TME_LEAVE.convert()
        track.hwndTrack = window
        TrackMouseEvent(track.ptr)
    }

    /** Client-area coordinates, in physical pixels, from a packed `lParam`. */
    private fun clientPosition(lParam: LPARAM): Offset =
        Offset(x = lParam.loWord().toFloat(), y = lParam.hiWord().toFloat())

    private fun screenToClientPosition(lParam: LPARAM): Offset = memScoped {
        val point = alloc<POINT>()
        point.x = lParam.loWord()
        point.y = lParam.hiWord()
        ScreenToClient(window, point.ptr)
        Offset(x = point.x.toFloat(), y = point.y.toFloat())
    }

    private fun dispose() {
        if (isDisposed) return
        isDisposed = true

        interopContainer?.stopObserving()
        interopContainer = null
        dropTarget?.dispose()
        dropTarget = null
        ComposeWindows.unregister(this)
        archComponentsOwner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        archComponentsOwner.viewModelStore.clear()
        skiaLayer.detach()
        scene.close()
        frameRecomposer.close()
        // Clear the back-pointer before freeing it, so nothing can resolve a dangling reference.
        SetWindowLongPtrW(window, 0, 0)
        selfRef.dispose()
    }
}

/**
 * Reports a failure raised while rendering a frame. Only the first one is printed in full: a
 * broken composition repeats on every frame, and a repainting window would otherwise fill the
 * console with the same stack trace.
 */
private var hasReportedRenderFailure = false

private fun reportRenderFailure(throwable: Throwable) {
    if (hasReportedRenderFailure) return
    hasReportedRenderFailure = true
    reportFailure("Exception while rendering a Compose frame:", throwable)
}

/**
 * Prints a failure and flushes immediately. Diagnostics from a window procedure are worth nothing
 * buffered: if the process is torn down by Windows, whatever is still in the stdio buffers is lost.
 */
private fun reportFailure(message: String, throwable: Throwable) {
    println(message)
    throwable.printStackTrace()
    fflush(null)
}

private const val WINDOW_CLASS_NAME = "AndroidxComposeWindow"

/** Registered once per process, on first window creation. */
private val windowClass: UShort by lazy { registerWindowClass() }

private fun registerWindowClass(): UShort = memScoped {
    // Must precede the first window: it decides whether Windows scales the window for us or hands
    // us real pixels, and whether WM_DPICHANGED is delivered at all.
    markProcessDpiAware()

    val windowClass = alloc<WNDCLASSEXW>()
    windowClass.cbSize = sizeOf<WNDCLASSEXW>().convert()
    windowClass.style = (CS_HREDRAW or CS_VREDRAW).convert()
    windowClass.lpfnWndProc = staticCFunction(::composeWindowProc)
    windowClass.hInstance = GetModuleHandleW(null)
    // The cursor is chosen per frame by the content, and skia paints every pixel itself.
    windowClass.hCursor = null
    windowClass.hbrBackground = null
    windowClass.lpszClassName = WINDOW_CLASS_NAME.wcstr.ptr
    // Room for the ComposeWindow pointer. `GWLP_USERDATA` is unavailable: skiko's SkiaLayer
    // claims it when it subclasses the window procedure.
    windowClass.cbWndExtra = sizeOf<COpaquePointerVar>().convert()

    val atom = RegisterClassExW(windowClass.ptr)
    check(atom != 0.toUShort()) { "RegisterClassExW failed: ${GetLastError()}" }
    atom
}

private fun createWindow(title: String, size: DpSize, selfRef: StableRef<*>): HWND = memScoped {
    check(windowClass != 0.toUShort())

    val scale = systemScale()
    // WS_CLIPCHILDREN keeps our BitBlt out of the area covered by interop child windows; without
    // it the surface paints over them every frame and they flicker.
    val style = WS_OVERLAPPEDWINDOW or WS_CLIPCHILDREN
    // `size` describes the client area; `CreateWindowExW` takes the outer window size.
    val bounds = alloc<RECT>()
    bounds.left = 0
    bounds.top = 0
    bounds.right = (size.width.value * scale).roundToInt()
    bounds.bottom = (size.height.value * scale).roundToInt()
    AdjustWindowRect(bounds.ptr, style.convert(), 0)

    // Created hidden: the content is attached and composed before the first paint.
    val hwnd = CreateWindowExW(
        dwExStyle = 0u,
        lpClassName = WINDOW_CLASS_NAME,
        lpWindowName = title,
        dwStyle = style.convert(),
        X = CW_USEDEFAULT,
        Y = CW_USEDEFAULT,
        nWidth = bounds.right - bounds.left,
        nHeight = bounds.bottom - bounds.top,
        hWndParent = null,
        hMenu = null,
        hInstance = GetModuleHandleW(null),
        lpParam = selfRef.asCPointer(),
    )
    checkNotNull(hwnd) { "CreateWindowExW failed: ${GetLastError()}" }
}

private fun composeWindowProc(
    hwnd: HWND?,
    message: UINT,
    wParam: WPARAM,
    lParam: LPARAM,
): LRESULT = try {
    dispatchWindowMessage(hwnd, message, wParam, lParam)
} catch (throwable: Throwable) {
    // A Kotlin exception must never unwind into the Win32 message dispatcher: it crosses a C
    // callback frame, so Windows kills the process with STATUS_FATAL_USER_CALLBACK_EXCEPTION and
    // no diagnostic at all. Report it here, where a stack trace is still available, and let the
    // system handle the message so the window stays responsive.
    reportFailure(
        "Exception in the Compose window procedure (message 0x${message.toString(16)}):",
        throwable,
    )
    DefWindowProcW(hwnd, message, wParam, lParam)
}

private fun dispatchWindowMessage(
    hwnd: HWND?,
    message: UINT,
    wParam: WPARAM,
    lParam: LPARAM,
): LRESULT {
    if (message.toInt() == WM_NCCREATE) {
        // The instance travels in `CREATESTRUCTW.lpCreateParams` and is parked in the class's
        // extra window memory, where it stays reachable for every later message.
        lParam.toCPointer<CREATESTRUCTW>()?.pointed?.lpCreateParams?.let { self ->
            SetWindowLongPtrW(hwnd, 0, self.toLong())
        }
        return DefWindowProcW(hwnd, message, wParam, lParam)
    }
    val window = hwnd?.let(::composeWindowOf)
        ?: return DefWindowProcW(hwnd, message, wParam, lParam)
    return window.handleMessage(message, wParam, lParam)
        ?: DefWindowProcW(hwnd, message, wParam, lParam)
}

private fun composeWindowOf(hwnd: HWND): ComposeWindow? {
    val address = GetWindowLongPtrW(hwnd, 0)
    if (address == 0L) return null
    return address.toCPointer<CPointed>()?.asStableRef<ComposeWindow>()?.get()
}

private fun xButton(wParam: WPARAM): PointerButton? = when (wParam.hiWord()) {
    XBUTTON1 -> PointerButton.Back
    XBUTTON2 -> PointerButton.Forward
    else -> null
}

/** `MK_*` button flags, which occupy the low word of a mouse message's `wParam`. */
private fun pressedButtonMask(wParam: WPARAM): Int = wParam.loWord()

private fun pointerButtons(wParam: WPARAM): PointerButtons {
    val pressed = pressedButtonMask(wParam)
    return PointerButtons(
        isPrimaryPressed = pressed and MK_LBUTTON != 0,
        isSecondaryPressed = pressed and MK_RBUTTON != 0,
        isTertiaryPressed = pressed and MK_MBUTTON != 0,
        isBackPressed = pressed and MK_XBUTTON1 != 0,
        isForwardPressed = pressed and MK_XBUTTON2 != 0,
    )
}

private fun LPARAM.loWord(): Int = toShort().toInt()

private fun LPARAM.hiWord(): Int = (this shr 16).toShort().toInt()

private fun WPARAM.loWord(): Int = toShort().toInt()

private fun WPARAM.hiWord(): Int = (this shr 16).toShort().toInt()
