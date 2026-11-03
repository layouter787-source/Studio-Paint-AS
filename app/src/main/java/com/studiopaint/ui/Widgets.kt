package com.studiopaint.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Sistema de design do Studio Paint AS: grafite profundo, superfícies suaves e um acento violeta→azul. */
object Ds {
    val bg0 = Color(0xFF14161B)
    val bg1 = Color(0xFF22252C)
    val surfaceTop = Color(0xFF3C4049)
    val surfaceBot = Color(0xFF2A2D34)
    val stroke = Color(0x22FFFFFF)
    val ink = Color(0xFFF3F4F7)
    val inkDim = Color(0xFF9CA3AF)
    val accentA = Color(0xFF8B6CFF)
    val accentB = Color(0xFF3FA7FF)
    val accent = Brush.linearGradient(listOf(accentA, accentB))
    val surfaceBrush = Brush.verticalGradient(listOf(surfaceTop, surfaceBot))
    val panelBrush = Brush.verticalGradient(listOf(Color(0xFF30343C), Color(0xFF24272E)))
}

@Composable
fun dsSliderColors() = SliderDefaults.colors(
    thumbColor = Ds.accentB, activeTrackColor = Ds.accentA, inactiveTrackColor = Color(0xFF3A3E47),
)

/** Botão quadrado arredondado (squircle) com estado ativo em gradiente. */
@Composable
fun SquircleButton(
    icon: ImageVector?,
    desc: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 46.dp,
    onClick: () -> Unit,
    content: (@Composable BoxScope.() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.size(size)
            .shadow(if (active) 12.dp else 6.dp, shape, ambientColor = Color.Black, spotColor = if (active) Ds.accentA else Color.Black)
            .clip(shape)
            .background(if (active) Ds.accent else Ds.surfaceBrush)
            .border(1.dp, if (active) Color(0x66FFFFFF) else Ds.stroke, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) content() else if (icon != null) {
            Icon(icon, desc, tint = if (enabled) Ds.ink else Ds.inkDim.copy(alpha = 0.4f), modifier = Modifier.size(size * 0.5f))
        }
    }
}

/** Pequeno botão de texto em pílula, usado nos painéis. */
@Composable
fun PillButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, active: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.clip(shape).background(if (active) Ds.accent else Ds.surfaceBrush).border(1.dp, Ds.stroke, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = Ds.ink, modifier = Modifier.size(18.dp)); Box(Modifier.width(8.dp)) }
        Text(text, color = Ds.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Cartão flutuante dos painéis (estilo popover). */
@Composable
fun Popover(title: String, onClose: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.shadow(28.dp, shape).clip(shape).background(Ds.panelBrush).border(1.dp, Ds.stroke, shape).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Ds.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0x22FFFFFF)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, "Fechar", tint = Ds.ink, modifier = Modifier.size(16.dp))
            }
        }
        Box(Modifier.height(10.dp))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = content)
    }
}

/** Slider vertical fino (tamanho/opacidade do pincel) com balão de valor ao arrastar. */
@Composable
fun VerticalSlider(value: Float, onChange: (Float) -> Unit, label: String, modifier: Modifier = Modifier, height: Dp = 190.dp) {
    var dragging by remember { mutableStateOf(false) }
    val update by rememberUpdatedState(onChange)
    val shape = RoundedCornerShape(17.dp)
    Box(modifier.width(34.dp).height(height)) {
        Box(
            Modifier.matchParentSize().shadow(8.dp, shape).clip(shape)
                .background(Brush.verticalGradient(listOf(Color(0xFF30333A), Color(0xFF1F2227))))
                .border(1.dp, Ds.stroke, shape)
                .pointerInput(Unit) { detectTapGestures { o -> update((1f - o.y / size.height).coerceIn(0f, 1f)) } }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { o -> dragging = true; update((1f - o.y / size.height).coerceIn(0f, 1f)) },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { ch, _ -> ch.consume(); update((1f - ch.position.y / size.height).coerceIn(0f, 1f)) }
                },
        ) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(value.coerceIn(0.03f, 1f)).background(Ds.accent))
        }
        if (dragging) {
            Box(
                Modifier.align(Alignment.TopStart).offset(x = 42.dp, y = height * (1f - value.coerceIn(0f, 1f)) - 16.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Color(0xEE15171B)).border(1.dp, Ds.stroke, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) { Text(label, color = Ds.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** Quadrado de saturação (x) e brilho (y) para um dado matiz. */
@Composable
fun SvSquare(hue: Float, sat: Float, v: Float, onChange: (Float, Float) -> Unit, modifier: Modifier = Modifier) {
    val update by rememberUpdatedState(onChange)
    Canvas(
        modifier.aspectRatio(1.4f).clip(RoundedCornerShape(14.dp))
            .pointerInput(Unit) { detectTapGestures { o -> update((o.x / size.width).coerceIn(0f, 1f), (1f - o.y / size.height).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectDragGestures(onDragStart = { o -> update((o.x / size.width).coerceIn(0f, 1f), (1f - o.y / size.height).coerceIn(0f, 1f)) }) { ch, _ ->
                    ch.consume(); update((ch.position.x / size.width).coerceIn(0f, 1f), (1f - ch.position.y / size.height).coerceIn(0f, 1f))
                }
            },
    ) {
        drawRect(Color.hsv(hue.coerceIn(0f, 359.9f), 1f, 1f))
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val c = Offset(sat * size.width, (1f - v) * size.height)
        drawCircle(Color.Black, 12.dp.toPx(), c, style = Stroke(3.dp.toPx()))
        drawCircle(Color.White, 10.dp.toPx(), c, style = Stroke(2.dp.toPx()))
    }
}

/** Barra de matiz em arco-íris. */
@Composable
fun HueBar(hue: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val update by rememberUpdatedState(onChange)
    Canvas(
        modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(13.dp))
            .pointerInput(Unit) { detectTapGestures { o -> update((o.x / size.width).coerceIn(0f, 1f) * 359f) } }
            .pointerInput(Unit) {
                detectDragGestures(onDragStart = { o -> update((o.x / size.width).coerceIn(0f, 1f) * 359f) }) { ch, _ ->
                    ch.consume(); update((ch.position.x / size.width).coerceIn(0f, 1f) * 359f)
                }
            },
    ) {
        drawRect(Brush.horizontalGradient((0..6).map { Color.hsv((it * 60f) % 360f, 1f, 1f) }))
        val x = hue.coerceIn(0f, 359f) / 359f * size.width
        drawCircle(Color.White, 11.dp.toPx(), Offset(x, size.height / 2f), style = Stroke(3.dp.toPx()))
        drawCircle(Color.hsv(hue.coerceIn(0f, 359.9f), 1f, 1f), 8.dp.toPx(), Offset(x, size.height / 2f))
    }
}
