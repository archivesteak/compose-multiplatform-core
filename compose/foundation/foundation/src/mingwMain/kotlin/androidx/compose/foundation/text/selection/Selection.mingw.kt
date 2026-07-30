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

package androidx.compose.foundation.text.selection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.DpSize
import kotlinx.coroutines.CoroutineScope

private val DefaultSelectionColor = Color(0xFF4286F4)

@Stable
internal actual val DefaultTextSelectionColors = TextSelectionColors(
    handleColor = DefaultSelectionColor,
    backgroundColor = DefaultSelectionColor.copy(alpha = 0.4f)
)

internal actual val FirstLongPressSelectionAdjustment: SelectionAdjustment
    get() = SelectionAdjustment.Word

@Composable
internal actual fun SelectionHandle(
    offsetProvider: OffsetProvider,
    isStartHandle: Boolean,
    direction: ResolvedTextDirection,
    handlesCrossed: Boolean,
    minTouchTargetSize: DpSize,
    lineHeight: Float,
    modifier: Modifier
) = SkikoSelectionHandle(
    offsetProvider = offsetProvider,
    isStartHandle = isStartHandle,
    direction = direction,
    handlesCrossed = handlesCrossed,
    minTouchTargetSize = minTouchTargetSize,
    lineHeight = lineHeight,
    modifier = modifier
)

// this doesn't sounds very sustainable
// it would end up being a function for any conceptual keyevent (selectall, cut, copy, paste)
// TODO(b/1564937)
internal actual fun isCopyKeyEvent(keyEvent: KeyEvent): Boolean =
    keyEvent.key == Key.C && keyEvent.isCtrlPressed || keyEvent.key == Key.Copy

/** Magnification is not supported on desktop. */
internal actual fun Modifier.selectionMagnifier(manager: SelectionManager): Modifier = this

/** Magnification is not supported on desktop. */
internal actual fun Modifier.textFieldMagnifier(manager: TextFieldSelectionManager): Modifier = this

/** Whether the selection handle is in the visible bound of the TextField. */
internal actual fun TextFieldSelectionManager.isSelectionHandleInVisibleBound(
    isStartHandle: Boolean
): Boolean = isSelectionHandleInVisibleBoundDefault(isStartHandle)

// TODO: https://youtrack.jetbrains.com/issue/CMP-7819
internal actual fun Modifier.addSelectionContainerTextContextMenuComponents(
    selectionManager: SelectionManager
): Modifier = this

// TODO: https://youtrack.jetbrains.com/issue/CMP-7819
internal actual fun Modifier.addBasicTextFieldTextContextMenuComponents(
    manager: TextFieldSelectionManager,
    coroutineScope: CoroutineScope,
): Modifier = this
