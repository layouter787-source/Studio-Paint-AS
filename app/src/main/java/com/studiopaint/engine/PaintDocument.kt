package com.studiopaint.engine

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class LayerKind { DRAWING, SCENERY }

enum class BlendKind(val label: String, val mode: BlendMode) {
    NORMAL("Normal", BlendMode.SRC_OVER),
    MULTIPLY("Multiplicar", BlendMode.MULTIPLY),
    SCREEN("Tela", BlendMode.SCREEN),
    OVERLAY("Sobrepor", BlendMode.OVERLAY),
    DARKEN("Escurecer", BlendMode.DARKEN),
    LIGHTEN("Clarear", BlendMode.LIGHTEN),
    ADD("Adicionar", BlendMode.PLUS),
}

class PaintLayer(val id: Long, name: String, val kind: LayerKind, val bitmap: Bitmap) {
    var name by mutableStateOf(name)
    var visible by mutableStateOf(true)
    var opacity by mutableFloatStateOf(1f)
    var blend by mutableStateOf(BlendKind.NORMAL)
    var locked by mutableStateOf(false)
    var version = 0
    var cachedPng: ByteArray? = null
    var cachedVersion = -1
    fun touch() { version++ }
}

class UndoEntry(val undo: () -> Unit, val redo: () -> Unit, val bytes: Long)

class History(private val maxBytes: Long = 256L * 1024 * 1024, private val maxEntries: Int = 100) {
    private val undoS = ArrayDeque<UndoEntry>()
    private val redoS = ArrayDeque<UndoEntry>()
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set

    fun push(e: UndoEntry) {
        undoS.addLast(e); redoS.clear()
        while (undoS.size > maxEntries || (undoS.size > 1 && undoS.sumOf { it.bytes } > maxBytes)) undoS.removeFirst()
        refresh()
    }

    fun undo(): Boolean {
        val e = undoS.removeLastOrNull() ?: return false
        e.undo(); redoS.addLast(e); refresh(); return true
    }

    fun redo(): Boolean {
        val e = redoS.removeLastOrNull() ?: return false
        e.redo(); undoS.addLast(e); refresh(); return true
    }

    private fun refresh() { canUndo = undoS.isNotEmpty(); canRedo = redoS.isNotEmpty() }
}

class PaintDocument(val width: Int, val height: Int, var nextId: Long, var paperArgb: Int = Color.WHITE) {
    val layers = mutableStateListOf<PaintLayer>()
    var activeId by mutableLongStateOf(-1L)
    var revision by mutableIntStateOf(0)
    val history = History()

    var strokeBuffer: Bitmap? = null
    var liveLayerId = -1L
    var liveOpacity = 1f

    val active: PaintLayer? get() = layers.firstOrNull { it.id == activeId }
    fun bump() { revision++ }
    fun newBitmap(): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    fun obtainStrokeBuffer(): Bitmap = strokeBuffer ?: newBitmap().also { strokeBuffer = it }

    private val srcPaint = Paint().apply { blendMode = BlendMode.SRC }

    private fun restore(layer: PaintLayer, rect: Rect, bmp: Bitmap) {
        Canvas(layer.bitmap).drawBitmap(bmp, rect.left.toFloat(), rect.top.toFloat(), srcPaint)
        layer.touch(); bump()
    }

    fun pixelEntry(layer: PaintLayer, rect: Rect, before: Bitmap, after: Bitmap) = UndoEntry(
        undo = { restore(layer, rect, before) },
        redo = { restore(layer, rect, after) },
        bytes = (before.allocationByteCount + after.allocationByteCount).toLong(),
    )

    /** Regista no histórico a diferença entre [snapshot] (antes) e o estado atual dentro de [rect]. */
    fun recordPixels(layer: PaintLayer, snapshot: Bitmap, rect: Rect) {
        val before = Bitmap.createBitmap(snapshot, rect.left, rect.top, rect.width(), rect.height())
        val after = Bitmap.createBitmap(layer.bitmap, rect.left, rect.top, rect.width(), rect.height())
        history.push(pixelEntry(layer, rect, before, after))
        layer.touch(); bump()
    }

    // ---------- camadas ----------
    fun addLayer(kind: LayerKind): PaintLayer {
        val n = layers.count { it.kind == kind } + 1
        val layer = PaintLayer(nextId++, if (kind == LayerKind.DRAWING) "Camada $n" else "Cenário $n", kind, newBitmap())
        val idx = (layers.indexOfFirst { it.id == activeId } + 1).coerceAtLeast(0)
        val prevActive = activeId
        insert(idx, layer)
        history.push(UndoEntry({ remove(layer); activeId = prevActive }, { insert(idx, layer) }, 0))
        return layer
    }

    private fun insert(idx: Int, l: PaintLayer) { layers.add(idx.coerceIn(0, layers.size), l); activeId = l.id; bump() }
    private fun remove(l: PaintLayer) { layers.remove(l); if (activeId == l.id) activeId = layers.lastOrNull()?.id ?: -1L; bump() }

    fun deleteLayer(id: Long) {
        if (layers.size <= 1) return
        val idx = layers.indexOfFirst { it.id == id }
        if (idx < 0) return
        val l = layers[idx]
        remove(l)
        history.push(UndoEntry({ insert(idx, l) }, { remove(l) }, 0))
    }

    fun duplicateLayer(id: Long) {
        val idx = layers.indexOfFirst { it.id == id }
        if (idx < 0) return
        val src = layers[idx]
        val copy = PaintLayer(nextId++, src.name + " cópia", src.kind, src.bitmap.copy(Bitmap.Config.ARGB_8888, true))
        copy.opacity = src.opacity; copy.blend = src.blend; copy.visible = src.visible
        insert(idx + 1, copy)
        history.push(UndoEntry({ remove(copy) }, { insert(idx + 1, copy) }, copy.bitmap.allocationByteCount.toLong()))
    }

    fun moveLayer(id: Long, delta: Int) {
        val i = layers.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in layers.indices) return
        fun swap() { val a = layers[i]; layers[i] = layers[j]; layers[j] = a; bump() }
        swap()
        history.push(UndoEntry({ swap() }, { swap() }, 0))
    }

    fun mergeDown(id: Long) {
        val i = layers.indexOfFirst { it.id == id }
        if (i <= 0) return
        val upper = layers[i]; val lower = layers[i - 1]
        val before = lower.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val p = Paint().apply { alpha = (upper.opacity * 255).toInt(); blendMode = upper.blend.mode }
        Canvas(lower.bitmap).drawBitmap(upper.bitmap, 0f, 0f, p)
        val after = lower.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val whole = Rect(0, 0, width, height)
        remove(upper); activeId = lower.id; lower.touch()
        history.push(UndoEntry(
            { restore(lower, whole, before); insert(i, upper) },
            { restore(lower, whole, after); remove(upper); activeId = lower.id },
            (before.allocationByteCount * 2).toLong(),
        ))
    }
}
