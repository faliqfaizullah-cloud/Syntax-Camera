package com.example.syntaxcam

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random

private class Star(val x: Float, val y: Float, val r: Float, val speed: Float, val phase: Float)

/** Black starfield, chrome balloon-style title, glowing planet horizon, swipe up to enter. */
@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    val density = LocalDensity.current
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
        List(230) { Star(rnd.nextFloat(), rnd.nextFloat(), 0.4f + rnd.nextFloat() * 1.1f, 0.4f + rnd.nextFloat() * 1.6f, rnd.nextFloat() * 6.28f) }
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
        // Sky + planet
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width; val h = this.size.height
            drawRect(Color.Black)
            stars.forEach { s ->
                val y = ((s.y + time * s.speed * 0.003f) % 1f) * h
                val a = (0.12f + 0.5f * (sin(time * s.speed + s.phase) * 0.5f + 0.5f)) * intro.value
                drawCircle(Color.White.copy(alpha = a), s.r * density.density, Offset(s.x * w, y))
            }
            val pr = w * 0.98f
            val cx = w / 2f
            val top = h * (0.74f + 0.25f * (1f - intro.value)) - progress.value * h * 0.95f
            val cy = top + pr
            // atmosphere halo
            drawCircle(
                Brush.radialGradient(
                    0.86f to Color.Transparent, 0.95f to Color(0x3A6A9CFF), 1f to Color.Transparent,
                    center = Offset(cx, cy), radius = pr * 1.18f
                ), radius = pr * 1.18f, center = Offset(cx, cy)
            )
            // body
            drawCircle(
                Brush.verticalGradient(
                    listOf(Color(0xFF2C323E), Color(0xFF161C26), Color(0xFF123B48), Color(0xFF1C6A80)),
                    startY = top, endY = top + pr * 1.1f
                ), pr, Offset(cx, cy)
            )
            // soft rim glow + sharp rim light
            drawCircle(
                Brush.verticalGradient(listOf(Color(0x66BBD2F5), Color.Transparent), startY = top, endY = top + pr * 0.5f),
                pr + 5.dp.toPx(), Offset(cx, cy), style = Stroke(width = 18.dp.toPx())
            )
            drawCircle(
                Brush.verticalGradient(listOf(Color(0xFFF2F6FF), Color(0x88C6D6F0), Color.Transparent), startY = top, endY = top + pr * 0.55f),
                pr, Offset(cx, cy), style = Stroke(width = 4.dp.toPx())
            )
        }

        // Chrome balloon title
        Column(
            Modifier.align(Alignment.Center).offset(y = (-96).dp).graphicsLayer {
                translationY = sin(time * 1.2f) * 6.dp.toPx() - progress.value * this.size.height * 0.6f
                rotationZ = sin(time * 0.7f) * 1.2f
                val sc = 0.88f + 0.12f * intro.value
                scaleX = sc; scaleY = sc
                alpha = intro.value * (1f - progress.value * 1.5f).coerceIn(0f, 1f)
            },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Chrome("SYN", 104, -4f, Modifier.offset(x = (-20).dp))
            Chrome("TAX", 104, 3f, Modifier.offset(x = 16.dp, y = (-26).dp))
            Chrome("CAM", 104, -2f, Modifier.offset(x = (-8).dp, y = (-52).dp))
        }

        Text(
            "Swipe up to enter",
            Modifier.align(Alignment.BottomCenter).padding(bottom = 110.dp)
                .graphicsLayer { alpha = (0.55f + 0.3f * sin(time * 2f)) * (1f - progress.value) * intro.value }
                .clickable { enter() },
            color = Color(0xCCDDE6F0), fontSize = 17.sp, letterSpacing = 0.5.sp
        )

        // fade to black as the planet fills the screen
        Box(Modifier.fillMaxSize().drawBehind {
            val p = progress.value
            drawRect(Color.Black.copy(alpha = p * p * p))
        })
    }
}

/** Faux inflated-chrome lettering: silver outline, blue-steel gradient fill, white specular edge. */
@Composable
private fun Chrome(text: String, sizeSp: Int, rot: Float, modifier: Modifier = Modifier) {
    val base = TextStyle(
        fontSize = sizeSp.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.SansSerif,
        letterSpacing = (-3).sp, lineHeight = sizeSp.sp
    )
    Box(modifier.graphicsLayer { rotationZ = rot }) {
        Text(text, style = base.copy(
            brush = Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFF6F7480))),
            drawStyle = Stroke(width = 30f, join = StrokeJoin.Round)))
        Text(text, style = base.copy(
            brush = Brush.verticalGradient(
                0f to Color(0xFFF7FAFF), 0.28f to Color(0xFFA9C0F0), 0.5f to Color(0xFF28437F),
                0.72f to Color(0xFF7E9AD6), 1f to Color(0xFFDDE6F5))))
        Text(text, style = base.copy(
            brush = SolidColor(Color.White.copy(alpha = 0.5f)),
            drawStyle = Stroke(width = 2f, join = StrokeJoin.Round)))
    }
}
