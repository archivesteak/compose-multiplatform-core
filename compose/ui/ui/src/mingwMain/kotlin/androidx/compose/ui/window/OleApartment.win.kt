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

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package androidx.compose.ui.window

import androidx.compose.ui.win32.S_FALSE
import androidx.compose.ui.win32.S_OK
import platform.windows.GetCurrentThreadId
import platform.windows.OleInitialize
import platform.windows.OleUninitialize

internal const val RPC_E_CHANGED_MODE = -2147417850 // 0x80010106

internal enum class OleInitializationStatus {
    Initialized,
    AlreadyInitialized,
    ChangedMode,
    Failed,
}

/** One balanced call to `OleInitialize` on the window's single-threaded apartment. */
internal class OleApartment private constructor(
    val hresult: Int,
    private val apartmentThreadId: UInt,
    private val currentThreadId: () -> UInt,
    private val uninitialize: () -> Unit,
) {
    val status: OleInitializationStatus = when (hresult) {
        S_OK -> OleInitializationStatus.Initialized
        S_FALSE -> OleInitializationStatus.AlreadyInitialized
        RPC_E_CHANGED_MODE -> OleInitializationStatus.ChangedMode
        else -> OleInitializationStatus.Failed
    }

    val supportsOle: Boolean
        get() = status == OleInitializationStatus.Initialized ||
            status == OleInitializationStatus.AlreadyInitialized

    private var isClosed = false

    /**
     * `S_OK` and `S_FALSE` both increment COM's per-apartment initialization count. Both therefore
     * require exactly one `OleUninitialize`, on the same thread. Failed calls require none.
     */
    fun close() {
        if (isClosed) return
        if (!supportsOle) {
            isClosed = true
            return
        }
        check(currentThreadId() == apartmentThreadId) {
            "OleUninitialize must run on the thread that called OleInitialize"
        }
        isClosed = true
        uninitialize()
    }

    companion object {
        fun initialize(): OleApartment = fromResult(
            hresult = OleInitialize(null),
            apartmentThreadId = GetCurrentThreadId(),
            currentThreadId = ::GetCurrentThreadId,
            uninitialize = ::OleUninitialize,
        )

        internal fun fromResult(
            hresult: Int,
            apartmentThreadId: UInt = GetCurrentThreadId(),
            currentThreadId: () -> UInt = ::GetCurrentThreadId,
            uninitialize: () -> Unit,
        ): OleApartment = OleApartment(hresult, apartmentThreadId, currentThreadId, uninitialize)
    }
}
