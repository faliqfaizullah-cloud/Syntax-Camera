package com.example.syntaxcam

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private class Star(val x: Float, val y: Float, val r: Float, val speed: Float, val phase: Float)
private class Sticker(val kind: Int, val ang: Float, val dist: Float, val sizeDp: Float, val rot0: Float, val spin: Float, val textIdx: Int)

private fun ss(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun lerpf(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Smooth piecewise interpolation through keyframes (us = positions 0..1, vs = values). */
private fun keyframe(u: Float, us: FloatArray, vs: FloatArray): Float {
    if (u <= us[0]) return vs[0]
    for (i in 1 until us.size) {
        if (u <= us[i]) {
            val t = (u - us[i - 1]) / (us[i] - us[i - 1])
            val e = t * t * (3f - 2f * t)
            return lerpf(vs[i - 1], vs[i], e * 0.6f + t * 0.4f)
        }
    }
    return vs.last()
}

private val phrases = listOf("in real time", "like a camcorder", "on a Game Boy", "with one swipe")
private val bubbleTexts = listOf("say cheese", "film look", "just shoot it", "camcorder 05", "game boy cam", "spide")

/**
 * Opening: chrome logo over a glass planet. An iridescent orb rolls across the planet now and then.
 * Swipe up -> the planet lifts into a sphere, shrinks into a small glass orb, the onboarding text and
 * "Let's go" button appear, and stickers fountain out of the orb. Tap "Let's go" to enter the camera.
 */
@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    val density = LocalDensity.current
    val cfg = LocalConfiguration.current
    val screenW = cfg.screenWidthDp
    val screenH = cfg.screenHeightDp
    val view = LocalView.current
    val haptics = remember(view) { Haptics(view) }
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }      // 0 = idle planet ... 1 = onboarding orb
    val intro = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }
    var lastStep by remember { mutableIntStateOf(0) }
    var time by remember { mutableFloatStateOf(0f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var burstT0 by remember { mutableFloatStateOf(-1f) }
    var entering by remember { mutableStateOf(false) }

    val tm = rememberTextMeasurer()
    val bubbleLayouts = remember(tm) {
        bubbleTexts.map {
            tm.measure(AnnotatedString(it), TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, color = Color.Black))
        }
    }
    val stars = remember {
        val rnd = Random(11)
        List(280) { Star(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat(), 0.4f + rnd.nextFloat() * 1.6f, rnd.nextFloat() * 6.28f) }
    }
    val stickers = remember {
        val rnd = Random(21)
        val kinds = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 1, 6, 7, 2)
        List(100) {
            Sticker(kinds[rnd.nextInt(kinds.size)], -38f + rnd.nextFloat() * 76f, 0.12f + rnd.nextFloat() * 0.38f,
                46f + rnd.nextFloat() * 40f, -35f + rnd.nextFloat() * 70f, -14f + rnd.nextFloat() * 28f, rnd.nextInt(bubbleTexts.size))
        }
    }
    val phraseIdx by remember { derivedStateOf { if (burstT0 < 0f) 0 else ((time - burstT0) / 2.6f).toInt() % phrases.size } }

    LaunchedEffect(Unit) {
        launch { intro.animateTo(1f, tween(1500, easing = FastOutSlowInEasing)) }
        launch {
            snapshotFlow { progress.value >= 0.88f }.collect { hit ->
                if (hit && burstT0 < 0f) { burstT0 = time; haptics.confirm() }
            }
        }
        val t0 = withFrameNanos { it }
        while (true) withFrameNanos { time = (it - t0) / 1_000_000_000f }
    }

    val enter: () -> Unit = {
        if (!entering) {
            entering = true
            scope.launch {
                haptics.gestureStart()
                progress.animateTo(1f, tween(2300, easing = FastOutSlowInEasing))
            }
        }
    }
    val letsGo: () -> Unit = {
        scope.launch {
            haptics.confirm()
            exit.animateTo(1f, tween(380))
            onStart()
        }
    }

    Box(
        Modifier.fillMaxSize().onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { if (!entering) haptics.gestureStart() },
                    onVerticalDrag = { change, dy ->
                        if (entering) return@detectVerticalDragGestures
                        change.consume()
                        scope.launch { progress.snapTo((progress.value - dy / (size.height * 0.45f) * 0.3f).coerceIn(0f, 0.3f)) }
                        val step = (progress.value * 30).toInt()
                        if (step != lastStep) { lastStep = step; haptics.tick() }
                    },
                    onDragEnd = {
                        if (!entering) scope.launch {
                            if (progress.value > 0.08f) enter()
                            else { haptics.gestureEnd(); progress.animateTo(0f, spring()) }
                        }
                    },
                    onDragCancel = { if (!entering) scope.launch { progress.animateTo(0f) } }
                )
            }
    ) {
        // ---------------- sky, planet, orb, stickers ----------------
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width; val h = this.size.height
            val u = progress.value
            drawRect(Color.Black)

            // fine, faint stars (dimmer once the onboarding is up)
            val starDim = 1f - 0.45f * ss(0.3f, 0.9f, u)
            stars.forEach { s ->
                val y = ((s.y + time * s.speed * 0.0012f) % 1f) * h
                val tw = 0.5f + 0.5f * sin(time * s.speed + s.phase)
                val a = (0.12f + 0.55f * tw * (0.4f + 0.6f * s.r)) * intro.value * starDim
                drawCircle(Color(0xFFDDE6FF).copy(alpha = a.coerceIn(0f, 1f)), (0.22f + s.r * s.r * 0.55f) * density.density, Offset(s.x * w, y))
            }

            // geometry: planet -> big sphere -> glass ball -> small orb
            val top0 = h * (0.716f + 0.25f * (1f - intro.value))
            val us = floatArrayOf(0f, 0.20f, 0.45f, 0.70f, 1f)
            val R = keyframe(u, us, floatArrayOf(0.60f * w, 0.52f * w, 0.26f * w, 0.20f * w, 0.085f * w))
            val cy = keyframe(u, us, floatArrayOf(top0 + 0.60f * w, 0.80f * h, 0.60f * h, 0.47f * h, 0.355f * h))
            val cx = w / 2f
            val c = Offset(cx, cy)

            val planetLook = 1f - ss(0.18f, 0.50f, u)
            val orbLook = ss(0.22f, 0.60f, u)
            if (planetLook > 0.01f) drawPlanet(c, R, h, planetLook)
            if (orbLook > 0.01f) drawGlassOrb(c, R, orbLook, ss(0.85f, 1f, u), 1f + 0.025f * sin(time * 3f) * ss(0.9f, 1f, u))

            // orb rolling across the planet every few seconds (idle only)
            if (u < 0.04f) {
                val cyc = 6.5f
                val rp = ((time % cyc) - 2.5f) / 2.3f
                if (rp in 0f..1f && intro.value > 0.95f) {
                    val e = rp * rp * (3f - 2f * rp)
                    val ox = w * (-0.12f + 1.24f * e)
                    val dx = ox - cx
                    val surf = cy - sqrt((R * R - dx * dx).coerceAtLeast(0f))
                    val orbR = 0.16f * w
                    val act = sin(Math.PI.toFloat() * rp)
                    // cyan light travelling along the rim
                    drawCircle(
                        Brush.horizontalGradient(0f to Color.Transparent, 0.5f to Color(0xFF3DDCFF), 1f to Color.Transparent,
                            startX = ox - 0.4f * w, endX = ox + 0.4f * w),
                        R, c, alpha = 0.85f * act, style = Stroke(3.dp.toPx())
                    )
                    drawIridescent(Offset(ox, surf + 0.10f * w), orbR, act)
                }
            }
            // the same iridescent ring rides the top of the sphere while it lifts
            val liftA = ss(0.02f, 0.10f, u) * (1f - ss(0.35f, 0.55f, u))
            if (liftA > 0.01f) {
                drawIridescent(Offset(cx, cy - R * 0.80f), 0.18f * R, liftA)
                drawCircle(
                    Brush.sweepGradient(
                        listOf(Color.Transparent, Color(0xFF3DDCFF), Color.Transparent, Color.Transparent),
                        c
                    ), R, c, alpha = 0.8f * liftA, style = Stroke(3.dp.toPx())
                )
            }

            // sticker fountain out of the orb
            if (burstT0 >= 0f) drawStickers(stickers, time - burstT0, Offset(cx, cy), h, bubbleLayouts, density.density)
        }

        // ---------------- chrome logo ----------------
        val logoW = screenW * 0.56f
        val logoH = logoW * 720f / 873f
        Image(
            painterResource(R.drawable.logo_syntax), "Syntax Cam",
            Modifier.align(Alignment.TopCenter)
                .padding(top = (screenH * 0.45f - logoH / 2f).dp)
                .width(logoW.dp)
                .graphicsLayer {
                    val u = progress.value
                    translationY = sin(time * 1.2f) * 6.dp.toPx() - ss(0f, 0.4f, u) * screenH.dp.toPx() * 0.08f
                    rotationZ = sin(time * 0.7f) * 1.2f
                    val sc = 0.85f + 0.15f * intro.value
                    scaleX = sc; scaleY = sc
                    alpha = intro.value * (1f - ss(0f, 0.30f, u))
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    val x = (((time * 0.28f) % 2.4f) - 0.7f) * this.size.width
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.Transparent, Color(0x55FFFFFF), Color.Transparent),
                            start = Offset(x - this.size.width * 0.12f, 0f),
                            end = Offset(x + this.size.width * 0.12f, this.size.height * 0.35f)
                        ),
                        blendMode = BlendMode.SrcAtop
                    )
                },
            contentScale = ContentScale.Fit
        )

        Text(
            "Swipe up to enter",
            Modifier.align(Alignment.BottomCenter).padding(bottom = (screenH * 0.095f).dp)
                .graphicsLayer { alpha = (0.78f + 0.17f * sin(time * 2f)) * (1f - ss(0f, 0.08f, progress.value)) * intro.value }
                .clickable { enter() },
            color = Color(0xFFC3C9D3), fontSize = 17.sp
        )

        // ---------------- onboarding text + button ----------------
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = (screenH * 0.60f).dp)
                .graphicsLayer {
                    val a = ss(0.78f, 0.96f, progress.value)
                    alpha = a; translationY = (1f - a) * 16.dp.toPx()
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val st = TextStyle(color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium, lineHeight = 30.sp, textAlign = TextAlign.Center)
            Text("Shoot and style", style = st)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = Color(0xFF8E8E99))) { append("boring") }
                    append(" photos")
                }, style = st
            )
            Text(
                phrases[phraseIdx],
                Modifier.graphicsLayer {
                    val f = if (burstT0 < 0f) 0f else ((time - burstT0) / 2.6f) % 1f
                    alpha = ss(0f, 0.12f, f) * (1f - ss(0.86f, 1f, f))
                    translationY = (1f - ss(0f, 0.12f, f)) * 6.dp.toPx()
                },
                style = st
            )
        }
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 44.dp).fillMaxWidth(0.84f).height(54.dp)
                .graphicsLayer {
                    val a = ss(0.82f, 1f, progress.value)
                    alpha = a; translationY = (1f - a) * 40.dp.toPx()
                }
                .clip(RoundedCornerShape(27.dp)).background(Color(0xFFF2F1F7))
                .clickable(enabled = progress.value > 0.95f) { letsGo() },
            contentAlignment = Alignment.Center
        ) { Text("Let's go", color = Color(0xFF111111), fontSize = 15.sp, fontWeight = FontWeight.Medium) }

        // fade to black when leaving
        Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black.copy(alpha = exit.value)) })
    }
}

// =====================================================================
// drawing helpers
// =====================================================================

/** Glass planet: halo, silver rim (thick at the top), dark body fading to teal, thin edge light. */
private fun DrawScope.drawPlanet(c: Offset, R: Float, h: Float, alpha: Float) {
    val top = c.y - R
    drawCircle(
        Brush.radialGradient(0.90f to Color.Transparent, 0.965f to Color(0x2A2A4C7A), 1f to Color.Transparent, center = c, radius = R * 1.14f),
        R * 1.14f, c, alpha = alpha
    )
    drawCircle(
        Brush.verticalGradient(listOf(Color(0xFFC3CAD6), Color(0xFF8993A3), Color(0x44606A7A)), startY = top, endY = top + R * 0.75f),
        R, c, alpha = alpha
    )
    val rimTop = 10.dp.toPx(); val rimSide = 3.5.dp.toPx()
    val bodyC = Offset(c.x, c.y + rimTop)
    drawCircle(
        Brush.verticalGradient(
            listOf(Color(0xFF0E131B), Color(0xFF121B26), Color(0xFF15303D), Color(0xFF1A5262)),
            startY = top, endY = h
        ), R - rimSide, bodyC, alpha = alpha
    )
    drawCircle(
        Brush.verticalGradient(listOf(Color(0x555C7390), Color.Transparent), startY = top, endY = top + R * 0.40f),
        R - rimSide - 1f, bodyC, alpha = alpha, style = Stroke(18.dp.toPx())
    )
    drawCircle(
        Brush.verticalGradient(listOf(Color(0x99FFFFFF), Color(0x00FFFFFF)), startY = top, endY = top + R * 0.5f),
        R, c, alpha = alpha, style = Stroke(1.2.dp.toPx())
    )
}

/** Dark glass ball with a thin light rim; shows a + once it has become the small orb. */
private fun DrawScope.drawGlassOrb(c: Offset, R: Float, alpha: Float, plusAlpha: Float, pulse: Float) {
    val r = R * pulse
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFF1B1C22), Color(0xFF0A0B0F)), center = c + Offset(-r * 0.2f, -r * 0.25f), radius = r * 1.3f),
        r, c, alpha = alpha
    )
    drawCircle(
        Brush.radialGradient(0.78f to Color.Transparent, 1f to Color(0x445C7390), center = c, radius = r),
        r, c, alpha = alpha
    )
    drawCircle(Color.White, r, c, alpha = 0.62f * alpha, style = Stroke(1.4.dp.toPx()))
    drawArc(
        Color.White, 195f, 75f, false, c - Offset(r, r), Size(r * 2f, r * 2f),
        alpha = 0.55f * alpha, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round)
    )
    if (plusAlpha > 0.01f) {
        val len = r * 0.42f; val sw = 2.dp.toPx()
        drawLine(Color.White, Offset(c.x - len, c.y), Offset(c.x + len, c.y), sw, StrokeCap.Round, alpha = plusAlpha)
        drawLine(Color.White, Offset(c.x, c.y - len), Offset(c.x, c.y + len), sw, StrokeCap.Round, alpha = plusAlpha)
    }
}

/** Dark core with a blue / cyan / violet iridescent ring and faint ripples. */
private fun DrawScope.drawIridescent(c: Offset, r: Float, a: Float) {
    if (a <= 0.01f) return
    val cols = listOf(Color(0xFF2F6BFF), Color(0xFF3DDCFF), Color(0xFF8A5CFF), Color(0xFF2F6BFF))
    drawCircle(Brush.radialGradient(listOf(Color(0x552F6BFF), Color.Transparent), c, r * 1.8f), r * 1.8f, c, alpha = a)
    for ((k, wd, al) in listOf(Triple(0.85f, 0.55f, 0.35f), Triple(0.78f, 0.34f, 0.5f), Triple(0.72f, 0.16f, 0.7f))) {
        drawCircle(Brush.sweepGradient(cols, c), r * k, c, alpha = al * a, style = Stroke(r * wd))
    }
    drawCircle(Brush.radialGradient(listOf(Color(0xFF05060A), Color(0xFF0B0E16)), c, r * 0.6f), r * 0.55f, c, alpha = a)
    drawCircle(Color.White, r * 1.35f, c, alpha = 0.10f * a, style = Stroke(1.dp.toPx()))
    drawCircle(Color.White, r * 1.7f, c, alpha = 0.06f * a, style = Stroke(1.dp.toPx()))
}

private fun DrawScope.drawStickers(
    list: List<Sticker>, t: Float, origin: Offset, h: Float, layouts: List<TextLayoutResult>, density: Float
) {
    val unit = 40f * density
    val life = 7f; val gap = 0.14f
    for (i in list.indices) {
        val s = list[i]
        val age = t - i * gap
        if (age < 0f || age > life) continue
        val k = age / life
        val e = 1f - (1f - k) * (1f - k) * (1f - k)
        val rad = s.dist * h * e
        val ang = Math.toRadians(s.ang.toDouble())
        val dx = (sin(ang) * rad * 0.9f).toFloat()
        val dy = (-cos(ang) * rad).toFloat()
        val sc = s.sizeDp / 80f * (0.25f + 0.75f * ss(0f, 0.07f, k))
        val a = (1f - ss(0.85f, 1f, k)) * ss(0f, 0.03f, k)
        withTransform({
            translate(origin.x + dx, origin.y + dy)
            rotate(s.rot0 + s.spin * age, Offset.Zero)
            scale(sc, sc, Offset.Zero)
        }) {
            drawStickerShape(s.kind, unit, a, if (s.kind == 1 || s.kind == 6) layouts[s.textIdx] else null)
        }
    }
}

/** Flat white-bordered "sticker" art, centred on (0,0); U is the half-size in pixels. */
private fun DrawScope.drawStickerShape(kind: Int, U: Float, a: Float, layout: TextLayoutResult?) {
    val white = Color.White
    val bw = 0.30f * U
    val round = StrokeJoin.Round
    fun fourStar(r: Float) = Path().apply {
        moveTo(0f, -r); quadraticBezierTo(0f, 0f, r, 0f); quadraticBezierTo(0f, 0f, 0f, r)
        quadraticBezierTo(0f, 0f, -r, 0f); quadraticBezierTo(0f, 0f, 0f, -r); close()
    }
    fun poly(vararg p: Float) = Path().apply {
        moveTo(p[0] * U, p[1] * U)
        var i = 2
        while (i < p.size) { lineTo(p[i] * U, p[i + 1] * U); i += 2 }
        close()
    }
    fun bordered(path: Path, fill: Color) {
        drawPath(path, white, alpha = a, style = Stroke(bw * 2f, join = round, cap = StrokeCap.Round))
        drawPath(path, fill, alpha = a)
    }
    when (kind) {
        0 -> { // camera
            bordered(poly(-0.55f, -0.85f, 0.15f, -0.85f, 0.15f, -0.5f, -0.55f, -0.5f), Color(0xFF23252B))
            val tl = Offset(-U, -0.55f * U); val sz = Size(2f * U, 1.3f * U); val cr = CornerRadius(0.28f * U)
            drawRoundRect(white, tl, sz, cr, alpha = a, style = Stroke(bw * 2f))
            drawRoundRect(Color(0xFF23252B), tl, sz, cr, alpha = a)
            drawCircle(white, 0.46f * U, Offset(0f, 0.1f * U), alpha = a)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF6C93FF), Color(0xFF1A2D7A)), Offset(0f, 0.1f * U), 0.34f * U), 0.34f * U, Offset(0f, 0.1f * U), alpha = a)
            drawCircle(white, 0.07f * U, Offset(-0.12f * U, -0.04f * U), alpha = 0.9f * a)
            drawRoundRect(Color(0xFFFFD23F), Offset(0.58f * U, -0.38f * U), Size(0.26f * U, 0.18f * U), CornerRadius(0.04f * U), alpha = a)
        }
        1, 6 -> { // speech bubble
            val tw = (layout?.size?.width ?: 0).toFloat(); val th = (layout?.size?.height ?: 0).toFloat()
            val bwid = tw + 0.9f * U; val bhei = th + 0.5f * U
            val tl = Offset(-bwid / 2f, -bhei / 2f)
            val tail = Path().apply {
                moveTo(-bwid * 0.30f, bhei / 2f - 2f); lineTo(-bwid * 0.42f, bhei / 2f + 0.55f * U); lineTo(-bwid * 0.10f, bhei / 2f - 2f); close()
            }
            drawRoundRect(white, tl, Size(bwid, bhei), CornerRadius(bhei / 2f), alpha = a, style = Stroke(bw * 2f))
            drawPath(tail, white, alpha = a, style = Stroke(bw * 2f, join = round))
            drawRoundRect(white, tl, Size(bwid, bhei), CornerRadius(bhei / 2f), alpha = a)
            drawPath(tail, white, alpha = a)
            drawRoundRect(Color.Black, tl, Size(bwid, bhei), CornerRadius(bhei / 2f), alpha = a, style = Stroke(0.06f * U))
            layout?.let { drawText(it, topLeft = Offset(-tw / 2f, -th / 2f), alpha = a) }
        }
        2 -> bordered(fourStar(1.0f * U), Color(0xFF14151A))                          // black four-point star
        3 -> bordered(Path().apply {                                                   // heart
            moveTo(0f, 0.9f * U)
            cubicTo(-1.5f * U, -0.1f * U, -0.8f * U, -1.0f * U, 0f, -0.35f * U)
            cubicTo(0.8f * U, -1.0f * U, 1.5f * U, -0.1f * U, 0f, 0.9f * U); close()
        }, Color(0xFFFF4D6D))
        4 -> { // polaroid
            val tl = Offset(-0.85f * U, -1.0f * U); val sz = Size(1.7f * U, 2.0f * U); val cr = CornerRadius(0.12f * U)
            drawRoundRect(white, tl, sz, cr, alpha = a, style = Stroke(bw * 2f))
            drawRoundRect(Color(0xFFF4F4F6), tl, sz, cr, alpha = a)
            drawRoundRect(
                Brush.verticalGradient(listOf(Color(0xFF8FB0E0), Color(0xFF2B3F6B)), startY = -0.82f * U, endY = 0.56f * U),
                Offset(-0.68f * U, -0.82f * U), Size(1.36f * U, 1.38f * U), CornerRadius(0.06f * U), alpha = a
            )
        }
        5 -> bordered(poly(0.25f, -1.0f, -0.65f, 0.15f, -0.05f, 0.15f, -0.3f, 1.0f, 0.7f, -0.2f, 0.05f, -0.2f), Color(0xFFFFD23F)) // flash bolt
        7 -> bordered(fourStar(0.8f * U), Color(0xFF9CC2FF))                          // sparkle
        8 -> { // film strip
            val tl = Offset(-U, -0.7f * U); val sz = Size(2f * U, 1.4f * U); val cr = CornerRadius(0.1f * U)
            drawRoundRect(white, tl, sz, cr, alpha = a, style = Stroke(bw * 2f))
            drawRoundRect(Color(0xFF17181C), tl, sz, cr, alpha = a)
            for (i in 0..4) {
                drawRect(white, Offset((-0.85f + i * 0.4f) * U, -0.6f * U), Size(0.18f * U, 0.14f * U), alpha = a)
                drawRect(white, Offset((-0.85f + i * 0.4f) * U, 0.46f * U), Size(0.18f * U, 0.14f * U), alpha = a)
            }
            drawRect(Color(0xFF8FB0E0), Offset(-0.75f * U, -0.3f * U), Size(0.7f * U, 0.6f * U), alpha = a)
            drawRect(Color(0xFF8FB0E0), Offset(0.05f * U, -0.3f * U), Size(0.7f * U, 0.6f * U), alpha = a)
        }
        9 -> { // paper plane
            val p1 = poly(-1.0f, -0.1f, 1.0f, -0.9f, 0.05f, 0.25f)
            val p2 = poly(0.05f, 0.25f, 1.0f, -0.9f, 0.3f, 0.9f)
            drawPath(p1, white, alpha = a, style = Stroke(bw * 2f, join = round)); drawPath(p2, white, alpha = a, style = Stroke(bw * 2f, join = round))
            drawPath(p1, Color(0xFFDDE8FF), alpha = a); drawPath(p2, Color(0xFF9DB8F0), alpha = a)
        }
        else -> { // lens
            drawCircle(white, U + bw, Offset.Zero, alpha = a)
            drawCircle(Brush.radialGradient(listOf(Color(0xFFE9F0FA), Color(0xFF6C93CF), Color(0xFF1A2D5C)), Offset.Zero, U), U, Offset.Zero, alpha = a)
            drawCircle(Color(0xFF0B1228), 0.5f * U, Offset.Zero, alpha = a)
            drawCircle(white, 0.12f * U, Offset(-0.2f * U, -0.2f * U), alpha = 0.9f * a)
        }
    }
}
