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

import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.geometry.Offset
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.windows.DVASPECT_CONTENT
import platform.windows.DragQueryFileW
import platform.windows.FORMATETC
import platform.windows.GlobalLock
import platform.windows.GlobalUnlock
import platform.windows.HDROP
import platform.windows.HWND
import platform.windows.IDataObject
import platform.windows.IDropTarget
import platform.windows.IDropTargetVtbl
import platform.windows.POINTL
import platform.windows.RegisterDragDrop
import platform.windows.ReleaseStgMedium
import platform.windows.RevokeDragDrop
import platform.windows.STGMEDIUM
import platform.windows.ScreenToClient
import platform.windows.TYMED_HGLOBAL
import platform.windows.WCHARVar

/** `DROPEFFECT_*`; only copy is offered, which is what a viewer-style drop wants. */
private const val DROPEFFECT_NONE = 0u
private const val DROPEFFECT_COPY = 1u

/** Clipboard format ids, the same ones the clipboard code uses. */
private const val CF_UNICODETEXT = 13u
private const val CF_HDROP = 15u

private const val S_OK = 0
private const val E_NOINTERFACE = -2147467262 // 0x80004002

/**
 * The `IDropTarget` a Compose window registers with the shell, so files and text can be dropped
 * onto it.
 *
 * Kotlin/Native has no COM support, so the object is assembled by hand: a block of native memory
 * whose first word points at a vtable of [staticCFunction] entries. Those are plain C functions
 * with no captured state, so each one finds its Kotlin instance through [targets], keyed by the
 * COM pointer the shell passes back as `This`.
 *
 * Lifetime is managed by the window rather than by COM reference counting: [dispose] revokes the
 * registration and frees the memory, and the shell holds no reference past that point.
 */
internal class Win32DropTarget(
    private val hwnd: HWND,
    private val onDragEnter: (DragAndDropEvent) -> Boolean,
    private val onDragOver: (DragAndDropEvent) -> Unit,
    private val onDragLeave: () -> Unit,
    private val onDrop: (DragAndDropEvent) -> Boolean,
) {
    private val vtable = nativeHeap.alloc<IDropTargetVtbl>()
    private val comObject = nativeHeap.alloc<IDropTarget>()

    /** Payload of the drag in progress, read once on enter and reused while it moves. */
    private var draggedText: String? = null
    private var draggedFiles: List<String> = emptyList()
    private var isAccepted = false

    init {
        vtable.QueryInterface = staticCFunction { self, _, ppv ->
            // Every interface this object can be asked for is the same pointer; the shell only
            // ever wants IUnknown or IDropTarget.
            ppv?.pointed?.value = self
            S_OK
        }
        // Reference counting is a no-op: the window owns this object outright.
        vtable.AddRef = staticCFunction { _ -> 1u }
        vtable.Release = staticCFunction { _ -> 1u }

        vtable.DragEnter = staticCFunction { self, dataObject, keyState, point, effect ->
            val target = targets[self.rawAddress()]
            if (target == null) {
                effect?.pointed?.value = DROPEFFECT_NONE
                S_OK
            } else {
                target.readPayload(dataObject)
                target.isAccepted = target.onDragEnter(target.eventAt(point, keyState.toInt()))
                effect?.pointed?.value = target.currentEffect()
                S_OK
            }
        }

        vtable.DragOver = staticCFunction { self, keyState, point, effect ->
            val target = targets[self.rawAddress()]
            target?.onDragOver(target.eventAt(point, keyState.toInt()))
            effect?.pointed?.value = target?.currentEffect() ?: DROPEFFECT_NONE
            S_OK
        }

        vtable.DragLeave = staticCFunction { self ->
            targets[self.rawAddress()]?.let { target ->
                target.onDragLeave()
                target.clearPayload()
            }
            S_OK
        }

        vtable.Drop = staticCFunction { self, dataObject, keyState, point, effect ->
            val target = targets[self.rawAddress()]
            if (target == null) {
                effect?.pointed?.value = DROPEFFECT_NONE
            } else {
                // Re-read: a shell drop may carry data the enter notification did not.
                target.readPayload(dataObject)
                val accepted = target.onDrop(target.eventAt(point, keyState.toInt()))
                effect?.pointed?.value = if (accepted) DROPEFFECT_COPY else DROPEFFECT_NONE
                target.clearPayload()
            }
            S_OK
        }

        comObject.lpVtbl = vtable.ptr
        targets[comObject.ptr.rawAddress()] = this
        RegisterDragDrop(hwnd, comObject.ptr)
    }

    fun dispose() {
        RevokeDragDrop(hwnd)
        targets.remove(comObject.ptr.rawAddress())
        nativeHeap.free(comObject.rawPtr)
        nativeHeap.free(vtable.rawPtr)
    }

    private fun currentEffect(): UInt = if (isAccepted) DROPEFFECT_COPY else DROPEFFECT_NONE

    private fun clearPayload() {
        draggedText = null
        draggedFiles = emptyList()
        isAccepted = false
    }

    /** Builds the Compose event, converting the shell's screen point to client coordinates. */
    private fun eventAt(point: CValue<POINTL>, keyState: Int): DragAndDropEvent {
        val position = point.useContents {
            memScoped {
                val client = alloc<platform.windows.POINT>()
                client.x = x
                client.y = y
                ScreenToClient(hwnd, client.ptr)
                Offset(client.x.toFloat(), client.y.toFloat())
            }
        }
        return DragAndDropEvent(
            position = position,
            text = draggedText,
            files = draggedFiles,
            keyState = keyState,
        )
    }

    /**
     * Copies the payload out of the data object. It is only valid for the duration of the OLE
     * call, so nothing may be read from it later.
     */
    private fun readPayload(dataObject: CPointer<IDataObject>?) {
        clearPayload()
        if (dataObject == null) return
        draggedText = readText(dataObject)
        draggedFiles = readFiles(dataObject)
    }

    private fun readText(dataObject: CPointer<IDataObject>): String? = memScoped {
        val medium = alloc<STGMEDIUM>()
        if (!getData(dataObject, CF_UNICODETEXT, medium)) return@memScoped null
        try {
            val locked = GlobalLock(medium.hGlobal) ?: return@memScoped null
            try {
                locked.reinterpret<WCHARVar>().toKStringFromUtf16()
            } finally {
                GlobalUnlock(medium.hGlobal)
            }
        } finally {
            ReleaseStgMedium(medium.ptr)
        }
    }

    private fun readFiles(dataObject: CPointer<IDataObject>): List<String> = memScoped {
        val medium = alloc<STGMEDIUM>()
        if (!getData(dataObject, CF_HDROP, medium)) return@memScoped emptyList()
        try {
            val drop: HDROP = medium.hGlobal!!.reinterpret()
            // 0xFFFFFFFF asks for the number of paths rather than one of them.
            val count = DragQueryFileW(drop, 0xFFFFFFFFu, null, 0u).toInt()
            if (count <= 0) return@memScoped emptyList()
            (0 until count).mapNotNull { index ->
                val length = DragQueryFileW(drop, index.toUInt(), null, 0u).toInt()
                if (length <= 0) return@mapNotNull null
                val buffer = allocArray<WCHARVar>(length + 1)
                DragQueryFileW(drop, index.toUInt(), buffer, (length + 1).toUInt())
                buildString(length) { for (i in 0 until length) append(buffer[i].toInt().toChar()) }
            }
        } finally {
            ReleaseStgMedium(medium.ptr)
        }
    }
}

/** Requests one clipboard format from the data object, filling [medium] on success. */
private fun getData(
    dataObject: CPointer<IDataObject>,
    format: UInt,
    medium: STGMEDIUM,
): Boolean {
    val getDataMethod = dataObject.pointed.lpVtbl?.pointed?.GetData ?: return false
    return memScoped {
        val request = alloc<FORMATETC>()
        request.cfFormat = format.toUShort()
        request.ptd = null
        request.dwAspect = DVASPECT_CONTENT.toUInt()
        request.lindex = -1
        request.tymed = TYMED_HGLOBAL.toUInt()
        getDataMethod(dataObject, request.ptr, medium.ptr) == S_OK
    }
}

/** Identity of a COM pointer, used to find the Kotlin object behind a `This` pointer. */
private fun CPointer<IDropTarget>?.rawAddress(): Long = this?.rawValue?.toLong() ?: 0L

/**
 * Every live drop target, keyed by its COM pointer. The vtable entries are static C functions and
 * cannot capture state, so this is how they get back to their instance.
 */
private val targets = mutableMapOf<Long, Win32DropTarget>()
