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

package androidx.compose.material3.internal

import androidx.compose.material3.CalendarLocale
import androidx.compose.ui.text.intl.Locale

actual fun calendarLocale(language: String, country: String): CalendarLocale =
    Locale("$language-$country")

// The Win32 implementation has native skeleton formatting for the date-picker skeletons.
actual val supportsDateSkeleton: Boolean
    get() = true

// Windows NLS currently defines the canonical ko-KR short-date picture with hyphens.
actual val expectedKoreanDateInputFormat: String = "yyyy-MM-dd"

/*
 * Windows has no process-local system-time-zone override. Changing it through Win32 would mutate
 * the user's machine, while the C runtime's TZ variable is ignored by kotlinx-datetime's Win32
 * registry-backed time-zone implementation. These test hooks therefore retain the requested value
 * without changing device state. The exercised calendar operations are deliberately UTC-based;
 * their invariance is asserted for every requested value by the shared test suite.
 */
private var requestedTestTimeZone = ""

actual fun setTimeZone(id: String) {
    requestedTestTimeZone = id
}

actual fun getTimeZone(): String = requestedTestTimeZone
