package com.studiopaint.engine

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import com.studiopaint.format.AspkPoint
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Um traço em curso. Usa o motor de dabs em Kotlin, ou o libmypaint nativo quando o preset
 * o pede e a biblioteca está disponível. [clip] (seleção) limita onde se pinta; o motor nativo ainda ignora a seleção.
 */
class StrokeSession(
    private val doc: PaintDocument,
    private val layer: PaintLayer,
    private val preset: BrushPreset,
    private val colorArgb: Int,
    private val size: Float,
    private val opacity: Float,
    private val erase: Boolean,
    private val clip: Path? = null,
) {
    val points = ArrayList<AspkPoint>()
    private val snapshot: Bitmap = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
    private var minX = Int.MAX_VALUE; private var minY = Int.MAX_VALUE
    private var maxX = Int.MIN_VALUE; private var maxY = Int.MIN_VALUE

    private val useNative = preset.mypaint != null && NativeMyPaint.available && clip == null
    private var handle = 0L
    private var target: Canvas? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rnd = java.util.Random()
    private var sx = 0f; private var sy = 0f; private var lx = 0f; private var ly = 0f
    private var rawX = 0f; private var rawY = 0f; private var rawP = 1f
    private var carry = 0f
    private var lastNs = 0L
    private val gradients = HashMap<Int, RadialGradient>()

    fun down(x: Float, y: Float, p: Float) {
        sx = x; sy = y; lx = x; ly = y; rawX = x; rawY = y; rawP = p
        lastNs = System.nanoTime()
        points.add(AspkPoint(x, y, p))
        if (useNative) {
            handle = NativeMyPaint.create()
            configureNative()
            NativeMyPaint.newStroke(handle)
            NativeMyPaint.strokeTo(handle, layer.bitmap, x, y, p, 0f, 0f, 0.016)
            grow(x, y, size * 2f + 8f)
        } else {
            if (erase) {
                target = Canvas(layer.bitmap); paint.blendMode = BlendMode.DST_OUT
            } else {
                val b = doc.obtainStrokeBuffer(); b.eraseColor(0)
                target = Canvas(b); paint.blendMode = BlendMode.SRC_OVER
                doc.liveLayerId = layer.id; doc.liveOpacity = opacity
            }
            clip?.let { target!!.clipPath(it) }
            stamp(x, y, p)
        }
        doc.bump()
    }

    fun move(x: Float, y: Float, p: Float) {
        rawX = x; rawY = y; rawP = p
        points.add(AspkPoint(x, y, p))
        if (useNative) {
            val now = System.nanoTime()
            val dt = ((now - lastNs) / 1e9).coerceIn(0.001, 0.1); lastNs = now
            NativeMyPaint.strokeTo(handle, layer.bitmap, x, y, p, 0f, 0f, dt)
            grow(x, y, size * 2f + 8f)
        } else {
            val k = 1f - preset.smoothing.coerceIn(0f, 0.95f) * 0.9f
            segmentTo(sx + (x - sx) * k, sy + (y - sy) * k, p)
        }
        doc.bump()
    }

    private fun segmentTo(nx: Float, ny: Float, p: Float) {
        val dx = nx - lx; val dy = ny - ly
        val dist = hypot(dx, dy)
        if (dist > 0f) {
            val step = max(0.5f, dabDiameter(p) * preset.spacing)
            val ux = dx / dist; val uy = dy / dist
            var pos = step - carry
            while (pos <= dist) { stamp(lx + ux * pos, ly + uy * pos, p); pos += step }
            carry = dist - (pos - step)
        }
        lx = nx; ly = ny; sx = nx; sy = ny
    }

    private fun dabDiameter(p: Float): Float {
        val f = 1f - preset.sizePressure + preset.sizePressure * max(preset.minSize, p)
        return max(0.5f, size * f)
    }

    private fun stamp(x: Float, y: Float, p: Float) {
        val c = target ?: return
        val d = dabDiameter(p); val r = d / 2f
        val ox = (rnd.nextFloat() - 0.5f) * 2f * preset.scatter * d
        val oy = (rnd.nextFloat() - 0.5f) * 2f * preset.scatter * d
        var a = preset.flow * (1f - preset.flowPressure + preset.flowPressure * p)
        a *= 1f - preset.grain * rnd.nextFloat()
        if (erase) a *= opacity
        paint.shader = gradient(preset.hardness)
        paint.alpha = (a * 255f).roundToInt().coerceIn(1, 255)
        c.save(); c.translate(x + ox, y + oy); c.scale(r, r); c.drawCircle(0f, 0f, 1f, paint); c.restore()
        grow(x + ox, y + oy, r + 2f)
    }

    private fun gradient(hardness: Float): RadialGradient {
        val h = hardness.coerceIn(0.01f, 0.99f)
        val key = (h * 64).toInt()
        return gradients.getOrPut(key) {
            val col = (if (erase) Color.BLACK else colorArgb) or (0xFF shl 24)
            RadialGradient(0f, 0f, 1f, intArrayOf(col, col, col and 0x00FFFFFF), floatArrayOf(0f, h, 1f), Shader.TileMode.CLAMP)
        }
    }

    private fun grow(x: Float, y: Float, r: Float) {
        minX = minOf(minX, (x - r).toInt() - 1); minY = minOf(minY, (y - r).toInt() - 1)
        maxX = maxOf(maxX, (x + r).toInt() + 2); maxY = maxOf(maxY, (y + r).toInt() + 2)
    }

    private fun configureNative() {
        val spec = preset.mypaint ?: return
        for ((k, v) in spec.base) NativeMyPaint.setBase(handle, k, v)
        NativeMyPaint.setBase(handle, "radius_logarithmic", ln(max(size, 1f) / 2f))
        NativeMyPaint.setBase(handle, "opaque", opacity * (spec.base["opaque"] ?: 1f))
        val hsv = FloatArray(3); Color.colorToHSV(colorArgb, hsv)
        NativeMyPaint.setBase(handle, "color_h", hsv[0] / 360f)
        NativeMyPaint.setBase(handle, "color_s", hsv[1]); NativeMyPaint.setBase(handle, "color_v", hsv[2])
        if (erase) NativeMyPaint.setBase(handle, "eraser", 1f)
        for ((k, pts) in spec.pressure) {
            NativeMyPaint.setMapping(handle, k, "pressure", FloatArray(pts.size) { pts[it].first }, FloatArray(pts.size) { pts[it].second })
        }
    }

    /** Termina e regista no histórico. */
    fun up() {
        if (!useNative) segmentTo(rawX, rawY, rawP)
        val dirty = clampedRect()
        if (useNative) {
            NativeMyPaint.destroy(handle)
        } else if (!erase) {
            val b = doc.strokeBuffer
            if (b != null && dirty != null) {
                val p = Paint().apply { alpha = (opacity * 255).roundToInt() }
                val c = Canvas(layer.bitmap)
                clip?.let { c.clipPath(it) }
                c.drawBitmap(b, dirty, dirty, p)
            }
        }
        doc.liveLayerId = -1L
        if (dirty != null) doc.recordPixels(layer, snapshot, dirty)
        snapshot.recycle(); doc.bump()
    }

    fun cancel() {
        if (useNative) NativeMyPaint.destroy(handle)
        val p = Paint().apply { blendMode = BlendMode.SRC }
        Canvas(layer.bitmap).drawBitmap(snapshot, 0f, 0f, p)
        doc.liveLayerId = -1L
        snapshot.recycle(); doc.bump()
    }

    private fun clampedRect(): Rect? {
        if (minX > maxX) return null
        val r = Rect(minX.coerceAtLeast(0), minY.coerceAtLeast(0), maxX.coerceAtMost(doc.width), maxY.coerceAtMost(doc.height))
        return if (r.width() > 0 && r.height() > 0) r else null
    }
}
