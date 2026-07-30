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

import androidx.compose.ui.InternalComposeUiApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.windows.BOOLVar
import platform.windows.SPI_GETSCREENREADER
import platform.windows.SystemParametersInfoW

/**
 * Reports whether a screen reader is running, so Compose can keep its semantics tree up to date
 * only when something is listening.
 *
 * `SPI_GETSCREENREADER` is the flag assistive software sets while it is active; it is read on
 * demand rather than cached, because a reader can be started or stopped while the app runs.
 */
@OptIn(InternalComposeUiApi::class)
internal class Win32ScreenReader : PlatformScreenReader {
    override val isActive: Boolean
        get() = memScoped {
            val running = alloc<BOOLVar>()
            val read = SystemParametersInfoW(
                SPI_GETSCREENREADER.convert(),
                0u,
                running.ptr,
                0u,
            )
            read != 0 && running.value != 0
        }
}
