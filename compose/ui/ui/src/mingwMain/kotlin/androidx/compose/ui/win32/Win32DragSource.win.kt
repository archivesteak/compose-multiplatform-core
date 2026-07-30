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
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import platform.windows.DWORDVar
import platform.windows.DoDragDrop
import platform.windows.GlobalAlloc
import platform.windows.GlobalLock
import platform.windows.GlobalUnlock
import platform.windows.IDataObject
import platform.windows.IDataObjectVtbl
import platform.windows.IDropSource
import platform.windows.IDropSourceVtbl
import platform.windows.MK_LBUTTON
import platform.windows.MK_RBUTTON
import platform.windows.STGMEDIUM
import platform.windows.TYMED_HGLOBAL
import platform.windows.WCHARVar

private const val S_OK = 0
private const val E_NOTIMPL = -2147467263 // 0x80004001
private const val E_FAIL = -2147467259 // 0x80004005
private const val DRAGDROP_S_DROP = 262400 // 0x00040100
private const val DRAGDROP_S_CANCEL = 262401 // 0x00040101
private const val DRAGDROP_S_USEDEFAULTCURSORS = 262402 // 0x00040102
private const val DV_E_FORMATETC = -2147221404 // 0x80040064

private const val CF_UNICODETEXT = 13u
private const val GMEM_MOVEABLE = 0x2002u

private const val DROPEFFECT_NONE = 0u
private const val DROPEFFECT_COPY = 1u

/**
 * Starts a shell drag carrying [text], blocking until the user drops or cancels.
 *
 * This is the outgoing half of drag and drop: `DoDragDrop` runs its own modal loop, calling back
 * into the [IDropSource] to ask whether to continue and into the [IDataObject] to fetch the
 * payload when a target accepts it. Both objects are built by hand — Kotlin/Native has no COM —
 * as native memory whose first word points at a vtable of [staticCFunction] entries.
 *
 * Returns true if the drop was accepted somewhere.
 */
internal fun startTextDrag(text: String): Boolean {
    val source = Win32DropSource()
    val data = Win32TextDataObject(text)
    return try {
        memScoped {
            val effect = alloc<DWORDVar>()
            val result = DoDragDrop(data.comObject.ptr, source.comObject.ptr, DROPEFFECT_COPY, effect.ptr)
            result == DRAGDROP_S_DROP && effect.value != DROPEFFECT_NONE
        }
    } finally {
        data.dispose()
        source.dispose()
    }
}

/**
 * Tells the shell when the drag ends: releasing the button drops, pressing Escape cancels.
 *
 * It holds no per-drag state, so unlike the drop target it needs no map back to a Kotlin
 * instance — the vtable entries answer purely from their arguments.
 */
private class Win32DropSource {
    private val vtable = nativeHeap.alloc<IDropSourceVtbl>()
    val comObject = nativeHeap.alloc<IDropSource>()

    init {
        vtable.QueryInterface = staticCFunction { self, _, ppv ->
            ppv?.pointed?.value = self
            S_OK
        }
        vtable.AddRef = staticCFunction { _ -> 1u }
        vtable.Release = staticCFunction { _ -> 1u }

        vtable.QueryContinueDrag = staticCFunction { _, escapePressed, keyState ->
            when {
                escapePressed != 0 -> DRAGDROP_S_CANCEL
                // The gesture ends when every mouse button is up.
                keyState.toInt() and (MK_LBUTTON or MK_RBUTTON) == 0 -> DRAGDROP_S_DROP
                else -> S_OK
            }
        }

        // Let the shell draw the standard drag cursors.
        vtable.GiveFeedback = staticCFunction { _, _ -> DRAGDROP_S_USEDEFAULTCURSORS }

        comObject.lpVtbl = vtable.ptr
    }

    fun dispose() {
        nativeHeap.free(comObject.rawPtr)
        nativeHeap.free(vtable.rawPtr)
    }
}

/**
 * A minimal [IDataObject] offering a single `CF_UNICODETEXT` value.
 *
 * Only the methods `DoDragDrop` and a drop target actually use are implemented; the rest return
 * `E_NOTIMPL`, which is what a source-only data object is expected to do.
 */
private class Win32TextDataObject(text: String) {
    private val vtable = nativeHeap.alloc<IDataObjectVtbl>()
    val comObject = nativeHeap.alloc<IDataObject>()

    /** The payload, kept in a moveable global block for the lifetime of the drag. */
    private val payload = allocateUtf16Global(text)

    init {
        objects[comObject.ptr.rawValue.toLong()] = this

        vtable.QueryInterface = staticCFunction { self, _, ppv ->
            ppv?.pointed?.value = self
            S_OK
        }
        vtable.AddRef = staticCFunction { _ -> 1u }
        vtable.Release = staticCFunction { _ -> 1u }

        vtable.GetData = staticCFunction { self, format, medium ->
            val owner = objects[self.rawAddressOfData()]
            val requested = format?.pointed
            when {
                owner == null || medium == null || requested == null -> E_FAIL
                requested.cfFormat.toUInt() != CF_UNICODETEXT -> DV_E_FORMATETC
                requested.tymed and TYMED_HGLOBAL.toUInt() == 0u -> DV_E_FORMATETC
                else -> {
                    // The receiver takes ownership of the copy and frees it via ReleaseStgMedium.
                    val copy = owner.duplicatePayload()
                    if (copy == null) {
                        E_FAIL
                    } else {
                        medium.pointed.tymed = TYMED_HGLOBAL.toUInt()
                        medium.pointed.hGlobal = copy
                        medium.pointed.pUnkForRelease = null
                        S_OK
                    }
                }
            }
        }

        vtable.QueryGetData = staticCFunction { _, format ->
            val requested = format?.pointed
            if (requested != null && requested.cfFormat.toUInt() == CF_UNICODETEXT) S_OK
            else DV_E_FORMATETC
        }

        // A drag source never has to satisfy these.
        vtable.GetDataHere = staticCFunction { _, _, _ -> E_NOTIMPL }
        vtable.GetCanonicalFormatEtc = staticCFunction { _, _, _ -> E_NOTIMPL }
        vtable.SetData = staticCFunction { _, _, _, _ -> E_NOTIMPL }
        vtable.EnumFormatEtc = staticCFunction { _, _, _ -> E_NOTIMPL }
        vtable.DAdvise = staticCFunction { _, _, _, _, _ -> E_NOTIMPL }
        vtable.DUnadvise = staticCFunction { _, _ -> E_NOTIMPL }
        vtable.EnumDAdvise = staticCFunction { _, _ -> E_NOTIMPL }

        comObject.lpVtbl = vtable.ptr
    }

    /** A fresh global block with the same text, for a receiver that will free it. */
    fun duplicatePayload(): platform.windows.HGLOBAL? = payload?.let { source ->
        val locked = GlobalLock(source) ?: return null
        try {
            allocateUtf16Global(locked.reinterpret<WCHARVar>().readUtf16())
        } finally {
            GlobalUnlock(source)
        }
    }

    fun dispose() {
        objects.remove(comObject.ptr.rawValue.toLong())
        payload?.let { platform.windows.GlobalFree(it) }
        nativeHeap.free(comObject.rawPtr)
        nativeHeap.free(vtable.rawPtr)
    }
}

private fun CPointer<IDataObject>?.rawAddressOfData(): Long = this?.rawValue?.toLong() ?: 0L

private val objects = mutableMapOf<Long, Win32TextDataObject>()

/** Copies [text] into a moveable global block, NUL terminated, as clipboard formats require. */
private fun allocateUtf16Global(text: String): platform.windows.HGLOBAL? {
    val characters = text.length + 1
    val handle = GlobalAlloc(GMEM_MOVEABLE, (characters * 2).convert()) ?: return null
    val locked = GlobalLock(handle)
    if (locked == null) {
        platform.windows.GlobalFree(handle)
        return null
    }
    val chars = locked.reinterpret<WCHARVar>()
    for (i in text.indices) chars[i] = text[i].code.toUShort()
    chars[text.length] = 0u
    GlobalUnlock(handle)
    return handle
}

private fun CPointer<WCHARVar>.readUtf16(): String = buildString {
    var i = 0
    while (this@readUtf16[i] != 0.toUShort()) {
        append(this@readUtf16[i].toInt().toChar())
        i++
    }
}
