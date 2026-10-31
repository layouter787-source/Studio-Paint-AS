package com.studiopaint.storage

import android.content.Context
import android.graphics.Bitmap
import com.studiopaint.engine.AspkBridge
import com.studiopaint.format.AspkIO
import com.studiopaint.format.AspkProject
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ProjectMeta(val id: String, val name: String, val updatedAt: Long, val width: Int, val height: Int, val frameIndex: Int)

/** Projetos locais em filesDir/projects/<id>/{project.aspk, meta.json, thumb.png}. Grava de forma atómica com cópia .bak. */
class ProjectStore(ctx: Context) {
    private val root = File(ctx.filesDir, "projects").also { it.mkdirs() }
    private fun dir(id: String) = File(root, id)
    fun projectFile(id: String) = File(dir(id), "project.aspk")
    fun thumbFile(id: String) = File(dir(id), "thumb.png")
    private fun metaFile(id: String) = File(dir(id), "meta.json")

    fun list(): List<ProjectMeta> =
        (root.listFiles() ?: emptyArray()).mapNotNull { runCatching { meta(it.name) }.getOrNull() }.sortedByDescending { it.updatedAt }

    fun meta(id: String): ProjectMeta {
        val j = JSONObject(metaFile(id).readText())
        return ProjectMeta(id, j.getString("name"), j.getLong("updatedAt"), j.getInt("width"), j.getInt("height"), j.optInt("frameIndex", 0))
    }

    fun create(name: String, w: Int, h: Int): ProjectMeta = importProject(AspkBridge.newProject(name, w, h), 0)

    fun importProject(p: AspkProject, frameIndex: Int): ProjectMeta {
        val id = UUID.randomUUID().toString()
        dir(id).mkdirs()
        save(id, p, null, frameIndex)
        return meta(id)
    }

    fun save(id: String, p: AspkProject, thumb: Bitmap?, frameIndex: Int) {
        val d = dir(id); d.mkdirs()
        val tmp = File(d, "project.aspk.tmp")
        tmp.outputStream().use { AspkIO.write(p, it) }
        val cur = projectFile(id); val bak = File(d, "project.aspk.bak")
        if (cur.exists()) { bak.delete(); cur.renameTo(bak) }
        if (!tmp.renameTo(cur)) throw java.io.IOException("Não foi possível gravar o projeto")
        bak.delete()
        thumb?.let { b -> thumbFile(id).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) } }
        metaFile(id).writeText(JSONObject().put("name", p.name).put("updatedAt", System.currentTimeMillis())
            .put("width", p.width).put("height", p.height).put("frameIndex", frameIndex).toString())
    }

    fun load(id: String): AspkProject {
        val f = projectFile(id)
        val bak = File(dir(id), "project.aspk.bak")
        val src = if (f.exists()) f else if (bak.exists()) bak else throw java.io.IOException("Projeto em falta")
        return src.inputStream().use { AspkIO.read(it) }
    }

    fun delete(id: String) { dir(id).deleteRecursively() }

    fun duplicate(id: String): ProjectMeta {
        val m = meta(id); val p = load(id)
        val copy = AspkProject(m.name + " cópia", p.width, p.height, p.fps, p.paperArgb, p.frameIds, p.currentFrame, p.layers, p.nextId, p.producer, p.extras)
        val n = importProject(copy, m.frameIndex)
        if (thumbFile(id).exists()) thumbFile(id).copyTo(thumbFile(n.id), true)
        return n
    }
}
