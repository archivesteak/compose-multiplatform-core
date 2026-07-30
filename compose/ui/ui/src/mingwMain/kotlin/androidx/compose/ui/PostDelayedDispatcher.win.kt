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

package androidx.compose.ui

import androidx.compose.ui.window.Win32MainDispatcher
import kotlin.coroutines.CoroutineContext

/**
 * Kotlin/Native has no `Dispatchers.Main` for `mingwX64`, so delayed work is posted to the Win32
 * message loop instead. The delay itself is served by the default scheduler; only the resumption
 * comes back here, onto the UI thread.
 */
internal actual val PostDelayedDispatcher: CoroutineContext
    get() = Win32MainDispatcher
