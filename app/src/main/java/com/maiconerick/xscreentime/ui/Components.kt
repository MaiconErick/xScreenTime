package com.maiconerick.xscreentime.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xscreentime.data.AppInfo
import com.maiconerick.xscreentime.data.DayTotal
import java.time.LocalDate

// ---------------------------------------------------------------- surfaces & text

private val CardShape = RoundedCornerShape(24.dp)
private val CardFill = Brush.verticalGradient(listOf(Color(0xFF110E15), Color(0xFF060508)))
private val CardRim = Brush.linearGradient(listOf(Orange.copy(alpha = 0.45f), Violet.copy(alpha = 0.40f), Cyan.copy(alpha = 0.18f)))
val AccentBrush = Brush.horizontalGradient(listOf(Orange, Violet))

/** Near-black glass with a thin orange→violet rim. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(18.dp), content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(CardFill)
            .border(1.dp, CardRim, CardShape)
            .padding(padding),
        content = content,
    )
}

/** Tiny letter-spaced caps label. */
@Composable
fun Label(text: String, color: Color = TextDim, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = color, fontSize = 10.5.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Medium, modifier = modifier)
}

@Composable
fun SectionLabel(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(text, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, color = TextFaint, fontSize = 11.sp) }
    }
}

@Composable
fun BigValue(text: String, color: Color = TextMain, size: Int = 44) {
    Text(text, color = color, fontSize = size.sp, fontWeight = FontWeight.ExtraLight, letterSpacing = (-1).sp, lineHeight = (size + 4).sp)
}

/** Tinted chip: 12% fill, 40% border, as in xVideoWatcher. */
@Composable
fun Chip(text: String, tint: Color = Violet, icon: Ico? = null, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.4f), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, tint, 14.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** A row of options in one track; the picked one lights up. */
@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(modifier.clip(shape).background(Track).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Text(
                label,
                color = if (on) TextMain else TextDim,
                fontSize = 12.5.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(shape)
                    .then(if (on) Modifier.background(Violet.copy(alpha = 0.22f)).border(1.dp, Violet.copy(alpha = 0.5f), shape) else Modifier)
                    .clickable { onSelect(i) }
                    .padding(vertical = 7.dp),
            )
        }
    }
}

/** Filled orange→violet button; shows a spinner instead of the icon while [loading]. */
@Composable
fun GradientButton(text: String, icon: Ico, loading: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .clip(shape)
            .background(AccentBrush)
            .clickable(enabled = !loading, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) Spinner(Black, 16.dp) else Icon(icon, Black, 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, color = Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun GhostButton(text: String, icon: Ico, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .clip(shape)
            .background(SurfaceHi)
            .border(1.dp, Color.White.copy(alpha = 0.06f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, TextMain, 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun RoundButton(icon: Ico, tint: Color = TextMain, size: Dp = 40.dp, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(SurfaceHi).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, if (enabled) tint else TextFaint, size * 0.5f) }
}

@Composable
fun Spinner(color: Color, size: Dp) {
    val t = rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "a")
    Canvas(Modifier.size(size)) {
        drawArc(color, a, 270f, false, style = Stroke(size.toPx() * 0.14f, cap = StrokeCap.Round))
    }
}

@Composable
fun Empty(text: String) {
    Text(text, color = TextFaint, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(36.dp), textAlign = TextAlign.Center)
}

@Composable
fun AppIcon(info: AppInfo?, size: Dp = 36.dp) {
    val icon = info?.icon
    if (icon != null) {
        Image(icon, contentDescription = null, modifier = Modifier.size(size), filterQuality = FilterQuality.Medium)
    } else {
        // Uninstalled / imported app: a monogram tile in its color.
        val c = info?.let { Color(it.color) } ?: Violet
        Box(
            Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(c.copy(alpha = 0.16f)).border(1.dp, c.copy(alpha = 0.4f), RoundedCornerShape(size * 0.28f)),
            contentAlignment = Alignment.Center,
        ) {
            Text((info?.label ?: "?").take(1).uppercase(), color = c, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---------------------------------------------------------------- icons (drawn, no icon font)

enum class Ico { Overview, Timeline, Trends, Games, History, Upload, Left, Right, Plus, Check, Search, Lock, Layers, Clock, Back, Warning }

@Composable
fun Icon(kind: Ico, color: Color, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) { drawIco(kind, color) }
}

private fun DrawScope.drawIco(kind: Ico, c: Color) {
    val s = size.minDimension
    val w = s * 0.085f
    val st = Stroke(w, cap = StrokeCap.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(c, Offset(x1 * s, y1 * s), Offset(x2 * s, y2 * s), w, StrokeCap.Round)
    fun rrect(x: Float, y: Float, rw: Float, rh: Float, r: Float) =
        drawRoundRect(c, Offset(x * s, y * s), Size(rw * s, rh * s), CornerRadius(r * s), style = st)
    when (kind) {
        Ico.Overview -> {
            drawCircle(c.copy(alpha = 0.35f), s * 0.36f, center, style = st)
            drawArc(c, -90f, 250f, false, Offset(s * 0.14f, s * 0.14f), Size(s * 0.72f, s * 0.72f), style = st)
        }
        Ico.Timeline, Ico.Clock -> {
            drawCircle(c, s * 0.38f, center, style = st)
            line(0.5f, 0.5f, 0.5f, 0.28f); line(0.5f, 0.5f, 0.66f, 0.6f)
        }
        Ico.Trends -> { line(0.22f, 0.8f, 0.22f, 0.58f); line(0.5f, 0.8f, 0.5f, 0.38f); line(0.78f, 0.8f, 0.78f, 0.2f) }
        Ico.Games -> {
            rrect(0.08f, 0.28f, 0.84f, 0.46f, 0.2f)
            line(0.3f, 0.41f, 0.3f, 0.61f); line(0.2f, 0.51f, 0.4f, 0.51f)
            drawCircle(c, w * 0.8f, Offset(s * 0.66f, s * 0.46f)); drawCircle(c, w * 0.8f, Offset(s * 0.75f, s * 0.57f))
        }
        Ico.History -> {
            rrect(0.14f, 0.2f, 0.72f, 0.66f, 0.12f)
            line(0.14f, 0.4f, 0.86f, 0.4f); line(0.34f, 0.12f, 0.34f, 0.26f); line(0.66f, 0.12f, 0.66f, 0.26f)
        }
        Ico.Upload -> {
            line(0.5f, 0.64f, 0.5f, 0.16f); line(0.3f, 0.35f, 0.5f, 0.16f); line(0.7f, 0.35f, 0.5f, 0.16f)
            line(0.18f, 0.66f, 0.18f, 0.84f); line(0.18f, 0.84f, 0.82f, 0.84f); line(0.82f, 0.84f, 0.82f, 0.66f)
        }
        Ico.Left, Ico.Back -> { line(0.62f, 0.2f, 0.34f, 0.5f); line(0.34f, 0.5f, 0.62f, 0.8f) }
        Ico.Right -> { line(0.38f, 0.2f, 0.66f, 0.5f); line(0.66f, 0.5f, 0.38f, 0.8f) }
        Ico.Plus -> { line(0.5f, 0.2f, 0.5f, 0.8f); line(0.2f, 0.5f, 0.8f, 0.5f) }
        Ico.Check -> { line(0.2f, 0.52f, 0.42f, 0.74f); line(0.42f, 0.74f, 0.8f, 0.28f) }
        Ico.Search -> { drawCircle(c, s * 0.24f, Offset(s * 0.44f, s * 0.44f), style = st); line(0.62f, 0.62f, 0.82f, 0.82f) }
        Ico.Lock -> { rrect(0.2f, 0.44f, 0.6f, 0.42f, 0.1f); drawArc(c, 180f, 180f, false, Offset(s * 0.32f, s * 0.16f), Size(s * 0.36f, s * 0.5f), style = st) }
        Ico.Warning -> {
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(0.5f * s, 0.12f * s); lineTo(0.9f * s, 0.84f * s); lineTo(0.1f * s, 0.84f * s); close()
            }
            drawPath(p, c, style = Stroke(w, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            line(0.5f, 0.4f, 0.5f, 0.58f)
            drawCircle(c, w * 0.7f, Offset(0.5f * s, 0.71f * s))
        }
        Ico.Layers -> { rrect(0.16f, 0.16f, 0.5f, 0.5f, 0.1f); rrect(0.34f, 0.34f, 0.5f, 0.5f, 0.1f) }
    }
}

// ---------------------------------------------------------------- bottom navigation

@Composable
fun NavBar(items: List<Pair<String, Ico>>, selected: Int, onSelect: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth().background(Black)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, Violet.copy(alpha = 0.35f), Color.Transparent))))
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)) {
            items.forEachIndexed { i, (label, ico) ->
                val on = i == selected
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { onSelect(i) }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).then(if (on) Modifier.background(Violet.copy(alpha = 0.18f)) else Modifier)
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) { Icon(ico, if (on) Violet else TextDim, 22.dp) }
                    Spacer(Modifier.height(3.dp))
                    Text(label, color = if (on) TextMain else TextFaint, fontSize = 11.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- charts

private val BarBrush = Brush.verticalGradient(listOf(Cyan, Violet))
private val Dashed = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))

/** 24 bars, one per hour; tap a bar to jump to that hour. Empty hours are just a dot on the baseline. */
@Composable
fun HourlyChart(hourly: List<Long>, onHour: (Int) -> Unit) {
    val measurer = rememberTextMeasurer()
    val labelStyle = remember { TextStyle(color = TextFaint, fontSize = 10.sp) }
    val max = (hourly.maxOrNull() ?: 0L).coerceAtLeast(15 * 60_000L)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(116.dp)
            .pointerInput(hourly) {
                detectTapGestures { p -> onHour((p.x / (size.width / 24f)).toInt().coerceIn(0, 23)) }
            },
    ) {
        val labelH = 18.dp.toPx()
        val chartH = size.height - labelH
        val slot = size.width / 24f
        val barW = slot * 0.56f
        drawLine(Track, Offset(0f, chartH), Offset(size.width, chartH), 1.dp.toPx())
        drawLine(TextFaint.copy(alpha = 0.35f), Offset(0f, chartH / 2), Offset(size.width, chartH / 2), 1f, pathEffect = Dashed)
        hourly.forEachIndexed { h, v ->
            val x = h * slot + (slot - barW) / 2
            if (v > 0) {
                val bh = (chartH * v / max).coerceAtLeast(barW * 0.8f)
                drawRoundRect(BarBrush, Offset(x, chartH - bh), Size(barW, bh), CornerRadius(barW / 2))
            } else {
                drawCircle(Track, barW / 4, Offset(x + barW / 2, chartH - barW / 2))
            }
            if (h % 6 == 0) drawText(measurer, "%02d".format(h), Offset(x, chartH + 4.dp.toPx()), labelStyle)
        }
    }
}

/** Daily totals with a dashed average line; the selected day is drawn in the accent gradient. */
@Composable
fun DayBars(
    totals: List<DayTotal>,
    selected: LocalDate?,
    color: Color = Violet,
    height: Dp = 140.dp,
    onDay: (LocalDate) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = remember { TextStyle(color = TextFaint, fontSize = 10.sp) }
    val max = (totals.maxOfOrNull { it.total } ?: 0L).coerceAtLeast(30 * 60_000L)
    val used = totals.filter { it.total > 0 }
    val avg = if (used.isEmpty()) 0L else used.sumOf { it.total } / used.size
    val showLetters = totals.size <= 14
    val selBrush = remember(color) { Brush.verticalGradient(listOf(Orange, color)) }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(totals) {
                detectTapGestures { p ->
                    if (totals.isNotEmpty()) onDay(totals[(p.x / (size.width / totals.size.toFloat())).toInt().coerceIn(0, totals.lastIndex)].date)
                }
            },
    ) {
        if (totals.isEmpty()) return@Canvas
        val labelH = if (showLetters) 18.dp.toPx() else 4.dp.toPx()
        val chartH = size.height - labelH
        val slot = size.width / totals.size
        val barW = (slot * 0.58f).coerceAtLeast(1.5f)
        drawLine(Track, Offset(0f, chartH), Offset(size.width, chartH), 1.dp.toPx())
        totals.forEachIndexed { i, d ->
            val x = i * slot + (slot - barW) / 2
            val isSel = d.date == selected
            if (d.total > 0) {
                val bh = (chartH * d.total / max).coerceAtLeast(3f)
                val r = CornerRadius(minOf(barW / 2, 6.dp.toPx()))
                if (isSel) drawRoundRect(selBrush, Offset(x, chartH - bh), Size(barW, bh), r)
                else drawRoundRect(color.copy(alpha = 0.42f), Offset(x, chartH - bh), Size(barW, bh), r)
            }
            if (showLetters) {
                val layout = measurer.measure(Fmt.dayLetter(d.date), if (isSel) labelStyle.copy(color = TextMain) else labelStyle)
                drawText(layout, topLeft = Offset(x + barW / 2 - layout.size.width / 2, chartH + 4.dp.toPx()))
            }
        }
        if (avg > 0) {
            val y = chartH - chartH * avg / max
            drawLine(Orange.copy(alpha = 0.7f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = Dashed)
        }
    }
}

/** Thin bar in the app's own color, fading in from the left. */
@Composable
fun UsageBar(fraction: Float, color: Color) {
    val brush = remember(color) { Brush.horizontalGradient(listOf(color.copy(alpha = 0.35f), color)) }
    Canvas(Modifier.fillMaxWidth().height(4.dp)) {
        drawRoundRect(Track, cornerRadius = CornerRadius(size.height / 2))
        drawRoundRect(brush, size = Size(size.width * fraction.coerceIn(0.02f, 1f), size.height), cornerRadius = CornerRadius(size.height / 2))
    }
}

/** Labelled bars (months / years); tap to select. */
@Composable
fun PeriodBars(values: List<Long>, labels: List<String>, selected: Int, color: Color, onSelect: (Int) -> Unit) {
    val measurer = rememberTextMeasurer()
    val labelStyle = remember { TextStyle(color = TextFaint, fontSize = 10.sp) }
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val selBrush = remember(color) { Brush.verticalGradient(listOf(Orange, color)) }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(130.dp)
            .pointerInput(values) {
                detectTapGestures { p ->
                    if (values.isNotEmpty()) onSelect((p.x / (size.width / values.size.toFloat())).toInt().coerceIn(0, values.lastIndex))
                }
            },
    ) {
        if (values.isEmpty()) return@Canvas
        val labelH = 18.dp.toPx()
        val chartH = size.height - labelH
        val slot = size.width / values.size
        val barW = (slot * 0.5f).coerceAtMost(28.dp.toPx())
        drawLine(Track, Offset(0f, chartH), Offset(size.width, chartH), 1.dp.toPx())
        values.forEachIndexed { i, v ->
            val x = i * slot + (slot - barW) / 2
            val isSel = i == selected
            if (v > 0) {
                val bh = (chartH * v / max).coerceAtLeast(4f)
                val r = CornerRadius(8.dp.toPx())
                if (isSel) drawRoundRect(selBrush, Offset(x, chartH - bh), Size(barW, bh), r)
                else drawRoundRect(color.copy(alpha = 0.38f), Offset(x, chartH - bh), Size(barW, bh), r)
            }
            val layout = measurer.measure(labels[i], if (isSel) labelStyle.copy(color = TextMain) else labelStyle)
            drawText(layout, topLeft = Offset(x + barW / 2 - layout.size.width / 2, chartH + 4.dp.toPx()))
        }
    }
}
