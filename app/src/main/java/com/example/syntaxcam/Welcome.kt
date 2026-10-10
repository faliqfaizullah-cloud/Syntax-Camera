package com.example.syntaxcam

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random

private class Star(val x: Float, val y: Float, val r: Float, val speed: Float, val phase: Float)

/**
 * Opening screen: black starfield, chrome-balloon "SYN TAX" logo (pre-rendered artwork),
 * planet horizon with a glass rim and a teal glow, "Swipe up to enter".
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
    val progress = remember { Animatable(0f) }
    val intro = remember { Animatable(0f) }
    var lastStep by remember { mutableIntStateOf(0) }
    var time by remember { mutableFloatStateOf(0f) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(Unit) {
        launch { intro.animateTo(1f, tween(1500, easing = FastOutSlowInEasing)) }
        val t0 = withFrameNanos { it }
        while (true) withFrameNanos { time = (it - t0) / 1_000_000_000f }
    }
    val stars = remember {
        val rnd = Random(11)
        List(280) { Star(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat(), 0.4f + rnd.nextFloat() * 1.6f, rnd.nextFloat() * 6.28f) }
    }
    val enter: () -> Unit = {
        scope.launch {
            haptics.confirm()
            progress.animateTo(1f, tween(500))
            onStart()
        }
    }

    Box(
        Modifier.fillMaxSize().onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { haptics.gestureStart() },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        scope.launch { progress.snapTo((progress.value - dy / (size.height * 0.6f)).coerceIn(0f, 1f)) }
                        val step = (progress.value * 10).toInt()
                        if (step != lastStep) { lastStep = step; haptics.tick() }
                    },
                    onDragEnd = {
                        scope.launch {
                            if (progress.value > 0.3f) enter()
                            else { haptics.gestureEnd(); progress.animateTo(0f, spring()) }
                        }
                    },
                    onDragCancel = { scope.launch { progress.animateTo(0f) } }
                )
            }
    ) {
        // ---- sky + planet ----
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width; val h = this.size.height
            drawRect(Color.Black)

            // fine, faint stars
            stars.forEach { s ->
                val y = ((s.y + time * s.speed * 0.0012f) % 1f) * h
                val tw = 0.5f + 0.5f * sin(time * s.speed + s.phase)
                val a = (0.12f + 0.55f * tw * (0.4f + 0.6f * s.r)) * intro.value
                val rad = (0.22f + s.r * s.r * 0.55f) * density.density
                drawCircle(Color(0xFFDDE6FF).copy(alpha = a.coerceIn(0f, 1f)), rad, Offset(s.x * w, y))
            }

            // planet: a wide, gently curved horizon
            val R = w * 0.60f
            val cx = w / 2f
            val top = h * (0.716f + 0.25f * (1f - intro.value)) - progress.value * h * 0.95f
            val cy = top + R
            val c = Offset(cx, cy)

            // faint atmosphere above the rim
            drawCircle(
                Brush.radialGradient(
                    0.90f to Color.Transparent, 0.965f to Color(0x2A2A4C7A), 1f to Color.Transparent,
                    center = c, radius = R * 1.14f
                ), radius = R * 1.14f, center = c
            )
            // glass rim: silver-blue disc, then the body shifted down leaves a crescent (thick at the top, thin at the sides)
            drawCircle(
                Brush.verticalGradient(listOf(Color(0xFFC3CAD6), Color(0xFF8993A3), Color(0x44606A7A)), startY = top, endY = top + R * 0.75f),
                R, c
            )
            val rimTop = 10.dp.toPx(); val rimSide = 3.5.dp.toPx()
            val bodyC = Offset(cx, cy + rimTop)
            drawCircle(
                Brush.verticalGradient(
                    listOf(Color(0xFF0E131B), Color(0xFF121B26), Color(0xFF15303D), Color(0xFF1A5262)),
                    startY = top, endY = h
                ), R - rimSide, bodyC
            )
            // soft light just inside the rim, and a thin bright edge line
            drawCircle(
                Brush.verticalGradient(listOf(Color(0x555C7390), Color.Transparent), startY = top, endY = top + R * 0.40f),
                R - rimSide - 1f, bodyC, style = Stroke(18.dp.toPx())
            )
            drawCircle(
                Brush.verticalGradient(listOf(Color(0x99FFFFFF), Color(0x00FFFFFF)), startY = top, endY = top + R * 0.5f),
                R, c, style = Stroke(1.2.dp.toPx())
            )
        }

        // ---- chrome balloon logo (SYN / TAX) ----
        val logoW = screenW * 0.56f
        val logoH = logoW * 720f / 873f
        Image(
            painterResource(R.drawable.logo_syntax), "Syntax Cam",
            Modifier.align(Alignment.TopCenter)
                .padding(top = (screenH * 0.45f - logoH / 2f).dp)
                .width(logoW.dp)
                .graphicsLayer {
                    translationY = sin(time * 1.2f) * 6.dp.toPx() - progress.value * screenH.dp.toPx() * 0.5f
                    rotationZ = sin(time * 0.7f) * 1.2f
                    val sc = 0.85f + 0.15f * intro.value
                    scaleX = sc; scaleY = sc
                    alpha = intro.value * (1f - progress.value * 1.5f).coerceIn(0f, 1f)
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    // slow glint across the chrome
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
                .graphicsLayer { alpha = (0.78f + 0.17f * sin(time * 2f)) * (1f - progress.value) * intro.value }
                .clickable { enter() },
            color = Color(0xFFC3C9D3), fontSize = 17.sp
        )

        // fade to black as the planet fills the screen
        Box(Modifier.fillMaxSize().drawBehind {
            val p = progress.value
            drawRect(Color.Black.copy(alpha = p * p * p))
        })
    }
}
