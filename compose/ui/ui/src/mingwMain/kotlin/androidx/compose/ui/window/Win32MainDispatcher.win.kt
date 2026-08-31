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

package androidx.compose.ui.window

import androidx.compose.ui.platform.makeSynchronizedObject
import androidx.compose.ui.platform.synchronized
import kotlin.coroutines.CoroutineContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import platform.windows.GetCurrentThreadId
import platform.windows.GetLastError
import platform.windows.MSG
import platform.windows.PM_NOREMOVE
import platform.windows.PM_REMOVE
import platform.windows.PeekMessageW
import platform.windows.PostThreadMessageW
import platform.windows.WM_APP

/** Private message that asks the UI thread to drain [Win32MainDispatcher]. */
internal const val WM_COMPOSE_DISPATCH = WM_APP + 1

/**
 * Confines coroutine dispatch to the thread that pumps the Win32 message loop — the role
 * `Dispatchers.Main` plays on Android and Apple platforms, which Kotlin/Native does not provide
 * for `mingwX64`.
 *
 * Compose needs this in two places that outlive any single window:
 * [androidx.compose.ui.platform.FrameRecomposer], which requires a single-threaded
 * [kotlin.coroutines.ContinuationInterceptor], and `postDelayed`, which the layout system calls
 * while a scene is still being constructed. So the dispatcher is process-wide and wakes the UI
 * thread with a *thread* message rather than a window message: work can be queued before a window
 * exists, and it still arrives on the right thread.
 */
internal object Win32MainDispatcher : CoroutineDispatcher() {
    private val lock = makeSynchronizedObject(this)
    private var queue = ArrayDeque<Runnable>()

    /** Id of the thread running the message loop; 0 until [bindToCurrentThread] is called. */
    private var loopThreadId: UInt = 0u

    /**
     * Marks the calling thread as the one that pumps messages. Called by the message loop before
     * it starts, so anything queued during start-up is delivered once it is running.
     */
    fun bindToCurrentThread() {
        // PostThreadMessage fails until the destination has created a message queue. PeekMessage
        // creates it without consuming anything, before the thread id becomes visible to dispatch.
        memScoped {
            val message = alloc<MSG>()
            PeekMessageW(message.ptr, null, 0u, 0u, PM_NOREMOVE.convert())
        }
        val currentThreadId = GetCurrentThreadId()
        synchronized(lock) {
            check(loopThreadId == 0u) {
                "The Win32 main dispatcher is already bound to a message-loop thread"
            }
            loopThreadId = currentThreadId
        }
    }

    /** Fails before native UI state can be touched from a thread that does not own the loop. */
    fun checkCurrentThread() {
        val currentThreadId = GetCurrentThreadId()
        synchronized(lock) {
            check(loopThreadId != 0u && loopThreadId == currentThreadId) {
                "Win32 UI work must run on the bound message-loop thread"
            }
        }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val threadId = synchronized(lock) {
            queue.addLast(block)
            loopThreadId
        }
        // Outside the lock: PostThreadMessage is thread-safe, and a task must never run holding it.
        // A zero id means the loop has not started yet; it drains the backlog when it does.
        if (threadId != 0u) {
            if (PostThreadMessageW(threadId, WM_COMPOSE_DISPATCH.convert(), 0uL, 0L) == 0) {
                val error = GetLastError()
                val rejected = synchronized(lock) {
                    // If the loop unbound concurrently, the queued task is intentional backlog for
                    // the next bind. If drain already took it, it will run without this wake-up.
                    loopThreadId == threadId && queue.remove(block)
                }
                check(!rejected) {
                    "PostThreadMessageW(WM_COMPOSE_DISPATCH) failed: $error"
                }
            }
        }
    }

    /**
     * Runs the work queued so far, on the calling (message loop) thread.
     *
     * Tasks queued *while* draining are left for the next drain rather than extending this one —
     * their own [dispatch] has already posted a wake-up, so a task that reposts itself yields to
     * pending window messages instead of starving the loop.
     */
    fun drain() {
        val currentThreadId = GetCurrentThreadId()
        val pending = synchronized(lock) {
            check(loopThreadId == currentThreadId) {
                "The Win32 main dispatcher must be drained by its message-loop thread"
            }
            val pending = queue
            queue = ArrayDeque()
            pending
        }
        var failure: Throwable? = null
        while (pending.isNotEmpty()) {
            try {
                pending.removeFirst().run()
            } catch (throwable: Throwable) {
                val first = failure
                if (first == null) failure = throwable else first.addSuppressed(throwable)
            }
        }
        failure?.let { throw it }
    }

    /**
     * Runs every task accepted by the active loop, then atomically stops accepting wake-ups for it.
     * Tasks dispatched after the unbind become intentional backlog for the next application.
     */
    fun drainAndUnbindFromCurrentThread() {
        val currentThreadId = GetCurrentThreadId()
        var failure: Throwable? = null
        while (true) {
            try {
                drain()
            } catch (throwable: Throwable) {
                val first = failure
                if (first == null) failure = throwable else first.addSuppressed(throwable)
            }
            val unbound = synchronized(lock) {
                check(loopThreadId == currentThreadId) {
                    "The Win32 main dispatcher must be unbound by its message-loop thread"
                }
                if (queue.isEmpty()) {
                    loopThreadId = 0u
                    true
                } else {
                    false
                }
            }
            if (unbound) break
        }

        // Wake messages corresponding to the tasks just drained are redundant. Remove them before
        // a later application binds this same OS thread, without touching any other message.
        memScoped {
            val message = alloc<MSG>()
            while (
                PeekMessageW(
                    message.ptr,
                    null,
                    WM_COMPOSE_DISPATCH.convert(),
                    WM_COMPOSE_DISPATCH.convert(),
                    PM_REMOVE.convert(),
                ) != 0
            ) {
                // discard
            }
        }
        failure?.let { throw it }
    }
}
