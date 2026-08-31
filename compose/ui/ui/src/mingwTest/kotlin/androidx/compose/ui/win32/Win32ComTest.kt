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

package androidx.compose.ui.win32

import androidx.compose.ui.window.OleApartment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.value
import platform.posix.GUID
import platform.windows.CreateWindowExW
import platform.windows.DVASPECT_CONTENT
import platform.windows.DestroyWindow
import platform.windows.FORMATETC
import platform.windows.GetModuleHandleW
import platform.windows.GlobalLock
import platform.windows.GlobalSize
import platform.windows.GlobalUnlock
import platform.windows.HGLOBAL
import platform.windows.HWND
import platform.windows.IDataObject
import platform.windows.IDropSource
import platform.windows.IDropTarget
import platform.windows.POINTL
import platform.windows.ReleaseStgMedium
import platform.windows.STGMEDIUM
import platform.windows.TYMED_FILE
import platform.windows.TYMED_HGLOBAL
import platform.windows.WCHARVar
import platform.windows.WS_OVERLAPPED

class Win32ComTest {
    @Test
    fun staReferenceCountDestroysExactlyAtZero() {
        var destructions = 0
        val references = StaComReferenceCount { destructions++ }

        assertEquals(1u, references.value)
        assertEquals(2u, references.addRef())
        assertEquals(1u, references.release())
        assertEquals(0u, references.release())
        assertEquals(1, destructions)
        assertEquals(0u, references.release())
        assertEquals(1, destructions)
    }

    @Test
    fun formatValidationReportsTheSpecificInvalidField() {
        assertEquals(
            S_OK,
            validateUnicodeTextFormat(
                CF_UNICODETEXT,
                DVASPECT_CONTENT,
                -1,
                TYMED_HGLOBAL,
            ),
        )
        assertEquals(
            DV_E_FORMATETC,
            validateUnicodeTextFormat(1u, DVASPECT_CONTENT, -1, TYMED_HGLOBAL),
        )
        assertEquals(
            DV_E_DVASPECT,
            validateUnicodeTextFormat(CF_UNICODETEXT, 0u, -1, TYMED_HGLOBAL),
        )
        assertEquals(
            DV_E_LINDEX,
            validateUnicodeTextFormat(
                CF_UNICODETEXT,
                DVASPECT_CONTENT,
                0,
                TYMED_HGLOBAL,
            ),
        )
        assertEquals(
            DV_E_TYMED,
            validateUnicodeTextFormat(
                CF_UNICODETEXT,
                DVASPECT_CONTENT,
                -1,
                TYMED_FILE,
            ),
        )
    }

    @Test
    fun dropSourceQueryInterfaceUsesDeclaredIidsAndRealReferences() = memScoped {
        val baseline = liveWin32DropSourceCount
        val source = Win32DropSource()
        val queryInterface = assertNotNull(source.comObject.lpVtbl?.pointed?.QueryInterface)
        val release = assertNotNull(source.comObject.lpVtbl?.pointed?.Release)
        val iid = alloc<GUID>()
        val output = alloc<COpaquePointerVar>()

        iid.setOleInterfaceId(IID_DATA1_IUNKNOWN)
        assertEquals(S_OK, queryInterface(source.comObject.ptr, iid.ptr, output.ptr))
        assertNotNull(output.value)
        assertEquals(2u, source.referenceCount)

        assertEquals(E_POINTER, queryInterface(source.comObject.ptr, iid.ptr, null))
        output.value = source.comObject.ptr.reinterpret()
        assertEquals(E_POINTER, queryInterface(source.comObject.ptr, null, output.ptr))
        assertNull(output.value)

        iid.setOleInterfaceId(IID_DATA1_IDROPSOURCE)
        iid.Data4[7] = 0u.toUByte()
        output.value = source.comObject.ptr.reinterpret()
        assertEquals(E_NOINTERFACE, queryInterface(source.comObject.ptr, iid.ptr, output.ptr))
        assertNull(output.value)

        iid.setOleInterfaceId(0xDEADBEEFu)
        output.value = source.comObject.ptr.reinterpret()
        assertEquals(E_NOINTERFACE, queryInterface(source.comObject.ptr, iid.ptr, output.ptr))
        assertNull(output.value)

        source.dispose()
        assertEquals(baseline + 1, liveWin32DropSourceCount)
        assertEquals(0u, release(source.comObject.ptr))
        assertEquals(baseline, liveWin32DropSourceCount)
    }

    @Test
    fun textDataObjectReturnsIndependentOwnedUnicodeMedia() = memScoped {
        val baseline = liveWin32TextDataObjectCount
        val text = "Compose — Кириллица — 😀"
        val data = assertNotNull(Win32TextDataObject.create(text))
        val getData = assertNotNull(data.comObject.lpVtbl?.pointed?.GetData)
        val queryGetData = assertNotNull(data.comObject.lpVtbl?.pointed?.QueryGetData)
        val format = alloc<FORMATETC>()
        format.cfFormat = CF_UNICODETEXT.toUShort()
        format.ptd = null
        format.dwAspect = DVASPECT_CONTENT
        format.lindex = -1
        format.tymed = TYMED_HGLOBAL

        assertEquals(S_OK, queryGetData(data.comObject.ptr, format.ptr))
        assertEquals(E_INVALIDARG, queryGetData(data.comObject.ptr, null))

        format.cfFormat = 1u.toUShort()
        assertEquals(DV_E_FORMATETC, queryGetData(data.comObject.ptr, format.ptr))
        format.cfFormat = CF_UNICODETEXT.toUShort()
        format.dwAspect = 0u
        assertEquals(DV_E_DVASPECT, queryGetData(data.comObject.ptr, format.ptr))
        format.dwAspect = DVASPECT_CONTENT
        format.lindex = 0
        assertEquals(DV_E_LINDEX, queryGetData(data.comObject.ptr, format.ptr))
        format.lindex = -1
        format.tymed = TYMED_FILE
        assertEquals(DV_E_TYMED, queryGetData(data.comObject.ptr, format.ptr))
        format.tymed = TYMED_HGLOBAL

        val first = alloc<STGMEDIUM>()
        val second = alloc<STGMEDIUM>()
        assertEquals(S_OK, getData(data.comObject.ptr, format.ptr, first.ptr))
        assertEquals(S_OK, getData(data.comObject.ptr, format.ptr, second.ptr))
        try {
            assertEquals(TYMED_HGLOBAL, first.tymed)
            assertEquals(TYMED_HGLOBAL, second.tymed)
            assertNull(first.pUnkForRelease)
            assertNull(second.pUnkForRelease)
            assertNotNull(first.hGlobal)
            assertNotNull(second.hGlobal)
            assertNotEquals(first.hGlobal, second.hGlobal)
            assertEquals(text, readNullTerminatedUtf16(first.hGlobal!!))
            assertEquals(text, readNullTerminatedUtf16(second.hGlobal!!))
        } finally {
            ReleaseStgMedium(first.ptr)
            ReleaseStgMedium(second.ptr)
        }

        assertEquals(E_INVALIDARG, getData(data.comObject.ptr, null, first.ptr))
        assertEquals(E_INVALIDARG, getData(data.comObject.ptr, format.ptr, null))
        data.dispose()
        assertEquals(baseline, liveWin32TextDataObjectCount)
    }

    @Test
    fun textDataObjectQueryInterfaceKeepsObjectAliveUntilRelease() = memScoped {
        val baseline = liveWin32TextDataObjectCount
        val data = assertNotNull(Win32TextDataObject.create("text"))
        val queryInterface = assertNotNull(data.comObject.lpVtbl?.pointed?.QueryInterface)
        val release = assertNotNull(data.comObject.lpVtbl?.pointed?.Release)
        val iid = alloc<GUID>()
        val output = alloc<COpaquePointerVar>()

        iid.setOleInterfaceId(IID_DATA1_IDATAOBJECT)
        assertEquals(S_OK, queryInterface(data.comObject.ptr, iid.ptr, output.ptr))
        assertEquals(2u, data.referenceCount)

        output.value = data.comObject.ptr.reinterpret()
        assertEquals(E_POINTER, queryInterface(data.comObject.ptr, null, output.ptr))
        assertNull(output.value)

        data.dispose()
        assertEquals(baseline + 1, liveWin32TextDataObjectCount)
        assertEquals(0u, release(data.comObject.ptr))
        assertEquals(baseline, liveWin32TextDataObjectCount)
    }

    @Test
    fun dropRegistrationIsTransactionalAndRevocable() = memScoped {
        val baseline = liveWin32DropTargetCount
        val apartment = OleApartment.initialize()
        assertTrue(apartment.supportsOle, "native test thread must be an OLE-compatible STA")
        val hwnd = assertNotNull(
            CreateWindowExW(
                0u,
                "STATIC",
                "Compose Win32DropTarget test",
                WS_OVERLAPPED.toUInt(),
                0,
                0,
                32,
                32,
                null,
                null,
                GetModuleHandleW(null),
                null,
            )
        )
        var target: Win32DropTarget? = null
        try {
            val first = registerNoOpTarget(hwnd)
            target = assertNotNull(first.target, "RegisterDragDrop HRESULT=${first.hresult}")
            assertEquals(S_OK, first.hresult)
            assertEquals(2u, target.referenceCount)
            assertEquals(baseline + 1, liveWin32DropTargetCount)

            val duplicate = registerNoOpTarget(hwnd)
            assertNull(duplicate.target)
            assertFalse(duplicate.hresult == S_OK)
            assertEquals(baseline + 1, liveWin32DropTargetCount)

            assertEquals(S_OK, target.dispose())
            target = null
            assertEquals(baseline, liveWin32DropTargetCount)

            val afterRevoke = registerNoOpTarget(hwnd)
            target = assertNotNull(afterRevoke.target, "RegisterDragDrop after revoke failed")
            assertEquals(S_OK, afterRevoke.hresult)
        } finally {
            target?.dispose()
            DestroyWindow(hwnd)
            apartment.close()
        }
        assertEquals(baseline, liveWin32DropTargetCount)
    }

    @Test
    fun dropTargetQueryInterfaceRejectsUnknownIidAndBalancesReferences() = memScoped {
        val baseline = liveWin32DropTargetCount
        val apartment = OleApartment.initialize()
        assertTrue(apartment.supportsOle, "native test thread must be an OLE-compatible STA")
        val hwnd = assertNotNull(
            CreateWindowExW(
                0u,
                "STATIC",
                "Compose IDropTarget QueryInterface test",
                WS_OVERLAPPED.toUInt(),
                0,
                0,
                32,
                32,
                null,
                null,
                GetModuleHandleW(null),
                null,
            )
        )
        var target: Win32DropTarget? = null
        try {
            target = assertNotNull(registerNoOpTarget(hwnd).target)
            val queryInterface = assertNotNull(target.comObject.lpVtbl?.pointed?.QueryInterface)
            val release = assertNotNull(target.comObject.lpVtbl?.pointed?.Release)
            val iid = alloc<GUID>()
            val output = alloc<COpaquePointerVar>()

            iid.setOleInterfaceId(IID_DATA1_IDROPTARGET)
            assertEquals(S_OK, queryInterface(target.comObject.ptr, iid.ptr, output.ptr))
            assertEquals(3u, target.referenceCount)
            assertEquals(2u, release(target.comObject.ptr))

            iid.setOleInterfaceId(0xDEADBEEFu)
            output.value = target.comObject.ptr.reinterpret()
            assertEquals(E_NOINTERFACE, queryInterface(target.comObject.ptr, iid.ptr, output.ptr))
            assertNull(output.value)
            assertEquals(E_POINTER, queryInterface(target.comObject.ptr, iid.ptr, null))
            output.value = target.comObject.ptr.reinterpret()
            assertEquals(E_POINTER, queryInterface(target.comObject.ptr, null, output.ptr))
            assertNull(output.value)
        } finally {
            target?.dispose()
            DestroyWindow(hwnd)
            apartment.close()
        }
        assertEquals(baseline, liveWin32DropTargetCount)
    }

    @Test
    fun dropWithoutCopyPermissionEndsDragWithoutCallingOnDrop() = memScoped {
        val apartment = OleApartment.initialize()
        assertTrue(apartment.supportsOle, "native test thread must be an OLE-compatible STA")
        val hwnd = createDropTargetTestWindow("Compose disallowed drop test")
        val data = assertNotNull(Win32TextDataObject.create("payload"))
        var leaves = 0
        var drops = 0
        var target: Win32DropTarget? = null
        try {
            target = assertNotNull(
                Win32DropTarget.register(
                    hwnd = hwnd,
                    onDragEnter = { true },
                    onDragOver = {},
                    onDragLeave = { leaves++ },
                    onDrop = {
                        drops++
                        true
                    },
                ).target
            )
            val dragEnter = assertNotNull(target.comObject.lpVtbl?.pointed?.DragEnter)
            val drop = assertNotNull(target.comObject.lpVtbl?.pointed?.Drop)
            val point = alloc<POINTL>().apply {
                x = 0
                y = 0
            }
            val effect = alloc<UIntVar>()

            effect.value = 1u
            assertEquals(
                S_OK,
                dragEnter(
                    target.comObject.ptr,
                    data.comObject.ptr,
                    0u,
                    point.readValue(),
                    effect.ptr,
                ),
            )
            assertEquals(1u, effect.value)

            effect.value = 0u
            assertEquals(
                S_OK,
                drop(
                    target.comObject.ptr,
                    data.comObject.ptr,
                    0u,
                    point.readValue(),
                    effect.ptr,
                ),
            )
            assertEquals(0u, effect.value)
            assertEquals(0, drops)
            assertEquals(1, leaves)
        } finally {
            target?.dispose()
            data.dispose()
            DestroyWindow(hwnd)
            apartment.close()
        }
    }

    @Test
    fun dragOverFailurePreservesSessionForBalancedLeave() = memScoped {
        val apartment = OleApartment.initialize()
        assertTrue(apartment.supportsOle, "native test thread must be an OLE-compatible STA")
        val hwnd = createDropTargetTestWindow("Compose failing drag-over test")
        val data = assertNotNull(Win32TextDataObject.create("payload"))
        var leaves = 0
        var target: Win32DropTarget? = null
        try {
            target = assertNotNull(
                Win32DropTarget.register(
                    hwnd = hwnd,
                    onDragEnter = { true },
                    onDragOver = { error("drag-over callback failed") },
                    onDragLeave = { leaves++ },
                    onDrop = { true },
                ).target
            )
            val dragEnter = assertNotNull(target.comObject.lpVtbl?.pointed?.DragEnter)
            val dragOver = assertNotNull(target.comObject.lpVtbl?.pointed?.DragOver)
            val dragLeave = assertNotNull(target.comObject.lpVtbl?.pointed?.DragLeave)
            val point = alloc<POINTL>().apply {
                x = 0
                y = 0
            }
            val effect = alloc<UIntVar>()

            effect.value = 1u
            assertEquals(
                S_OK,
                dragEnter(
                    target.comObject.ptr,
                    data.comObject.ptr,
                    0u,
                    point.readValue(),
                    effect.ptr,
                ),
            )
            effect.value = 1u
            assertEquals(
                E_UNEXPECTED,
                dragOver(target.comObject.ptr, 0u, point.readValue(), effect.ptr),
            )
            assertEquals(0u, effect.value)
            assertEquals(S_OK, dragLeave(target.comObject.ptr))
            assertEquals(1, leaves)
        } finally {
            target?.dispose()
            data.dispose()
            DestroyWindow(hwnd)
            apartment.close()
        }
    }
}

private fun createDropTargetTestWindow(title: String): HWND = assertNotNull(
    CreateWindowExW(
        0u,
        "STATIC",
        title,
        WS_OVERLAPPED.toUInt(),
        0,
        0,
        32,
        32,
        null,
        null,
        GetModuleHandleW(null),
        null,
    )
)

private fun registerNoOpTarget(hwnd: HWND): Win32DropTargetRegistration =
    Win32DropTarget.register(
        hwnd = hwnd,
        onDragEnter = { true },
        onDragOver = {},
        onDragLeave = {},
        onDrop = { true },
    )

private fun GUID.setOleInterfaceId(data1: UInt) {
    Data1 = data1
    Data2 = 0u.toUShort()
    Data3 = 0u.toUShort()
    Data4[0] = 0xC0u.toUByte()
    Data4[1] = 0u.toUByte()
    Data4[2] = 0u.toUByte()
    Data4[3] = 0u.toUByte()
    Data4[4] = 0u.toUByte()
    Data4[5] = 0u.toUByte()
    Data4[6] = 0u.toUByte()
    Data4[7] = 0x46u.toUByte()
}

private fun readNullTerminatedUtf16(handle: HGLOBAL): String {
    val characterCount = (GlobalSize(handle) / 2uL).toInt()
    val locked = assertNotNull(GlobalLock(handle))
    try {
        val characters = locked.reinterpret<WCHARVar>()
        val terminator = (0 until characterCount).first { characters[it] == 0.toUShort() }
        return buildString(terminator) {
            for (index in 0 until terminator) append(characters[index].toInt().toChar())
        }
    } finally {
        GlobalUnlock(handle)
    }
}
