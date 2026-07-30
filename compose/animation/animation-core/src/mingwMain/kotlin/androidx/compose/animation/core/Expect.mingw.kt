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

package androidx.compose.animation.core

import platform.windows.GetCurrentThreadId

// TODO: https://youtrack.jetbrains.com/issue/CMP-9987/Propose-a-better-solution-for-checking-the-main-thread-in-compose-animation-rememberTransition
// Like the appleMain implementation, this is valid only for the use case of rememberTransition:
// the identity is only ever compared for equality. Windows thread ids are unique for the lifetime
// of a thread, and boxing the id gives the structural equality that comparison relies on.
internal actual fun getCurrentThread(): Any {
    return GetCurrentThreadId()
}
