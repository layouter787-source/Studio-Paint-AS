package com.studiopaint.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.studiopaint.format.AspkDrawing
import com.studiopaint.format.AspkLayer
import com.studiopaint.format.AspkLayerMeta
import com.studiopaint.format.AspkProject
import com.studiopaint.format.AspkStroke
import kotlin.math.roundToInt

/** Dados de uma camada prontos a gravar (PNG já codificado). */
class LayerSave(
    val id: Long, val name: String, val kind: LayerKind, val opacity: Float,
    val visible: Boolean, val blend: BlendKind, val locked: Boolean, val png: ByteArray,
)

/** Liga o modelo ASPK (Animation Studio) ao documento raster do Studio Paint. */
object AspkBridge {

    fun newProject(name: String, w: Int, h: Int): AspkProject {
        // Igual a Project.initial() do Animation Studio: 1 frame, cenário (id 1) e camada de desenho (id 2).
        fun empty(id: Long, n: String, kind: String) = AspkLayer(AspkLayerMeta(id, n, kind), emptyMap(), emptyMap())
        return AspkProject(name, w, h, 12, -1, listOf(0L), 0, listOf(empty(1, "Cenário 1", "SCENERY"), empty(2, "Camada 1", "DRAWING")), 3)
    }

    fun open(p: AspkProject, frameIndex: Int): PaintDocument {
        val fid = p.frameIds[frameIndex]
        val doc = PaintDocument(p.width, p.height, p.nextId, p.paperArgb)
        for (l in p.layers) {
            val drawing = l.cels[fid]?.let { l.drawings[it] }
            val kind = if (l.meta.kind == "SCENERY") LayerKind.SCENERY else LayerKind.DRAWING
            val pl = PaintLayer(l.meta.id, l.meta.name, kind, renderDrawing(drawing, p.width, p.height))
            pl.opacity = l.meta.opacityPercent / 100f
            pl.visible = l.meta.visible; pl.locked = l.meta.locked
            pl.blend = BlendKind.values().firstOrNull { it.name == l.meta.blend } ?: BlendKind.NORMAL
            doc.layers.add(pl)
        }
        if (doc.layers.isEmpty()) doc.layers.add(PaintLayer(doc.nextId++, "Camada 1", LayerKind.DRAWING, doc.newBitmap()))
        doc.activeId = (doc.layers.lastOrNull { it.kind == LayerKind.DRAWING } ?: doc.layers.last()).id
        return doc
    }

    private fun renderDrawing(d: AspkDrawing?, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (d == null) return out
        val png = d.rasterPng
        if (png != null) {
            val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            BitmapFactory.decodeByteArray(png, 0, png.size, o)?.let { Canvas(out).drawBitmap(it, 0f, 0f, null); it.recycle() }
        } else {
            replayStrokes(Canvas(out), d.strokes)
        }
        return out
    }

    /** Traços vetoriais do Animation Studio (sem pressão) desenhados como polilinhas redondas. */
    private fun replayStrokes(c: Canvas, strokes: List<AspkStroke>) {
        for (s in strokes) {
            if (s.points.isEmpty()) continue
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = s.colorArgb; strokeWidth = s.width; style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                if (s.erase) blendMode = BlendMode.CLEAR
            }
            if (s.points.size == 1) {
                p.style = Paint.Style.FILL; c.drawCircle(s.points[0].x, s.points[0].y, s.width / 2f, p)
            } else {
                val path = Path().apply { moveTo(s.points[0].x, s.points[0].y); s.points.drop(1).forEach { lineTo(it.x, it.y) } }
                c.drawPath(path, p)
            }
        }
    }

    /**
     * Escreve o frame editado de volta no projeto [base], preservando os outros frames, os cenários
     * partilhados e entradas desconhecidas. O raster passa a ser a fonte do desenho (substitui os traços).
     */
    fun build(base: AspkProject, saves: List<LayerSave>, frameIndex: Int, nextIdIn: Long): AspkProject {
        val fid = base.frameIds[frameIndex]
        var next = maxOf(base.nextId, nextIdIn)
        val layers = saves.map { s ->
            val old = base.layers.firstOrNull { it.meta.id == s.id }
            val kind = old?.meta?.kind ?: s.kind.name
            val drawings = HashMap(old?.drawings ?: emptyMap())
            val cels = HashMap(old?.cels ?: emptyMap())
            var did = cels[fid]
            if (did == null) {
                val nid = next++
                did = nid
                if (kind == "SCENERY") base.frameIds.forEach { f -> if (f !in cels) cels[f] = nid } else cels[fid] = nid
            }
            drawings[did] = AspkDrawing(did, emptyList(), s.png)
            drawings.keys.retainAll(cels.values.toSet())
            val meta = AspkLayerMeta(s.id, s.name, kind, (s.opacity * 100).roundToInt(), s.visible, s.blend.name, s.locked)
            AspkLayer(meta, drawings, cels)
        }
        return AspkProject(
            base.name, base.width, base.height, base.fps, base.paperArgb, base.frameIds, frameIndex,
            layers, next, "Studio-Paint-AS 0.1.0", base.extras,
        )
    }
}
