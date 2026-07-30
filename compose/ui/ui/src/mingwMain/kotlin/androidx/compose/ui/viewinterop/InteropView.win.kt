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

package androidx.compose.ui.viewinterop

// An interop view here is an HWND. It cannot be named as such: an `actual typealias` has to
// resolve to a class, and `HWND` is itself an alias for the parameterized `CPointer<HWND__>`,
// which the compiler rejects on the right-hand side. The JVM desktop target has the same
// constraint and writes `Any // java.awt.Component` for the same reason.
//
// The typing is recovered where it matters: Win32InteropViewHolder is a
// TypedInteropViewHolder<HWND>, so factories and update blocks handed to Win32View are typed.
actual typealias InteropView = Any

/** The Compose window that interop children are parented to; an `HWND`. */
internal actual typealias InteropViewGroup = Any
