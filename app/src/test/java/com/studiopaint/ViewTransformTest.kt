package com.studiopaint

import com.studiopaint.domain.Pt
import com.studiopaint.domain.ViewTransform
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewTransformTest {
    @Test fun roundTrip() {
        val t = ViewTransform(2.5f, 0.7f, 120f, -40f)
        val p = Pt(310f, 77f)
        val back = t.screenToDoc(t.docToScreen(p))
        assertEquals(p.x, back.x, 0.01f); assertEquals(p.y, back.y, 0.01f)
    }

    @Test fun gestureKeepsFocalPoint() {
        val t = ViewTransform(1f, 0f, 10f, 20f)
        val focal = Pt(200f, 300f)
        val docBefore = t.screenToDoc(focal)
        val t2 = t.gesture(focal, Pt(250f, 280f), 2f, 0.5f)
        val after = t2.docToScreen(docBefore)
        assertEquals(250f, after.x, 0.01f); assertEquals(280f, after.y, 0.01f)
    }

    @Test fun fitCentersDocument() {
        val t = ViewTransform.fit(1000, 500, 800f, 800f, 0f)
        assertEquals(0.8f, t.scale, 0.001f)
        assertEquals(200f, t.ty, 0.01f)
    }
}
