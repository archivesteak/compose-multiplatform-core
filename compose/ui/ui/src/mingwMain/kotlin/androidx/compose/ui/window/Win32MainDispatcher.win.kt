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

package androidx.compose.ui.window

import androidx.compose.ui.platform.makeSynchronizedObject
import androidx.compose.ui.platform.synchronized
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable

/**
 * Confines coroutine dispatch to the thread that pumps a window's Win32 message loop.
 *
 * [androidx.compose.ui.platform.FrameRecomposer] requires a single-threaded
 * [kotlin.coroutines.ContinuationInterceptor] — the role `Dispatchers.Main` plays on Android and
 * Apple platforms. Kotlin/Native has no main dispatcher for `mingwX64`, and Windows has no
 * ambient run loop to attach one to: the message loop belongs to the application. So the window
 * owns the dispatcher, and posting a wake-up message is what hands work back to the loop.
 *
 * Work submitted from other threads (a background coroutine resuming on the UI thread, snapshot
 * apply notifications) is queued under [lock] and drained on the loop thread by [drain].
 */
internal class Win32MainDispatcher : CoroutineDispatcher() {
    private val lock = makeSynchronizedObject(this)
    private var queue = ArrayDeque<Runnable>()
    private var wakeUp: (() -> Unit)? = null

    /**
     * Installs the callback that asks the message loop to call [drain] — set once the window it
     * posts to exists. Work dispatched before then stays queued; the window flushes the backlog
     * when it installs its wake-up.
     */
    fun setWakeUp(wakeUp: (() -> Unit)?) {
        synchronized(lock) { this.wakeUp = wakeUp }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val wakeUp = synchronized(lock) {
            queue.addLast(block)
            this.wakeUp
        }
        // Outside the lock: `PostMessage` is thread-safe, and a task must never run holding it.
        wakeUp?.invoke()
    }

    /**
     * Runs the work queued so far, on the calling (message loop) thread.
     *
     * Tasks queued *while* draining are left for the next drain rather than extending this one —
     * their own [dispatch] has already posted a wake-up, so a task that reposts itself yields to
     * pending window messages instead of starving the loop.
     */
    fun drain() {
        val pending = synchronized(lock) {
            val pending = queue
            queue = ArrayDeque()
            pending
        }
        while (pending.isNotEmpty()) {
            pending.removeFirst().run()
        }
    }
}
