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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import platform.windows.GetCurrentThreadId
import platform.windows.Sleep

class SynchronizationTest {
    @Test
    fun uiActionsStayOnOnePhysicalThreadAndNestInline() {
        val callerThread = GetCurrentThreadId()
        acquireTestUiThread()
        try {
            val firstThread = runOnUiThread {
                assertTrue(isOnUiThread())
                GetCurrentThreadId()
            }
            Sleep(25u)
            val secondThread = runOnUiThread { GetCurrentThreadId() }
            val nestedThread = runOnUiThread {
                runOnUiThread { GetCurrentThreadId() }
            }

            assertNotEquals(callerThread, firstThread)
            assertEquals(firstThread, secondThread)
            assertEquals(firstThread, nestedThread)
            assertFalse(isOnUiThread())
        } finally {
            releaseTestUiThread()
        }
    }

    @Test
    fun uiActionExceptionPropagatesAndDoesNotPoisonTheThread() {
        acquireTestUiThread()
        try {
            val failure = assertFailsWith<IllegalStateException> {
                runOnUiThread { error("UI action failed") }
            }
            assertEquals("UI action failed", failure.message)
            assertTrue(runOnUiThread { isOnUiThread() })
        } finally {
            releaseTestUiThread()
        }
    }

    @Test
    fun sequentialScenesReuseTheProcessUiThread() {
        acquireTestUiThread()
        val firstThread = try {
            runOnUiThread { GetCurrentThreadId() }
        } finally {
            releaseTestUiThread()
        }

        acquireTestUiThread()
        val secondThread = try {
            runOnUiThread { GetCurrentThreadId() }
        } finally {
            releaseTestUiThread()
        }

        assertEquals(firstThread, secondThread)
    }

    @Test
    fun releasedUiThreadCannotBeUsed() {
        acquireTestUiThread()
        releaseTestUiThread()

        assertFailsWith<IllegalStateException> { runOnUiThread {} }
    }
}
