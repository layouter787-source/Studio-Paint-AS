package com.studiopaint

import com.studiopaint.format.*
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AspkIoTest {
    @Test fun roundTripKeepsEverything() {
        val png = byteArrayOf(1, 2, 3, 4)
        val stroke = AspkStroke(listOf(AspkPoint(1f, 2f, 0.5f), AspkPoint(3f, 4f, 1f)), -16777216, 6f, false, "ink")
        val layer = AspkLayer(
            AspkLayerMeta(7, "Linha", "DRAWING", 80, true, "MULTIPLY", false),
            mapOf(9L to AspkDrawing(9, listOf(stroke), png)),
            mapOf(0L to 9L, 1L to 9L),
        )
        val p = AspkProject("Teste", 640, 480, 12, -1, listOf(0L, 1L), 1, listOf(layer), 20, extras = mapOf("audio/a.wav" to byteArrayOf(9)))
        val bos = ByteArrayOutputStream(); AspkIO.write(p, bos)
        val q = AspkIO.read(ByteArrayInputStream(bos.toByteArray()))
        assertEquals("Teste", q.name); assertEquals(640, q.width); assertEquals(listOf(0L, 1L), q.frameIds)
        val l = q.layers.single()
        assertEquals("MULTIPLY", l.meta.blend); assertEquals(80, l.meta.opacityPercent)
        assertEquals(mapOf(0L to 9L, 1L to 9L), l.cels)
        assertArrayEquals(png, l.drawings.getValue(9).rasterPng)
        assertEquals(stroke, l.drawings.getValue(9).strokes.single())
        assertArrayEquals(byteArrayOf(9), q.extras.getValue("audio/a.wav"))
    }

    @Test fun rejectsGarbage() {
        val ok = try { AspkIO.read(ByteArrayInputStream(byteArrayOf(1, 2, 3))); false } catch (e: AspkException) { true }
        assertTrue(ok)
    }
}
