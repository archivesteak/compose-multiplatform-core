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

@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package androidx.compose.ui.draganddrop

import androidx.compose.ui.win32.DRAGDROP_S_CANCEL
import androidx.compose.ui.win32.DRAGDROP_S_DROP
import androidx.compose.ui.win32.E_UNEXPECTED
import androidx.compose.ui.win32.didStartDragLoop
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Win32DragAndDropTest {
    @Test
    fun cancelledDragCountsAsStartedSoNestedSourcesAreNotRetried() {
        assertTrue(didStartDragLoop(DRAGDROP_S_CANCEL))
        assertTrue(didStartDragLoop(DRAGDROP_S_DROP))
        assertFalse(didStartDragLoop(E_UNEXPECTED))
    }

    @Test
    fun outgoingPayloadRequiresTextAndRejectsEveryFilePayload() {
        assertTrue(DragAndDropTransferData(text = "text").isSupportedOutgoingPayload)
        assertTrue(DragAndDropTransferData(text = "").isSupportedOutgoingPayload)
        assertFalse(DragAndDropTransferData().isSupportedOutgoingPayload)
        assertFalse(DragAndDropTransferData(files = listOf("C:\\file.txt")).isSupportedOutgoingPayload)
        assertFalse(
            DragAndDropTransferData(
                text = "text",
                files = listOf("C:\\file.txt"),
            ).isSupportedOutgoingPayload
        )
    }
}
