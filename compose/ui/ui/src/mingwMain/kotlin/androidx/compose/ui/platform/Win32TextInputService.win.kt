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

@file:OptIn(ExperimentalForeignApi::class)

package androidx.compose.ui.platform

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.FinishComposingTextCommand
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.PlatformTextInputService
import androidx.compose.ui.text.input.SetComposingTextCommand
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.windows.CANDIDATEFORM
import platform.windows.CFS_EXCLUDE
import platform.windows.CFS_POINT
import platform.windows.COMPOSITIONFORM
import platform.windows.CPS_CANCEL
import platform.windows.GCS_COMPSTR
import platform.windows.GCS_CURSORPOS
import platform.windows.GCS_RESULTSTR
import platform.windows.HWND
import platform.windows.ImmGetCompositionStringW
import platform.windows.ImmGetContext
import platform.windows.ImmNotifyIME
import platform.windows.ImmReleaseContext
import platform.windows.ImmSetCandidateWindow
import platform.windows.ImmSetCompositionWindow
import platform.windows.NI_COMPOSITIONSTR
import platform.windows.WCHARVar

/**
 * Text input backed by IMM32.
 *
 * Plain typing arrives as `WM_CHAR` and is turned into a key event by the window, so this service
 * only deals with *composed* input — the Chinese, Japanese and Korean editors, and the dead-key
 * sequences some European layouts use. Those are driven by the `WM_IME_*` messages the window
 * forwards to [onImeComposition] and friends.
 *
 * The composition is shown inline by Compose rather than in the IME's own window: each update is
 * pushed as a [SetComposingTextCommand], which the text field renders underlined, and the IME is
 * told where the caret is so its candidate list lands under the text being composed.
 */
internal class Win32TextInputService : PlatformTextInputService {
    data class CurrentInput(
        var value: TextFieldValue,
        val onEditCommand: ((List<EditCommand>) -> Unit),
    )

    private var currentInput: CurrentInput? = null

    /** Set by the window once it exists; IMM32 calls need the `HWND` that owns the input context. */
    var window: HWND? = null

    /** Caret rectangle in window (physical pixel) coordinates, as last reported by Compose. */
    private var focusedRect: Rect? = null

    override fun startInput(
        value: TextFieldValue,
        imeOptions: ImeOptions,
        onEditCommand: (List<EditCommand>) -> Unit,
        onImeActionPerformed: (ImeAction) -> Unit
    ) {
        currentInput = CurrentInput(value, onEditCommand)
    }

    override fun stopInput() {
        // Drop anything half-composed, otherwise the IME would deliver it into the next field.
        cancelComposition()
        currentInput = null
        focusedRect = null
    }

    override fun showSoftwareKeyboard() {
        // Desktop: no software keyboard.
    }

    override fun hideSoftwareKeyboard() {
        // Desktop: no software keyboard.
    }

    override fun updateState(oldValue: TextFieldValue?, newValue: TextFieldValue) {
        currentInput?.let { input ->
            input.value = newValue
        }
    }

    override fun notifyFocusedRect(rect: Rect) {
        focusedRect = rect
        positionImeWindows(rect)
    }

    /** True while a text field is accepting input, i.e. while IME messages are meaningful. */
    val isInputActive: Boolean get() = currentInput != null

    /**
     * Handles `WM_IME_COMPOSITION`. [flags] is the message's `lParam`, saying which parts of the
     * composition changed. Returns true if the message was consumed.
     */
    fun onImeComposition(flags: Long): Boolean {
        val input = currentInput ?: return false
        val hwnd = window ?: return false
        var handled = false

        withImmContext(hwnd) { context ->
            // The finished text comes first: the IME reports the result and the (now empty)
            // composition in the same message.
            if (flags and GCS_RESULTSTR.toLong() != 0L) {
                val result = compositionString(context, GCS_RESULTSTR.convert())
                if (result != null) {
                    input.onEditCommand(
                        listOf(
                            FinishComposingTextCommand(),
                            CommitTextCommand(AnnotatedString(result), 1),
                        )
                    )
                }
                handled = true
            }
            if (flags and GCS_COMPSTR.toLong() != 0L) {
                val composing = compositionString(context, GCS_COMPSTR.convert()).orEmpty()
                if (composing.isEmpty()) {
                    input.onEditCommand(listOf(FinishComposingTextCommand()))
                } else {
                    // GCS_CURSORPOS is measured in UTF-16 units from the start of the composition;
                    // SetComposingTextCommand counts from the end of the inserted text.
                    val caret = compositionCursorPosition(context, composing.length)
                    input.onEditCommand(
                        listOf(
                            SetComposingTextCommand(
                                annotatedString = AnnotatedString(composing),
                                newCursorPosition = caret - composing.length + 1,
                            )
                        )
                    )
                }
                handled = true
            }
        }
        return handled
    }

    /** Handles `WM_IME_ENDCOMPOSITION`. */
    fun onImeEndComposition() {
        currentInput?.onEditCommand(listOf(FinishComposingTextCommand()))
    }

    /** Handles `WM_IME_STARTCOMPOSITION`: place the IME windows before the first candidate shows. */
    fun onImeStartComposition() {
        focusedRect?.let(::positionImeWindows)
    }

    private fun cancelComposition() {
        val hwnd = window ?: return
        withImmContext(hwnd) { context ->
            ImmNotifyIME(context, NI_COMPOSITIONSTR.convert(), CPS_CANCEL.convert(), 0u)
        }
    }

    /**
     * Puts the composition at the caret and asks the candidate list to avoid covering the line
     * being edited (`CFS_EXCLUDE` takes the rectangle to steer clear of).
     */
    private fun positionImeWindows(rect: Rect) {
        val hwnd = window ?: return
        withImmContext(hwnd) { context ->
            memScoped {
                val composition = alloc<COMPOSITIONFORM>()
                composition.dwStyle = CFS_POINT.convert()
                composition.ptCurrentPos.x = rect.left.toInt()
                composition.ptCurrentPos.y = rect.top.toInt()
                ImmSetCompositionWindow(context, composition.ptr)

                val candidate = alloc<CANDIDATEFORM>()
                candidate.dwIndex = 0u
                candidate.dwStyle = CFS_EXCLUDE.convert()
                candidate.ptCurrentPos.x = rect.left.toInt()
                candidate.ptCurrentPos.y = rect.bottom.toInt()
                candidate.rcArea.left = rect.left.toInt()
                candidate.rcArea.top = rect.top.toInt()
                candidate.rcArea.right = rect.right.toInt()
                candidate.rcArea.bottom = rect.bottom.toInt()
                ImmSetCandidateWindow(context, candidate.ptr)
            }
        }
    }

    /** Runs [block] with the window's input context, always releasing it. */
    private inline fun withImmContext(hwnd: HWND, block: (context: platform.windows.HIMC?) -> Unit) {
        val context = ImmGetContext(hwnd) ?: return
        try {
            block(context)
        } finally {
            ImmReleaseContext(hwnd, context)
        }
    }

    /** Reads one of the composition strings; null when the IME has nothing for that index. */
    private fun compositionString(context: platform.windows.HIMC?, index: UInt): String? = memScoped {
        // The first call reports the size in *bytes*, not characters.
        val bytes = ImmGetCompositionStringW(context, index, null, 0u)
        if (bytes <= 0) return@memScoped null
        val characters = bytes / 2
        val buffer = allocArray<WCHARVar>(characters)
        val written = ImmGetCompositionStringW(context, index, buffer, bytes.convert())
        if (written <= 0) return@memScoped null
        buffer.readUtf16(written / 2)
    }

    private fun compositionCursorPosition(context: platform.windows.HIMC?, fallback: Int): Int {
        val position = ImmGetCompositionStringW(context, GCS_CURSORPOS.convert(), null, 0u)
        return if (position < 0) fallback else position
    }
}

private fun CPointer<WCHARVar>.readUtf16(length: Int): String =
    buildString(length) {
        for (i in 0 until length) append(this@readUtf16[i].toInt().toChar())
    }
