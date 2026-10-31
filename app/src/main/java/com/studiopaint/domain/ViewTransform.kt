package com.studiopaint.domain

import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

data class Pt(val x: Float, val y: Float)

/**
 * Transformação da vista: ecrã = R(rotação) * (escala * documento) + (tx, ty).
 * É só visualização; nunca altera a arte nem a resolução do documento.
 */
data class ViewTransform(
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val tx: Float = 0f,
    val ty: Float = 0f,
) {
    fun docToScreen(p: Pt): Pt {
        val c = cos(rotation); val s = sin(rotation)
        val x = p.x * scale; val y = p.y * scale
        return Pt(x * c - y * s + tx, x * s + y * c + ty)
    }

    fun screenToDoc(p: Pt): Pt {
        val x = p.x - tx; val y = p.y - ty
        val c = cos(-rotation); val s = sin(-rotation)
        return Pt((x * c - y * s) / scale, (x * s + y * c) / scale)
    }

    /** Gesto de dois dedos: o ponto do documento sob [prevFocal] passa a ficar sob [newFocal]. */
    fun gesture(prevFocal: Pt, newFocal: Pt, zoom: Float, rotDelta: Float): ViewTransform {
        val doc = screenToDoc(prevFocal)
        val ns = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val nr = rotation + rotDelta
        val c = cos(nr); val s = sin(nr)
        val x = doc.x * ns; val y = doc.y * ns
        return ViewTransform(ns, nr, newFocal.x - (x * c - y * s), newFocal.y - (x * s + y * c))
    }

    companion object {
        const val MIN_SCALE = 0.05f
        const val MAX_SCALE = 64f

        fun fit(docW: Int, docH: Int, viewW: Float, viewH: Float, margin: Float = 24f): ViewTransform {
            val s = min((viewW - 2 * margin) / docW, (viewH - 2 * margin) / docH).coerceIn(MIN_SCALE, MAX_SCALE)
            return ViewTransform(s, 0f, (viewW - docW * s) / 2f, (viewH - docH * s) / 2f)
        }
    }
}
