package com.studiopaint.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint as APaint
import android.graphics.Path as APath
import android.graphics.Color as AColor
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.studiopaint.SaveHooks
import com.studiopaint.domain.Pt
import com.studiopaint.engine.AspkBridge
import com.studiopaint.engine.BlendKind
import com.studiopaint.engine.BrushLibrary
import com.studiopaint.engine.BrushPreset
import com.studiopaint.engine.Compositor
import com.studiopaint.engine.LayerKind
import com.studiopaint.engine.NativeMyPaint
import com.studiopaint.engine.PaintLayer
import com.studiopaint.format.AspkIO
import com.studiopaint.storage.ProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private enum class Panel { BRUSH, COLOR, SELECTION, SHAPES, LAYERS }

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
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ds.bg0, Ds.bg1))), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error ?: "A abrir…", color = Ds.ink)
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

private fun decodeSampled(ctx: Context, uri: Uri, max: Int): android.graphics.Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
    var s = 1
    while (o.outWidth / s > max || o.outHeight / s > max) s *= 2
    val o2 = BitmapFactory.Options().apply { inSampleSize = s }
    return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
}

@Composable
private fun EditorContent(st: EditorState, ctx: Context, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    remember { st.fitMargin = with(density) { 64.dp.toPx() }; true }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var menu by remember { mutableStateOf(false) }
    var pendingTransparent by remember { mutableStateOf(false) }
    var dropPos by remember { mutableStateOf<Offset?>(null) }
    fun toggle(p: Panel) { panel = if (panel == p) null else p }

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
    val refPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val bmp = withContext(Dispatchers.IO) { runCatching { decodeSampled(ctx, uri, 1600) }.getOrNull() }
            if (bmp != null) { st.refImage = bmp; st.refVisible = true } else Toast.makeText(ctx, "Não foi possível abrir a imagem", Toast.LENGTH_SHORT).show()
        }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ds.bg0, Ds.bg1)))) {
        EditorCanvas(st)

        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
            // ---- topo: desfazer/refazer (esquerda) e ferramentas (direita) ----
            Row(Modifier.fillMaxWidth().align(Alignment.TopCenter), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SquircleButton(Icons.AutoMirrored.Filled.Undo, "Desfazer", enabled = st.doc.history.canUndo) { st.undo() }
                    SquircleButton(Icons.AutoMirrored.Filled.Redo, "Refazer", enabled = st.doc.history.canRedo) { st.redo() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SquircleButton(Icons.Default.Brush, "Pincel", active = st.tool == Tool.BRUSH) {
                        if (st.tool == Tool.BRUSH) toggle(Panel.BRUSH) else { st.tool = Tool.BRUSH; panel = null }
                    }
                    SquircleButton(Icons.Default.AutoFixNormal, "Borracha", active = st.tool == Tool.ERASER) {
                        if (st.tool == Tool.ERASER) toggle(Panel.BRUSH) else { st.tool = Tool.ERASER; panel = null }
                    }
                    ColorOrb(st.color, panel == Panel.COLOR, onTap = { toggle(Panel.COLOR) }, onDrag = { dropPos = it }, onDrop = { dropPos = null; st.colorDropAt(it) })
                    SquircleButton(Icons.Default.Gesture, "Laço", active = st.tool == Tool.LASSO) {
                        if (st.tool == Tool.LASSO) toggle(Panel.SELECTION) else { st.tool = Tool.LASSO; panel = null }
                    }
                }
            }

            // ---- esquerda: tamanho e opacidade ----
            Column(Modifier.align(Alignment.CenterStart), verticalArrangement = Arrangement.spacedBy(30.dp)) {
                val sizeV = sqrt(((st.brushSize - 1f) / 299f).coerceIn(0f, 1f))
                VerticalSlider(sizeV, { st.brushSize = 1f + it * it * 299f }, "${st.brushSize.roundToInt()} px")
                VerticalSlider(st.brushOpacity, { st.brushOpacity = it.coerceAtLeast(0.02f) }, "${(st.brushOpacity * 100).roundToInt()}%")
            }

            // ---- canto inferior esquerdo: menu do projeto ----
            Box(Modifier.align(Alignment.BottomStart)) {
                SquircleButton(Icons.Default.Menu, "Menu do projeto") { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Galeria (guardar e sair)") }, onClick = { menu = false; onClose() })
                    DropdownMenuItem(text = { Text("Ajustar à folha") }, onClick = { menu = false; st.fitToView() })
                    DropdownMenuItem(text = { Text("Exportar PNG") }, onClick = { menu = false; pendingTransparent = false; pngLauncher.launch("${st.name}.png") })
                    DropdownMenuItem(text = { Text("Exportar PNG transparente") }, onClick = { menu = false; pendingTransparent = true; pngLauncher.launch("${st.name}.png") })
                    DropdownMenuItem(text = { Text("Exportar projeto (.aspk)") }, onClick = { menu = false; aspkLauncher.launch("${st.name}.aspk") })
                    DropdownMenuItem(text = { Text("Enviar para o Animation Studio") }, onClick = { menu = false; scope.launch { shareToAnimationStudio(ctx, st) } })
                }
            }

            // ---- canto inferior direito: referência, régua, formas, camadas ----
            Row(Modifier.align(Alignment.BottomEnd), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SquircleButton(Icons.Default.Image, "Janela de referência", active = st.refVisible) {
                    if (st.refImage == null) refPicker.launch("image/*") else st.refVisible = !st.refVisible
                }
                SquircleButton(Icons.Default.Straighten, "Régua", active = st.rulerOn) { st.toggleRuler() }
                SquircleButton(Icons.Default.Category, "Formas", active = st.shapeMode != ShapeMode.NONE || panel == Panel.SHAPES) { toggle(Panel.SHAPES) }
                SquircleButton(Icons.Default.Layers, "Camadas", active = panel == Panel.LAYERS) { toggle(Panel.LAYERS) }
            }

            // ---- dica de estado ----
            val hint = when {
                st.shapeMode != ShapeMode.NONE -> "Forma: arraste na folha"
                st.rulerOn -> "Régua: traços próximos colam na linha"
                st.tool == Tool.LASSO -> "Laço: desenhe a seleção · toque para desmarcar"
                else -> null
            }
            if (hint != null) Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xCC15171B))
                    .border(1.dp, Ds.stroke, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
            ) { Text(hint, color = Ds.ink, fontSize = 12.sp) }

            // ---- painéis ----
            val topMod = Modifier.align(Alignment.TopEnd).padding(top = 58.dp).width(320.dp).heightIn(max = 540.dp)
            val botMod = Modifier.align(Alignment.BottomEnd).padding(bottom = 58.dp).width(320.dp).heightIn(max = 480.dp)
            when (panel) {
                Panel.BRUSH -> Popover(if (st.tool == Tool.ERASER) "Borracha" else "Pincéis", { panel = null }, topMod) { BrushPanel(st) }
                Panel.COLOR -> Popover("Cor", { panel = null }, topMod) { ColorPanel(st) }
                Panel.SELECTION -> Popover("Seleção", { panel = null }, topMod) { SelectionPanel(st) }
                Panel.SHAPES -> Popover("Formas", { panel = null }, botMod) { ShapesPanel(st) }
                Panel.LAYERS -> Popover("Camadas", { panel = null }, botMod) { LayersPanel(st) }
                null -> {}
            }

            if (st.refVisible) ReferenceWindow(st, onPick = { refPicker.launch("image/*") })
        }

        dropPos?.let { p ->
            Box(
                Modifier.offset { IntOffset((p.x - 18.dp.toPx()).roundToInt(), (p.y - 18.dp.toPx()).roundToInt()) }.size(36.dp)
                    .background(Color(st.color or (0xFF shl 24)), CircleShape).border(3.dp, Color.White, CircleShape),
            )
        }
    }
}

/** Bola de cor: toque abre o seletor; arraste para a folha para preencher uma região (gota de cor). */
@Composable
private fun ColorOrb(color: Int, active: Boolean, onTap: () -> Unit, onDrag: (Offset?) -> Unit, onDrop: (Offset) -> Unit) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var pos by remember { mutableStateOf<Offset?>(null) }
    Box(
        Modifier.size(46.dp)
            .shadow(if (active) 12.dp else 6.dp, CircleShape, ambientColor = Color.Black, spotColor = if (active) Ds.accentA else Color.Black)
            .clip(CircleShape).background(Ds.surfaceBrush).border(1.dp, if (active) Color(0x88FFFFFF) else Ds.stroke, CircleShape)
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(Unit) { detectTapGestures { onTap() } }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o -> pos = origin + o; onDrag(pos) },
                    onDragEnd = { pos?.let { onDrop(it) }; pos = null; onDrag(null) },
                    onDragCancel = { pos = null; onDrag(null) },
                ) { ch, d -> ch.consume(); pos = (pos ?: origin) + d; onDrag(pos) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(32.dp).background(Color(color or (0xFF shl 24)), CircleShape).border(2.5.dp, Color.White, CircleShape))
    }
}

@Composable
private fun EditorCanvas(st: EditorState) {
    val matrix = remember { Matrix() }
    val shadowPaint = remember { APaint().apply { style = APaint.Style.FILL; color = AColor.argb(12, 0, 0, 0) } }
    val antA = remember { APaint().apply { style = APaint.Style.STROKE; color = AColor.WHITE } }
    val antB = remember { APaint().apply { style = APaint.Style.STROKE; color = AColor.BLACK } }
    Canvas(
        Modifier.fillMaxSize()
            .onSizeChanged { st.onViewSize(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(st) { handleGestures(st) },
    ) {
        val t = st.transform
        @Suppress("UNUSED_VARIABLE") val rev = st.doc.revision
        @Suppress("UNUSED_VARIABLE") val selRev = st.selectionRev
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            matrix.setScale(t.scale, t.scale)
            matrix.postRotate(Math.toDegrees(t.rotation.toDouble()).toFloat())
            matrix.postTranslate(t.tx, t.ty)
            nc.save(); nc.concat(matrix)
            val w = st.doc.width.toFloat(); val h = st.doc.height.toFloat()
            for (i in 7 downTo 1) {
                val g = i * 3f / t.scale
                nc.drawRect(-g, -g + 5f / t.scale, w + g, h + g + 5f / t.scale, shadowPaint)
            }
            Compositor.draw(nc, st.doc, true)
            st.selection?.let { sel ->
                val d = 9f / t.scale
                antA.strokeWidth = 1.6f / t.scale; antB.strokeWidth = 1.6f / t.scale
                antA.pathEffect = DashPathEffect(floatArrayOf(d, d), 0f); antB.pathEffect = DashPathEffect(floatArrayOf(d, d), d)
                nc.drawPath(sel, antB); nc.drawPath(sel, antA)
            }
            if (st.lassoDraft.size >= 2) {
                val p = APath()
                p.moveTo(st.lassoDraft[0].x, st.lassoDraft[0].y)
                for (i in 1 until st.lassoDraft.size) p.lineTo(st.lassoDraft[i].x, st.lassoDraft[i].y)
                val d = 8f / t.scale
                antA.strokeWidth = 1.8f / t.scale; antA.pathEffect = DashPathEffect(floatArrayOf(d, d), 0f)
                nc.drawPath(p, antA)
            }
            nc.restore()
        }
        if (st.rulerOn) {
            val a = t.docToScreen(st.rulerA); val b = t.docToScreen(st.rulerB)
            val dx = b.x - a.x; val dy = b.y - a.y
            val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
            val ux = dx / len; val uy = dy / len
            val far = 6000f
            drawLine(Color(0x99FFFFFF), Offset(a.x - ux * far, a.y - uy * far), Offset(b.x + ux * far, b.y + uy * far), strokeWidth = 2.dp.toPx())
            drawLine(Color(0xFF8B6CFF), Offset(a.x, a.y), Offset(b.x, b.y), strokeWidth = 5.dp.toPx())
            for (p in listOf(a, b)) {
                drawCircle(Color.White, 13.dp.toPx(), Offset(p.x, p.y))
                drawCircle(Color(0xFF8B6CFF), 9.dp.toPx(), Offset(p.x, p.y))
            }
        }
        st.eyedropAt?.let { p ->
            val c = Offset(p.x, p.y - 55.dp.toPx())
            drawCircle(Color(st.color or (0xFF shl 24)), 34.dp.toPx(), c)
            drawCircle(Color.White, 34.dp.toPx(), c, style = Stroke(4.dp.toPx()))
            drawCircle(Color.Black, 6.dp.toPx(), p, style = Stroke(2.dp.toPx()))
        }
    }
}

private fun pressureOf(c: PointerInputChange): Float =
    if (c.type == PointerType.Stylus || c.type == PointerType.Eraser) c.pressure.coerceIn(0.02f, 1f) else 1f

private enum class GMode { DRAW, SHAPE, LASSO, RULER, EYEDROP }

/**
 * Um dedo/stylus desenha (ou faz o gesto da ferramenta); dois dedos navegam (zoom, pan, rotação);
 * toque de dois dedos = desfazer; toque longo parado = conta-gotas.
 */
private suspend fun PointerInputScope.handleGestures(st: EditorState) {
    awaitEachGesture {
        val slop = viewConfiguration.touchSlop
        val handleR = 24.dp.toPx()
        val down = awaitFirstDown(requireUnconsumed = false)
        fun doc(o: Offset) = st.transform.screenToDoc(Pt(o.x, o.y))
        var mode: GMode? = null
        var rulerHit = 0
        val brushLike = st.tool == Tool.BRUSH || st.tool == Tool.ERASER
        if (st.rulerOn) {
            rulerHit = st.hitRuler(Pt(down.position.x, down.position.y), handleR)
            if (rulerHit != 0) mode = GMode.RULER
        }
        if (mode == null) {
            if (st.tool == Tool.LASSO) {
                st.lassoBegin(doc(down.position)); mode = GMode.LASSO
            } else if (brushLike && st.shapeMode != ShapeMode.NONE) {
                if (st.beginShape(doc(down.position))) mode = GMode.SHAPE
            } else if (brushLike) {
                if (st.beginStroke(doc(down.position), pressureOf(down))) mode = GMode.DRAW
            }
        }
        var moved = false; var multi = false; var holdDone = false
        var travel = 0f
        val t0 = down.uptimeMillis; var lastT = t0
        while (true) {
            val canHold = !holdDone && mode == GMode.DRAW
            val ev = if (canHold) withTimeoutOrNull(450L) { awaitPointerEvent() } else awaitPointerEvent()
            if (ev == null) {
                holdDone = true
                if (!moved && !multi) { st.cancelStroke(); mode = GMode.EYEDROP; st.eyedrop(down.position) }
                continue
            }
            val pressed = ev.changes.filter { it.pressed }
            lastT = ev.changes.firstOrNull()?.uptimeMillis ?: lastT
            if (pressed.isEmpty()) break
            if (pressed.size >= 2) {
                when (mode) {
                    GMode.DRAW -> st.cancelStroke()
                    GMode.SHAPE -> st.cancelShape()
                    GMode.LASSO -> st.lassoCancel()
                    GMode.EYEDROP -> st.eyedropEnd()
                    else -> {}
                }
                mode = null; multi = true
                val c0 = ev.calculateCentroid(useCurrent = false); val c1 = ev.calculateCentroid(useCurrent = true)
                val zoom = ev.calculateZoom(); val rot = ev.calculateRotation()
                if (c0.isSpecified && c1.isSpecified) {
                    travel += (c1 - c0).getDistance() + abs(zoom - 1f) * 200f + abs(rot) * 2f
                    st.transform = st.transform.gesture(Pt(c0.x, c0.y), Pt(c1.x, c1.y), zoom, Math.toRadians(rot.toDouble()).toFloat())
                }
                ev.changes.forEach { it.consume() }
            } else if (pressed.size == 1 && !multi) {
                val ch = pressed[0]
                if ((ch.position - down.position).getDistance() > slop) { moved = true; holdDone = true }
                when (mode) {
                    GMode.DRAW -> {
                        for (h in ch.historical) st.strokeTo(doc(h.position), pressureOf(ch))
                        st.strokeTo(doc(ch.position), pressureOf(ch))
                    }
                    GMode.SHAPE -> if (moved) st.updateShape(doc(ch.position))
                    GMode.LASSO -> st.lassoAdd(doc(ch.position))
                    GMode.RULER -> st.dragRuler(rulerHit, Pt(ch.position.x, ch.position.y))
                    GMode.EYEDROP -> st.eyedrop(ch.position)
                    null -> {}
                }
                ch.consume()
            }
        }
        when (mode) {
            GMode.DRAW -> st.endStroke()
            GMode.SHAPE -> if (moved) st.endShape() else st.cancelShape()
            GMode.LASSO -> st.lassoEnd()
            GMode.EYEDROP -> st.eyedropEnd()
            else -> {}
        }
        if (multi && (lastT - t0) < 300 && travel < 14f) st.undo()
    }
}

// ===================== painéis =====================

@Composable
private fun DsSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text(label, color = Ds.inkDim, fontSize = 12.sp)
    Slider(value = value, onValueChange = onChange, valueRange = range, colors = dsSliderColors())
}

@Composable
private fun BrushPanel(st: EditorState) {
    val list = if (NativeMyPaint.available) BrushLibrary.presets else BrushLibrary.presets.filter { it.mypaint == null }
    list.groupBy { it.category }.forEach { (cat, items) ->
        Text(cat, color = Ds.inkDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
        items.chunked(2).forEach { rowItems ->
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowItems.forEach { b -> BrushCard(b, st.preset.id == b.id, Modifier.weight(1f)) { st.preset = b } }
                if (rowItems.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
    if (!NativeMyPaint.available) Text("libmypaint não está compilado nesta build; a usar o motor Kotlin.", color = Ds.inkDim, fontSize = 11.sp)
    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Ds.stroke)
    DsSlider("Estabilização: ${(st.preset.smoothing * 100).roundToInt()}%", st.preset.smoothing, 0f..0.95f) { st.preset = st.preset.copy(smoothing = it) }
}

@Composable
private fun BrushCard(b: BrushPreset, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.clip(shape).background(if (selected) Color(0x338B6CFF) else Color(0x14FFFFFF))
            .border(1.dp, if (selected) Ds.accentA else Ds.stroke, shape).clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val h = b.hardness.coerceIn(0.05f, 0.95f)
        Canvas(Modifier.size(30.dp)) {
            drawCircle(Brush.radialGradient(colorStops = arrayOf(0f to Ds.ink, h to Ds.ink, 1f to Color.Transparent), center = center, radius = size.minDimension / 2f))
        }
        Text(b.name, color = Ds.ink, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp), maxLines = 2)
    }
}

@Composable
private fun ColorPanel(st: EditorState) {
    val init = remember { FloatArray(3).also { AColor.colorToHSV(st.color, it) } }
    var h by remember { mutableFloatStateOf(init[0]) }
    var s by remember { mutableFloatStateOf(init[1]) }
    var v by remember { mutableFloatStateOf(init[2]) }
    var hex by remember { mutableStateOf(String.format("%06X", st.color and 0xFFFFFF)) }
    fun push() { st.color = AColor.HSVToColor(floatArrayOf(h, s, v)); hex = String.format("%06X", st.color and 0xFFFFFF) }
    LaunchedEffect(st.color) {
        val cur = AColor.HSVToColor(floatArrayOf(h, s, v)) and 0xFFFFFF
        if (cur != (st.color and 0xFFFFFF)) {
            val f = FloatArray(3); AColor.colorToHSV(st.color, f); h = f[0]; s = f[1]; v = f[2]
            hex = String.format("%06X", st.color and 0xFFFFFF)
        }
    }
    SvSquare(h, s, v, { ns, nv -> s = ns; v = nv; push() }, Modifier.fillMaxWidth())
    Box(Modifier.height(10.dp))
    HueBar(h, { h = it; push() })
    Box(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(st.color or (0xFF shl 24))).border(1.dp, Ds.stroke, RoundedCornerShape(12.dp)))
        OutlinedTextField(
            hex, { t ->
                hex = t.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                if (hex.length == 6) st.color = AColor.parseColor("#$hex")
            },
            label = { Text("Hex") }, singleLine = true, modifier = Modifier.padding(start = 10.dp).weight(1f),
        )
    }
    if (st.recent.isNotEmpty()) {
        Text("Recentes", color = Ds.inkDim, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
        st.recent.take(16).chunked(8).forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                r.forEach { c -> Box(Modifier.size(28.dp).background(Color(c), CircleShape).border(1.dp, Ds.stroke, CircleShape).clickable { st.color = c }) }
            }
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Ds.stroke)
    Text("Arraste a bola de cor para a folha para preencher uma região.", color = Ds.inkDim, fontSize = 11.sp)
    DsSlider("Preenchimento — tolerância: ${st.fillTolerance}", st.fillTolerance.toFloat(), 0f..128f) { st.fillTolerance = it.roundToInt() }
    DsSlider("Preenchimento — expandir: ${st.fillGrow} px", st.fillGrow.toFloat(), 0f..4f) { st.fillGrow = it.roundToInt() }
}

@Composable
private fun SelectionPanel(st: EditorState) {
    @Suppress("UNUSED_VARIABLE") val rev = st.selectionRev
    val has = st.selection != null
    Text(
        if (has) "Pincel e borracha só atuam dentro da seleção." else "Desenhe um laço na folha para criar uma seleção.",
        color = Ds.inkDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp),
    )
    if (has) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            PillButton("Preencher", icon = Icons.Default.FormatColorFill) { st.fillSelection() }
            PillButton("Apagar", icon = Icons.Default.Delete) { st.clearSelectionContent() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Inverter", icon = Icons.Default.Flip) { st.invertSelection() }
            PillButton("Desmarcar", icon = Icons.Default.Close) { st.deselect() }
        }
    }
}

@Composable
private fun ShapesPanel(st: EditorState) {
    Text("Escolha uma forma e arraste na folha. Usa o pincel e a cor atuais.", color = Ds.inkDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ShapeOption(Icons.Default.Draw, "Livre", st.shapeMode == ShapeMode.NONE) { st.shapeMode = ShapeMode.NONE }
        ShapeOption(Icons.Default.Remove, "Linha", st.shapeMode == ShapeMode.LINE) { st.shapeMode = ShapeMode.LINE }
        ShapeOption(Icons.Default.CropSquare, "Retângulo", st.shapeMode == ShapeMode.RECT) { st.shapeMode = ShapeMode.RECT }
        ShapeOption(Icons.Default.RadioButtonUnchecked, "Elipse", st.shapeMode == ShapeMode.ELLIPSE) { st.shapeMode = ShapeMode.ELLIPSE }
    }
}

@Composable
private fun ShapeOption(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SquircleButton(icon, label, active = active, size = 52.dp, onClick = onClick)
        Text(label, color = Ds.inkDim, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun LayerThumb(st: EditorState, l: PaintLayer) {
    Canvas(Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFEDEDED))) {
        @Suppress("UNUSED_VARIABLE") val rev = st.doc.revision
        drawIntoCanvas { c ->
            val bw = l.bitmap.width.toFloat(); val bh = l.bitmap.height.toFloat()
            val sc = min(size.width / bw, size.height / bh)
            val w = bw * sc; val hh = bh * sc
            val left = (size.width - w) / 2f; val top = (size.height - hh) / 2f
            val p = APaint(APaint.FILTER_BITMAP_FLAG).apply { alpha = (l.opacity * 255).toInt() }
            c.nativeCanvas.drawBitmap(l.bitmap, null, android.graphics.RectF(left, top, left + w, top + hh), p)
        }
    }
}

@Composable
private fun LayersPanel(st: EditorState) {
    val doc = st.doc
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        PillButton("Camada", icon = Icons.Default.Add) { st.addLayer(LayerKind.DRAWING) }
        PillButton("Cenário", icon = Icons.Default.Add) { st.addLayer(LayerKind.SCENERY) }
    }
    doc.layers.reversed().forEach { l ->
        val active = l.id == doc.activeId
        val shape = RoundedCornerShape(12.dp)
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(shape).background(if (active) Color(0x338B6CFF) else Color(0x14FFFFFF))
                .border(1.dp, if (active) Ds.accentA else Ds.stroke, shape).clickable { doc.activeId = l.id }.padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LayerThumb(st, l)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(l.name, color = Ds.ink, fontSize = 13.sp, maxLines = 1)
                Text(if (l.kind == LayerKind.SCENERY) "cenário" else "${(l.opacity * 100).roundToInt()}% · ${l.blend.label}", color = Ds.inkDim, fontSize = 11.sp)
            }
            Icon(if (l.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff, "Visibilidade", tint = Ds.ink,
                modifier = Modifier.size(34.dp).clip(CircleShape).clickable { l.visible = !l.visible; st.layerChanged(l) }.padding(7.dp))
            Icon(if (l.locked) Icons.Default.Lock else Icons.Default.LockOpen, "Bloquear", tint = if (l.locked) Ds.accentB else Ds.inkDim,
                modifier = Modifier.size(34.dp).clip(CircleShape).clickable { l.locked = !l.locked; st.layerChanged(l) }.padding(7.dp))
        }
    }
    doc.active?.let { l ->
        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Ds.stroke)
        DsSlider("Opacidade: ${(l.opacity * 100).roundToInt()}%", l.opacity, 0f..1f) { l.opacity = it; st.layerChanged(l) }
        var open by remember { mutableStateOf(false) }
        Box {
            PillButton("Mistura: ${l.blend.label}") { open = true }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                BlendKind.values().forEach { b -> DropdownMenuItem(text = { Text(b.label) }, onClick = { l.blend = b; open = false; st.layerChanged(l) }) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
            SquircleButton(Icons.Default.ArrowUpward, "Subir", size = 40.dp) { st.moveLayer(l.id, 1) }
            SquircleButton(Icons.Default.ArrowDownward, "Descer", size = 40.dp) { st.moveLayer(l.id, -1) }
            SquircleButton(Icons.Default.ContentCopy, "Duplicar", size = 40.dp) { st.duplicateLayer(l.id) }
            SquircleButton(Icons.Default.MergeType, "Fundir para baixo", size = 40.dp) { st.mergeDown(l.id) }
            SquircleButton(Icons.Default.Delete, "Apagar", size = 40.dp) { st.deleteLayer(l.id) }
        }
    }
}

/** Janela de referência flutuante: arraste pela barra, redimensione no canto, toque na imagem para apanhar uma cor. */
@Composable
private fun ReferenceWindow(st: EditorState, onPick: () -> Unit) {
    val bmp = st.refImage ?: return
    var off by remember { mutableStateOf(Offset(40f, 260f)) }
    var wDp by remember { mutableFloatStateOf(200f) }
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.offset { IntOffset(off.x.roundToInt(), off.y.roundToInt()) }.width(wDp.dp)
            .shadow(18.dp, shape).clip(shape).background(Color(0xFF1B1D22)).border(1.dp, Ds.stroke, shape),
    ) {
        Image(
            bmp.asImageBitmap(), "Referência",
            Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height.toFloat()).pointerInput(Unit) {
                detectTapGestures { o ->
                    val x = (o.x / size.width * bmp.width).toInt().coerceIn(0, bmp.width - 1)
                    val y = (o.y / size.height * bmp.height).toInt().coerceIn(0, bmp.height - 1)
                    st.color = bmp.getPixel(x, y) or (0xFF shl 24)
                }
            },
            contentScale = ContentScale.FillBounds,
        )
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().height(28.dp).background(Color(0x88000000))
                .pointerInput(Unit) { detectDragGestures { ch, d -> ch.consume(); off += d } },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Referência", color = Ds.ink, fontSize = 11.sp, modifier = Modifier.padding(start = 10.dp).weight(1f))
            Icon(Icons.Default.Image, "Trocar imagem", tint = Ds.ink, modifier = Modifier.size(28.dp).clickable(onClick = onPick).padding(6.dp))
            Icon(Icons.Default.Close, "Fechar", tint = Ds.ink, modifier = Modifier.size(28.dp).clickable { st.refVisible = false }.padding(6.dp))
        }
        Icon(
            Icons.Default.OpenInFull, "Redimensionar", tint = Ds.ink,
            modifier = Modifier.align(Alignment.BottomEnd).size(30.dp).padding(6.dp)
                .pointerInput(Unit) { detectDragGestures { ch, d -> ch.consume(); wDp = (wDp + d.x / density).coerceIn(120f, 420f) } },
        )
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
