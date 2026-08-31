/*
 * Copyright 2026 The Android Open Source Project
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

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package androidx.compose.ui.test

import androidx.compose.ui.test.platform.makeSynchronizedObject
import androidx.compose.ui.test.platform.synchronized
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import platform.windows.GetCurrentThreadId
import platform.windows.Sleep

/**
 * One process-wide physical UI thread, leased by active test scenes.
 *
 * A platform UI thread is process infrastructure, not scene infrastructure. Keeping the worker
 * alive across sequential scenes both matches that contract and prevents scene creation from
 * paying the native worker start-up cost on every test action. The OS reclaims this single worker
 * when the native test process exits; [owners] still prevents use outside an active scene.
 */
private object MingwTestUiThread {
    private class State(
        val dispatcher: CloseableCoroutineDispatcher,
        val threadId: UInt,
    )

    private val lock = makeSynchronizedObject()
    private var state: State? = null
    private var owners = 0

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    fun acquire() {
        synchronized(lock) {
            state?.let {
                owners++
                return
            }

            val dispatcher = newSingleThreadContext("Compose UI test thread")
            try {
                val threadId = runBlocking(dispatcher) { GetCurrentThreadId() }
                state = State(dispatcher, threadId)
                owners = 1
            } catch (throwable: Throwable) {
                dispatcher.close()
                throw throwable
            }
        }
    }

    fun release() {
        synchronized(lock) {
            check(owners > 0) { "The Compose UI test thread was not acquired" }
            owners--
        }
    }

    fun isCurrent(): Boolean {
        val threadId = synchronized(lock) {
            if (owners > 0) state?.threadId else null
        }
        return threadId != null && GetCurrentThreadId() == threadId
    }

    fun <T> run(action: () -> T): T {
        val currentState = synchronized(lock) {
            check(owners > 0) { "The Compose UI test thread was not acquired" }
            checkNotNull(state) { "The Compose UI test thread was not acquired" }
        }
        return if (GetCurrentThreadId() == currentState.threadId) {
            action()
        } else {
            runBlocking(currentState.dispatcher) {
                check(GetCurrentThreadId() == currentState.threadId) {
                    "The Compose UI test dispatcher changed physical threads"
                }
                action()
            }
        }
    }
}

internal actual fun <T> runOnUiThread(action: () -> T): T = MingwTestUiThread.run(action)

internal actual fun isOnUiThread(): Boolean = MingwTestUiThread.isCurrent()

internal actual fun sleep(timeMillis: Long) {
    require(timeMillis >= 0) { "timeMillis must not be negative" }
    var remainingMillis = timeMillis
    while (remainingMillis > 0) {
        // `0xFFFFFFFF` is Win32's INFINITE sentinel, not a finite duration.
        val chunk = minOf(remainingMillis, UInt.MAX_VALUE.toLong() - 1L)
        Sleep(chunk.toUInt())
        remainingMillis -= chunk
    }
}

internal actual fun acquireTestUiThread() = MingwTestUiThread.acquire()

internal actual fun releaseTestUiThread() = MingwTestUiThread.release()
