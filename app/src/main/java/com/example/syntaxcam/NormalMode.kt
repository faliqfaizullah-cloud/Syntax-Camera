package com.example.syntaxcam

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val Yel = Color(0xFFD9DD1C)
private val PanelBg = Color(0xFF0A0A0A)
private val Chip = Color(0xFF1E1E1E)
private val Wide = FontFamily.SansSerif

private fun mmss(sec: Int) = String.format("%d:%02d", sec / 60, sec % 60)

@Composable
private fun WText(text: String, size: Int, color: Color, ls: Float = 2f, modifier: Modifier = Modifier) {
    Text(text, modifier, color = color, fontFamily = Wide, fontWeight = FontWeight.Black, fontSize = size.sp, letterSpacing = ls.sp)
}

/**
 * Normal camera: full-quality viewfinder with PHOTO / VIDEO, grid, AF badge and focal length.
 * Tap the style tile to open the style editor: NUANCE (colour) and MATERIAL (texture), card decks and a strength slider.
 */
@Composable
fun NormalModeScreen(
    frame: Bitmap?,
    style: StyleSpec,
    onCommit: (StyleSpec) -> Unit,
    onDraft: (StyleSpec?) -> Unit,
    zoom: Float, zoomMin: Float, zoomMax: Float,
    onZoomTo: (Float) -> Unit,
    focusPt: Offset?,
    onFocus: (Offset, IntSize, Boolean) -> Unit,
    onFlip: () -> Unit,
    recording: Boolean, recSec: Int,
    onShutter: (Boolean) -> Unit,
    onExit: () -> Unit,
    haptics: Haptics
) {
    var video by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(style) }
    var tab by remember { mutableIntStateOf(0) }
    var afHold by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    val vf by animateFloatAsState(if (editing) 0.61f else 0.78f, spring(dampingRatio = 0.85f, stiffness = 220f), label = "vf")
    val ctlA by animateFloatAsState(if (editing) 0f else 1f, tween(220), label = "ctl")
    val zoomNow by rememberUpdatedState(zoom)
    val zoomTo by rememberUpdatedState(onZoomTo)
    val focusNow by rememberUpdatedState(onFocus)

    val closeEditor: (Boolean) -> Unit = { keep ->
        if (keep) onCommit(draft)
        editing = false
        onDraft(null)
    }
    BackHandler { if (editing) closeEditor(false) else onExit() }

    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } }) {

        // ---------------- viewfinder ----------------
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().fillMaxHeight(vf).clipToBounds()
                .pointerInput(Unit) { detectTransformGestures { _, _, z, _ -> zoomTo(zoomNow * z) } }
                .pointerInput(Unit) { detectTapGestures { off -> focusNow(off, this.size, afHold) } }
        ) {
            frame?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Canvas(Modifier.fillMaxSize()) {
                val c = Color(0x66FFFFFF); val sw = 1.dp.toPx()
                for (i in 1..2) {
                    drawLine(c, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), sw)
                    drawLine(c, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), sw)
                }
                focusPt?.let { p -> drawCircle(Yel, 34.dp.toPx(), p, style = Stroke(2.5.dp.toPx())) }
            }
            Row(
                Modifier.fillMaxWidth().displayCutoutPadding().padding(top = 52.dp, start = 16.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.width(64.dp)) { FxChip { haptics.select(); onExit() } }
                Spacer(Modifier.weight(1f))
                ModePill(video) { video = it; haptics.tick() }
                Spacer(Modifier.weight(1f))
                Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterEnd) {
                    AfBadge(afHold, focusPt != null) { afHold = !afHold; haptics.toggle(afHold) }
                }
            }
            val presets = listOf(0.5f, 1f, 2f, 3f, 5f).filter { it >= zoomMin - 0.01f && it <= zoomMax + 0.01f }
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp)
                    .frostedGlass(RoundedCornerShape(30.dp), false, darken = 0.35f, milk = 0f)
                    .clickable {
                        haptics.tick()
                        val next = presets.firstOrNull { it > zoom + 0.05f } ?: presets.firstOrNull() ?: 1f
                        zoomTo(next)
                    }
                    .padding(horizontal = 26.dp, vertical = 14.dp)
            ) { WText("${(24f * zoom).roundToInt()}mm", 20, Yel, 0.5f) }
        }

        // ---------------- bottom panel ----------------
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(1f - vf).background(PanelBg)) {
            if (ctlA > 0.01f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = ctlA }) {
                Row(
                    Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 30.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        Modifier.size(64.dp).clickable { editing = true; draft = style; tab = 0; onDraft(draft); haptics.select() },
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(Modifier.size(58.dp)) {
                            drawStyleCard(size.width / 2f, size.height / 2f, size.minDimension * 0.92f,
                                Styles.nuanceColors[style.nuance.coerceIn(0, 5)], style.material, 1f, false)
                        }
                    }
                    ShutterButton(video, recording) { onShutter(video) }
                    Box(Modifier.size(56.dp).clip(CircleShape).background(Chip).clickable { onFlip() }, contentAlignment = Alignment.Center) {
                        Text("\u27F2", color = Color.White, fontSize = 22.sp)
                    }
                }
                if (recording) Row(Modifier.align(Alignment.TopCenter).padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFFFF3B30)))
                    Spacer(Modifier.width(8.dp))
                    WText(mmss(recSec), 13, Color.White, 1f)
                }
            }
            if (ctlA < 0.99f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - ctlA }) {
                EditorPanel(
                    spec = draft, tab = tab, onTab = { tab = it; haptics.tick() },
                    onSpec = { draft = it; onDraft(it) },
                    onCancel = { haptics.select(); closeEditor(false) },
                    onConfirm = { haptics.confirm(); closeEditor(true) },
                    onHelp = { haptics.tick(); showHelp = true },
                    onReset = {
                        haptics.heavy()
                        val d = if (tab == 0) draft.copy(nuance = 0, nuanceAmt = 0.5f) else draft.copy(material = 0, materialAmt = 0.4f)
                        draft = d; onDraft(d)
                    },
                    haptics = haptics
                )
            }
        }

        if (showHelp) AlertDialog(
            onDismissRequest = { showHelp = false },
            confirmButton = { TextButton(onClick = { showHelp = false }) { WText("GOT IT", 13, Yel, 1.5f) } },
            title = { WText("STYLES", 16, Color.White, 3f) },
            text = {
                Text(
                    "NUANCE changes the colour of your photos.\nMATERIAL changes the surface texture.\n\n" +
                        "Swipe the cards to choose one, drag the slider to set how strong it is. " +
                        "\u2715 cancels, \u2713 keeps the style. \u21BB resets the tab you are on.",
                    color = Color(0xFFCFCFCF), fontSize = 14.sp
                )
            },
            containerColor = Color(0xFF151515)
        )
    }
}

// ============================================================ viewfinder bits

@Composable
private fun FxChip(onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).frostedGlass(CircleShape, false, darken = 0.30f, milk = 0f).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { WText("FX", 13, Color.White, 1f) }
}

@Composable
private fun ModePill(video: Boolean, onSelect: (Boolean) -> Unit) {
    val x by animateFloatAsState(if (video) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 380f), label = "mode")
    Box(Modifier.height(48.dp).width(208.dp).frostedGlass(RoundedCornerShape(24.dp), false, darken = 0.30f, milk = 0f).padding(4.dp)) {
        Box(Modifier.offset(x = (100f * x).dp).width(100.dp).fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(Color(0x40FFFFFF)))
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(100.dp).fillMaxHeight().clickable { onSelect(false) }, contentAlignment = Alignment.Center) {
                WText("PHOTO", 14, if (!video) Color.White else Color(0x99FFFFFF), 2f)
            }
            Box(Modifier.width(100.dp).fillMaxHeight().clickable { onSelect(true) }, contentAlignment = Alignment.Center) {
                WText("VIDEO", 14, if (video) Color.White else Color(0x99FFFFFF), 2f)
            }
        }
    }
}

@Composable
private fun AfBadge(hold: Boolean, active: Boolean, onClick: () -> Unit) {
    val s by animateFloatAsState(if (active) 1.12f else 1f, spring(dampingRatio = 0.4f, stiffness = 400f), label = "af")
    Box(
        Modifier.graphicsLayer { scaleX = s; scaleY = s }.width(64.dp).height(40.dp)
            .clip(RoundedCornerShape(20.dp)).background(Color(0x66000000))
            .border(2.5.dp, Yel, RoundedCornerShape(20.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { WText(if (hold) "AF-L" else "AF", 17, Yel, 1f) }
}

@Composable
private fun ShutterButton(video: Boolean, recording: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(80.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        when {
            recording -> Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFFF3B30)))
            video -> Box(Modifier.size(62.dp).clip(CircleShape).background(Color(0xFFFF3B30)))
            else -> Box(Modifier.size(62.dp).clip(CircleShape).background(Color.White))
        }
    }
}

// ============================================================ editor panel

@Composable
private fun EditorPanel(
    spec: StyleSpec, tab: Int, onTab: (Int) -> Unit, onSpec: (StyleSpec) -> Unit,
    onCancel: () -> Unit, onConfirm: () -> Unit, onHelp: () -> Unit, onReset: () -> Unit,
    haptics: Haptics
) {
    val canReset = if (tab == 0) (spec.nuance != 0 || spec.nuanceAmt != 0.5f) else (spec.material != 0 || spec.materialAmt != 0.4f)
    val amtEnabled = if (tab == 0) spec.nuance != 0 else spec.material != 5
    val amt = if (tab == 0) spec.nuanceAmt else spec.materialAmt
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleBtn(onHelp) { WText("?", 20, Color(0xFFE0E0E0), 0f) }
            Spacer(Modifier.weight(1f))
            TabPill(tab, onTab)
            Spacer(Modifier.weight(1f))
            CircleBtn(onReset, enabled = canReset) {
                Canvas(Modifier.size(24.dp)) {
                    val c = if (canReset) Color(0xFFC8C8C8) else Color(0xFF4C4C4C)
                    val r = size.minDimension * 0.36f; val ctr = Offset(size.width / 2f, size.height / 2f)
                    drawArc(c, -50f, 290f, false, Offset(ctr.x - r, ctr.y - r), Size(2f * r, 2f * r), style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round))
                    val ang = Math.toRadians(240.0)
                    val tip = Offset(ctr.x + r * cos(ang).toFloat(), ctr.y + r * sin(ang).toFloat())
                    val tx = (-sin(ang)).toFloat(); val ty = cos(ang).toFloat()
                    for (sgn in listOf(-1f, 1f)) {
                        val a = Math.toRadians(150.0 * sgn)
                        val vx = tx * cos(a).toFloat() - ty * sin(a).toFloat()
                        val vy = tx * sin(a).toFloat() + ty * cos(a).toFloat()
                        drawLine(c, tip, Offset(tip.x + vx * 6.dp.toPx(), tip.y + vy * 6.dp.toPx()), 2.2.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1C1C1C)))
        StyleDeck(Modifier.fillMaxWidth().weight(1f), tab, spec, onSpec, haptics)
        StyleSlider(amt, amtEnabled, { v -> onSpec(if (tab == 0) spec.copy(nuanceAmt = v) else spec.copy(materialAmt = v)) }, haptics)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleBtn(onCancel) {
                Canvas(Modifier.size(22.dp)) {
                    val c = Color(0xFFE5334F); val sw = 2.4.dp.toPx()
                    drawLine(c, Offset(size.width * 0.12f, size.height * 0.12f), Offset(size.width * 0.88f, size.height * 0.88f), sw, StrokeCap.Round)
                    drawLine(c, Offset(size.width * 0.88f, size.height * 0.12f), Offset(size.width * 0.12f, size.height * 0.88f), sw, StrokeCap.Round)
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { WText(spec.label, 14, Color(0xFFD0D0D0), 3f) }
            CircleBtn(onConfirm) {
                Canvas(Modifier.size(24.dp)) {
                    val p = Path().apply {
                        moveTo(size.width * 0.12f, size.height * 0.55f); lineTo(size.width * 0.40f, size.height * 0.82f)
                        lineTo(size.width * 0.90f, size.height * 0.20f)
                    }
                    drawPath(p, Yel, style = Stroke(2.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }
}

@Composable
private fun CircleBtn(onClick: () -> Unit, enabled: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(Chip).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center, content = content
    )
}

@Composable
private fun TabPill(tab: Int, onTab: (Int) -> Unit) {
    val hx by animateFloatAsState(tab.toFloat(), spring(dampingRatio = 0.7f, stiffness = 380f), label = "tab")
    Box(Modifier.height(54.dp).width(258.dp).clip(RoundedCornerShape(27.dp)).background(Color(0xFF171717)).padding(4.dp)) {
        Box(Modifier.offset(x = (118f * hx).dp).width((118f + 14f * hx).dp).fillMaxHeight().clip(RoundedCornerShape(23.dp)).background(Color(0xFF2B2B2B)))
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(118.dp).fillMaxHeight().clickable { onTab(0) }, contentAlignment = Alignment.Center) {
                WText("NUANCE", 15, if (tab == 0) Yel else Color(0xFFC4C4C4), 2f)
            }
            Box(Modifier.width(132.dp).fillMaxHeight().clickable { onTab(1) }, contentAlignment = Alignment.Center) {
                WText("MATERIAL", 15, if (tab == 1) Yel else Color(0xFFC4C4C4), 2f)
            }
        }
    }
}

// ============================================================ deck of style cards

/** [cx, cy, size, alpha] of the card whose distance from the front is [d] (0 = front, 1 = next one behind, ...). */
private fun cardGeom(d: Float, w: Float, h: Float): FloatArray {
    val s0 = min(0.245f * w, h * 0.80f)
    val scale = if (d >= 0f) 1f - 0.075f * d else 1f
    val size = s0 * scale
    val cx = if (d >= 0f) w / 2f + s0 / 2f + d * 0.068f * w - size / 2f else w / 2f + d * s0 * 1.1f
    val alpha = if (d >= 0f) (1f - 0.2f * d).coerceIn(0.08f, 1f) else (1f + d * 1.2f).coerceIn(0f, 1f)
    return floatArrayOf(cx, h / 2f, size, alpha)
}

@Composable
private fun StyleDeck(modifier: Modifier, tab: Int, spec: StyleSpec, onSpec: (StyleSpec) -> Unit, haptics: Haptics) {
    val count = if (tab == 0) Styles.nuanceNames.size else Styles.materialNames.size
    val selIdx = if (tab == 0) spec.nuance else spec.material
    val pos = remember { Animatable(selIdx.toFloat()) }
    val scope = rememberCoroutineScope()
    val specNow by rememberUpdatedState(spec)
    val onSpecNow by rememberUpdatedState(onSpec)
    var lastTick by remember { mutableIntStateOf(selIdx) }
    LaunchedEffect(tab, selIdx) { pos.animateTo(selIdx.toFloat(), spring(dampingRatio = 0.62f, stiffness = 260f)) }
    fun select(i: Int) {
        val s = specNow
        val ns = if (tab == 0) s.copy(nuance = i) else s.copy(material = i)
        if (ns != s) onSpecNow(ns)
    }
    Canvas(
        modifier
            .pointerInput(tab) {
                detectHorizontalDragGestures(
                    onDragEnd = { select(pos.value.roundToInt().coerceIn(0, count - 1)) },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        val np = (pos.value - dx / (0.20f * this.size.width)).coerceIn(0f, count - 1f)
                        scope.launch { pos.snapTo(np) }
                        val r = np.roundToInt()
                        if (r != lastTick) { lastTick = r; haptics.tick() }
                    }
                )
            }
            .pointerInput(tab) {
                detectTapGestures { off ->
                    val w = this.size.width.toFloat(); val h = this.size.height.toFloat()
                    val order = (0 until count).sortedBy { abs(it - pos.value) }
                    for (i in order) {
                        val d = i - pos.value
                        if (d < -0.6f || d > 4.6f) continue
                        val g = cardGeom(d, w, h)
                        if (abs(off.x - g[0]) <= g[2] / 2f && abs(off.y - g[1]) <= g[2] / 2f) {
                            if (i != specNow.let { if (tab == 0) it.nuance else it.material }) haptics.tick()
                            select(i); break
                        }
                    }
                }
            }
    ) {
        val w = size.width; val h = size.height
        val nuanceColor = Styles.nuanceColors[spec.nuance.coerceIn(0, 5)]
        for (i in count - 1 downTo 0) {
            val d = i - pos.value
            if (d < -1f || d > 4.6f) continue
            val g = cardGeom(d, w, h)
            val color = if (tab == 0) Styles.nuanceColors[i] else nuanceColor
            val mat = if (tab == 0) spec.material else i
            drawStyleCard(g[0], g[1], g[2], color, mat, g[3], abs(d) < 0.5f)
        }
    }
}

/** One swatch: tinted rounded square with its surface pattern (dots, grid, lines, ...). */
private fun DrawScope.drawStyleCard(cx: Float, cy: Float, size: Float, color: Color, material: Int, alpha: Float, front: Boolean) {
    if (alpha <= 0.01f || size <= 2f) return
    val tl = Offset(cx - size / 2f, cy - size / 2f)
    val cr = CornerRadius(size * 0.24f)
    if (front) {
        val gc = Offset(cx, cy + size * 0.42f)
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.32f * alpha), Color.Transparent), gc, size * 0.95f), size * 0.95f, gc)
    }
    drawRoundRect(
        Brush.verticalGradient(
            listOf(lerp(color, Color.White, 0.14f).copy(alpha = 0.78f * alpha), color.copy(alpha = 0.58f * alpha)),
            startY = tl.y, endY = tl.y + size
        ), tl, Size(size, size), cr
    )
    val white = Color.White.copy(alpha = 0.72f * alpha)
    val u = size / 2f
    when (material) {
        0 -> for (iy in -5..5) for (ix in -5..5) {      // radial halftone burst
            val a = ix / 5f; val b = iy / 5f
            val m = min(abs(a), abs(b))
            val r = (u * 0.11f) * max(0.14f, 1f - 1.15f * m) * (1f - 0.35f * max(abs(a), abs(b)))
            drawCircle(white, r, Offset(cx + a * u * 0.78f, cy + b * u * 0.78f))
        }
        1 -> for (iy in -4..4) for (ix in -4..4) drawCircle(white, u * 0.06f, Offset(cx + ix / 4f * u * 0.78f, cy + iy / 4f * u * 0.78f))
        2 -> for (k in -4..4) drawRect(white, Offset(cx - u * 0.78f, cy + k * u * 0.19f - u * 0.022f), Size(u * 1.56f, u * 0.044f))
        3 -> for (iy in -4..4) for (ix in -4..4) if (((ix + iy) and 1) == 0) {
            drawRect(white, Offset(cx + ix / 4f * u * 0.78f - u * 0.05f, cy + iy / 4f * u * 0.78f - u * 0.05f), Size(u * 0.1f, u * 0.1f))
        }
        4 -> {
            val rnd = java.util.Random(5)
            repeat(70) {
                val rx = (rnd.nextFloat() * 2f - 1f) * u * 0.8f; val ry = (rnd.nextFloat() * 2f - 1f) * u * 0.8f
                drawCircle(white, u * (0.02f + rnd.nextFloat() * 0.03f), Offset(cx + rx, cy + ry))
            }
        }
        else -> Unit
    }
    drawRoundRect(Color.White.copy(alpha = 0.14f * alpha), tl, Size(size, size), cr, style = Stroke(1.dp.toPx()))
}

// ============================================================ strength slider

@Composable
private fun StyleSlider(value: Float, enabled: Boolean, onValue: (Float) -> Unit, haptics: Haptics) {
    val onV by rememberUpdatedState(onValue)
    var lastStep by remember { mutableIntStateOf((value * 10).toInt()) }
    fun setFrom(x: Float, w: Float) {
        val trackL = 0.305f * w; val trackR = 0.695f * w; val tw = 0.095f * w
        val v = ((x - trackL - tw / 2f) / (trackR - trackL - tw)).coerceIn(0f, 1f)
        val st = (v * 10).toInt()
        if (st != lastStep) { lastStep = st; haptics.tick() }
        onV(v)
    }
    Canvas(
        Modifier.fillMaxWidth().height(34.dp)
            .pointerInput(enabled) { if (enabled) detectDragGestures(onDrag = { change, _ -> change.consume(); setFrom(change.position.x, this.size.width.toFloat()) }) }
            .pointerInput(enabled) { if (enabled) detectTapGestures { off -> setFrom(off.x, this.size.width.toFloat()) } }
    ) {
        val w = size.width; val cy = size.height / 2f
        val trackL = 0.305f * w; val trackR = 0.695f * w; val tw = 0.095f * w
        drawRoundRect(Color(0xFF2B2B2B), Offset(trackL, cy - 1.5.dp.toPx()), Size(trackR - trackL, 3.dp.toPx()), CornerRadius(2.dp.toPx()))
        val cx = trackL + tw / 2f + (trackR - trackL - tw) * value.coerceIn(0f, 1f)
        val th = 20.dp.toPx()
        drawRoundRect(
            if (enabled) Color(0xFFC6C6C6) else Color(0xFF4A4A4A),
            Offset(cx - tw / 2f, cy - th / 2f), Size(tw, th), CornerRadius(th / 2f)
        )
    }
}
