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

@file:OptIn(ExperimentalForeignApi::class)

package androidx.compose.ui.win32

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import platform.posix.GUID
import platform.windows.WCHARVar

internal const val S_OK = 0
internal const val S_FALSE = 1

internal const val E_NOTIMPL = -2147467263 // 0x80004001
internal const val E_NOINTERFACE = -2147467262 // 0x80004002
internal const val E_POINTER = -2147467261 // 0x80004003
internal const val E_FAIL = -2147467259 // 0x80004005
internal const val E_UNEXPECTED = -2147418113 // 0x8000FFFF
internal const val E_INVALIDARG = -2147024809 // 0x80070057

internal const val STG_E_MEDIUMFULL = -2147286928 // 0x80030070
internal const val DV_E_FORMATETC = -2147221404 // 0x80040064
internal const val DV_E_LINDEX = -2147221400 // 0x80040068
internal const val DV_E_TYMED = -2147221399 // 0x80040069
internal const val DV_E_DVASPECT = -2147221397 // 0x8004006B
internal const val OLE_E_ADVISENOTSUPPORTED = -2147221501 // 0x80040003

/** The first DWORD of the COM interface IDs used by drag and drop. */
internal const val IID_DATA1_IUNKNOWN = 0x00000000u
internal const val IID_DATA1_IDATAOBJECT = 0x0000010Eu
internal const val IID_DATA1_IDROPSOURCE = 0x00000121u
internal const val IID_DATA1_IDROPTARGET = 0x00000122u

/**
 * COM's drag-and-drop interfaces all use the standard OLE IID tail
 * `0000-0000-C000-000000000046`; only `Data1` differs.
 */
internal fun CPointer<GUID>?.matchesOleInterfaceId(expectedData1: UInt): Boolean {
    val value = this?.pointed ?: return false
    return value.Data1 == expectedData1 &&
        value.Data2 == 0.toUShort() &&
        value.Data3 == 0.toUShort() &&
        value.Data4[0] == 0xC0.toUByte() &&
        value.Data4[1] == 0.toUByte() &&
        value.Data4[2] == 0.toUByte() &&
        value.Data4[3] == 0.toUByte() &&
        value.Data4[4] == 0.toUByte() &&
        value.Data4[5] == 0.toUByte() &&
        value.Data4[6] == 0.toUByte() &&
        value.Data4[7] == 0x46.toUByte()
}

/** Decodes only within the supplied allocation boundary and rejects unterminated input. */
internal fun CPointer<WCHARVar>.readBoundedNullTerminatedUtf16(maximumCharacters: Int): String? {
    if (maximumCharacters <= 0) return null
    val terminator = (0 until maximumCharacters).firstOrNull { this[it] == 0.toUShort() }
        ?: return null
    return buildString(terminator) {
        for (index in 0 until terminator) append(this@readBoundedNullTerminatedUtf16[index].toInt().toChar())
    }
}

/**
 * Reference counter for one COM object in an OLE single-threaded apartment.
 *
 * OLE drag-and-drop callbacks are delivered on the apartment thread that registered/started the
 * operation. Keeping the counter here (rather than returning a constant) gives every acquired
 * interface pointer a real lifetime while avoiding a false claim of cross-apartment support.
 */
internal class StaComReferenceCount(private val onZero: () -> Unit) {
    private var count = 1u

    val value: UInt get() = count

    fun addRef(): UInt {
        if (count != UInt.MAX_VALUE) count++
        return count
    }

    fun release(): UInt {
        if (count == 0u) return 0u
        count--
        if (count == 0u) onZero()
        return count
    }
}
