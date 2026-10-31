package com.studiopaint.engine

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.roundToInt

object Compositor {
    fun draw(c: Canvas, doc: PaintDocument, paper: Boolean) {
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        if (paper) {
            paint.color = doc.paperArgb
            c.drawRect(0f, 0f, doc.width.toFloat(), doc.height.toFloat(), paint)
        }
        for (l in doc.layers) {
            if (!l.visible) continue
            paint.reset(); paint.isFilterBitmap = true
            paint.alpha = (l.opacity * 255).roundToInt(); paint.blendMode = l.blend.mode
            c.drawBitmap(l.bitmap, 0f, 0f, paint)
            if (doc.liveLayerId == l.id) doc.strokeBuffer?.let { b ->
                paint.alpha = (l.opacity * doc.liveOpacity * 255).roundToInt()
                c.drawBitmap(b, 0f, 0f, paint)
            }
        }
    }

    fun flatten(doc: PaintDocument, transparent: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), doc, !transparent)
        return bmp
    }

    fun thumbnail(doc: PaintDocument, maxSide: Int): Bitmap {
        val s = maxSide.toFloat() / maxOf(doc.width, doc.height)
        val bmp = Bitmap.createBitmap((doc.width * s).roundToInt().coerceAtLeast(1), (doc.height * s).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.scale(s, s)
        draw(c, doc, true)
        return bmp
    }

    /** Cor composta (blend normal) no ponto do documento. */
    fun sample(doc: PaintDocument, x: Int, y: Int): Int {
        if (x !in 0 until doc.width || y !in 0 until doc.height) return doc.paperArgb
        var r = ((doc.paperArgb shr 16) and 255) / 255f
        var g = ((doc.paperArgb shr 8) and 255) / 255f
        var b = (doc.paperArgb and 255) / 255f
        for (l in doc.layers) {
            if (!l.visible) continue
            val px = l.bitmap.getPixel(x, y)
            val a = ((px ushr 24) / 255f) * l.opacity
            if (a <= 0f) continue
            r = r * (1 - a) + ((px shr 16) and 255) / 255f * a
            g = g * (1 - a) + ((px shr 8) and 255) / 255f * a
            b = b * (1 - a) + (px and 255) / 255f * a
        }
        return (0xFF shl 24) or ((r * 255).roundToInt() shl 16) or ((g * 255).roundToInt() shl 8) or (b * 255).roundToInt()
    }
}
