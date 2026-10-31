package com.studiopaint.engine

import android.graphics.Bitmap

/**
 * Ponte JNI para o libmypaint (experimental). Se a biblioteca nativa não foi compilada
 * ([available] = false) o app usa o motor Kotlin. Ver app/src/main/cpp.
 */
object NativeMyPaint {
    val available: Boolean = try {
        System.loadLibrary("studiopaint_native"); true
    } catch (t: Throwable) {
        false
    }

    external fun create(): Long
    external fun destroy(handle: Long)
    external fun setBase(handle: Long, cname: String, value: Float)
    external fun setMapping(handle: Long, cname: String, input: String, xs: FloatArray, ys: FloatArray)
    external fun newStroke(handle: Long)
    external fun strokeTo(handle: Long, bitmap: Bitmap, x: Float, y: Float, pressure: Float, xtilt: Float, ytilt: Float, dtime: Double)
}
