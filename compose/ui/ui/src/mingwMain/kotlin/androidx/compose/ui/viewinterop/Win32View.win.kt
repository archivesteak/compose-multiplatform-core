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

package androidx.compose.ui.viewinterop

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.cinterop.ExperimentalForeignApi
import platform.windows.HWND

/**
 * Places a native Win32 window in the Compose hierarchy — the counterpart of `AndroidView` on
 * Android and `UIKitView` on iOS.
 *
 * [factory] creates the child window, and is handed the Compose window to parent it to — a
 * `WS_CHILD` window cannot be created without a parent. Create it with `WS_CHILD` and without
 * `WS_VISIBLE`; Compose shows it and thereafter owns its position and size, which follow
 * [modifier] like any other layout. Do not call `DestroyWindow` yourself — the window is destroyed
 * when the composable leaves the composition.
 *
 * A child window is composited by the window manager, so it always draws **above** the Compose
 * content regardless of where it sits in the draw order, and Compose cannot clip or transform it.
 * Several interop children are stacked in the order Compose places them. This is the same
 * limitation the JVM desktop backend has with heavyweight AWT components.
 *
 * ```
 * Win32View(
 *     factory = { parent ->
 *         CreateWindowExW(
 *             dwExStyle = 0u,
 *             lpClassName = "EDIT",
 *             lpWindowName = null,
 *             dwStyle = (WS_CHILD or ES_MULTILINE).convert(),
 *             X = 0, Y = 0, nWidth = 0, nHeight = 0,
 *             hWndParent = parent, hMenu = null, hInstance = GetModuleHandleW(null),
 *             lpParam = null,
 *         )!!
 *     },
 *     modifier = Modifier.fillMaxWidth().height(120.dp),
 * )
 * ```
 *
 * @param factory creates the child window, given the Compose window to parent it to.
 * @param modifier the modifier applied to the layout node standing in for the window.
 * @param update invoked once initially and whenever state it reads changes.
 * @param onRelease invoked when the composable leaves the composition for good, before the window
 *   is destroyed. Use it to release anything attached to the window.
 */
@Composable
fun Win32View(
    factory: (parent: HWND) -> HWND,
    modifier: Modifier = Modifier,
    update: (HWND) -> Unit = {},
    onRelease: (HWND) -> Unit = {},
) {
    val interopContainer = LocalInteropContainer.current as Win32InteropContainer
    InteropView(
        factory = { compositeKeyHashCode ->
            Win32InteropViewHolder(
                factory = { factory(interopContainer.window) },
                win32Container = interopContainer,
                compositeKeyHashCode = compositeKeyHashCode,
            )
        },
        modifier = modifier,
        onRelease = onRelease,
        update = update,
    )
}
