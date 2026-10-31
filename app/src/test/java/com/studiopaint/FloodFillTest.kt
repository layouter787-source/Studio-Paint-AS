package com.studiopaint

import com.studiopaint.engine.FloodFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FloodFillTest {
    private val wall = 0xFF000000.toInt()
    private val red = 0xFFFF0000.toInt()

    @Test fun fillsOnlyEnclosedRegion() {
        // 5x3, parede vertical na coluna 2
        val px = IntArray(15) { if (it % 5 == 2) wall else 0 }
        val r = FloodFill.fill(px, 5, 3, 0, 0, red, 0, 0)!!
        assertEquals(red, px[0]); assertEquals(red, px[6])
        assertEquals(wall, px[2]); assertEquals(0, px[4])
        assertEquals(0, r[0]); assertEquals(1, r[2])
    }

    @Test fun sameColorIsNoop() {
        val px = IntArray(4) { red }
        assertNull(FloodFill.fill(px, 2, 2, 0, 0, red, 0, 0))
    }
}
