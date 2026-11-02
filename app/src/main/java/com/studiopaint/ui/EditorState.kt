package com.studiopaint.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.studiopaint.domain.Pt
import com.studiopaint.domain.ViewTransform
import com.studiopaint.engine.AspkBridge
import com.studiopaint.engine.BlendKind
import com.studiopaint.engine.BrushLibrary
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class Tool { BRUSH, ERASER, LASSO }
enum class ShapeMode { NONE, LINE, RECT, ELLIPSE }
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
    var fitMargin = 80f

    // formas, régua, referência, seleção, conta-gotas
    var shapeMode by mutableStateOf(ShapeMode.NONE)
    var rulerOn by mutableStateOf(false)
    var rulerA by mutableStateOf(Pt(0f, 0f))
    var rulerB by mutableStateOf(Pt(0f, 0f))
    var refImage by mutableStateOf<Bitmap?>(null)
    var refVisible by mutableStateOf(false)
    var selection by mutableStateOf<Path?>(null)
    var selectionRev by mutableIntStateOf(0)
    val lassoDraft = mutableStateListOf<Pt>()
    var eyedropAt by mutableStateOf<Offset?>(null)

    private var session: StrokeSession? = null
    private var fitted = false
    private var viewW = 0f
    private var viewH = 0f
    private var saveJob: Job? = null
    private val saveMutex = Mutex()
    private var snapping = false
    private var shapeStart: Pt? = null
    private var shapeLast: Pt? = null
    private var rulerGrab = Pt(0f, 0f)

    fun onViewSize(w: Float, h: Float) {
        viewW = w; viewH = h
        if (!fitted && w > 0f && h > 0f) { fitToView(); fitted = true }
    }

    fun fitToView() { if (viewW > 0f) transform = ViewTransform.fit(doc.width, doc.height, viewW, viewH, fitMargin) }

    private fun activeDrawable(): PaintLayer? {
        val layer = doc.active ?: return null
        if (!layer.visible || layer.locked) { message = "A camada está oculta ou bloqueada"; return null }
        return layer
    }

    // ---------- régua ----------
    fun toggleRuler() {
        rulerOn = !rulerOn
        if (rulerOn && rulerA == rulerB) {
            rulerA = Pt(doc.width * 0.2f, doc.height * 0.5f); rulerB = Pt(doc.width * 0.8f, doc.height * 0.5f)
        }
    }

    /** 1 = ponto A, 2 = ponto B, 3 = corpo, 0 = nada. [r] em pixels de ecrã. */
    fun hitRuler(screen: Pt, r: Float): Int {
        if (!rulerOn) return 0
        val a = transform.docToScreen(rulerA); val b = transform.docToScreen(rulerB)
        if (hypot(screen.x - a.x, screen.y - a.y) <= r) { rulerGrab = transform.screenToDoc(screen); return 1 }
        if (hypot(screen.x - b.x, screen.y - b.y) <= r) { rulerGrab = transform.screenToDoc(screen); return 2 }
        val dx = b.x - a.x; val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 > 1f) {
            val t = (((screen.x - a.x) * dx + (screen.y - a.y) * dy) / len2).coerceIn(0f, 1f)
            if (hypot(screen.x - (a.x + t * dx), screen.y - (a.y + t * dy)) <= r * 0.7f) { rulerGrab = transform.screenToDoc(screen); return 3 }
        }
        return 0
    }

    fun dragRuler(hit: Int, screen: Pt) {
        val d = transform.screenToDoc(screen)
        when (hit) {
            1 -> rulerA = d
            2 -> rulerB = d
            3 -> {
                val dx = d.x - rulerGrab.x; val dy = d.y - rulerGrab.y
                rulerA = Pt(rulerA.x + dx, rulerA.y + dy); rulerB = Pt(rulerB.x + dx, rulerB.y + dy); rulerGrab = d
            }
        }
    }

    private fun distToLine(p: Pt): Float {
        val dx = rulerB.x - rulerA.x; val dy = rulerB.y - rulerA.y
        val len = hypot(dx, dy)
        if (len < 1f) return Float.MAX_VALUE
        return kotlin.math.abs((p.x - rulerA.x) * dy - (p.y - rulerA.y) * dx) / len
    }

    private fun snapPt(p: Pt): Pt {
        if (!snapping) return p
        val dx = rulerB.x - rulerA.x; val dy = rulerB.y - rulerA.y
        val len2 = dx * dx + dy * dy
        if (len2 < 1f) return p
        val t = ((p.x - rulerA.x) * dx + (p.y - rulerA.y) * dy) / len2
        return Pt(rulerA.x + t * dx, rulerA.y + t * dy)
    }

    // ---------- traço livre ----------
    fun beginStroke(p: Pt, pressure: Float): Boolean {
        val layer = activeDrawable() ?: return false
        snapping = rulerOn && distToLine(p) <= 56f / transform.scale
        val q = snapPt(p)
        val s = StrokeSession(doc, layer, preset, color, brushSize, brushOpacity, tool == Tool.ERASER, selection)
        s.down(q.x, q.y, pressure)
        session = s
        return true
    }

    fun strokeTo(p: Pt, pressure: Float) { val q = snapPt(p); session?.move(q.x, q.y, pressure) }

    fun endStroke() {
        session?.up(); session = null; snapping = false
        if (tool == Tool.BRUSH) pushRecent(color)
        markDirty()
    }

    fun cancelStroke() { session?.cancel(); session = null; snapping = false }

    // ---------- formas ----------
    fun beginShape(p: Pt): Boolean {
        if (activeDrawable() == null) return false
        shapeStart = p; shapeLast = null
        return true
    }

    fun updateShape(p: Pt) {
        val a = shapeStart ?: return
        val last = shapeLast
        if (last != null && hypot(p.x - last.x, p.y - last.y) * transform.scale < 1.5f) return
        shapeLast = p
        val layer = activeDrawable() ?: return
        session?.cancel(); session = null
        val verts = shapeVertices(shapeMode, a, p)
        if (verts.size < 2) return
        val s = StrokeSession(doc, layer, preset.copy(smoothing = 0f, mypaint = null), color, brushSize, brushOpacity, tool == Tool.ERASER, selection)
        s.down(verts[0].x, verts[0].y, 1f)
        for (i in 1 until verts.size) s.move(verts[i].x, verts[i].y, 1f)
        session = s
    }

    fun endShape() {
        session?.up(); session = null; shapeStart = null; shapeLast = null
        if (tool == Tool.BRUSH) pushRecent(color)
        markDirty()
    }

    fun cancelShape() { session?.cancel(); session = null; shapeStart = null; shapeLast = null }

    private fun shapeVertices(mode: ShapeMode, a: Pt, b: Pt): List<Pt> = when (mode) {
        ShapeMode.LINE -> listOf(a, b)
        ShapeMode.RECT -> listOf(a, Pt(b.x, a.y), b, Pt(a.x, b.y), a)
        ShapeMode.ELLIPSE -> {
            val cx = (a.x + b.x) / 2f; val cy = (a.y + b.y) / 2f
            val rx = kotlin.math.abs(b.x - a.x) / 2f; val ry = kotlin.math.abs(b.y - a.y) / 2f
            (0..96).map { i -> val t = (i / 96.0 * 2 * PI); Pt(cx + rx * cos(t).toFloat(), cy + ry * sin(t).toFloat()) }
        }
        ShapeMode.NONE -> emptyList()
    }

    // ---------- laço / seleção ----------
    fun lassoBegin(p: Pt) { lassoDraft.clear(); lassoDraft.add(p) }
    fun lassoAdd(p: Pt) { lassoDraft.add(p) }
    fun lassoCancel() { lassoDraft.clear() }
    fun lassoEnd() {
        if (lassoDraft.size >= 3) {
            val path = Path()
            path.moveTo(lassoDraft[0].x, lassoDraft[0].y)
            for (i in 1 until lassoDraft.size) path.lineTo(lassoDraft[i].x, lassoDraft[i].y)
            path.close()
            selection = path
        } else {
            selection = null
        }
        selectionRev++
        lassoDraft.clear()
    }

    fun deselect() { selection = null; selectionRev++ }
    fun invertSelection() { selection?.let { s -> s.toggleInverseFillType(); selectionRev++ } }

    private fun selectionRect(sel: Path): Rect {
        if (sel.isInverseFillType) return Rect(0, 0, doc.width, doc.height)
        val rf = RectF(); sel.computeBounds(rf, true)
        val r = Rect(rf.left.toInt() - 1, rf.top.toInt() - 1, rf.right.toInt() + 2, rf.bottom.toInt() + 2)
        return if (r.intersect(0, 0, doc.width, doc.height)) r else Rect(0, 0, doc.width, doc.height)
    }

    fun fillSelection() = editSelection(false)
    fun clearSelectionContent() = editSelection(true)

    private fun editSelection(erase: Boolean) {
        val layer = activeDrawable() ?: return
        val sel = selection ?: return
        val snap = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val c = Canvas(layer.bitmap)
        c.save(); c.clipPath(sel)
        if (erase) c.drawColor(0, PorterDuff.Mode.CLEAR) else c.drawColor(color or (0xFF shl 24))
        c.restore()
        doc.recordPixels(layer, snap, selectionRect(sel))
        snap.recycle()
        if (!erase) pushRecent(color)
        markDirty()
    }

    // ---------- conta-gotas (toque longo) ----------
    fun eyedrop(screen: Offset) {
        val d = transform.screenToDoc(Pt(screen.x, screen.y))
        color = Compositor.sample(doc, d.x.toInt(), d.y.toInt())
        eyedropAt = screen
    }

    fun eyedropEnd() { eyedropAt = null; pushRecent(color) }

    // ---------- balde / gota de cor ----------
    /** Largar a gota de cor num ponto do ecrã. */
    fun colorDropAt(screen: Offset) {
        val d = transform.screenToDoc(Pt(screen.x, screen.y))
        fill(d.x.toInt(), d.y.toInt())
    }

    private fun fill(x: Int, y: Int) {
        val layer = activeDrawable() ?: return
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
    private class LayerInfo(val id: Long, val name: String, val kind: LayerKind, val opacity: Float, val visible: Boolean, val blend: BlendKind, val locked: Boolean)

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
