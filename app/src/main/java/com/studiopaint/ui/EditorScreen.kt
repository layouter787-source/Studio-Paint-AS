package com.studiopaint.ui

import android.content.Context
import android.content.Intent
import android.graphics.Matrix
import android.graphics.Color as AColor
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.studiopaint.SaveHooks
import com.studiopaint.domain.Pt
import com.studiopaint.engine.AspkBridge
import com.studiopaint.engine.BlendKind
import com.studiopaint.engine.BrushLibrary
import com.studiopaint.engine.Compositor
import com.studiopaint.engine.LayerKind
import com.studiopaint.engine.NativeMyPaint
import com.studiopaint.format.AspkIO
import com.studiopaint.storage.ProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

private enum class Panel { BRUSH, COLOR, LAYERS }

@Composable
fun EditorScreen(store: ProjectStore, projectId: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<EditorState?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(projectId) {
        try {
            val s = withContext(Dispatchers.Default) {
                val p = store.load(projectId)
                val fi = runCatching { store.meta(projectId).frameIndex }.getOrDefault(0).coerceIn(0, p.frameIds.lastIndex)
                EditorState(projectId, p.name, p, fi, AspkBridge.open(p, fi), store, scope)
            }
            state = s
        } catch (t: Throwable) {
            error = "Não foi possível abrir o projeto: ${t.message}"
        }
    }

    val st = state
    if (st == null) {
        Box(Modifier.fillMaxSize().background(Color(0xFF15181E)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error ?: "A abrir…", color = Color.White)
                if (error != null) TextButton(onClick = onClose) { Text("Voltar") }
            }
        }
        return
    }

    DisposableEffect(st) {
        val hook: () -> Unit = { scope.launch { st.saveNow() } }
        SaveHooks.hooks.add(hook)
        onDispose { SaveHooks.hooks.remove(hook) }
    }
    BackHandler { scope.launch { st.saveNow(); onClose() } }
    LaunchedEffect(st.message) { st.message?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); st.message = null } }

    EditorContent(st, ctx) { scope.launch { st.saveNow(); onClose() } }
}

@Composable
private fun EditorContent(st: EditorState, ctx: Context, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("studiopaint", Context.MODE_PRIVATE) }
    var container by remember { mutableStateOf(IntSize.Zero) }
    var topHidden by remember { mutableStateOf(prefs.getBoolean("top_hidden", false)) }
    var toolsHidden by remember { mutableStateOf(prefs.getBoolean("tools_hidden", false)) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var menu by remember { mutableStateOf(false) }
    var pendingTransparent by remember { mutableStateOf(false) }

    val pngLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) scope.launch {
            val bytes = st.renderPng(pendingTransparent)
            withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
            Toast.makeText(ctx, "PNG exportado", Toast.LENGTH_SHORT).show()
        }
    }
    val aspkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(AspkIO.MIME)) { uri ->
        if (uri != null) scope.launch {
            val bytes = st.projectFileBytes()
            withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
            Toast.makeText(ctx, "Projeto exportado", Toast.LENGTH_SHORT).show()
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF1B1E24)).onSizeChanged { container = it }) {
        EditorCanvas(st)

        if (!topHidden) FloatingBar("top", prefs, container, Offset(24f, 24f), horizontalHandle = true,
            onHide = { topHidden = true; prefs.edit().putBoolean("top_hidden", true).apply() }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, "Guardar e voltar") }
                Column(Modifier.widthIn(max = 120.dp)) {
                    Text(st.name, maxLines = 1, style = MaterialTheme.typography.labelLarge)
                    Text(when (st.saveStatus) { SaveStatus.SAVED -> "Guardado"; SaveStatus.SAVING -> "A guardar…"; SaveStatus.ERROR -> "Erro ao guardar" },
                        style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { st.undo() }, enabled = st.doc.history.canUndo) { Icon(Icons.Default.Undo, "Desfazer") }
                IconButton(onClick = { st.redo() }, enabled = st.doc.history.canRedo) { Icon(Icons.Default.Redo, "Refazer") }
                IconButton(onClick = { st.fitToView() }) { Icon(Icons.Default.FitScreen, "Ajustar à folha") }
                IconButton(onClick = { panel = if (panel == Panel.BRUSH) null else Panel.BRUSH }) { Icon(Icons.Default.Brush, "Pincéis") }
                IconButton(onClick = { panel = if (panel == Panel.LAYERS) null else Panel.LAYERS }) { Icon(Icons.Default.Layers, "Camadas") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Mais") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Exportar PNG (fundo da folha)") }, onClick = { menu = false; pendingTransparent = false; pngLauncher.launch("${st.name}.png") })
                        DropdownMenuItem(text = { Text("Exportar PNG transparente") }, onClick = { menu = false; pendingTransparent = true; pngLauncher.launch("${st.name}.png") })
                        DropdownMenuItem(text = { Text("Exportar projeto (.aspk)") }, onClick = { menu = false; aspkLauncher.launch("${st.name}.aspk") })
                        DropdownMenuItem(text = { Text("Enviar para o Animation Studio") }, onClick = { menu = false; scope.launch { shareToAnimationStudio(ctx, st) } })
                    }
                }
            }
        }

        if (!toolsHidden) FloatingBar("tools", prefs, container, Offset(24f, 220f), horizontalHandle = false,
            onHide = { toolsHidden = true; prefs.edit().putBoolean("tools_hidden", true).apply() }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ToolButton(Icons.Default.Brush, "Pincel", st.tool == Tool.BRUSH) { st.tool = Tool.BRUSH }
                ToolButton(Icons.Default.AutoFixNormal, "Borracha", st.tool == Tool.ERASER) { st.tool = Tool.ERASER }
                ToolButton(Icons.Default.FormatColorFill, "Balde", st.tool == Tool.FILL) { st.tool = Tool.FILL }
                ToolButton(Icons.Default.Colorize, "Conta-gotas", st.tool == Tool.EYEDROPPER) { st.tool = Tool.EYEDROPPER }
                ToolButton(Icons.Default.PanTool, "Mão", st.tool == Tool.HAND) { st.tool = Tool.HAND }
                HorizontalDivider(Modifier.width(32.dp).padding(vertical = 4.dp))
                Box(Modifier.padding(6.dp).size(34.dp).background(Color(st.color or (0xFF shl 24)), CircleShape)
                    .border(2.dp, Color.White, CircleShape).clickable { panel = if (panel == Panel.COLOR) null else Panel.COLOR })
            }
        }

        if (topHidden || toolsHidden) {
            Surface(
                onClick = { topHidden = false; toolsHidden = false; prefs.edit().putBoolean("top_hidden", false).putBoolean("tools_hidden", false).apply() },
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp).size(48.dp),
                shape = CircleShape,
                color = Color(0xE6272B33),
                tonalElevation = 6.dp,
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Menu, "Mostrar barras") }
            }
        }

        panel?.let { p ->
            Surface(Modifier.align(Alignment.CenterEnd).padding(12.dp).width(300.dp).heightIn(max = 560.dp), shape = RoundedCornerShape(16.dp),
                color = Color(0xF21E222A), tonalElevation = 8.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(when (p) { Panel.BRUSH -> "Pincéis"; Panel.COLOR -> "Cor"; Panel.LAYERS -> "Camadas" }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { panel = null }) { Icon(Icons.Default.Close, "Fechar") }
                    }
                    when (p) {
                        Panel.BRUSH -> BrushPanel(st)
                        Panel.COLOR -> ColorPanel(st)
                        Panel.LAYERS -> LayersPanel(st)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, active: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.padding(2.dp).then(if (active) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(12.dp)) else Modifier)) {
        Icon(icon, desc)
    }
}

/** Barra flutuante por cima da área de trabalho: não reserva espaço, é móvel (pela pega), ocultável e persistente. */
@Composable
private fun FloatingBar(
    key: String, prefs: android.content.SharedPreferences, container: IntSize, default: Offset,
    horizontalHandle: Boolean, onHide: () -> Unit, content: @Composable () -> Unit,
) {
    var off by remember { mutableStateOf(Offset(prefs.getFloat("${key}_x", default.x), prefs.getFloat("${key}_y", default.y))) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(o: Offset) = Offset(
        o.x.coerceIn(0f, max(0f, (container.width - size.width).toFloat())),
        o.y.coerceIn(0f, max(0f, (container.height - size.height).toFloat())),
    )
    val pos = clamp(off)
    Surface(
        modifier = Modifier.offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }.onSizeChanged { size = it },
        shape = RoundedCornerShape(16.dp), color = Color(0xE6272B33), tonalElevation = 6.dp,
    ) {
        val handle: @Composable () -> Unit = {
            Box(
                Modifier.size(if (horizontalHandle) 40.dp else 48.dp, 40.dp).pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { prefs.edit().putFloat("${key}_x", clamp(off).x).putFloat("${key}_y", clamp(off).y).apply() },
                    ) { change, drag -> change.consume(); off = clamp(off + drag) }
                },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.DragIndicator, "Mover barra") }
        }
        if (horizontalHandle) Row(verticalAlignment = Alignment.CenterVertically) {
            handle(); content(); IconButton(onClick = onHide) { Icon(Icons.Default.VisibilityOff, "Ocultar barra") }
        } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            handle(); content(); IconButton(onClick = onHide) { Icon(Icons.Default.VisibilityOff, "Ocultar barra") }
        }
    }
}

@Composable
private fun EditorCanvas(st: EditorState) {
    val matrix = remember { Matrix() }
    Canvas(
        Modifier.fillMaxSize()
            .onSizeChanged { st.onViewSize(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(st) { handleGestures(st) },
    ) {
        val t = st.transform
        @Suppress("UNUSED_VARIABLE") val rev = st.doc.revision
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            matrix.setScale(t.scale, t.scale)
            matrix.postRotate(Math.toDegrees(t.rotation.toDouble()).toFloat())
            matrix.postTranslate(t.tx, t.ty)
            nc.save(); nc.concat(matrix)
            Compositor.draw(nc, st.doc, true)
            nc.restore()
        }
    }
}

private fun pressureOf(c: PointerInputChange): Float =
    if (c.type == PointerType.Stylus || c.type == PointerType.Eraser) c.pressure.coerceIn(0.02f, 1f) else 1f

/** Um dedo/stylus desenha; dois dedos navegam (zoom, pan, rotação) e cancelam o traço acidental. */
private suspend fun PointerInputScope.handleGestures(st: EditorState) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        fun toDoc(o: Offset) = st.transform.screenToDoc(Pt(o.x, o.y))
        val pan = st.tool == Tool.HAND
        var drawing = false
        if (!pan && (st.tool == Tool.BRUSH || st.tool == Tool.ERASER)) drawing = st.beginStroke(toDoc(down.position), pressureOf(down))
        var moved = false; var multi = false
        do {
            val ev = awaitPointerEvent()
            val pressed = ev.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                if (drawing) { st.cancelStroke(); drawing = false }
                multi = true
                val c0 = ev.calculateCentroid(useCurrent = false); val c1 = ev.calculateCentroid(useCurrent = true)
                if (c0.isSpecified && c1.isSpecified) {
                    st.transform = st.transform.gesture(Pt(c0.x, c0.y), Pt(c1.x, c1.y), ev.calculateZoom(), Math.toRadians(ev.calculateRotation().toDouble()).toFloat())
                }
                ev.changes.forEach { it.consume() }
            } else if (pressed.size == 1 && !multi) {
                val ch = pressed[0]
                if (pan) {
                    val d = ch.position - ch.previousPosition
                    st.transform = st.transform.copy(tx = st.transform.tx + d.x, ty = st.transform.ty + d.y)
                } else if (drawing) {
                    for (h in ch.historical) st.strokeTo(toDoc(h.position), pressureOf(ch))
                    st.strokeTo(toDoc(ch.position), pressureOf(ch))
                }
                if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                ch.consume()
            }
        } while (ev.changes.any { it.pressed })
        if (drawing) st.endStroke() else if (!multi && !moved && !pan) st.tap(toDoc(down.position))
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    Slider(value = value, onValueChange = onChange, valueRange = range)
}

@Composable
private fun BrushPanel(st: EditorState) {
    val list = if (NativeMyPaint.available) BrushLibrary.presets else BrushLibrary.presets.filter { it.mypaint == null }
    list.groupBy { it.category }.forEach { (cat, items) ->
        Text(cat, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        items.forEach { b ->
            Text(b.name, Modifier.fillMaxWidth().clickable { st.preset = b; if (st.tool != Tool.ERASER) st.tool = Tool.BRUSH }
                .background(if (st.preset.id == b.id) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent, RoundedCornerShape(8.dp)).padding(10.dp))
        }
    }
    if (!NativeMyPaint.available) Text("libmypaint não está compilado nesta build (usa o motor Kotlin).", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
    val v = sqrt(((st.brushSize - 1f) / 299f).coerceIn(0f, 1f))
    LabeledSlider("Tamanho: ${st.brushSize.roundToInt()} px", v, 0f..1f) { st.brushSize = 1f + it * it * 299f }
    LabeledSlider("Opacidade: ${(st.brushOpacity * 100).roundToInt()}%", st.brushOpacity, 0.05f..1f) { st.brushOpacity = it }
    LabeledSlider("Balde — tolerância: ${st.fillTolerance}", st.fillTolerance.toFloat(), 0f..128f) { st.fillTolerance = it.roundToInt() }
    LabeledSlider("Balde — expandir: ${st.fillGrow} px", st.fillGrow.toFloat(), 0f..4f) { st.fillGrow = it.roundToInt() }
}

@Composable
private fun ColorPanel(st: EditorState) {
    val hsv = remember(st.color) { FloatArray(3).also { AColor.colorToHSV(st.color, it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var hex by remember(st.color) { mutableStateOf(String.format("%06X", st.color and 0xFFFFFF)) }
    fun apply(h: Float, s: Float, v: Float) { hue = h; st.color = AColor.HSVToColor(floatArrayOf(h, s, v)) }
    Box(Modifier.fillMaxWidth().height(36.dp).background(Color(st.color or (0xFF shl 24)), RoundedCornerShape(8.dp)))
    LabeledSlider("Matiz", hue, 0f..359f) { apply(it, hsv[1], hsv[2]) }
    LabeledSlider("Saturação", hsv[1], 0f..1f) { apply(hue, it, hsv[2]) }
    LabeledSlider("Brilho", hsv[2], 0f..1f) { apply(hue, hsv[1], it) }
    OutlinedTextField(hex, { t ->
        hex = t.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
        if (hex.length == 6) st.color = AColor.parseColor("#$hex")
    }, label = { Text("Hex") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (st.recent.isNotEmpty()) {
        Text("Recentes", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Row(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            st.recent.take(8).forEach { c -> Box(Modifier.size(28.dp).background(Color(c), CircleShape).border(1.dp, Color.Gray, CircleShape).clickable { st.color = c }) }
        }
    }
}

@Composable
private fun LayersPanel(st: EditorState) {
    val doc = st.doc
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { st.addLayer(LayerKind.DRAWING) }) { Text("+ Camada") }
        TextButton(onClick = { st.addLayer(LayerKind.SCENERY) }) { Text("+ Cenário") }
    }
    doc.layers.reversed().forEach { l ->
        val active = l.id == doc.activeId
        Row(Modifier.fillMaxWidth().background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable { doc.activeId = l.id }, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { l.visible = !l.visible; st.layerChanged(l) }) {
                Icon(if (l.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff, "Visibilidade")
            }
            Text(l.name + if (l.kind == LayerKind.SCENERY) " (cenário)" else "", Modifier.weight(1f), maxLines = 1)
            IconButton(onClick = { l.locked = !l.locked; st.layerChanged(l) }) { Icon(if (l.locked) Icons.Default.Lock else Icons.Default.LockOpen, "Bloquear") }
        }
    }
    doc.active?.let { l ->
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        LabeledSlider("Opacidade: ${(l.opacity * 100).roundToInt()}%", l.opacity, 0f..1f) { l.opacity = it; st.layerChanged(l) }
        var open by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { open = true }) { Text("Mistura: ${l.blend.label}") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                BlendKind.values().forEach { b -> DropdownMenuItem(text = { Text(b.label) }, onClick = { l.blend = b; open = false; st.layerChanged(l) }) }
            }
        }
        Row {
            IconButton(onClick = { st.moveLayer(l.id, 1) }) { Icon(Icons.Default.ArrowUpward, "Subir") }
            IconButton(onClick = { st.moveLayer(l.id, -1) }) { Icon(Icons.Default.ArrowDownward, "Descer") }
            IconButton(onClick = { st.duplicateLayer(l.id) }) { Icon(Icons.Default.ContentCopy, "Duplicar") }
            IconButton(onClick = { st.mergeDown(l.id) }) { Icon(Icons.Default.MergeType, "Fundir para baixo") }
            IconButton(onClick = { st.deleteLayer(l.id) }) { Icon(Icons.Default.Delete, "Apagar") }
        }
    }
}

/** Grava e abre o seletor Android com o ficheiro .aspk para o Animation Studio (ou qualquer app compatível). */
private suspend fun shareToAnimationStudio(ctx: Context, st: EditorState) {
    val bytes = st.projectFileBytes()
    val file = withContext(Dispatchers.IO) {
        File(ctx.cacheDir, "share").also { it.mkdirs() }.let { d -> File(d, "${st.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}.aspk").also { it.writeBytes(bytes) } }
    }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val i = Intent(Intent.ACTION_SEND).apply {
        type = AspkIO.MIME; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, "Enviar projeto para").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
