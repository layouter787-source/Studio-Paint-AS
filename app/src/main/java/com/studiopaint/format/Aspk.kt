package com.studiopaint.format

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * ASPK: formato de intercâmbio entre Studio Paint AS e Animation Studio.
 * Espelha o modelo do Animation Studio (frames, camadas DRAWING/SCENERY, cels -> desenhos),
 * e cada desenho pode ter traços vetoriais e/ou um raster PNG. Ver docs/INTERCHANGE.md.
 */
class AspkException(msg: String) : Exception(msg)

data class AspkPoint(val x: Float, val y: Float, val pressure: Float = 1f)

data class AspkStroke(
    val points: List<AspkPoint>,
    val colorArgb: Int,
    val width: Float,
    val erase: Boolean = false,
    val brushId: String? = null,
)

class AspkDrawing(val id: Long, val strokes: List<AspkStroke> = emptyList(), val rasterPng: ByteArray? = null)

data class AspkLayerMeta(
    val id: Long,
    val name: String,
    val kind: String, // "DRAWING" | "SCENERY"
    val opacityPercent: Int = 100,
    val visible: Boolean = true,
    val blend: String = "NORMAL",
    val locked: Boolean = false,
)

class AspkLayer(val meta: AspkLayerMeta, val drawings: Map<Long, AspkDrawing>, val cels: Map<Long, Long>)

class AspkProject(
    val name: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val paperArgb: Int,
    val frameIds: List<Long>,
    val currentFrame: Int,
    val layers: List<AspkLayer>,
    val nextId: Long,
    val producer: String = "Studio-Paint-AS",
    /** Entradas desconhecidas (áudio, câmara, ...) preservadas para não perder dados de outros apps. */
    val extras: Map<String, ByteArray> = emptyMap(),
)

object AspkIO {
    const val MIME = "application/vnd.animationstudio.project+zip"
    const val FORMAT = "animation-studio-interchange"
    const val VERSION = 1
    private const val MAX_TOTAL = 768L * 1024 * 1024

    private fun rasterPath(layerId: Long, drawingId: Long) = "layers/$layerId/raster/$drawingId.png"
    private fun strokesPath(layerId: Long, drawingId: Long) = "layers/$layerId/strokes/$drawingId.json"

    fun write(p: AspkProject, out: OutputStream) {
        ZipOutputStream(out).use { z ->
            fun put(name: String, bytes: ByteArray) {
                z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
            }
            put("manifest.json", manifest(p).toString(2).toByteArray(Charsets.UTF_8))
            for (l in p.layers) for (d in l.drawings.values) {
                if (d.strokes.isNotEmpty()) put(strokesPath(l.meta.id, d.id), strokesJson(d.strokes).toString().toByteArray(Charsets.UTF_8))
                d.rasterPng?.let { put(rasterPath(l.meta.id, d.id), it) }
            }
            for ((k, v) in p.extras) put(k, v)
        }
    }

    fun read(input: InputStream): AspkProject {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val n = e.name
                if (n.startsWith("/") || n.contains("..")) throw AspkException("Caminho inválido no pacote: $n")
                val bos = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = z.read(buf)
                    if (r < 0) break
                    total += r
                    if (total > MAX_TOTAL) throw AspkException("Pacote demasiado grande")
                    bos.write(buf, 0, r)
                }
                entries[n] = bos.toByteArray()
            }
        }
        val mBytes = entries.remove("manifest.json") ?: throw AspkException("manifest.json em falta")
        val m = try { JSONObject(String(mBytes, Charsets.UTF_8)) } catch (t: Exception) { throw AspkException("manifest.json inválido") }
        if (m.optString("format") != FORMAT) throw AspkException("Não é um projeto ASPK")
        if (m.optInt("version", 0) > VERSION) throw AspkException("Projeto criado por uma versão mais recente do formato")

        val frameIds = m.getJSONArray("frameIds").let { a -> (0 until a.length()).map { a.getLong(it) } }
        if (frameIds.isEmpty()) throw AspkException("Projeto sem frames")
        val used = HashSet<String>()
        val layers = ArrayList<AspkLayer>()
        val la = m.getJSONArray("layers")
        for (i in 0 until la.length()) {
            val lj = la.getJSONObject(i)
            val meta = AspkLayerMeta(
                id = lj.getLong("id"), name = lj.optString("name", "Camada"), kind = lj.optString("kind", "DRAWING"),
                opacityPercent = lj.optInt("opacityPercent", 100), visible = lj.optBoolean("visible", true),
                blend = lj.optString("blend", "NORMAL"), locked = lj.optBoolean("locked", false),
            )
            val cels = HashMap<Long, Long>()
            lj.optJSONObject("cels")?.let { c -> c.keys().forEach { k -> cels[k.toLong()] = c.getLong(k) } }
            val drawings = HashMap<Long, AspkDrawing>()
            val da = lj.optJSONArray("drawings") ?: JSONArray()
            for (j in 0 until da.length()) {
                val did = da.getLong(j)
                val sp = strokesPath(meta.id, did); val rp = rasterPath(meta.id, did)
                val strokes = entries[sp]?.let { used.add(sp); parseStrokes(JSONArray(String(it, Charsets.UTF_8))) } ?: emptyList()
                val raster = entries[rp]?.also { used.add(rp) }
                drawings[did] = AspkDrawing(did, strokes, raster)
            }
            layers.add(AspkLayer(meta, drawings, cels))
        }
        val extras = entries.filterKeys { it !in used }
        return AspkProject(
            name = m.optString("name", "Sem nome"), width = m.getInt("width"), height = m.getInt("height"),
            fps = m.optInt("fps", 12), paperArgb = m.optInt("paperColorArgb", -1), frameIds = frameIds,
            currentFrame = m.optInt("currentFrame", 0).coerceIn(0, frameIds.lastIndex), layers = layers,
            nextId = m.optLong("nextId", 1000000L), producer = m.optString("producer", "desconhecido"), extras = extras,
        )
    }

    private fun manifest(p: AspkProject) = JSONObject().apply {
        put("format", FORMAT); put("version", VERSION); put("producer", p.producer); put("name", p.name)
        put("width", p.width); put("height", p.height); put("fps", p.fps); put("paperColorArgb", p.paperArgb)
        put("currentFrame", p.currentFrame); put("nextId", p.nextId)
        put("frameIds", JSONArray().also { a -> p.frameIds.forEach { a.put(it) } })
        put("layers", JSONArray().also { arr ->
            for (l in p.layers) arr.put(JSONObject().apply {
                put("id", l.meta.id); put("name", l.meta.name); put("kind", l.meta.kind)
                put("opacityPercent", l.meta.opacityPercent); put("visible", l.meta.visible)
                put("blend", l.meta.blend); put("locked", l.meta.locked)
                put("cels", JSONObject().also { c -> l.cels.forEach { (f, d) -> c.put(f.toString(), d) } })
                put("drawings", JSONArray().also { a -> l.drawings.keys.forEach { a.put(it) } })
            })
        })
    }

    private fun strokesJson(list: List<AspkStroke>) = JSONArray().also { arr ->
        for (s in list) arr.put(JSONObject().apply {
            put("c", s.colorArgb); put("w", s.width.toDouble()); put("e", s.erase)
            s.brushId?.let { put("b", it) }
            put("p", JSONArray().also { a -> s.points.forEach { pt -> a.put(pt.x.toDouble()); a.put(pt.y.toDouble()); a.put(pt.pressure.toDouble()) } })
        })
    }

    private fun parseStrokes(arr: JSONArray): List<AspkStroke> = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val flat = o.getJSONArray("p")
        val pts = (0 until flat.length() / 3).map { k ->
            AspkPoint(flat.getDouble(k * 3).toFloat(), flat.getDouble(k * 3 + 1).toFloat(), flat.getDouble(k * 3 + 2).toFloat())
        }
        AspkStroke(pts, o.getInt("c"), o.getDouble("w").toFloat(), o.optBoolean("e", false), if (o.has("b")) o.getString("b") else null)
    }
}
