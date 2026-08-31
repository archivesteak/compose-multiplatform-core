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
import androidx.compose.ui.input.pointer.Win32Cursors
import androidx.compose.ui.input.pointer.win32CursorOrDefault
import androidx.compose.ui.platform.DefaultArchitectureComponentsOwner
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.Win32TextInputService
import androidx.compose.ui.platform.WindowInfoImpl
import androidx.compose.ui.platform.makeSynchronizedObject
import androidx.compose.ui.platform.synchronized
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
import androidx.compose.ui.draganddrop.isSupportedOutgoingPayload
import androidx.compose.ui.platform.PlatformDragAndDropManager
import androidx.compose.ui.platform.PlatformDragAndDropSource
import androidx.compose.ui.platform.Win32ScreenReader
import androidx.compose.ui.viewinterop.LocalInteropContainer
import androidx.compose.ui.viewinterop.TrackInteropPlacementContainer
import androidx.compose.ui.viewinterop.Win32InteropContainer
import androidx.compose.ui.win32.Win32DropTarget
import androidx.compose.ui.win32.S_FALSE
import androidx.compose.ui.win32.S_OK
import androidx.compose.ui.win32.startTextDrag
import androidx.compose.ui.win32.Win32Event
import androidx.compose.ui.win32.ensureProcessDpiAwareness
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
import org.jetbrains.skiko.GraphicsApi
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
import platform.windows.GetLastError
import platform.windows.GetMessageW
import platform.windows.GetModuleHandleW
import platform.windows.GetCurrentThreadId
import platform.windows.GetWindowLongPtrW
import platform.windows.HCURSOR
import platform.windows.HTCLIENT
import platform.windows.HWND
import platform.windows.LPARAM
import platform.windows.LRESULT
import platform.windows.MK_LBUTTON
import platform.windows.MK_MBUTTON
import platform.windows.MK_RBUTTON
import platform.windows.MK_XBUTTON1
import platform.windows.MK_XBUTTON2
import platform.windows.MSG
import platform.windows.POINT
import platform.windows.PostQuitMessage
import platform.windows.RECT
import platform.windows.RegisterClassExW
import platform.windows.ReleaseCapture
import platform.windows.SIZE_MAXIMIZED
import platform.windows.SIZE_MINIMIZED
import platform.windows.SIZE_RESTORED
import platform.windows.SWP_NOACTIVATE
import platform.windows.SWP_NOZORDER
import platform.windows.SW_SHOWNORMAL
import platform.windows.ScreenToClient
import platform.windows.SetCapture
import platform.windows.SetCursor
import platform.windows.SetLastError
import platform.windows.SetWindowLongPtrW
import platform.windows.SetWindowPos
import platform.windows.ShowWindow
import platform.windows.TME_LEAVE
import platform.windows.TRACKMOUSEEVENT
import platform.windows.TrackMouseEvent
import platform.windows.TranslateMessage
import platform.windows.UINT
import platform.windows.UpdateWindow
import platform.windows.WA_INACTIVE
import platform.windows.WHEEL_DELTA
import platform.windows.WM_ACTIVATE
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
import platform.windows.WM_SIZE
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

    /** Graphics backend the window's Skia layer actually created, after any safe fallback. */
    val renderApi: GraphicsApi get() = GraphicsApi.UNKNOWN
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
    withBoundMainDispatcher {
        ComposeWindows.enterApplicationScope()
        try {
            content()
            pumpMessages()
        } finally {
            // Teardown stays inside the dispatcher binding. Failure before the message loop and an
            // external WM_QUIT can both leave scene cleanup work queued; the outer scope drains it
            // before atomically unbinding.
            try {
                ComposeWindows.destroyAll()
            } finally {
                ComposeWindows.leaveApplicationScope()
            }
        }
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
    if (ComposeWindows.isApplicationScopeOnCurrentThread()) {
        ComposeWindow(title = title, size = size, content = content)
        return
    }
    withBoundMainDispatcher {
        val window = ComposeWindow(title = title, size = size, content = content)
        try {
            pumpMessages()
        } finally {
            // A no-op on the normal path: closing the window already ran the teardown. This covers
            // leaving the loop through an exception or an externally posted WM_QUIT.
            window.destroy()
        }
    }
}

/** Keeps construction, message pumping, and every teardown callback on one dispatcher lifetime. */
private inline fun <T> withBoundMainDispatcher(block: () -> T): T {
    Win32MainDispatcher.bindToCurrentThread()
    var primaryFailure: Throwable? = null
    try {
        // Work can have been queued while no application was active. Drain it before constructing
        // another scene, then own every subsequent wake-up until the scope is fully torn down.
        Win32MainDispatcher.drain()
        return block()
    } catch (throwable: Throwable) {
        primaryFailure = throwable
        throw throwable
    } finally {
        try {
            Win32MainDispatcher.drainAndUnbindFromCurrentThread()
        } catch (cleanupFailure: Throwable) {
            val primary = primaryFailure
            if (primary == null) throw cleanupFailure
            primary.addSuppressed(cleanupFailure)
        }
    }
}

/**
 * Every window this process has open, so the message loop knows when the last one has gone.
 *
 * The collections are used only by the bound UI thread, while the application owner is read by
 * callers before they bind. Synchronizing all state makes that cross-thread rejection deterministic.
 */
private object ComposeWindows {
    private val lock = makeSynchronizedObject(this)
    private val open = mutableSetOf<ComposeWindow>()

    /** Thread inside [application], where [Window] must return instead of pumping messages. */
    private var applicationThreadId = 0u

    /** True while [pumpMessages] is running, so closing the last window can end it. */
    private var isPumping = false

    fun enterApplicationScope() {
        Win32MainDispatcher.checkCurrentThread()
        val currentThreadId = GetCurrentThreadId()
        synchronized(lock) {
            check(applicationThreadId == 0u) { "application { } is already running" }
            applicationThreadId = currentThreadId
        }
    }

    fun leaveApplicationScope() {
        Win32MainDispatcher.checkCurrentThread()
        val currentThreadId = GetCurrentThreadId()
        synchronized(lock) {
            check(applicationThreadId == currentThreadId) {
                "application { } must leave on its owning message-loop thread"
            }
            applicationThreadId = 0u
        }
    }

    fun isApplicationScopeOnCurrentThread(): Boolean {
        val currentThreadId = GetCurrentThreadId()
        val ownerThreadId = synchronized(lock) { applicationThreadId }
        return when (ownerThreadId) {
            0u -> false
            currentThreadId -> {
                Win32MainDispatcher.checkCurrentThread()
                true
            }
            else -> error(
                "Window must be created on the thread that owns the active application { }"
            )
        }
    }

    fun beginPumping() {
        Win32MainDispatcher.checkCurrentThread()
        synchronized(lock) {
            check(!isPumping) { "The Win32 message loop is already running" }
            isPumping = true
        }
    }

    fun endPumping() {
        Win32MainDispatcher.checkCurrentThread()
        synchronized(lock) {
            check(isPumping) { "The Win32 message loop is not running" }
            isPumping = false
        }
    }

    fun register(window: ComposeWindow) {
        Win32MainDispatcher.checkCurrentThread()
        synchronized(lock) { open.add(window) }
    }

    fun unregister(window: ComposeWindow) {
        Win32MainDispatcher.checkCurrentThread()
        val shouldQuit = synchronized(lock) {
            open.remove(window) && open.isEmpty() && isPumping
        }
        if (shouldQuit) {
            // The last window has gone: let the message loop finish.
            PostQuitMessage(0)
        }
    }

    fun hasOpenWindows(): Boolean {
        Win32MainDispatcher.checkCurrentThread()
        return synchronized(lock) { open.isNotEmpty() }
    }

    fun destroyAll() {
        Win32MainDispatcher.checkCurrentThread()
        // Copied first: destroying a window unregisters it.
        val windows = synchronized(lock) { open.toList() }
        windows.forEach { window ->
            try {
                window.destroy()
            } catch (throwable: Throwable) {
                reportFailure("Unable to destroy a Compose window:", throwable)
            }
        }
    }
}

/** Drives the three-valued `GetMessageW` contract and is deterministic under native tests. */
internal fun runWin32MessageLoop(
    getMessage: () -> Int,
    dispatchMessage: () -> Unit,
    getMessageFailed: () -> Nothing,
) {
    while (true) {
        when (getMessage()) {
            -1 -> getMessageFailed()
            0 -> return
            else -> dispatchMessage()
        }
    }
}

/** Pumps messages until the last window closes. */
private fun pumpMessages() = memScoped {
    // `application { }` is valid with no windows, as is content that creates and closes a
    // window synchronously. Neither case should block forever waiting for a message.
    if (!ComposeWindows.hasOpenWindows()) return@memScoped
    ComposeWindows.beginPumping()
    try {

        val msg = alloc<MSG>()
        runWin32MessageLoop(
            getMessage = { GetMessageW(msg.ptr, null, 0u, 0u) },
            dispatchMessage = {
                // Thread messages have no window, so DispatchMessage would silently drop them.
                if (msg.hwnd == null && msg.message.toInt() == WM_COMPOSE_DISPATCH) {
                    Win32MainDispatcher.drain()
                } else {
                    TranslateMessage(msg.ptr)
                    DispatchMessageW(msg.ptr)
                }
            },
            getMessageFailed = { error("GetMessageW failed: ${GetLastError()}") },
        )
    } finally {
        ComposeWindows.endPumping()
    }
}

private class ComposeWindow(
    title: String,
    size: DpSize,
    content: @Composable WindowScope.() -> Unit,
) : WindowScope {
    private var isDisposed = false
    private var isConstructionComplete = false
    private var isNativeCleanupComplete = false
    private var isSelfReferenceDisposed = false

    private val textInputService = Win32TextInputService()
    private val _windowInfo = WindowInfoImpl().apply { isWindowFocused = true }
    private val archComponentsOwner = DefaultArchitectureComponentsOwner()

    /**
     * Window state the lifecycle is derived from. Win32 reports activation and minimization as two
     * independent messages, so both are tracked and the resulting state is computed in one place --
     * see [syncLifecycleState].
     */
    private var isWindowActive = true
    private var isWindowMinimized = false

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
            var transferSucceeded = false
            val scope = object : PlatformDragAndDropSource.StartTransferScope {
                override fun startDragAndDropTransfer(
                    transferData: DragAndDropTransferData,
                    decorationSize: Size,
                    drawDragDecoration: DrawScope.() -> Unit,
                ): Boolean {
                    if (oleApartment?.supportsOle != true ||
                        !transferData.isSupportedOutgoingPayload
                    ) return false
                    // The shell draws its own drag image, so the decoration is not used.
                    return startTextDrag(transferData.text!!).also { transferSucceeded = it }
                }
            }
            with(source) { scope.startDragAndDropTransfer(offset) { transferSucceeded } }
        }
    }

    /** Hosts native child windows placed by [androidx.compose.ui.viewinterop.Win32View]. */
    private var interopContainer: Win32InteropContainer? = null

    /** Shell drop target, registered once the window exists. */
    private var dropTarget: Win32DropTarget? = null

    /** This window's balanced contribution to the current thread's OLE initialization count. */
    private var oleApartment: OleApartment? = null

    /** Last event seen from the shell, so leave/end can be reported with a position. */
    private var lastDragEvent: DragAndDropEvent? = null

    /** Cursor for the client area, applied on every `WM_SETCURSOR`. */
    // Starts as the arrow, not null: WM_SETCURSOR runs long before Compose first sets a pointer
    // icon, and SetCursor(null) hides the cursor rather than leaving it alone.
    private var cursor: HCURSOR? = Win32Cursors.Arrow.win32CursorOrDefault()

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

    /** Delivers every terminal drag callback once, preserving both failures if either throws. */
    private fun finishDrag(event: DragAndDropEvent, sendExited: Boolean) {
        var failure: Throwable? = null
        if (sendExited) {
            try {
                scene.rootDragAndDropNode.onExited(event)
            } catch (throwable: Throwable) {
                failure = throwable
            }
        }
        try {
            scene.rootDragAndDropNode.onEnded(event)
        } catch (throwable: Throwable) {
            val first = failure
            if (first == null) failure = throwable else first.addSuppressed(throwable)
        } finally {
            lastDragEvent = null
        }
        failure?.let { throw it }
    }

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

    override val window: HWND = try {
        createWindow(title, size, selfRef)
    } catch (throwable: Throwable) {
        releaseSelfReferenceWithoutWindow()
        try {
            scene.close()
        } catch (closeFailure: Throwable) {
            throwable.addSuppressed(closeFailure)
        }
        try {
            frameRecomposer.close()
        } catch (closeFailure: Throwable) {
            throwable.addSuppressed(closeFailure)
        }
        throw throwable
    }

    override val renderApi: GraphicsApi
        get() = skiaLayer.renderApi

    init {
        // CreateWindowExW synchronously sends construction messages before assigning [window].
        // Only messages received after this point may enter the fully initialized window logic.
        isConstructionComplete = true
        try {
            // A live window is at least CREATED before any composition or lifecycle observer can
            // run. Besides matching the lifecycle contract, this makes a synchronous WM_CLOSE from
            // initial composition a valid CREATED -> DESTROYED transition.
            archComponentsOwner.setLifecycleState(Lifecycle.State.CREATED)
            archComponentsOwner.enableSavedStateHandles()

            // Register before entering Skia, lifecycle, or composition code. Any of those can invoke
            // user code synchronously, and that code is allowed to send WM_CLOSE/DestroyWindow.
            // WM_DESTROY must therefore be able to unregister this window before construction
            // resumes; registering after setContent would resurrect an already-destroyed HWND.
            ComposeWindows.register(this)

            skiaLayer.renderDelegate = renderDelegate
            skiaLayer.attachTo(window) // Subclasses the window procedure, so create the window first.

            // IMM32 calls need the window that owns the input context.
            textInputService.window = window

            // S_OK and S_FALSE both require a later OleUninitialize. RPC_E_CHANGED_MODE does not
            // and cannot support OLE drag-and-drop on this apartment.
            oleApartment = OleApartment.initialize()
            val ole = oleApartment!!
            if (ole.supportsOle) {
                val registration = Win32DropTarget.register(
                    hwnd = window,
                    onDragEnter = { event ->
                        lastDragEvent = null
                        val accepted = scene.rootDragAndDropNode.acceptDragAndDropTransfer(event)
                        if (accepted) {
                            lastDragEvent = event
                            try {
                                scene.rootDragAndDropNode.onStarted(event)
                                scene.rootDragAndDropNode.onEntered(event)
                            } catch (throwable: Throwable) {
                                try {
                                    finishDrag(event, sendExited = false)
                                } catch (finishFailure: Throwable) {
                                    throwable.addSuppressed(finishFailure)
                                }
                                throw throwable
                            }
                        }
                        accepted
                    },
                    onDragOver = { event ->
                        lastDragEvent = event
                        scene.rootDragAndDropNode.onMoved(event)
                    },
                    onDragLeave = onDragLeave@{
                        val event = lastDragEvent ?: return@onDragLeave
                        finishDrag(event, sendExited = true)
                    },
                    onDrop = { event ->
                        lastDragEvent = event
                        var accepted = false
                        var failure: Throwable? = null
                        try {
                            accepted = scene.rootDragAndDropNode.onDrop(event)
                        } catch (throwable: Throwable) {
                            failure = throwable
                        }
                        try {
                            finishDrag(event, sendExited = false)
                        } catch (finishFailure: Throwable) {
                            val first = failure
                            if (first == null) failure = finishFailure else first.addSuppressed(finishFailure)
                        }
                        failure?.let { throw it }
                        accepted
                    },
                )
                checkNotNull(registration.target) {
                    "RegisterDragDrop failed: HRESULT 0x${registration.hresult.toUInt().toString(16)}"
                }
                dropTarget = registration.target
            } else {
                reportOleUnavailable(ole)
            }

            scene.density = Density(windowScale(window))
            interopContainer = Win32InteropContainer(window).also { it.startObserving() }
            if (!isDisposed) {
                scene.setContent {
                    CompositionLocalProvider(LocalInteropContainer provides interopContainer!!) {
                        // Tracks where interop children sit in the draw order, so their z-order can follow.
                        interopContainer!!.TrackInteropPlacementContainer {
                            content()
                        }
                    }
                }
            }

            // Initial composition and lifecycle observers run synchronously and may have destroyed
            // the HWND. Never initialize, show, or update an object after WM_DESTROY disposed it.
            if (!isDisposed) {
                syncLifecycleState()
            }
            if (!isDisposed) ShowWindow(window, SW_SHOWNORMAL)
            if (!isDisposed) UpdateWindow(window)
        } catch (throwable: Throwable) {
            try {
                destroy()
            } catch (destroyFailure: Throwable) {
                throwable.addSuppressed(destroyFailure)
            }
            throw throwable
        }
    }

    /**
     * Closes the window if it is still alive. Destruction is synchronous, so [dispose] has run by
     * the time this returns.
     */
    fun destroy() {
        if (!isDisposed) {
            check(DestroyWindow(window) != 0) { "DestroyWindow failed: ${GetLastError()}" }
        }
    }

    /**
     * Brings the lifecycle in line with the window's current state.
     *
     * A minimized window is not visible, so it drops to CREATED; a window that is visible but not
     * focused is STARTED; a focused, visible window is RESUMED. Anything observing the lifecycle --
     * `ViewModel`s, `repeatOnLifecycle`, `collectAsStateWithLifecycle` -- stops doing work when the
     * user is not looking at the window, which is the whole point of it.
     *
     * The target state is assigned rather than the events being posted directly: [LifecycleRegistry]
     * walks to it one event at a time, and posting e.g. ON_STOP while RESUMED would throw. Once
     * DESTROYED nothing may move it again, so [dispose] wins permanently.
     */
    private fun syncLifecycleState() {
        val registry = archComponentsOwner.lifecycle
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        registry.currentState = when {
            isWindowMinimized -> Lifecycle.State.CREATED
            !isWindowActive -> Lifecycle.State.STARTED
            else -> Lifecycle.State.RESUMED
        }
    }

    /**
     * Returns the message result, or null to let `DefWindowProcW` handle the message.
     */
    fun handleMessage(hwnd: HWND, message: UINT, wParam: WPARAM, lParam: LPARAM): LRESULT? {
        if (!isConstructionComplete) {
            if (message.toInt() == WM_NCDESTROY) releaseSelfReference(hwnd)
            return null
        }
        if (message.toInt() == WM_NCDESTROY) {
            // WM_DESTROY runs before Windows destroys child HWNDs; WM_NCDESTROY is the last
            // message, after the children are gone. Managed composition teardown normally ran in
            // WM_DESTROY so every interop onRelease callback saw its live child. Keep only the
            // Skia subclass/back-pointer cleanup here.
            disposeManagedResources()
            disposeNativeWindow(hwnd)
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
                // null means the message was half of a surrogate pair with nothing to deliver yet.
                val keyEvent = win32KeyEvent(message, wParam, lParam)
                if (keyEvent != null && scene.sendKeyEvent(keyEvent)) 0 else null
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
            WM_ACTIVATE -> {
                // The activation state is the low word; the high word is the minimized flag, which
                // WM_SIZE reports more reliably.
                isWindowActive = (wParam.toInt() and 0xFFFF) != WA_INACTIVE
                syncLifecycleState()
                // Null, not 0: DefWindowProcW still has focus work to do for this message.
                null
            }
            WM_SIZE -> {
                when (wParam.toInt()) {
                    SIZE_MINIMIZED -> isWindowMinimized = true
                    SIZE_RESTORED, SIZE_MAXIMIZED -> isWindowMinimized = false
                }
                syncLifecycleState()
                null
            }
            WM_DPICHANGED -> {
                onDpiChanged(lParam)
                0
            }
            WM_CLOSE -> {
                destroy()
                0
            }
            WM_DESTROY -> {
                // DestroyWindow sends WM_DESTROY before destroying child HWNDs. Closing the scene
                // here synchronously releases interop holders while their child handles are still
                // valid; waiting for WM_NCDESTROY would violate Win32's lifetime ordering.
                disposeManagedResources()
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

    private fun releaseOleDragDrop() {
        val target = dropTarget
        if (target != null) {
            val result = target.dispose()
            if (result != S_OK && result != S_FALSE) {
                reportFailure(
                    "RevokeDragDrop failed:",
                    IllegalStateException("HRESULT 0x${result.toUInt().toString(16)}"),
                )
                // A failed RevokeDragDrop did not release OLE's COM reference. Preserve the target
                // and apartment so WM_NCDESTROY can retry while the HWND is still in its final
                // message, instead of freeing a vtable that OLE may still call.
                return
            }
            dropTarget = null
        }

        val apartment = oleApartment
        oleApartment = null
        if (apartment != null) {
            try {
                apartment.close()
            } catch (throwable: Throwable) {
                reportFailure("Unable to close the window's OLE apartment:", throwable)
            }
        }
    }

    /** Releases everything whose teardown may touch this window or any native child window. */
    private fun disposeManagedResources() {
        if (isDisposed) return
        isDisposed = true

        // Revoke while the HWND is still valid. RevokeDragDrop returns OLE's reference before
        // OleUninitialize returns this window's apartment initialization count.
        releaseOleDragDrop()

        val interop = interopContainer
        interopContainer = null
        disposeStep("stop observing native child windows") { interop?.stopObserving() }
        textInputService.window = null
        disposeStep("destroy the lifecycle") {
            archComponentsOwner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        disposeStep("clear the ViewModel store") { archComponentsOwner.viewModelStore.clear() }
        // Closing the scene releases every Win32InteropViewHolder synchronously. This must precede
        // DefWindowProcW's child-destruction phase that follows WM_DESTROY.
        disposeStep("close the Compose scene") { scene.close() }
        disposeStep("close the frame recomposer") { frameRecomposer.close() }
        // Closing the final window posts WM_QUIT. Do it only after scene/recomposer shutdown has
        // finished posting dispatcher work, or GetMessageW could exit with stale work behind it.
        disposeStep("unregister the Compose window") { ComposeWindows.unregister(this) }
    }

    /** Releases the window subclass and StableRef at WM_NCDESTROY, the HWND's final message. */
    private fun disposeNativeWindow(hwnd: HWND) {
        if (isNativeCleanupComplete) return
        isNativeCleanupComplete = true
        if (dropTarget != null || oleApartment != null) releaseOleDragDrop()
        disposeStep("detach the Skia layer") { skiaLayer.detach() }
        releaseSelfReference(hwnd)
    }

    private inline fun disposeStep(name: String, block: () -> Unit) {
        try {
            block()
        } catch (throwable: Throwable) {
            reportFailure("Failed to $name:", throwable)
        }
    }

    private fun releaseSelfReference(hwnd: HWND) {
        if (isSelfReferenceDisposed) return
        isSelfReferenceDisposed = true
        // Clear the back-pointer before freeing it, so nothing can resolve a dangling reference.
        SetWindowLongPtrW(hwnd, 0, 0)
        selfRef.dispose()
    }

    private fun releaseSelfReferenceWithoutWindow() {
        if (isSelfReferenceDisposed) return
        isSelfReferenceDisposed = true
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

private fun reportOleUnavailable(apartment: OleApartment) {
    val reason = when (apartment.status) {
        OleInitializationStatus.ChangedMode ->
            "the UI thread was already initialized as a multithreaded COM apartment"
        OleInitializationStatus.Failed ->
            "OleInitialize returned HRESULT 0x${apartment.hresult.toUInt().toString(16)}"
        OleInitializationStatus.Initialized,
        OleInitializationStatus.AlreadyInitialized -> return
    }
    println("OLE drag-and-drop is unavailable because $reason.")
    fflush(null)
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
    ensureProcessDpiAwareness()

    val windowClass = alloc<WNDCLASSEXW>()
    windowClass.cbSize = sizeOf<WNDCLASSEXW>().convert()
    windowClass.style = (CS_HREDRAW or CS_VREDRAW).convert()
    windowClass.lpfnWndProc = staticCFunction(::composeWindowProc)
    windowClass.hInstance = GetModuleHandleW(null)
    // The cursor is chosen per frame by the content, and skia paints every pixel itself.
    // A sane class cursor as a fallback for any path that does not reach WM_SETCURSOR.
    windowClass.hCursor = Win32Cursors.Arrow.win32CursorOrDefault()
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
        val self = lParam.toCPointer<CREATESTRUCTW>()?.pointed?.lpCreateParams ?: return 0
        // A zero return can mean either "the previous value was zero" or failure. Clear last-error
        // first so the second case is distinguishable and abort creation without a dangling ref.
        SetLastError(0u)
        val previous = SetWindowLongPtrW(hwnd, 0, self.toLong())
        if (previous == 0L && GetLastError() != 0u) {
            return 0
        }
        return DefWindowProcW(hwnd, message, wParam, lParam)
    }
    val window = hwnd?.let(::composeWindowOf)
        ?: return DefWindowProcW(hwnd, message, wParam, lParam)
    return window.handleMessage(hwnd, message, wParam, lParam)
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
