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

package androidx.compose.foundation.text

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.input.internal.selection.TextFieldSelectionState
import androidx.compose.foundation.text.selection.SelectionManager
import androidx.compose.foundation.text.selection.TextFieldSelectionManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation

/** Windows uses the same editing shortcuts as the other non-Apple platforms. */
internal actual val platformDefaultKeyMapping: KeyMapping = defaultKeyMapping

internal actual val isInTouchMode = false

/**
 * Only `WM_CHAR` carries a code point, and it is the Win32 counterpart of AWT's `KEY_TYPED`:
 * the system has already applied the keyboard layout, dead keys and modifiers. Key-down messages
 * arrive with a zero code point, which the ISO-control test rejects.
 */
internal actual val KeyEvent.isTypedEvent: Boolean
    get() = type == KeyEventType.KeyDown &&
        !isISOControl(utf16CodePoint) &&
        !isMetaPressed &&
        !isCtrlPressed

private fun isISOControl(codePoint: Int): Boolean =
    codePoint in 0x00..0x1F || codePoint in 0x7F..0x9F

@Composable
internal actual fun ContextMenuArea(
    manager: TextFieldSelectionManager,
    content: @Composable () -> Unit
) = CommonContextMenuArea(manager, content)

@Composable
internal actual fun ContextMenuArea(
    selectionState: TextFieldSelectionState,
    enabled: Boolean,
    content: @Composable () -> Unit
) = CommonContextMenuArea(selectionState, enabled, content)

@Composable
internal actual fun ContextMenuArea(
    manager: SelectionManager,
    content: @Composable () -> Unit
) = CommonContextMenuArea(manager, content)

internal actual fun Modifier.textFieldCursor(
    state: LegacyTextFieldState,
    value: TextFieldValue,
    offsetMapping: OffsetMapping,
    cursorBrush: Brush,
    showCursor: Boolean,
): Modifier = cursor(state, value, offsetMapping, cursorBrush, showCursor)

internal actual fun Modifier.textFieldDraw(
    state: LegacyTextFieldState,
    value: TextFieldValue,
    offsetMapping: OffsetMapping,
): Modifier = defaultTextFieldDraw(state, value, offsetMapping)

@Composable
internal actual fun Modifier.textFieldPointer(
    manager: TextFieldSelectionManager,
    enabled: Boolean,
    interactionSource: MutableInteractionSource?,
    state: LegacyTextFieldState,
    focusRequester: FocusRequester,
    readOnly: Boolean,
    offsetMapping: OffsetMapping
): Modifier = defaultTextFieldPointer(
    manager,
    enabled,
    interactionSource,
    state,
    focusRequester,
    readOnly,
    offsetMapping,
)

@ExperimentalFoundationApi
@Composable
internal actual fun rememberTextFieldOverscrollEffect(): OverscrollEffect? = null

internal actual fun Modifier.textFieldScroll(
    scrollerPosition: TextFieldScrollerPosition,
    textFieldValue: TextFieldValue,
    visualTransformation: VisualTransformation,
    overscrollEffect: OverscrollEffect?,
    textLayoutResultProvider: () -> TextLayoutResultProxy?
): Modifier = defaultTextFieldScroll(
    scrollerPosition,
    textFieldValue,
    visualTransformation,
    overscrollEffect,
    textLayoutResultProvider,
)
