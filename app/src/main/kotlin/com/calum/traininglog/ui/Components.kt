package com.calum.traininglog.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------
// Small shared helpers
// ---------------------------------------------------------------------------------------------

/** Draws a tick from 0..[progress] (0 = nothing, 1 = full tick). */
@Composable
private fun CheckGlyph(progress: Float, color: Color, modifier: Modifier = Modifier, strokeWidth: Dp = 4.dp) {
    Canvas(modifier) {
        if (progress <= 0.01f) return@Canvas
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.26f, h * 0.54f)
            lineTo(w * 0.43f, h * 0.70f)
            lineTo(w * 0.75f, h * 0.32f)
        }
        val measure = PathMeasure()
        measure.setPath(path, false)
        val seg = Path()
        measure.getSegment(0f, measure.length * progress.coerceIn(0f, 1f), seg, true)
        drawPath(seg, color, style = Stroke(strokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun DrawScope.starPath(center: Offset, outer: Float, inner: Float): Path {
    val p = Path()
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) outer else inner
        val a = -PI / 2 + i * PI / 5
        val x = center.x + (cos(a) * r).toFloat()
        val y = center.y + (sin(a) * r).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}

private fun formatClock(totalSec: Int): String {
    val s = max(0, totalSec)
    return "%d:%02d".format(s / 60, s % 60)
}

// ---------------------------------------------------------------------------------------------
// Progress ring
// ---------------------------------------------------------------------------------------------

/**
 * Animated progress ring. The inner thick arc shows [progress] toward the target (0..1, mint
 * gradient). The thin outer arc shows [bonusProgress] (0..1 = one extra lap of "beyond target",
 * orange gradient) so over-achieving is celebrated rather than clipped.
 *
 * Both values animate when they change. [centerContent] is laid out in the ring's hollow centre.
 */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    bonusProgress: Float = 0f,
    size: Dp = 220.dp,
    strokeWidth: Dp = 18.dp,
    centerContent: @Composable BoxScope.() -> Unit = {},
) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(1100, easing = FastOutSlowInEasing), label = "ring")
    val b by animateFloatAsState(
        bonusProgress.coerceIn(0f, 1f), tween(1100, delayMillis = 300, easing = FastOutSlowInEasing), label = "bonus",
    )
    val track = AppColors.track
    val c1 = AppColors.primaryDeep
    val c2 = AppColors.primary
    val e1 = AppColors.energyDeep
    val e2 = AppColors.energy
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = strokeWidth.toPx()
            val bw = sw * 0.4f
            val gap = sw * 0.4f
            drawRing(inset = bw / 2, width = bw, fraction = b, from = e1, to = e2, track = if (b > 0f) track.copy(alpha = 0.5f) else null)
            drawRing(inset = bw + gap + sw / 2, width = sw, fraction = p, from = c1, to = c2, track = track)
        }
        Box(
            Modifier.fillMaxSize().padding(strokeWidth * 2 + 10.dp),
            contentAlignment = Alignment.Center,
            content = centerContent,
        )
    }
}

private fun DrawScope.drawRing(inset: Float, width: Float, fraction: Float, from: Color, to: Color, track: Color?) {
    val d = this.size.minDimension - inset * 2
    val tl = Offset(inset, inset)
    val arc = Size(d, d)
    if (track != null) drawArc(track, 0f, 360f, false, tl, arc, style = Stroke(width))
    if (fraction > 0.001f) {
        val brush = Brush.sweepGradient(colorStops = arrayOf(0f to from, fraction to to, 1f to to), center = center)
        rotate(-90f, center) {
            drawArc(
                brush = brush, startAngle = 0f, sweepAngle = 360f * fraction, useCenter = false,
                topLeft = tl, size = arc, style = Stroke(width, cap = StrokeCap.Round),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Cards, tiles, pills
// ---------------------------------------------------------------------------------------------

/**
 * Big gradient header card for the home screen. Defaults to [AppColors.gradientHero] with soft
 * decorative bubbles. Put text in [AppColors.onHero]. Pass [onClick] to make it tappable.
 */
@Composable
fun HeroCard(
    modifier: Modifier = Modifier,
    brush: Brush = AppColors.gradientHero,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val bubble = Color.White.copy(alpha = if (AppColors.isDark) 0.06f else 0.35f)
    Column(
        modifier
            .fillMaxWidth()
            .clip(AppShapes.hero)
            .background(brush)
            .drawBehind {
                drawCircle(bubble, radius = size.minDimension * 0.55f, center = Offset(size.width * 0.95f, -size.height * 0.05f))
                drawCircle(bubble, radius = size.minDimension * 0.3f, center = Offset(size.width * 0.05f, size.height * 1.05f))
            }
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(Spacing.xl),
        content = content,
    )
}

/** Titled card on a raised surface; the default container for grouped content. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    action: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(AppShapes.large)
            .background(AppColors.surface)
            .border(1.dp, AppColors.outline.copy(alpha = 0.5f), AppShapes.large)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (title != null || action != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (title != null) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = AppColors.textPrimary, modifier = Modifier.weight(1f))
                } else Spacer(Modifier.weight(1f))
                action?.invoke()
            }
        }
        content()
    }
}

/**
 * Compact stat: eyebrow [label], big [value] and small [unit]. [accent] colours a small bar
 * (no icons needed). Fill a row of these with `Modifier.weight(1f)`.
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String = "",
    accent: Color = AppColors.primary,
    compact: Boolean = false,
) {
    // The value shrinks (never wraps or truncates) until it fits the tile.
    val base = AppType.numberM
    var scale by remember(value, unit, compact) { mutableStateOf(1f) }
    Column(
        modifier
            .clip(AppShapes.medium)
            .background(AppColors.surfaceRaised)
            .padding(if (compact) Spacing.md else Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Box(Modifier.width(22.dp).height(5.dp).clip(CircleShape).background(accent))
        Spacer(Modifier.height(Spacing.xs))
        Text(label.uppercase(), style = AppType.eyebrow, color = AppColors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value, style = base.copy(fontSize = base.fontSize * scale), color = AppColors.textPrimary,
                maxLines = 1, softWrap = false, modifier = Modifier.weight(1f, fill = false),
                onTextLayout = { if (it.hasVisualOverflow && scale > 0.5f) scale *= 0.9f },
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(unit, style = MaterialTheme.typography.titleSmall, color = AppColors.textMuted, maxLines = 1, softWrap = false, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

/** Static label pill (status, tag, "Level 3"). Tinted background + [color] text. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = AppColors.primary, filled: Boolean = false) {
    val bg = if (filled) color else color.copy(alpha = 0.16f)
    val fg = if (filled) (if (AppColors.isDark) Color(0xFF05100B) else Color.White) else color
    Text(
        text,
        modifier = modifier.clip(AppShapes.chip).background(bg).padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        maxLines = 1,
        textAlign = TextAlign.Center,
    )
}

/** Tappable choice chip with a 48dp minimum height. Selected = solid mint. */
@Composable
fun SelectChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = AppColors.primary,
) {
    val fill by animateFloatAsState(if (selected) 1f else 0f, tween(220), label = "chip")
    val haptic = LocalHapticFeedback.current
    val bg = lerp(AppColors.surfaceRaised, accent, fill)
    val fg = lerp(AppColors.textPrimary, if (AppColors.isDark) Color(0xFF05100B) else Color.White, fill)
    Box(
        modifier
            .heightIn(min = Spacing.minTap)
            .clip(AppShapes.chip)
            .background(bg)
            .clickable(role = Role.RadioButton) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------
// Week strip
// ---------------------------------------------------------------------------------------------

/**
 * State of one day in a week strip. None of them are negative: a missed day is just a quiet dot.
 */
enum class DayState {
    /** Planned session completed: solid mint with tick. */
    Done,
    /** Today: pulsing mint ring. */
    Today,
    /** Planned rest day: calm blue dash. */
    Rest,
    /** Upcoming training day: dashed outline. */
    Open,
    /** Rest day on which you trained anyway: violet bonus tick. */
    RestTrained,
    /** Past day with nothing logged: neutral, faint dot. */
    PastEmpty,
}

/** One column of the week strip: weekday [label] above a 44dp status dot. */
@Composable
fun DayDot(label: String, state: DayState, modifier: Modifier = Modifier) {
    val mint = AppColors.primary
    val deep = AppColors.primaryDeep
    val violet = AppColors.reward
    val info = AppColors.info
    val muted = AppColors.textMuted
    val track = AppColors.track
    val raised = AppColors.surfaceRaised
    val onMint = AppColors.onPrimary
    val onViolet = AppColors.onReward
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p",
    )
    val tick by animateFloatAsState(if (state == DayState.Done || state == DayState.RestTrained) 1f else 0f, tween(500), label = "tick")
    Column(modifier.widthIn(min = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label.uppercase(),
            style = AppType.eyebrow,
            color = if (state == DayState.Today) mint else muted,
        )
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2
                when (state) {
                    DayState.Done -> drawCircle(Brush.linearGradient(listOf(mint, deep)), r)
                    DayState.RestTrained -> drawCircle(violet, r)
                    DayState.Today -> {
                        drawCircle(mint.copy(alpha = 0.10f + 0.12f * pulse), r + 0f)
                        drawCircle(mint, r - 2.dp.toPx(), style = Stroke(3.dp.toPx()))
                        drawCircle(mint, 5.dp.toPx() + 2.dp.toPx() * pulse)
                    }
                    DayState.Rest -> {
                        drawCircle(info.copy(alpha = 0.14f), r)
                        drawLine(info, Offset(size.width * 0.34f, center.y), Offset(size.width * 0.66f, center.y), 3.dp.toPx(), StrokeCap.Round)
                    }
                    DayState.Open -> drawCircle(
                        muted.copy(alpha = 0.7f), r - 1.5.dp.toPx(),
                        style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 6.dp.toPx()))),
                    )
                    DayState.PastEmpty -> {
                        drawCircle(raised, r)
                        drawCircle(track, 4.dp.toPx())
                    }
                }
            }
            if (state == DayState.Done) CheckGlyph(tick, onMint, Modifier.size(26.dp), 3.5.dp)
            if (state == DayState.RestTrained) CheckGlyph(tick, onViolet, Modifier.size(26.dp), 3.5.dp)
        }
    }
}

/**
 * Achievement tile. Unlocked = violet gradient with a white glyph; locked = muted dashed outline with
 * a small lock (no shame: it just looks "not yet"). Pass [glyph] (a badge id such as `first_gym`) for a
 * distinct Canvas-drawn icon; without it the tile shows a star.
 */
@Composable
fun BadgeTile(
    title: String,
    unlocked: Boolean,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    glyph: String? = null,
) {
    val u by animateFloatAsState(if (unlocked) 1f else 0f, tween(600), label = "badge")
    val violet = AppColors.reward
    val violetDeep = AppColors.rewardDeep
    val muted = AppColors.textMuted
    val outline = AppColors.outline
    val raised = AppColors.surfaceRaised
    Column(
        modifier
            .clip(AppShapes.medium)
            .background(AppColors.surfaceRaised)
            .padding(Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Canvas(Modifier.size(64.dp)) {
            val c = center
            if (unlocked) {
                drawCircle(Brush.linearGradient(listOf(violet, violetDeep)), size.minDimension / 2)
                if (glyph == null) drawPath(starPath(c, size.minDimension * 0.30f, size.minDimension * 0.13f), Color.White.copy(alpha = 0.95f), style = Fill)
                else drawBadgeGlyph(glyph, c, size.minDimension * 0.30f, Color.White.copy(alpha = 0.95f))
            } else if (glyph != null) {
                drawCircle(
                    outline, size.minDimension / 2 - 1.5.dp.toPx(),
                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))),
                )
                drawBadgeGlyph(glyph, c, size.minDimension * 0.27f, muted.copy(alpha = 0.40f))
                val lc = Offset(size.width * 0.80f, size.height * 0.80f)
                drawCircle(raised, 11.dp.toPx(), lc)
                drawLock(lc, 7.dp.toPx(), muted.copy(alpha = 0.85f))
            } else {
                drawCircle(
                    outline, size.minDimension / 2 - 1.5.dp.toPx(),
                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))),
                )
                drawPath(starPath(c, size.minDimension * 0.26f, size.minDimension * 0.11f), muted.copy(alpha = 0.35f), style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
            }
        }
        Text(
            title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, maxLines = 2,
            color = if (unlocked) AppColors.textPrimary else AppColors.textMuted,
            modifier = Modifier.alpha(0.55f + 0.45f * u),
        )
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted, textAlign = TextAlign.Center, maxLines = 2)
    }
}

private fun DrawScope.drawLock(c: Offset, r: Float, color: Color) {
    val w = r * 1.5f
    val h = r * 1.1f
    drawRoundRect(color, Offset(c.x - w / 2, c.y - h / 2 + r * 0.25f), Size(w, h), CornerRadius(r * 0.25f))
    drawArc(
        color, 180f, 180f, false, Offset(c.x - w * 0.3f, c.y - h / 2 - r * 0.3f), Size(w * 0.6f, r * 1.1f),
        style = Stroke(r * 0.28f, cap = StrokeCap.Round),
    )
}

/** Simple badge glyphs for [id], centred on [c] and roughly [r] in radius. Pure Canvas, no icon library. */
private fun DrawScope.drawBadgeGlyph(id: String, c: Offset, r: Float, color: Color) {
    val sw = r * 0.22f
    val line = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(c.x + x * r, c.y + y * r)
    fun seg(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, p(x1, y1), p(x2, y2), sw, StrokeCap.Round)
    fun Path.m(x: Float, y: Float) = moveTo(p(x, y).x, p(x, y).y)
    fun Path.l(x: Float, y: Float) = lineTo(p(x, y).x, p(x, y).y)
    fun Path.cu(a: Float, b: Float, c2: Float, d: Float, e: Float, f: Float) =
        cubicTo(p(a, b).x, p(a, b).y, p(c2, d).x, p(c2, d).y, p(e, f).x, p(e, f).y)
    when (id) {
        "first_gym" -> { // dumbbell
            seg(-0.55f, 0f, 0.55f, 0f)
            seg(-0.55f, -0.5f, -0.55f, 0.5f); seg(0.55f, -0.5f, 0.55f, 0.5f)
            seg(-0.95f, -0.25f, -0.95f, 0.25f); seg(0.95f, -0.25f, 0.95f, 0.25f)
        }
        "streak_4" -> drawPath(Path().apply { // flame
            m(0f, -1f)
            cu(0.2f, -0.5f, 0.8f, -0.2f, 0.65f, 0.35f)
            cu(0.55f, 0.85f, 0.2f, 1f, 0f, 1f)
            cu(-0.4f, 1f, -0.75f, 0.7f, -0.65f, 0.2f)
            cu(-0.55f, -0.1f, -0.35f, -0.2f, -0.3f, -0.55f)
            cu(-0.1f, -0.45f, 0f, -0.7f, 0f, -1f)
            close()
        }, color, style = Fill)
        "sessions_50", "long_walker" -> drawPath(Path().apply { // mountain
            m(-1f, 0.7f); l(-0.3f, -0.5f); l(0.1f, 0.1f); l(0.4f, -0.2f); l(1f, 0.7f); close()
        }, color, style = Fill)
        "early_week", "first_session" -> { // sunrise
            drawArc(color, 180f, 180f, true, p(-0.5f, -0.1f), Size(r, r))
            seg(-1f, 0.4f, 1f, 0.4f)
            seg(0f, -0.65f, 0f, -0.95f); seg(-0.7f, -0.35f, -0.9f, -0.55f); seg(0.7f, -0.35f, 0.9f, -0.55f)
        }
        "comeback" -> drawPath(Path().apply { // heart
            m(0f, 0.85f)
            cu(-1.2f, 0f, -0.8f, -0.9f, 0f, -0.35f)
            cu(0.8f, -0.9f, 1.2f, 0f, 0f, 0.85f)
            close()
        }, color, style = Fill)
        "first_pr" -> { // trophy
            drawPath(Path().apply {
                m(-0.6f, -0.8f); l(0.6f, -0.8f)
                cu(0.6f, -0.1f, 0.35f, 0.25f, 0f, 0.25f)
                cu(-0.35f, 0.25f, -0.6f, -0.1f, -0.6f, -0.8f)
                close()
            }, color, style = Fill)
            drawArc(color, 90f, 180f, false, p(-1f, -0.7f), Size(0.8f * r, 0.8f * r), style = line)
            drawArc(color, -90f, 180f, false, p(0.2f, -0.7f), Size(0.8f * r, 0.8f * r), style = line)
            seg(0f, 0.25f, 0f, 0.65f); seg(-0.45f, 0.85f, 0.45f, 0.85f)
        }
        "first_treadmill" -> { // forward chevrons
            drawPath(Path().apply { m(-0.8f, -0.8f); l(-0.1f, 0f); l(-0.8f, 0.8f) }, color, style = Stroke(sw * 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(Path().apply { m(0.1f, -0.8f); l(0.8f, 0f); l(0.1f, 0.8f) }, color, style = Stroke(sw * 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        "sessions_5" -> drawPath(Path().apply { // check
            m(-0.8f, 0.05f); l(-0.25f, 0.6f); l(0.85f, -0.6f)
        }, color, style = Stroke(sw * 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        "sessions_10" -> drawPath(Path().apply { // bolt
            m(0.2f, -1f); l(-0.6f, 0.15f); l(-0.05f, 0.15f); l(-0.25f, 1f); l(0.65f, -0.2f); l(0.05f, -0.2f); close()
        }, color, style = Fill)
        "sessions_25" -> { // medal
            drawCircle(color, r * 0.55f, p(0f, 0.3f))
            seg(-0.35f, -0.95f, -0.1f, -0.3f); seg(0.35f, -0.95f, 0.1f, -0.3f)
        }
        "squat_100" -> { // weight plate
            drawCircle(color, r * 0.85f, c, style = Stroke(sw * 1.4f))
            drawCircle(color, r * 0.2f, c)
        }
        "all_styles" -> listOf(-0.45f to -0.45f, 0.45f to -0.45f, -0.45f to 0.45f, 0.45f to 0.45f)
            .forEach { (x, y) -> drawCircle(color, r * 0.33f, p(x, y)) }
        "quiet_hero" -> { // moon (crescent)
            val a = Path().apply { addOval(Rect(p(-0.85f, -0.85f), Size(1.7f * r, 1.7f * r))) }
            val b = Path().apply { addOval(Rect(p(-0.25f, -1.0f), Size(1.6f * r, 1.6f * r))) }
            drawPath(Path.combine(PathOperation.Difference, a, b), color, style = Fill)
        }
        else -> drawPath(starPath(c, r, r * 0.43f), color, style = Fill)
    }
}

// ---------------------------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------------------------

/**
 * The hero call to action ("Start workout"): 72dp tall, full width by default, gradient, springy
 * press-scale and a haptic tick. Use at most one per screen.
 */
@Composable
fun BigActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    brush: Brush = AppColors.gradientPrimary,
    contentColor: Color = AppColors.onPrimary,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.95f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "press",
    )
    val haptic = LocalHapticFeedback.current
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.4f }
            .clip(AppShapes.large)
            .background(brush)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = Spacing.xl, vertical = Spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black), color = contentColor, textAlign = TextAlign.Center)
    }
}

/** Quieter 56dp button: raised surface with a subtle border. Same press feel as [BigActionButton]. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = AppColors.textPrimary,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.96f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "press2",
    )
    Box(
        modifier
            .heightIn(min = 56.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.4f }
            .clip(AppShapes.medium)
            .background(AppColors.surfaceRaised)
            .border(1.5.dp, AppColors.outline, AppShapes.medium)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = accent, textAlign = TextAlign.Center)
    }
}

// ---------------------------------------------------------------------------------------------
// Steppers and set rows
// ---------------------------------------------------------------------------------------------

/** Round +/- button that fires once on press, then repeats (accelerating feel) while held. */
@Composable
private fun RepeatRoundButton(plus: Boolean, onStep: () -> Unit, modifier: Modifier = Modifier) {
    val latest by rememberUpdatedState(onStep)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (pressed) 0.88f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "step",
    )
    val glyph = if (plus) AppColors.primary else AppColors.textPrimary
    Box(
        modifier
            .size(Spacing.bigTap)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(if (pressed) AppColors.surfaceHigh else AppColors.surfaceRaised)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    latest()
                    val job = scope.launch {
                        delay(450)
                        var wait = 150L
                        while (true) {
                            latest()
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            delay(wait)
                            wait = max(55L, wait - 10L)
                        }
                    }
                    try { awaitRelease() } finally { job.cancel(); pressed = false }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val sw = 4.dp.toPx()
            drawLine(glyph, Offset(0f, center.y), Offset(size.width, center.y), sw, StrokeCap.Round)
            if (plus) drawLine(glyph, Offset(center.x, 0f), Offset(center.x, size.height), sw, StrokeCap.Round)
        }
    }
}

/**
 * Weight/reps stepper: [-]  BIG VALUE unit  [+]. Both 64dp buttons auto-repeat while held. The
 * value cross-fades with a slide when it changes. Pass the formatted [value] ("62.5", "8").
 * [onValueClick] (optional) lets the caller open a keypad when the number itself is tapped.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun BigNumberStepper(
    value: String,
    unit: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    onValueClick: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (label != null) {
            Text(label.uppercase(), style = AppType.eyebrow, color = AppColors.textMuted)
            Spacer(Modifier.height(Spacing.xs))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            RepeatRoundButton(plus = false, onStep = onMinus)
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = Spacing.bigTap)
                    .then(if (onValueClick != null) Modifier.clickable(onClick = onValueClick) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                AnimatedContent(
                    targetState = value,
                    transitionSpec = {
                        (slideInVertically { it / 2 } + fadeIn(tween(150))) togetherWith (slideOutVertically { -it / 2 } + fadeOut(tween(120)))
                    },
                    label = "stepValue",
                ) { v ->
                    Text(v, style = AppType.numberL, color = AppColors.textPrimary, maxLines = 1)
                }
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(unit, style = MaterialTheme.typography.titleMedium, color = AppColors.textMuted, modifier = Modifier.padding(top = 18.dp))
                }
            }
            RepeatRoundButton(plus = true, onStep = onPlus)
        }
    }
}

/**
 * The 64dp "Done" toggle used by [SetRow]: ring fills mint, tick draws itself, button pops and a
 * ripple ring radiates out. Fires a haptic when completing.
 */
@Composable
fun DoneToggle(done: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val fill by animateFloatAsState(if (done) 1f else 0f, tween(380, easing = FastOutSlowInEasing), label = "doneFill")
    val scale = remember { Animatable(1f) }
    val burst = remember { Animatable(1f) }
    var initialised by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(done) {
        if (initialised && done) {
            burst.snapTo(0f)
            launch { burst.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
            scale.animateTo(1.22f, tween(90))
            scale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow))
        }
        initialised = true
    }
    val mint = AppColors.primary
    val mintDeep = AppColors.primaryDeep
    val raised = AppColors.surfaceHigh
    val outline = AppColors.outline
    Box(
        modifier
            .size(Spacing.bigTap)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .drawBehind {
                val b = burst.value
                if (b < 1f) drawCircle(mint.copy(alpha = 0.5f * (1f - b)), radius = size.minDimension / 2 * (1f + 0.9f * b), style = Stroke(3.dp.toPx()))
            }
            .clip(CircleShape)
            .drawBehind {
                drawCircle(raised)
                drawCircle(Brush.linearGradient(listOf(mint, mintDeep)), radius = size.minDimension / 2 * fill)
                if (fill < 1f) drawCircle(outline, size.minDimension / 2 - 1.5.dp.toPx(), style = Stroke(3.dp.toPx()))
            }
            .toggleable(done, role = Role.Checkbox) {
                if (it) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggle(it)
            },
        contentAlignment = Alignment.Center,
    ) {
        CheckGlyph(fill, AppColors.onPrimary, Modifier.size(36.dp), 5.dp)
    }
}

/**
 * One gym set. Layout: set number badge, weight block, reps block (both tappable if a click
 * handler is given, 56dp+ high), and the [DoneToggle]. Below: RIR chips (reps in reserve).
 * When [done] a mint colour sweep wipes across the card.
 *
 * @param rir selected RIR or null; tapping the selected chip clears it.
 */
@Composable
fun SetRow(
    setNumber: Int,
    weight: String,
    reps: String,
    done: Boolean,
    onDoneChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    weightUnit: String = "kg",
    rir: Int? = null,
    onRirChange: (Int?) -> Unit = {},
    rirOptions: List<Int> = listOf(0, 1, 2, 3, 4),
    onWeightClick: (() -> Unit)? = null,
    onRepsClick: (() -> Unit)? = null,
) {
    val sweep by animateFloatAsState(if (done) 1f else 0f, tween(550, easing = FastOutSlowInEasing), label = "sweep")
    val mint = AppColors.primary
    val haptic = LocalHapticFeedback.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(AppShapes.medium)
            .background(AppColors.surfaceRaised)
            .drawBehind {
                if (sweep > 0f) {
                    drawRect(
                        Brush.horizontalGradient(listOf(mint.copy(alpha = 0.28f), mint.copy(alpha = 0.07f))),
                        size = Size(size.width * sweep, size.height),
                    )
                }
            }
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(if (done) mint.copy(alpha = 0.25f) else AppColors.surfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text("$setNumber", style = MaterialTheme.typography.titleMedium, color = if (done) mint else AppColors.textMuted)
            }
            SetValue("WEIGHT", weight, weightUnit, onWeightClick, Modifier.weight(1f))
            SetValue("REPS", reps, "", onRepsClick, Modifier.weight(0.8f))
            DoneToggle(done, onDoneChange)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text("RIR", style = AppType.eyebrow, color = AppColors.textMuted, modifier = Modifier.width(36.dp), textAlign = TextAlign.Center)
            rirOptions.forEach { opt ->
                val selected = rir == opt
                val bg by animateFloatAsState(if (selected) 1f else 0f, tween(200), label = "rir")
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .clip(AppShapes.chip)
                        .background(lerp(AppColors.surfaceHigh, AppColors.energy, bg))
                        .clickable(role = Role.RadioButton) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onRirChange(if (selected) null else opt)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "$opt", style = MaterialTheme.typography.titleMedium,
                        color = lerp(AppColors.textPrimary, AppColors.onEnergy, bg),
                    )
                }
            }
        }
    }
}

@Composable
private fun SetValue(label: String, value: String, unit: String, onClick: (() -> Unit)?, modifier: Modifier) {
    Column(
        modifier
            .heightIn(min = 56.dp)
            .clip(AppShapes.small)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = AppType.eyebrow, color = AppColors.textMuted)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = AppType.numberM, color = AppColors.textPrimary, maxLines = 1)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(unit, style = MaterialTheme.typography.labelMedium, color = AppColors.textMuted, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Rest timer
// ---------------------------------------------------------------------------------------------

/**
 * Rest countdown bar. Mint while resting, warms to orange in the last 5s, and when
 * [remainingSec] hits 0 shows a flashing "GO" state. [onSkip] adds a 48dp skip button.
 */
@Composable
fun RestTimerBar(
    remainingSec: Int,
    totalSec: Int,
    modifier: Modifier = Modifier,
    onSkip: (() -> Unit)? = null,
) {
    val flashing = remainingSec <= 0
    val target = if (totalSec <= 0) 0f else (remainingSec.toFloat() / totalSec).coerceIn(0f, 1f)
    val frac by animateFloatAsState(target, tween(if (remainingSec == totalSec) 0 else 1000, easing = LinearEasing), label = "rest")
    val flash by rememberInfiniteTransition(label = "flash").animateFloat(
        0f, 1f, infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Reverse), label = "f",
    )
    val warm = remainingSec in 1..5
    val barBrush = if (warm || flashing) AppColors.gradientEnergy else AppColors.gradientPrimary
    val tint = if (warm || flashing) AppColors.energy else AppColors.primary
    Column(
        modifier
            .fillMaxWidth()
            .clip(AppShapes.medium)
            .background(if (flashing) tint.copy(alpha = 0.12f + 0.18f * flash) else AppColors.surfaceRaised)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (flashing) "REST OVER" else "REST", style = AppType.eyebrow, color = AppColors.textMuted, modifier = Modifier.weight(1f))
            Text(
                if (flashing) "GO!" else formatClock(remainingSec),
                style = AppType.numberM, color = tint,
                modifier = Modifier.alpha(if (flashing) 0.6f + 0.4f * flash else 1f),
            )
            if (onSkip != null) {
                Spacer(Modifier.width(Spacing.md))
                Box(
                    Modifier.heightIn(min = Spacing.minTap).clip(AppShapes.chip).background(AppColors.surfaceHigh)
                        .clickable(role = Role.Button, onClick = onSkip).padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(if (flashing) "Next" else "Skip", style = MaterialTheme.typography.labelLarge, color = AppColors.textPrimary) }
            }
        }
        Box(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(AppColors.track)) {
            Box(Modifier.fillMaxWidth(if (flashing) 1f else frac).height(12.dp).clip(CircleShape).background(barBrush))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Celebration, XP, numbers
// ---------------------------------------------------------------------------------------------

private class Particle(
    val angle: Float, val speed: Float, val color: Color, val size: Float, val spin: Float, val round: Boolean,
)

/**
 * Lightweight confetti. Place it in a full-size Box above content (it never consumes touches).
 * Every time [trigger] changes to a value > 0 a new burst plays from just above the centre.
 * Typical use: increment an Int when a workout completes or a badge unlocks.
 */
@Composable
fun ConfettiBurst(
    trigger: Int,
    modifier: Modifier = Modifier,
    particleCount: Int = 80,
    durationMillis: Int = 1600,
    colors: List<Color> = listOf(AppColors.primary, AppColors.energy, AppColors.reward, AppColors.info, Color.White),
) {
    val progress = remember { Animatable(1f) }
    var particles by remember { mutableStateOf<List<Particle>>(emptyList()) }
    LaunchedEffect(trigger) {
        if (trigger <= 0) return@LaunchedEffect
        val rnd = Random(trigger * 7919)
        particles = List(particleCount) {
            Particle(
                angle = rnd.nextFloat() * (2 * PI).toFloat(),
                speed = 0.35f + rnd.nextFloat() * 0.65f,
                color = colors[rnd.nextInt(colors.size)],
                size = 8f + rnd.nextFloat() * 10f,
                spin = (rnd.nextFloat() - 0.5f) * 1400f,
                round = rnd.nextFloat() < 0.3f,
            )
        }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis, easing = LinearEasing))
    }
    Canvas(modifier.fillMaxSize()) {
        val t = progress.value
        if (t >= 1f || particles.isEmpty()) return@Canvas
        val origin = Offset(size.width / 2, size.height * 0.38f)
        val reach = size.minDimension * 0.85f
        val ease = 1f - (1f - t) * (1f - t) * (1f - t)
        val fade = 1f - ((t - 0.65f) / 0.35f).coerceIn(0f, 1f)
        val density = this.density
        particles.forEach { p ->
            val x = origin.x + cos(p.angle) * p.speed * reach * ease
            val y = origin.y + sin(p.angle) * p.speed * reach * ease + t * t * size.height * 0.55f
            val s = p.size * density
            val color = p.color.copy(alpha = fade)
            if (p.round) drawCircle(color, s / 2.2f, Offset(x, y))
            else rotate(p.spin * t, Offset(x, y)) {
                val flip = 0.35f + 0.65f * abs(cos(t * 9f + p.angle))
                drawRect(color, Offset(x - s / 2, y - s * flip / 4), Size(s, s * flip / 2))
            }
        }
    }
}

/**
 * Level + XP progress. Violet badge with the [level] number, [levelName] title, a gradient bar
 * showing [xpInto]/[xpForNext] and a caption of XP remaining.
 */
@Composable
fun XpBar(
    xpInto: Int,
    xpForNext: Int,
    level: Int,
    levelName: String,
    modifier: Modifier = Modifier,
    showName: Boolean = true,
) {
    val target = if (xpForNext <= 0) 1f else (xpInto.toFloat() / xpForNext).coerceIn(0f, 1f)
    val frac by animateFloatAsState(target, tween(900, easing = FastOutSlowInEasing), label = "xp")
    Row(
        modifier.fillMaxWidth().clip(AppShapes.medium).background(AppColors.surfaceRaised).padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(AppColors.gradientReward),
            contentAlignment = Alignment.Center,
        ) { Text("$level", style = AppType.numberM, color = Color.White) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(if (showName) levelName else "To level ${level + 1}", style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary, modifier = Modifier.weight(1f), maxLines = 1)
                Text("$xpInto / $xpForNext XP", style = MaterialTheme.typography.labelMedium, color = AppColors.reward)
            }
            Box(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(AppColors.track)) {
                Box(Modifier.fillMaxWidth(frac).height(12.dp).clip(CircleShape).background(AppColors.gradientReward))
            }
        }
    }
}

/** Counts smoothly to [value] whenever it changes. Customise display with [format]. */
@Composable
fun AnimatedNumber(
    value: Float,
    modifier: Modifier = Modifier,
    style: TextStyle = AppType.numberXL,
    color: Color = AppColors.textPrimary,
    durationMillis: Int = 800,
    format: (Float) -> String = { it.roundToInt().toString() },
) {
    val v by animateFloatAsState(value, tween(durationMillis, easing = FastOutSlowInEasing), label = "num")
    Text(format(v), modifier = modifier, style = style, color = color, maxLines = 1)
}

// ---------------------------------------------------------------------------------------------
// Line chart
// ---------------------------------------------------------------------------------------------

/**
 * One line of a [LineChart]. [values] aligns index-for-index with the chart's x labels.
 * Optional [min]/[max] (same length) draw a translucent band around the line (forecast range).
 */
class Series(
    val name: String,
    val values: List<Float>,
    val color: Color,
    val min: List<Float>? = null,
    val max: List<Float>? = null,
    val dashed: Boolean = false,
)

private fun Path.smoothTo(points: List<Offset>, startNew: Boolean) {
    if (points.isEmpty()) return
    if (startNew) moveTo(points[0].x, points[0].y) else lineTo(points[0].x, points[0].y)
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        val mx = (a.x + b.x) / 2
        cubicTo(mx, a.y, mx, b.y, b.x, b.y)
    }
}

/** A "nice" tick step (1, 2, 2.5, 5 x 10^n) giving roughly [target] steps across [range]. */
private fun niceStep(range: Float, target: Int): Float {
    val raw = (range / target).coerceAtLeast(1e-6f)
    val mag = Math.pow(10.0, Math.floor(Math.log10(raw.toDouble()))).toFloat()
    val f = raw / mag
    val m = when { f <= 1f -> 1f; f <= 2f -> 2f; f <= 2.5f -> 2.5f; f <= 5f -> 5f; else -> 10f }
    return m * mag
}

/**
 * Simple animated line chart (reveals left to right). Supports several [series] (e.g.
 * conservative/expected/best scenarios), an optional min/max band per series, rounded "nice" y ticks
 * formatted with [yFormat] (the left gutter is sized to the widest label, so nothing clips), subtle
 * horizontal grid lines, thinned x labels and, when [endLabels] is on, a coloured value label at the
 * end of each line (formatted with [endFormat], default [yFormat]). A legend is rendered below.
 */
@Composable
fun LineChart(
    series: List<Series>,
    xLabels: List<String>,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    yFormat: (Float) -> String = { it.roundToInt().toString() },
    showLegend: Boolean = true,
    endLabels: Boolean = true,
    endFormat: ((Float) -> String)? = null,
) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(series) { reveal.snapTo(0f); reveal.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
    val measurer = rememberTextMeasurer()
    val muted = AppColors.textMuted
    val grid = AppColors.outline.copy(alpha = 0.45f)
    val labelStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = muted)
    val hasData = series.any { it.values.isNotEmpty() }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (!hasData) {
            Box(Modifier.fillMaxWidth().height(height / 2), contentAlignment = Alignment.Center) {
                Text("Nothing to chart yet", style = MaterialTheme.typography.bodyMedium, color = muted)
            }
        } else Canvas(Modifier.fillMaxWidth().height(height)) {
            val all = series.flatMap { it.values + (it.min ?: emptyList()) + (it.max ?: emptyList()) }
            var lo = all.min()
            var hi = all.max()
            if (hi - lo < 1e-3f) { hi += 1f; lo -= 1f }
            val pad = (hi - lo) * 0.08f
            lo -= pad; hi += pad
            val step = niceStep(hi - lo, 3)
            val firstTick = ceil(lo / step) * step
            val ticks = generateSequence(firstTick) { it + step }.takeWhile { it <= hi + 1e-4f }.toList()
            val tickLabels = ticks.map { measurer.measure(yFormat(it), labelStyle) }
            val endText = series.map { s ->
                s.values.lastOrNull()?.let { measurer.measure((endFormat ?: yFormat)(it), labelStyle.copy(color = s.color)) }
            }
            val n = series.maxOf { it.values.size }.coerceAtLeast(2)
            val left = (tickLabels.maxOfOrNull { it.size.width } ?: 0) + 10.dp.toPx()
            val right = if (endLabels) (endText.maxOfOrNull { it?.size?.width ?: 0 } ?: 0) + 12.dp.toPx() else 8.dp.toPx()
            val bottom = 24.dp.toPx()
            val top = 10.dp.toPx()
            val plotW = size.width - left - right
            val plotH = size.height - top - bottom
            fun px(i: Int) = left + plotW * i / (n - 1)
            fun py(v: Float) = top + plotH * (1f - (v - lo) / (hi - lo))

            // grid + y labels
            ticks.forEachIndexed { k, v ->
                val y = py(v)
                drawLine(grid, Offset(left, y), Offset(size.width - right, y), 1.dp.toPx())
                val tl = tickLabels[k]
                drawText(tl, topLeft = Offset(left - 8.dp.toPx() - tl.size.width, y - tl.size.height / 2f))
            }
            // x labels
            val stride = ceil(xLabels.size / 5f).toInt().coerceAtLeast(1)
            xLabels.forEachIndexed { i, s ->
                if (i % stride == 0 || i == xLabels.lastIndex && (xLabels.size - 1) % stride == 0) {
                    val tl = measurer.measure(s, labelStyle)
                    val x = (px(i) - tl.size.width / 2f).coerceIn(0f, size.width - tl.size.width)
                    drawText(tl, topLeft = Offset(x, size.height - bottom + 6.dp.toPx()))
                }
            }
            clipRect(right = left + plotW * reveal.value + right) {
                series.forEach { s ->
                    val pts = s.values.mapIndexed { i, v -> Offset(px(i), py(v)) }
                    if (s.min != null && s.max != null) {
                        val upper = s.max.mapIndexed { i, v -> Offset(px(i), py(v)) }
                        val lower = s.min.mapIndexed { i, v -> Offset(px(i), py(v)) }
                        val band = Path().apply {
                            smoothTo(upper, true)
                            smoothTo(lower.reversed(), false)
                            close()
                        }
                        drawPath(band, s.color.copy(alpha = 0.16f), style = Fill)
                    }
                    val line = Path().apply { smoothTo(pts, true) }
                    drawPath(
                        line, s.color,
                        style = Stroke(
                            3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                            pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 9.dp.toPx())) else null,
                        ),
                    )
                    pts.lastOrNull()?.let { drawCircle(s.color, 5.dp.toPx(), it) }
                }
            }
            if (endLabels) {
                // end-of-line labels, nudged apart so close lines stay readable
                val items = series.mapIndexedNotNull { i, s ->
                    val t = endText[i] ?: return@mapIndexedNotNull null
                    Triple(t, py(s.values.last()), px(s.values.size - 1))
                }.sortedBy { it.second }
                var prev = -1e9f
                val alpha = ((reveal.value - 0.8f) / 0.2f).coerceIn(0f, 1f)
                items.forEach { (t, y0, x0) ->
                    val y = maxOf(y0, prev + t.size.height + 1.dp.toPx())
                    prev = y
                    drawText(t, topLeft = Offset(x0 + 9.dp.toPx(), y - t.size.height / 2f), alpha = alpha)
                }
            }
        }
        if (showLegend && series.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                series.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(s.color))
                        Text(s.name, style = MaterialTheme.typography.labelMedium, color = AppColors.textMuted)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Treadmill
// ---------------------------------------------------------------------------------------------

/**
 * Giant-number layout for the treadmill player: speed fills the width at 128sp, with incline and
 * elapsed/distance tiles beneath. Pure display; pair with [BigNumberStepper]s for control.
 * Speed animates when it changes.
 */
@Composable
fun TreadmillReadout(
    speed: Float,
    incline: Float,
    modifier: Modifier = Modifier,
    elapsed: String? = null,
    distanceKm: Float? = null,
    phaseLabel: String? = null,
    speedUnit: String = "km/h",
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        if (phaseLabel != null) Pill(phaseLabel, color = AppColors.energy)
        Text("SPEED", style = AppType.eyebrow, color = AppColors.textMuted)
        Row(verticalAlignment = Alignment.Bottom) {
            AnimatedNumber(
                speed, style = AppType.numberHero.copy(brush = AppColors.gradientPrimary),
                color = Color.Unspecified, durationMillis = 350, format = { "%.1f".format(it) },
            )
            Spacer(Modifier.width(8.dp))
            Text(speedUnit, style = MaterialTheme.typography.titleLarge, color = AppColors.textMuted, modifier = Modifier.padding(bottom = 22.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            StatTile("Incline", "%.1f".format(incline), Modifier.weight(1f), unit = "%", accent = AppColors.info)
            if (elapsed != null) StatTile("Time", elapsed, Modifier.weight(1f), accent = AppColors.energy)
            if (distanceKm != null) StatTile("Distance", "%.2f".format(distanceKm), Modifier.weight(1f), unit = "km", accent = AppColors.reward)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Previews (see Gallery.kt for the full scrolling gallery)
// ---------------------------------------------------------------------------------------------

@Composable
private fun PreviewFrame(content: @Composable ColumnScope.() -> Unit) {
    TrainingTheme {
        Column(Modifier.background(AppColors.background).padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.md), content = content)
    }
}

@Preview(name = "ProgressRing", widthDp = 360, heightDp = 300)
@Composable
private fun PreviewProgressRing() = PreviewFrame {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        ProgressRing(0.72f, bonusProgress = 0.35f) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("72%", style = AppType.numberXL, color = AppColors.textPrimary)
                Text("of weekly goal", style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
            }
        }
    }
}

@Preview(name = "Hero + stats", widthDp = 360, heightDp = 420)
@Composable
private fun PreviewHero() = PreviewFrame {
    HeroCard {
        Text("THIS WEEK", style = AppType.eyebrow, color = AppColors.onHero.copy(alpha = 0.8f))
        Text("3 of 4 done", style = MaterialTheme.typography.headlineLarge, color = AppColors.onHero)
        Spacer(Modifier.height(Spacing.md))
        Pill("5 day streak", color = AppColors.energy, filled = true)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        StatTile("Volume", "12.4", Modifier.weight(1f), unit = "t")
        StatTile("Distance", "18.2", Modifier.weight(1f), unit = "km", accent = AppColors.energy)
    }
    SectionCard(title = "Section card", action = { Pill("New", color = AppColors.reward) }) {
        Text("Grouped content lives here.", color = AppColors.textMuted)
    }
}

@Preview(name = "Week strip", widthDp = 360, heightDp = 120)
@Composable
private fun PreviewWeek() = PreviewFrame {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        DayDot("M", DayState.Done); DayDot("T", DayState.Rest); DayDot("W", DayState.RestTrained)
        DayDot("T", DayState.PastEmpty); DayDot("F", DayState.Today); DayDot("S", DayState.Open); DayDot("S", DayState.Rest)
    }
}

@Preview(name = "Badges", widthDp = 360, heightDp = 180)
@Composable
private fun PreviewBadges() = PreviewFrame {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        BadgeTile("First week", true, Modifier.weight(1f))
        BadgeTile("10 sessions", false, Modifier.weight(1f), subtitle = "6 to go")
    }
}

@Preview(name = "Buttons", widthDp = 360, heightDp = 240)
@Composable
private fun PreviewButtons() = PreviewFrame {
    BigActionButton("Start workout", {})
    SecondaryButton("Swap exercise", {}, Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SelectChip("Easy", true, {}); SelectChip("Good", false, {}); SelectChip("Hard", false, {})
    }
}

@Preview(name = "Stepper + SetRow", widthDp = 360, heightDp = 420)
@Composable
private fun PreviewSets() = PreviewFrame {
    BigNumberStepper("62.5", "kg", {}, {}, label = "Weight")
    SetRow(1, "60", "8", done = true, onDoneChange = {}, rir = 2)
    SetRow(2, "62.5", "8", done = false, onDoneChange = {})
}

@Preview(name = "Rest timer", widthDp = 360, heightDp = 260)
@Composable
private fun PreviewRest() = PreviewFrame {
    RestTimerBar(72, 120, onSkip = {})
    RestTimerBar(3, 120)
    RestTimerBar(0, 120, onSkip = {})
}

@Preview(name = "XP + number", widthDp = 360, heightDp = 200)
@Composable
private fun PreviewXp() = PreviewFrame {
    XpBar(340, 500, 4, "Gym Regular")
    AnimatedNumber(1280f, format = { "%,d".format(it.roundToInt()) })
}

@Preview(name = "LineChart", widthDp = 360, heightDp = 320)
@Composable
private fun PreviewChart() = PreviewFrame {
    val x = listOf("Now", "W4", "W8", "W12", "W16", "W20", "W24")
    LineChart(
        listOf(
            Series("Conservative", listOf(90f, 89f, 88f, 87.2f, 86.5f, 85.9f, 85.3f), AppColors.info, dashed = true),
            Series(
                "Expected", listOf(90f, 88.5f, 87f, 85.5f, 84.2f, 83f, 82f), AppColors.primary,
                min = listOf(90f, 89f, 87.8f, 86.5f, 85.3f, 84.2f, 83.2f), max = listOf(90f, 88f, 86.2f, 84.5f, 83f, 81.8f, 80.7f),
            ),
            Series("Best", listOf(90f, 87.8f, 85.6f, 83.7f, 82f, 80.4f, 79f), AppColors.energy, dashed = true),
        ),
        x, yFormat = { "%.0f".format(it) },
    )
}

@Preview(name = "Treadmill", widthDp = 360, heightDp = 420)
@Composable
private fun PreviewTreadmill() = PreviewFrame {
    TreadmillReadout(speed = 9.5f, incline = 2.0f, elapsed = "12:34", distanceKm = 2.01f, phaseLabel = "Interval 2 of 6")
}

@Preview(name = "Light theme", widthDp = 360, heightDp = 300)
@Composable
private fun PreviewLight() {
    TrainingTheme(darkTheme = false) {
        Column(Modifier.background(AppColors.background).padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            HeroCard { Text("Light hero", style = MaterialTheme.typography.headlineMedium, color = AppColors.onHero) }
            BigActionButton("Start workout", {})
            SetRow(1, "60", "8", done = true, onDoneChange = {})
        }
    }
}
