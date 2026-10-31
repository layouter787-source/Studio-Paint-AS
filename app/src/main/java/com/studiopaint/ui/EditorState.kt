package com.studiopaint.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.studiopaint.domain.Pt
import com.studiopaint.domain.ViewTransform
import com.studiopaint.engine.AspkBridge
import com.studiopaint.engine.BrushLibrary
import com.studiopaint.engine.BrushPreset
import com.studiopaint.engine.Compositor
import com.studiopaint.engine.FloodFill
import com.studiopaint.engine.LayerKind
import com.studiopaint.engine.LayerSave
import com.studiopaint.engine.PaintDocument
import com.studiopaint.engine.PaintLayer
import com.studiopaint.engine.StrokeSession
import com.studiopaint.format.AspkProject
import com.studiopaint.storage.ProjectStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

enum class Tool { BRUSH, ERASER, FILL, EYEDROPPER, HAND }
enum class SaveStatus { SAVED, SAVING, ERROR }

class EditorState(
    val projectId: String,
    val name: String,
    private var base: AspkProject,
    val frameIndex: Int,
    val doc: PaintDocument,
    private val store: ProjectStore,
    private val scope: CoroutineScope,
) {
    var tool by mutableStateOf(Tool.BRUSH)
    var preset by mutableStateOf(BrushLibrary.default)
    var brushSize by mutableFloatStateOf(18f)
    var brushOpacity by mutableFloatStateOf(1f)
    var color by mutableIntStateOf(Color.BLACK)
    val recent = mutableStateListOf<Int>()
    var fillTolerance by mutableIntStateOf(24)
    var fillGrow by mutableIntStateOf(1)
    var transform by mutableStateOf(ViewTransform())
    var message by mutableStateOf<String?>(null)
    var saveStatus by mutableStateOf(SaveStatus.SAVED)

    private var session: StrokeSession? = null
    private var fitted = false
    private var viewW = 0f
    private var viewH = 0f
    private var saveJob: Job? = null
    private val saveMutex = Mutex()

    fun onViewSize(w: Float, h: Float) {
        viewW = w; viewH = h
        if (!fitted && w > 0f && h > 0f) { fitToView(); fitted = true }
    }

    fun fitToView() { if (viewW > 0f) transform = ViewTransform.fit(doc.width, doc.height, viewW, viewH) }

    // ---------- desenho ----------
    fun beginStroke(p: Pt, pressure: Float): Boolean {
        val layer = doc.active ?: return false
        if (!layer.visible || layer.locked) { message = "A camada está oculta ou bloqueada"; return false }
        val s = StrokeSession(doc, layer, preset, color, brushSize, brushOpacity, tool == Tool.ERASER)
        s.down(p.x, p.y, pressure)
        session = s
        return true
    }

    fun strokeTo(p: Pt, pressure: Float) { session?.move(p.x, p.y, pressure) }

    fun endStroke() {
        session?.up(); session = null
        if (tool == Tool.BRUSH) pushRecent(color)
        markDirty()
    }

    fun cancelStroke() { session?.cancel(); session = null }

    fun tap(p: Pt) {
        val x = p.x.toInt(); val y = p.y.toInt()
        when (tool) {
            Tool.FILL -> fill(x, y)
            Tool.EYEDROPPER -> { color = Compositor.sample(doc, x, y); tool = Tool.BRUSH }
            else -> {}
        }
    }

    private fun fill(x: Int, y: Int) {
        val layer = doc.active ?: return
        if (!layer.visible || layer.locked) { message = "A camada está oculta ou bloqueada"; return }
        val w = doc.width; val h = doc.height
        val fillColor = color or (0xFF shl 24)
        scope.launch {
            val res = withContext(Dispatchers.Default) {
                val snap = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                val px = IntArray(w * h)
                layer.bitmap.getPixels(px, 0, w, 0, 0, w, h)
                val r = FloodFill.fill(px, w, h, x, y, fillColor, fillTolerance, fillGrow)
                if (r == null) { snap.recycle(); null } else {
                    layer.bitmap.setPixels(px, 0, w, 0, 0, w, h)
                    snap to Rect(r[0], r[1], r[2] + 1, r[3] + 1)
                }
            }
            if (res != null) {
                doc.recordPixels(layer, res.first, res.second)
                res.first.recycle(); pushRecent(color); markDirty()
            }
        }
    }

    private fun pushRecent(c: Int) {
        val v = c or (0xFF shl 24)
        recent.remove(v); recent.add(0, v)
        while (recent.size > 16) recent.removeAt(recent.lastIndex)
    }

    // ---------- histórico e camadas ----------
    fun undo() { if (doc.history.undo()) markDirty() }
    fun redo() { if (doc.history.redo()) markDirty() }
    fun addLayer(kind: LayerKind) { doc.addLayer(kind); markDirty() }
    fun deleteLayer(id: Long) { doc.deleteLayer(id); markDirty() }
    fun duplicateLayer(id: Long) { doc.duplicateLayer(id); markDirty() }
    fun moveLayer(id: Long, d: Int) { doc.moveLayer(id, d); markDirty() }
    fun mergeDown(id: Long) { doc.mergeDown(id); markDirty() }
    fun layerChanged(l: PaintLayer) { l.touch(); doc.bump(); markDirty() }

    // ---------- gravação ----------
    fun markDirty() {
        saveStatus = SaveStatus.SAVING
        saveJob?.cancel()
        saveJob = scope.launch { delay(1200); saveNow() }
    }

    private class Snap(val layer: PaintLayer, val version: Int, val png: ByteArray?, val copy: Bitmap?)

    suspend fun saveNow() = withContext(NonCancellable) {
        saveMutex.withLock {
            saveStatus = SaveStatus.SAVING
            try {
                val snaps = doc.layers.map { l ->
                    val cached = l.cachedPng
                    if (cached != null && l.cachedVersion == l.version) Snap(l, l.version, cached, null)
                    else Snap(l, l.version, null, l.bitmap.copy(Bitmap.Config.ARGB_8888, false))
                }
                val thumb = Compositor.thumbnail(doc, 320)
                val nextId = doc.nextId
                val infos = snaps.map { s -> s.layer.let { LayerInfo(it.id, it.name, it.kind, it.opacity, it.visible, it.blend, it.locked) } }
                val built = withContext(Dispatchers.Default) {
                    val pngs = snaps.map { s -> s.png ?: encodePng(s.copy!!).also { s.copy.recycle() } }
                    val saves = infos.mapIndexed { i, n -> LayerSave(n.id, n.name, n.kind, n.opacity, n.visible, n.blend, n.locked, pngs[i]) }
                    val proj = AspkBridge.build(base, saves, frameIndex, nextId)
                    store.save(projectId, proj, thumb, frameIndex)
                    proj to pngs
                }
                base = built.first
                snaps.forEachIndexed { i, s -> s.layer.cachedPng = built.second[i]; s.layer.cachedVersion = s.version }
                thumb.recycle()
                saveStatus = SaveStatus.SAVED
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                saveStatus = SaveStatus.ERROR; message = "Falha ao guardar: ${t.message}"
            }
        }
    }

    private class LayerInfo(val id: Long, val name: String, val kind: LayerKind, val opacity: Float, val visible: Boolean, val blend: com.studiopaint.engine.BlendKind, val locked: Boolean)

    private fun encodePng(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    suspend fun renderPng(transparent: Boolean): ByteArray = withContext(Dispatchers.Default) {
        val bmp = Compositor.flatten(doc, transparent)
        encodePng(bmp).also { bmp.recycle() }
    }

    /** Projeto ASPK completo (todos os frames), para exportar/partilhar. */
    suspend fun projectFileBytes(): ByteArray {
        saveNow()
        return withContext(Dispatchers.IO) { store.projectFile(projectId).readBytes() }
    }
}
