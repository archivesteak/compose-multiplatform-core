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

package androidx.compose.ui.win32

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toLong
import kotlinx.cinterop.value
import platform.posix.memcpy
import platform.windows.DATADIR_GET
import platform.windows.DVASPECT_CONTENT
import platform.windows.DWORDVar
import platform.windows.DoDragDrop
import platform.windows.FORMATETC
import platform.windows.GlobalAlloc
import platform.windows.GlobalFree
import platform.windows.GlobalLock
import platform.windows.GlobalSize
import platform.windows.GlobalUnlock
import platform.windows.HGLOBAL
import platform.windows.IDataObject
import platform.windows.IDataObjectVtbl
import platform.windows.IDropSource
import platform.windows.IDropSourceVtbl
import platform.windows.MK_LBUTTON
import platform.windows.MK_RBUTTON
import platform.windows.SHCreateStdEnumFmtEtc
import platform.windows.STGMEDIUM
import platform.windows.TYMED_HGLOBAL
import platform.windows.WCHARVar

internal const val DRAGDROP_S_DROP = 262400 // 0x00040100
internal const val DRAGDROP_S_CANCEL = 262401 // 0x00040101
private const val DRAGDROP_S_USEDEFAULTCURSORS = 262402 // 0x00040102
private const val DATA_S_SAMEFORMATETC = 262448 // 0x00040130

internal const val CF_UNICODETEXT = 13u
private const val GMEM_MOVEABLE = 0x0002u

private const val DROPEFFECT_NONE = 0u
private const val DROPEFFECT_COPY = 1u

/**
 * Starts a shell drag carrying [text], blocking until the user drops or cancels.
 *
 * Each COM object retains its creator reference for the full modal loop. Interface pointers handed
 * out by `QueryInterface` and by OLE add their own references, so native memory is not reclaimed
 * until all of those pointers have been released.
 */
internal fun startTextDrag(text: String): Boolean {
    val source = Win32DropSource()
    try {
        val data = Win32TextDataObject.create(text) ?: return false
        try {
            return memScoped {
                val effect = alloc<DWORDVar>()
                effect.value = DROPEFFECT_NONE
                val result = DoDragDrop(
                    data.comObject.ptr,
                    source.comObject.ptr,
                    DROPEFFECT_COPY,
                    effect.ptr,
                )
                // The Boolean answers whether this source started the modal transfer, not whether
                // the user ultimately dropped. A normal Escape cancellation must still stop source
                // traversal; returning false would immediately start a second eligible source.
                didStartDragLoop(result)
            }
        } finally {
            data.dispose()
        }
    } finally {
        source.dispose()
    }
}

internal fun didStartDragLoop(result: Int): Boolean =
    result == DRAGDROP_S_DROP || result == DRAGDROP_S_CANCEL

/** Tells OLE when the initiating button is released or Escape cancels the drag. */
internal class Win32DropSource {
    private val vtable = nativeHeap.alloc<IDropSourceVtbl>()
    internal val comObject = nativeHeap.alloc<IDropSource>()
    private val references = StaComReferenceCount(::destroyNative)
    private var ownerReferenceReleased = false

    internal val referenceCount: UInt get() = references.value

    init {
        vtable.QueryInterface = staticCFunction { self, iid, output ->
            if (output == null) {
                E_POINTER
            } else {
                output.pointed.value = null
                if (iid == null) {
                    E_POINTER
                } else {
                    val source = sources[self.rawAddressOfSource()]
                    when {
                        source == null -> E_UNEXPECTED
                        !iid.matchesOleInterfaceId(IID_DATA1_IUNKNOWN) &&
                            !iid.matchesOleInterfaceId(IID_DATA1_IDROPSOURCE) -> E_NOINTERFACE
                        else -> {
                            output.pointed.value = self
                            source.references.addRef()
                            S_OK
                        }
                    }
                }
            }
        }
        vtable.AddRef = staticCFunction { self ->
            sources[self.rawAddressOfSource()]?.references?.addRef() ?: 0u
        }
        vtable.Release = staticCFunction { self ->
            sources[self.rawAddressOfSource()]?.references?.release() ?: 0u
        }

        vtable.QueryContinueDrag = staticCFunction { _, escapePressed, keyState ->
            when {
                escapePressed != 0 -> DRAGDROP_S_CANCEL
                // The gesture ends when every initiating mouse button is up.
                keyState.toInt() and (MK_LBUTTON or MK_RBUTTON) == 0 -> DRAGDROP_S_DROP
                else -> S_OK
            }
        }

        // Let the shell draw the standard drag cursors.
        vtable.GiveFeedback = staticCFunction { _, _ -> DRAGDROP_S_USEDEFAULTCURSORS }

        comObject.lpVtbl = vtable.ptr
        sources[comObject.ptr.rawAddressOfSource()] = this
    }

    fun dispose() {
        if (ownerReferenceReleased) return
        ownerReferenceReleased = true
        references.release()
    }

    private fun destroyNative() {
        sources.remove(comObject.ptr.rawAddressOfSource())
        nativeHeap.free(comObject.rawPtr)
        nativeHeap.free(vtable.rawPtr)
    }
}

/** A read-only [IDataObject] offering one `CF_UNICODETEXT` value. */
internal class Win32TextDataObject private constructor(private val payload: HGLOBAL) {
    private val vtable = nativeHeap.alloc<IDataObjectVtbl>()
    internal val comObject = nativeHeap.alloc<IDataObject>()
    private val references = StaComReferenceCount(::destroyNative)
    private var ownerReferenceReleased = false

    internal val referenceCount: UInt get() = references.value

    init {
        vtable.QueryInterface = staticCFunction { self, iid, output ->
            if (output == null) {
                E_POINTER
            } else {
                output.pointed.value = null
                if (iid == null) {
                    E_POINTER
                } else {
                    val data = dataObjects[self.rawAddressOfData()]
                    when {
                        data == null -> E_UNEXPECTED
                        !iid.matchesOleInterfaceId(IID_DATA1_IUNKNOWN) &&
                            !iid.matchesOleInterfaceId(IID_DATA1_IDATAOBJECT) -> E_NOINTERFACE
                        else -> {
                            output.pointed.value = self
                            data.references.addRef()
                            S_OK
                        }
                    }
                }
            }
        }
        vtable.AddRef = staticCFunction { self ->
            dataObjects[self.rawAddressOfData()]?.references?.addRef() ?: 0u
        }
        vtable.Release = staticCFunction { self ->
            dataObjects[self.rawAddressOfData()]?.references?.release() ?: 0u
        }

        vtable.GetData = staticCFunction { self, format, medium ->
            val owner = dataObjects[self.rawAddressOfData()]
            when {
                owner == null -> E_UNEXPECTED
                format == null || medium == null -> E_INVALIDARG
                else -> {
                    medium.pointed.tymed = 0u
                    medium.pointed.hGlobal = null
                    medium.pointed.pUnkForRelease = null
                    val validation = validateUnicodeTextFormat(
                        clipboardFormat = format.pointed.cfFormat.toUInt(),
                        aspect = format.pointed.dwAspect,
                        index = format.pointed.lindex,
                        media = format.pointed.tymed,
                    )
                    if (validation != S_OK) {
                        validation
                    } else {
                        // The receiver owns this independent block and releases it via
                        // ReleaseStgMedium because pUnkForRelease is null.
                        val copy = owner.duplicatePayload()
                        if (copy == null) {
                            STG_E_MEDIUMFULL
                        } else {
                            medium.pointed.tymed = TYMED_HGLOBAL
                            medium.pointed.hGlobal = copy
                            medium.pointed.pUnkForRelease = null
                            S_OK
                        }
                    }
                }
            }
        }

        vtable.QueryGetData = staticCFunction { self, format ->
            if (dataObjects[self.rawAddressOfData()] == null) {
                E_UNEXPECTED
            } else if (format == null) {
                E_INVALIDARG
            } else {
                validateUnicodeTextFormat(
                    clipboardFormat = format.pointed.cfFormat.toUInt(),
                    aspect = format.pointed.dwAspect,
                    index = format.pointed.lindex,
                    media = format.pointed.tymed,
                )
            }
        }

        vtable.GetDataHere = staticCFunction { _, _, _ -> E_NOTIMPL }
        vtable.GetCanonicalFormatEtc = staticCFunction { _, input, output ->
            if (input == null || output == null) {
                E_INVALIDARG
            } else {
                // No alternate target-device representation is needed for plain Unicode text.
                output.pointed.ptd = null
                DATA_S_SAMEFORMATETC
            }
        }
        vtable.SetData = staticCFunction { _, _, _, _ -> E_NOTIMPL }
        vtable.EnumFormatEtc = staticCFunction { self, direction, output ->
            val owner = dataObjects[self.rawAddressOfData()]
            when {
                owner == null -> E_UNEXPECTED
                output == null -> E_POINTER
                else -> {
                    output.pointed.value = null
                    if (direction != DATADIR_GET) {
                        E_NOTIMPL
                    } else {
                        memScoped {
                            val format = alloc<FORMATETC>()
                            format.cfFormat = CF_UNICODETEXT.toUShort()
                            format.ptd = null
                            format.dwAspect = DVASPECT_CONTENT
                            format.lindex = -1
                            format.tymed = TYMED_HGLOBAL
                            SHCreateStdEnumFmtEtc(1u, format.ptr, output)
                        }
                    }
                }
            }
        }
        vtable.DAdvise = staticCFunction { _, _, _, _, connection ->
            connection?.pointed?.value = 0u
            OLE_E_ADVISENOTSUPPORTED
        }
        vtable.DUnadvise = staticCFunction { _, _ -> OLE_E_ADVISENOTSUPPORTED }
        vtable.EnumDAdvise = staticCFunction { _, output ->
            output?.pointed?.value = null
            OLE_E_ADVISENOTSUPPORTED
        }

        comObject.lpVtbl = vtable.ptr
        dataObjects[comObject.ptr.rawAddressOfData()] = this
    }

    /** A byte-for-byte copy; no decoding, truncation, or ownership sharing with the source. */
    private fun duplicatePayload(): HGLOBAL? {
        val byteCount = GlobalSize(payload)
        if (byteCount == 0uL) return null
        val source = GlobalLock(payload) ?: return null
        try {
            val copy = GlobalAlloc(GMEM_MOVEABLE, byteCount) ?: return null
            val destination = GlobalLock(copy)
            if (destination == null) {
                GlobalFree(copy)
                return null
            }
            try {
                memcpy(destination, source, byteCount)
            } finally {
                GlobalUnlock(copy)
            }
            return copy
        } finally {
            GlobalUnlock(payload)
        }
    }

    fun dispose() {
        if (ownerReferenceReleased) return
        ownerReferenceReleased = true
        references.release()
    }

    private fun destroyNative() {
        dataObjects.remove(comObject.ptr.rawAddressOfData())
        GlobalFree(payload)
        nativeHeap.free(comObject.rawPtr)
        nativeHeap.free(vtable.rawPtr)
    }

    companion object {
        fun create(text: String): Win32TextDataObject? =
            allocateUtf16Global(text)?.let(::Win32TextDataObject)
    }
}

/** Validates every FORMATETC field relevant to this one-format data object. */
internal fun validateUnicodeTextFormat(
    clipboardFormat: UInt,
    aspect: UInt,
    index: Int,
    media: UInt,
): Int = when {
    clipboardFormat != CF_UNICODETEXT -> DV_E_FORMATETC
    aspect != DVASPECT_CONTENT -> DV_E_DVASPECT
    index != -1 -> DV_E_LINDEX
    media and TYMED_HGLOBAL == 0u -> DV_E_TYMED
    else -> S_OK
}

private fun CPointer<IDropSource>?.rawAddressOfSource(): Long = this?.rawValue?.toLong() ?: 0L
private fun CPointer<IDataObject>?.rawAddressOfData(): Long = this?.rawValue?.toLong() ?: 0L

private val sources = mutableMapOf<Long, Win32DropSource>()
private val dataObjects = mutableMapOf<Long, Win32TextDataObject>()

internal val liveWin32DropSourceCount: Int get() = sources.size
internal val liveWin32TextDataObjectCount: Int get() = dataObjects.size

/** Copies [text] into an owned moveable global block, NUL terminated. */
private fun allocateUtf16Global(text: String): HGLOBAL? {
    val characters = text.length.toULong() + 1uL
    val byteCount = characters * sizeOf<WCHARVar>().toULong()
    val handle = GlobalAlloc(GMEM_MOVEABLE, byteCount) ?: return null
    val locked = GlobalLock(handle)
    if (locked == null) {
        GlobalFree(handle)
        return null
    }
    try {
        val output = locked.reinterpret<WCHARVar>()
        for (index in text.indices) output[index] = text[index].code.toUShort()
        output[text.length] = 0u
    } finally {
        GlobalUnlock(handle)
    }
    return handle
}
