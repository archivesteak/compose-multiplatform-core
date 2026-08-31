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

package androidx.compose.ui.window

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.Win32View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import platform.windows.CreateWindowExW
import platform.windows.GetModuleHandleW
import platform.windows.HWND
import platform.windows.IsWindow
import platform.windows.PostMessageW
import platform.windows.SendMessageW
import platform.windows.WM_CLOSE
import platform.windows.WS_CHILD

class ComposeWindowInteropTest {
    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun activeApplicationRejectsWindowCreationFromAnotherPhysicalThread() {
        val otherThread = newSingleThreadContext("Wrong Compose window thread")
        try {
            lateinit var failure: IllegalStateException
            application {
                failure = runBlocking(otherThread) {
                    assertFailsWith {
                        Window(
                            title = "Window that must never be created",
                            size = DpSize(64.dp, 64.dp),
                        ) {}
                    }
                }
            }

            assertEquals(
                "Window must be created on the thread that owns the active application { }",
                failure.message,
            )
        } finally {
            otherThread.close()
        }
    }

    @Test
    fun dispatcherDrainRunsEveryAcceptedTaskAndAggregatesFailures() {
        val calls = mutableListOf<String>()
        Win32MainDispatcher.bindToCurrentThread()
        try {
            Win32MainDispatcher.dispatch(
                EmptyCoroutineContext,
                Runnable {
                    calls += "first"
                    error("first dispatcher failure")
                },
            )
            Win32MainDispatcher.dispatch(
                EmptyCoroutineContext,
                Runnable { calls += "middle" },
            )
            Win32MainDispatcher.dispatch(
                EmptyCoroutineContext,
                Runnable {
                    calls += "last"
                    error("last dispatcher failure")
                },
            )

            val failure = assertFailsWith<IllegalStateException> {
                Win32MainDispatcher.drain()
            }
            assertEquals("first dispatcher failure", failure.message)
            assertEquals(listOf("first", "middle", "last"), calls)
            assertEquals(1, failure.suppressedExceptions.size)
            assertEquals("last dispatcher failure", failure.suppressedExceptions.single().message)
        } finally {
            Win32MainDispatcher.drainAndUnbindFromCurrentThread()
        }
    }

    @Test
    fun applicationFailureDrainsTeardownWorkBeforeDispatcherUnbinds() {
        var cleanupRuns = 0

        val failure = assertFailsWith<IllegalStateException> {
            application {
                Window(title = "Compose failed application cleanup test", size = DpSize(64.dp, 64.dp)) {
                    DisposableEffect(Unit) {
                        onDispose {
                            Win32MainDispatcher.dispatch(
                                EmptyCoroutineContext,
                                Runnable { cleanupRuns++ },
                            )
                        }
                    }
                }
                error("application content failed")
            }
        }

        assertEquals("application content failed", failure.message)
        assertEquals(1, cleanupRuns, "failure-path teardown work must run before dispatcher unbind")
        application {}
        assertEquals(1, cleanupRuns, "a later application must not inherit disposed-scene work")
    }

    @Test
    fun synchronousCloseDuringInitialCompositionDoesNotRegisterDeadWindow() {
        var createdWindow: HWND? = null
        var closeSent = false
        var initialLifecycleState: Lifecycle.State? = null

        application {
            Window(title = "Compose synchronous construction close test", size = DpSize(64.dp, 64.dp)) {
                createdWindow = window
                initialLifecycleState = LocalLifecycleOwner.current.lifecycle.currentState
                SideEffect {
                    if (!closeSent) {
                        closeSent = true
                        // Send, rather than post: this destroys the HWND before setContent returns.
                        SendMessageW(window, WM_CLOSE.toUInt(), 0u, 0)
                    }
                }
            }
        }

        val destroyedWindow = assertNotNull(createdWindow)
        assertTrue(closeSent)
        assertEquals(Lifecycle.State.CREATED, initialLifecycleState)
        assertFalse(IsWindow(destroyedWindow) != 0)

        // A stale registration would make this second empty application wait forever.
        application {}
    }

    @Test
    fun interopReleaseReceivesLiveChildBeforeParentDestroysIt() {
        var child: HWND? = null
        var releaseSawLiveChild = false
        var closePosted = false
        var cleanupRuns = 0

        Window(title = "Compose interop teardown test", size = DpSize(64.dp, 64.dp)) {
            val parent = window
            Win32View(
                factory = {
                    assertNotNull(
                        CreateWindowExW(
                            0u,
                            "STATIC",
                            "Compose interop child",
                            WS_CHILD.convert(),
                            0,
                            0,
                            16,
                            16,
                            parent,
                            null,
                            GetModuleHandleW(null),
                            null,
                        )
                    ).also { child = it }
                },
                update = {
                    if (!closePosted) {
                        closePosted = true
                        check(PostMessageW(parent, WM_CLOSE.toUInt(), 0u, 0) != 0) {
                            "PostMessageW(WM_CLOSE) failed"
                        }
                    }
                },
                onRelease = {
                    releaseSawLiveChild = IsWindow(it) != 0
                    Win32MainDispatcher.dispatch(EmptyCoroutineContext, Runnable { cleanupRuns++ })
                },
            )
        }

        val releasedChild = assertNotNull(child)
        assertTrue(releaseSawLiveChild, "onRelease must run while its child HWND is valid")
        assertFalse(IsWindow(releasedChild) != 0, "Compose must destroy the child after onRelease")
        assertEquals(1, cleanupRuns, "shutdown work must run before the final WM_QUIT")

        // A later application must not inherit work stranded behind the preceding loop's WM_QUIT.
        application {}
        assertEquals(1, cleanupRuns)
    }
}
