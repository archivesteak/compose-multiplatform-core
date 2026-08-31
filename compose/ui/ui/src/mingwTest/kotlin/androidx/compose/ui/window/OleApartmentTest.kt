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

package androidx.compose.ui.window

import androidx.compose.ui.win32.S_FALSE
import androidx.compose.ui.win32.S_OK
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OleApartmentTest {
    @Test
    fun successfulInitializationIsBalancedExactlyOnce() {
        var uninitializations = 0
        val apartment = OleApartment.fromResult(S_OK) { uninitializations++ }

        assertEquals(OleInitializationStatus.Initialized, apartment.status)
        assertTrue(apartment.supportsOle)

        apartment.close()
        apartment.close()

        assertEquals(1, uninitializations)
    }

    @Test
    fun alreadyInitializedResultIsAlsoBalancedExactlyOnce() {
        var uninitializations = 0
        val apartment = OleApartment.fromResult(S_FALSE) { uninitializations++ }

        assertEquals(OleInitializationStatus.AlreadyInitialized, apartment.status)
        assertTrue(apartment.supportsOle)

        apartment.close()
        apartment.close()

        assertEquals(1, uninitializations)
    }

    @Test
    fun successfulInitializationCanOnlyCloseOnItsApartmentThread() {
        var currentThreadId = 2u
        var uninitializations = 0
        val apartment = OleApartment.fromResult(
            hresult = S_OK,
            apartmentThreadId = 1u,
            currentThreadId = { currentThreadId },
            uninitialize = { uninitializations++ },
        )

        assertFailsWith<IllegalStateException> { apartment.close() }
        assertEquals(0, uninitializations)

        currentThreadId = 1u
        apartment.close()
        assertEquals(1, uninitializations)
    }

    @Test
    fun changedApartmentModeIsNotUninitialized() {
        var uninitializations = 0
        val apartment = OleApartment.fromResult(RPC_E_CHANGED_MODE) { uninitializations++ }

        assertEquals(OleInitializationStatus.ChangedMode, apartment.status)
        assertFalse(apartment.supportsOle)
        apartment.close()

        assertEquals(0, uninitializations)
    }

    @Test
    fun arbitraryFailureIsNotUninitialized() {
        var uninitializations = 0
        val apartment = OleApartment.fromResult(-1) { uninitializations++ }

        assertEquals(OleInitializationStatus.Failed, apartment.status)
        assertFalse(apartment.supportsOle)
        apartment.close()

        assertEquals(0, uninitializations)
    }

    @Test
    fun messageLoopDispatchesPositiveResultsAndStopsAtQuit() {
        val results = ArrayDeque(listOf(1, 2, 0))
        var dispatched = 0

        runWin32MessageLoop(
            getMessage = { results.removeFirst() },
            dispatchMessage = { dispatched++ },
            getMessageFailed = { error("unexpected GetMessage failure") },
        )

        assertEquals(2, dispatched)
        assertTrue(results.isEmpty())
    }

    @Test
    fun messageLoopTreatsMinusOneAsAnError() {
        var dispatched = 0

        val failure = assertFailsWith<IllegalStateException> {
            runWin32MessageLoop(
                getMessage = { -1 },
                dispatchMessage = { dispatched++ },
                getMessageFailed = { error("GetMessage failed") },
            )
        }

        assertEquals("GetMessage failed", failure.message)
        assertEquals(0, dispatched)
    }
}
