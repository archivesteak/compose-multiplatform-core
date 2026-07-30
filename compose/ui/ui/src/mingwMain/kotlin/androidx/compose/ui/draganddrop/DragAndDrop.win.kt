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

package androidx.compose.ui.draganddrop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset

/**
 * A drag-and-drop event from the shell, as delivered to `IDropTarget`.
 *
 * The payload is read out of the `IDataObject` when the drag arrives, so the contents stay
 * readable after the OLE call has returned — the data object itself is only valid for the
 * duration of that call.
 */
actual class DragAndDropEvent internal constructor(
    /** Where the pointer is, in the window's client area, in physical pixels. */
    internal val position: Offset,

    /** Plain text being dragged (`CF_UNICODETEXT`), or null if the drag carries none. */
    @property:ExperimentalComposeUiApi
    val text: String? = null,

    /** Paths being dragged (`CF_HDROP`); empty when the drag carries no files. */
    @property:ExperimentalComposeUiApi
    val files: List<String> = emptyList(),

    /** The `MK_*` modifier and button state reported with the drag. */
    @property:ExperimentalComposeUiApi
    val keyState: Int = 0,
)

/**
 * Returns the position of this [DragAndDropEvent] relative to the root Compose scene.
 */
internal actual val DragAndDropEvent.positionInRoot: Offset
    get() = position

/**
 * Describes a drag started from inside Compose.
 *
 * Outgoing drags are not wired to `DoDragDrop` yet, so this carries the payload without a shell
 * session behind it; incoming drops are fully supported.
 */
actual class DragAndDropTransferData @ExperimentalComposeUiApi constructor(
    /** Plain text to hand to the drop target. */
    @property:ExperimentalComposeUiApi
    val text: String? = null,

    /** Paths to hand to the drop target. */
    @property:ExperimentalComposeUiApi
    val files: List<String> = emptyList(),
)
