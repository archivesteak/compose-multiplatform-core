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

package androidx.compose.foundation.text.input.internal.selection

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.TextDragObserver
import androidx.compose.foundation.text.input.internal.TextLayoutState
import androidx.compose.foundation.text.input.internal.TransformedTextFieldState
import androidx.compose.foundation.text.selection.MouseSelectionObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.platform.Clipboard
import kotlinx.coroutines.CoroutineScope

/**
 * Magnification is not supported on desktop, so this is a node that only satisfies the contract.
 */
internal actual fun textFieldMagnifierNode(
    textFieldState: TransformedTextFieldState,
    textFieldSelectionState: TextFieldSelectionState,
    textLayoutState: TextLayoutState,
    visible: Boolean
): TextFieldMagnifierNode {
    return object : TextFieldMagnifierNode() {
        override fun update(
            textFieldState: TransformedTextFieldState,
            textFieldSelectionState: TextFieldSelectionState,
            textLayoutState: TextLayoutState,
            visible: Boolean
        ) {}
    }
}

/** Runs platform-specific text tap gestures logic. */
internal actual suspend fun TextFieldSelectionState.detectTextFieldTapGestures(
    pointerInputScope: PointerInputScope,
    interactionSource: MutableInteractionSource?,
    requestFocus: () -> Unit,
    showKeyboard: () -> Unit,
) = defaultDetectTextFieldTapGestures(
    pointerInputScope,
    interactionSource,
    requestFocus,
    showKeyboard,
)

/** Runs platform-specific text selection gestures logic. */
internal actual suspend fun TextFieldSelectionState.textFieldSelectionGestures(
    pointerInputScope: PointerInputScope,
    mouseSelectionObserver: MouseSelectionObserver,
    textDragObserver: TextDragObserver
) = pointerInputScope.defaultTextFieldSelectionGestures(mouseSelectionObserver, textDragObserver)

// The new provider-based context-menu pipeline is disabled for Skiko. CommonContextMenuArea is the
// supported Windows path; this hook must stay inert until CMP-7819 supplies a native menu host.
// https://youtrack.jetbrains.com/issue/CMP-7819
internal actual fun Modifier.addBasicTextFieldTextContextMenuComponents(
    state: TextFieldSelectionState,
    coroutineScope: CoroutineScope
): Modifier = this

internal actual class ClipboardPasteState actual constructor(private val clipboard: Clipboard) {
    private var _hasClip = false
    private var _hasText = false

    actual val hasText: Boolean get() = _hasText
    actual val hasClip: Boolean get() = _hasClip

    actual suspend fun update() {
        // The Win32 clipboard is only read as text here, so a clip and text are the same thing.
        _hasText = clipboard.getClipEntry() != null
        _hasClip = _hasText
    }
}
