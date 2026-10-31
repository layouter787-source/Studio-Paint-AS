package com.studiopaint.engine

/** Definição de um pincel libmypaint: valores base (por cname) e mapeamentos de pressão. */
class MyPaintSpec(
    val base: Map<String, Float>,
    val pressure: Map<String, List<Pair<Float, Float>>> = emptyMap(),
)

/**
 * Preset de pincel. Os parâmetros "dab" servem o motor Kotlin; [mypaint] ativa o libmypaint
 * quando a biblioteca nativa está compilada (senão o preset usa os parâmetros dab como fallback).
 */
data class BrushPreset(
    val id: String,
    val name: String,
    val category: String,
    val spacing: Float,
    val hardness: Float,
    val flow: Float,
    val sizePressure: Float,
    val flowPressure: Float,
    val smoothing: Float,
    val scatter: Float = 0f,
    val grain: Float = 0f,
    val minSize: Float = 0.15f,
    val mypaint: MyPaintSpec? = null,
)

object BrushLibrary {
    private val ramp = listOf(0f to 0f, 1f to 1f)

    val presets: List<BrushPreset> = listOf(
        BrushPreset("pencil", "Lápis HB", "Desenho", 0.08f, 0.85f, 0.55f, 0.5f, 0.8f, 0.15f, 0.05f, 0.5f),
        BrushPreset("ink", "Tinta nanquim", "Tinta", 0.05f, 0.95f, 1f, 0.85f, 0f, 0.5f),
        BrushPreset("marker", "Marcador", "Tinta", 0.1f, 0.9f, 1f, 0f, 0f, 0.2f),
        BrushPreset("airbrush", "Aerografo", "Pintura", 0.08f, 0f, 0.12f, 0.2f, 0.6f, 0.1f),
        BrushPreset("charcoal", "Carvão", "Textura", 0.12f, 0.6f, 0.5f, 0.6f, 0.7f, 0.1f, 0.25f, 0.9f),
        BrushPreset("watercolor", "Aguarela suave", "Pintura", 0.1f, 0.3f, 0.25f, 0.4f, 0.6f, 0.3f, 0.02f, 0.2f),
        BrushPreset(
            "mp_ink", "MyPaint · Tinta", "libmypaint", 0.05f, 0.95f, 1f, 0.8f, 0f, 0.4f,
            mypaint = MyPaintSpec(
                mapOf("opaque" to 1f, "hardness" to 0.9f, "dabs_per_actual_radius" to 5f),
                mapOf("opaque_multiply" to ramp),
            ),
        ),
        BrushPreset(
            "mp_soft", "MyPaint · Suave", "libmypaint", 0.1f, 0.3f, 0.3f, 0.4f, 0.6f, 0.2f,
            mypaint = MyPaintSpec(
                mapOf("opaque" to 0.6f, "hardness" to 0.3f, "dabs_per_actual_radius" to 4f),
                mapOf("opaque_multiply" to ramp),
            ),
        ),
        BrushPreset(
            "mp_smudge", "MyPaint · Borrão", "libmypaint", 0.1f, 0.8f, 0.5f, 0.3f, 0.3f, 0.2f,
            mypaint = MyPaintSpec(
                mapOf("opaque" to 0.8f, "hardness" to 0.8f, "smudge" to 0.6f, "smudge_length" to 0.5f, "dabs_per_actual_radius" to 4f),
            ),
        ),
        BrushPreset(
            "mp_charcoal", "MyPaint · Carvão", "libmypaint", 0.12f, 0.6f, 0.5f, 0.6f, 0.7f, 0.1f, 0.25f, 0.9f,
            mypaint = MyPaintSpec(
                mapOf("opaque" to 0.5f, "hardness" to 0.5f, "offset_by_random" to 0.4f, "dabs_per_actual_radius" to 3f),
                mapOf("opaque_multiply" to ramp),
            ),
        ),
    )

    val default: BrushPreset get() = presets.first()
    fun byId(id: String): BrushPreset = presets.firstOrNull { it.id == id } ?: default
}
