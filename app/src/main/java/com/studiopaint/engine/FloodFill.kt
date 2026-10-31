package com.studiopaint.engine

import kotlin.math.max
import kotlin.math.min

/** Preenchimento por scanline sobre pixels ARGB (não pré-multiplicados). Função pura e testável. */
object FloodFill {
    /** @return [minX, minY, maxX, maxY] alterado, ou null se nada mudou. */
    fun fill(px: IntArray, w: Int, h: Int, sx: Int, sy: Int, fillArgb: Int, tolerance: Int, grow: Int): IntArray? {
        if (sx !in 0 until w || sy !in 0 until h) return null
        val target = px[sy * w + sx]
        if (target == fillArgb && grow == 0) return null
        val mask = BooleanArray(w * h)
        val stack = IntArray(w * h + 1)
        var sp = 0
        stack[sp++] = sy * w + sx
        while (sp > 0) {
            val idx = stack[--sp]
            val y = idx / w
            var x = idx % w
            if (mask[idx] || !matches(px[idx], target, tolerance)) continue
            var l = x
            while (l > 0 && !mask[y * w + l - 1] && matches(px[y * w + l - 1], target, tolerance)) l--
            var r = x
            while (r < w - 1 && !mask[y * w + r + 1] && matches(px[y * w + r + 1], target, tolerance)) r++
            for (i in l..r) mask[y * w + i] = true
            for (ny in intArrayOf(y - 1, y + 1)) {
                if (ny !in 0 until h) continue
                var inRun = false
                for (i in l..r) {
                    val ok = !mask[ny * w + i] && matches(px[ny * w + i], target, tolerance)
                    if (ok && !inRun) { if (sp < stack.size) stack[sp++] = ny * w + i; inRun = true } else if (!ok) inRun = false
                }
            }
            x = r
        }
        // expansão opcional para cobrir o halo antialias das linhas
        var cur = mask
        repeat(grow) {
            val next = cur.copyOf()
            for (y in 0 until h) for (x in 0 until w) {
                if (cur[y * w + x]) continue
                if ((x > 0 && cur[y * w + x - 1]) || (x < w - 1 && cur[y * w + x + 1]) ||
                    (y > 0 && cur[(y - 1) * w + x]) || (y < h - 1 && cur[(y + 1) * w + x])) next[y * w + x] = true
            }
            cur = next
        }
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) if (cur[y * w + x]) {
            px[y * w + x] = fillArgb
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
        }
        return if (maxX < 0) null else intArrayOf(minX, minY, maxX, maxY)
    }

    private fun matches(c: Int, t: Int, tol: Int): Boolean {
        if (c == t) return true
        if (tol <= 0) return false
        return kotlin.math.abs((c ushr 24) - (t ushr 24)) <= tol &&
            kotlin.math.abs(((c shr 16) and 255) - ((t shr 16) and 255)) <= tol &&
            kotlin.math.abs(((c shr 8) and 255) - ((t shr 8) and 255)) <= tol &&
            kotlin.math.abs((c and 255) - (t and 255)) <= tol
    }
}
