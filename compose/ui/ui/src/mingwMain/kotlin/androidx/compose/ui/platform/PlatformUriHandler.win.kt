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

import kotlinx.cinterop.ExperimentalForeignApi
import platform.windows.SW_SHOWNORMAL
import platform.windows.ShellExecuteW

private class Win32UriHandler : UriHandler {
    override fun openUri(uri: String) {
        // A null verb lets the shell pick the default association for the scheme.
        ShellExecuteW(null, null, uri, null, null, SW_SHOWNORMAL)
    }
}

internal actual fun createPlatformUriHandler(): UriHandler = Win32UriHandler()
