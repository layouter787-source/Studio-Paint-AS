// Ponte JNI: superfície libmypaint que desenha diretamente nos pixels de um Bitmap RGBA_8888 (pré-multiplicado).
#include <jni.h>
#include <android/bitmap.h>
#include <algorithm>
#include <cmath>
#include <cstdint>

extern "C" {
#include "mypaint-brush.h"
#include "mypaint-surface.h"
}

struct Surf {
    MyPaintSurface parent;
    uint8_t* px;
    int w, h, stride;
};

struct Handle {
    MyPaintBrush* brush;
    Surf surf;
};

static float calcOpa(float rr, float hardness) {
    if (rr <= hardness) return rr + 1.0f - rr / hardness;
    return hardness / (1.0f - hardness) * (1.0f - rr);
}

static int drawDab(MyPaintSurface* self, float x, float y, float radius, float cr, float cg, float cb,
                   float opaque, float hardness, float alphaEraser, float aspect, float angle,
                   float /*lockAlpha*/, float /*colorize*/) {
    Surf* s = reinterpret_cast<Surf*>(self);
    if (!s->px || radius < 0.1f || opaque <= 0.f) return 0;
    hardness = std::min(0.99f, std::max(0.01f, hardness));
    if (aspect < 1.f) aspect = 1.f;
    const float r = radius, r2 = r * r;
    const float ang = angle * 3.14159265f / 180.f, cs = std::cos(ang), sn = std::sin(ang);
    const int x0 = std::max(0, (int)std::floor(x - r - 1)), x1 = std::min(s->w - 1, (int)std::ceil(x + r + 1));
    const int y0 = std::max(0, (int)std::floor(y - r - 1)), y1 = std::min(s->h - 1, (int)std::ceil(y + r + 1));
    for (int yy = y0; yy <= y1; ++yy) {
        for (int xx = x0; xx <= x1; ++xx) {
            const float dx = xx + 0.5f - x, dy = yy + 0.5f - y;
            const float dyr = (dy * cs - dx * sn) * aspect, dxr = dy * sn + dx * cs;
            const float rr = (dyr * dyr + dxr * dxr) / r2;
            if (rr >= 1.f) continue;
            const float opa = std::min(1.f, calcOpa(rr, hardness) * opaque);
            if (opa <= 0.f) continue;
            uint8_t* p = s->px + yy * s->stride + xx * 4;
            const float inv = 1.f - opa, sa = opa * alphaEraser;
            p[0] = (uint8_t)std::min(255.f, p[0] * inv + cr * 255.f * sa);
            p[1] = (uint8_t)std::min(255.f, p[1] * inv + cg * 255.f * sa);
            p[2] = (uint8_t)std::min(255.f, p[2] * inv + cb * 255.f * sa);
            p[3] = (uint8_t)std::min(255.f, p[3] * inv + 255.f * sa);
        }
    }
    return 1;
}

static void getColor(MyPaintSurface* self, float x, float y, float radius, float* r, float* g, float* b, float* a) {
    Surf* s = reinterpret_cast<Surf*>(self);
    float sr = 0, sg = 0, sb = 0, sa = 0; int n = 0;
    const int steps = 5;
    for (int j = 0; j < steps; ++j) for (int i = 0; i < steps; ++i) {
        const int px = (int)(x + ((i / (float)(steps - 1)) * 2 - 1) * radius);
        const int py = (int)(y + ((j / (float)(steps - 1)) * 2 - 1) * radius);
        if (px < 0 || py < 0 || px >= s->w || py >= s->h || !s->px) continue;
        const uint8_t* p = s->px + py * s->stride + px * 4;
        sr += p[0] / 255.f; sg += p[1] / 255.f; sb += p[2] / 255.f; sa += p[3] / 255.f; ++n;
    }
    if (n == 0 || sa <= 0.f) { *r = *g = *b = *a = 0.f; return; }
    *r = std::min(1.f, sr / sa); *g = std::min(1.f, sg / sa); *b = std::min(1.f, sb / sa); *a = sa / n;
}

static void beginAtomic(MyPaintSurface*) {}
static void endAtomic(MyPaintSurface*, MyPaintRectangle*) {}
static void destroySurf(MyPaintSurface*) {}

extern "C" {

JNIEXPORT jlong JNICALL Java_com_studiopaint_engine_NativeMyPaint_create(JNIEnv*, jobject) {
    Handle* h = new Handle();
    h->brush = mypaint_brush_new();
    mypaint_surface_init(&h->surf.parent);
    h->surf.parent.draw_dab = drawDab;
    h->surf.parent.get_color = getColor;
    h->surf.parent.begin_atomic = beginAtomic;
    h->surf.parent.end_atomic = endAtomic;
    h->surf.parent.destroy = destroySurf;
    h->surf.px = nullptr; h->surf.w = h->surf.h = h->surf.stride = 0;
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL Java_com_studiopaint_engine_NativeMyPaint_destroy(JNIEnv*, jobject, jlong handle) {
    Handle* h = reinterpret_cast<Handle*>(handle);
    if (!h) return;
    mypaint_brush_unref(h->brush);
    delete h;
}

JNIEXPORT void JNICALL Java_com_studiopaint_engine_NativeMyPaint_setBase(JNIEnv* env, jobject, jlong handle, jstring cname, jfloat value) {
    Handle* h = reinterpret_cast<Handle*>(handle);
    const char* c = env->GetStringUTFChars(cname, nullptr);
    const int id = (int)mypaint_brush_setting_from_cname(c);
    if (id >= 0) mypaint_brush_set_base_value(h->brush, (MyPaintBrushSetting)id, value);
    env->ReleaseStringUTFChars(cname, c);
}

JNIEXPORT void JNICALL Java_com_studiopaint_engine_NativeMyPaint_setMapping(JNIEnv* env, jobject, jlong handle, jstring cname, jstring input, jfloatArray xs, jfloatArray ys) {
    Handle* h = reinterpret_cast<Handle*>(handle);
    const char* c = env->GetStringUTFChars(cname, nullptr);
    const char* in = env->GetStringUTFChars(input, nullptr);
    const int sid = (int)mypaint_brush_setting_from_cname(c);
    const int iid = (int)mypaint_brush_input_from_cname(in);
    const jsize n = env->GetArrayLength(xs);
    if (sid >= 0 && iid >= 0 && n >= 2) {
        jfloat* px = env->GetFloatArrayElements(xs, nullptr);
        jfloat* py = env->GetFloatArrayElements(ys, nullptr);
        mypaint_brush_set_mapping_n(h->brush, (MyPaintBrushSetting)sid, (MyPaintBrushInput)iid, n);
        for (jsize i = 0; i < n; ++i)
            mypaint_brush_set_mapping_point(h->brush, (MyPaintBrushSetting)sid, (MyPaintBrushInput)iid, i, px[i], py[i]);
        env->ReleaseFloatArrayElements(xs, px, JNI_ABORT);
        env->ReleaseFloatArrayElements(ys, py, JNI_ABORT);
    }
    env->ReleaseStringUTFChars(cname, c);
    env->ReleaseStringUTFChars(input, in);
}

JNIEXPORT void JNICALL Java_com_studiopaint_engine_NativeMyPaint_newStroke(JNIEnv*, jobject, jlong handle) {
    mypaint_brush_new_stroke(reinterpret_cast<Handle*>(handle)->brush);
}

JNIEXPORT void JNICALL Java_com_studiopaint_engine_NativeMyPaint_strokeTo(JNIEnv* env, jobject, jlong handle, jobject bitmap,
        jfloat x, jfloat y, jfloat pressure, jfloat xtilt, jfloat ytilt, jdouble dtime) {
    Handle* h = reinterpret_cast<Handle*>(handle);
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return;
    void* addr = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &addr) != ANDROID_BITMAP_RESULT_SUCCESS) return;
    h->surf.px = static_cast<uint8_t*>(addr);
    h->surf.w = (int)info.width; h->surf.h = (int)info.height; h->surf.stride = (int)info.stride;
    mypaint_brush_stroke_to(h->brush, &h->surf.parent, x, y, pressure, xtilt, ytilt, dtime);
    h->surf.px = nullptr;
    AndroidBitmap_unlockPixels(env, bitmap);
}

}  // extern "C"
