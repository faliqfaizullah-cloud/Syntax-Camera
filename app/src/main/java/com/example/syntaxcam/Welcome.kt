package com.example.syntaxcam

import android.annotation.SuppressLint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin
import kotlin.random.Random

/**
 * AGSL (Android 13+) liquid-glass lens: magnifies + bends whatever is behind it,
 * splits colour channels near the rim (chromatic aberration) and adds a specular edge.
 */
private const val LENS_AGSL = """
uniform shader content;
uniform float2 center;
uniform float radius;
uniform float time;

half4 main(float2 p) {
    float2 d = p - center;
    float dist = length(d);
    float t = dist / radius;
    if (t >= 1.0) { return content.eval(p); }

    float lens = 1.0 - t * t;
    float2 wobble = float2(sin(time * 1.6 + p.y * 0.03), cos(time * 1.4 + p.x * 0.03)) * 5.0 * lens;
    float2 dir = normalize(d + float2(0.0001, 0.0001));
    float2 off = d * (-0.45 * lens) + wobble;
    float ca = 7.0 * t * t;

    float r = content.eval(p + off + dir * ca).r;
    float g = content.eval(p + off).g;
    float b = content.eval(p + off - dir * ca).b;
    float3 rgb = float3(r, g, b);

    float rim = smoothstep(0.84, 1.0, t);
    float spec = pow(max(0.0, dot(normalize(float2(-0.6, -0.8)), -dir)), 6.0) * rim;
    rgb += float3(0.05, 0.06, 0.11) * lens;
    rgb += float3(0.55) * spec + float3(0.07) * rim;
    return half4(half3(rgb), 1.0);
}
"""

private class Star(val x: Float, val y: Float, val r: Float, val speed: Float, val phase: Float)

@SuppressLint("NewApi")
@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    val density = LocalDensity.current
    val view = LocalView.current
    val haptics = remember(view) { Haptics(view) }
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val t0 = withFrameNanos { it }
        while (true) withFrameNanos { time = (it - t0) / 1_000_000_000f }
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var drag by remember { mutableStateOf<Offset?>(null) }
    val stars = remember {
        val rnd = Random(42)
        List(170) { Star(rnd.nextFloat(), rnd.nextFloat(), 0.6f + rnd.nextFloat() * 1.8f, 0.6f + rnd.nextFloat() * 2f, rnd.nextFloat() * 6.28f) }
    }
    val radiusPx = with(density) { 110.dp.toPx() }
    val shader = remember { if (Build.VERSION.SDK_INT >= 33) RuntimeShader(LENS_AGSL) else null }

    // Lens drifts on its own until the user drags it
    val center = drag ?: Offset(
        size.width * (0.5f + 0.28f * sin(time * 0.5f)),
        size.height * (0.40f + 0.12f * sin(time * 0.37f + 1f))
    )

    Box(
        Modifier.fillMaxSize().onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { drag = it; haptics.gestureStart() },
                    onDrag = { change, _ -> drag = change.position },
                    onDragEnd = { drag = null; haptics.gestureEnd() },
                    onDragCancel = { drag = null }
                )
            }
    ) {
        // Layer that gets bent by the lens: sky + title
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                if (Build.VERSION.SDK_INT >= 33 && shader != null) {
                    shader.setFloatUniform("center", center.x, center.y)
                    shader.setFloatUniform("radius", radiusPx)
                    shader.setFloatUniform("time", time)
                    renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
                }
            }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF04020D), Color(0xFF0A0722), Color(0xFF170B33))))
                drawCircle(
                    Brush.radialGradient(listOf(Color(0x668A4DFF), Color.Transparent),
                        center = Offset(size.width * 0.2f, size.height * 0.25f), radius = size.width * 0.7f),
                    radius = size.width * 0.7f, center = Offset(size.width * 0.2f, size.height * 0.25f)
                )
                drawCircle(
                    Brush.radialGradient(listOf(Color(0x552F7BFF), Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.6f), radius = size.width * 0.8f),
                    radius = size.width * 0.8f, center = Offset(size.width * 0.85f, size.height * 0.6f)
                )
                drawCircle(
                    Brush.radialGradient(listOf(Color(0x44FF4DC8), Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 0.95f), radius = size.width * 0.6f),
                    radius = size.width * 0.6f, center = Offset(size.width * 0.5f, size.height * 0.95f)
                )
                // constellation
                val pts = stars.take(9).map { Offset(it.x * size.width, it.y * size.height) }
                for (i in 0 until pts.size - 1) drawLine(Color(0x33FFFFFF), pts[i], pts[i + 1], 1.5f)
                // twinkling stars
                stars.forEach { s ->
                    val a = 0.35f + 0.65f * (sin(time * s.speed + s.phase) * 0.5f + 0.5f)
                    drawCircle(Color.White.copy(alpha = a), s.r * density.density, Offset(s.x * size.width, s.y * size.height))
                }
            }
            Column(
                Modifier.align(Alignment.TopCenter).padding(top = 140.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("SYNTAX CAM", fontSize = 34.sp, fontWeight = FontWeight.Thin, letterSpacing = 9.sp,
                    color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text("see the night differently", fontSize = 14.sp, letterSpacing = 3.sp,
                    color = Color(0xB3FFFFFF), textAlign = TextAlign.Center)
            }
        }

        // Fallback glass disc for Android 10-12 (no AGSL): frosted circle, no displacement
        if (shader == null) Canvas(Modifier.fillMaxSize()) {
            drawCircle(Brush.radialGradient(listOf(Color(0x33FFFFFF), Color(0x0AFFFFFF)), center = center, radius = radiusPx),
                radiusPx, center)
            drawCircle(Color(0x66FFFFFF), radiusPx, center, style = Stroke(2f))
        }

        // Glass button + hint
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("drag to bend the light", fontSize = 12.sp, letterSpacing = 2.sp, color = Color(0x80FFFFFF))
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier.width(220.dp).height(60.dp).clip(RoundedCornerShape(30.dp))
                    .background(Brush.verticalGradient(listOf(Color(0x38FFFFFF), Color(0x10FFFFFF))))
                    .border(1.dp, Brush.verticalGradient(listOf(Color(0x99FFFFFF), Color(0x1AFFFFFF))), RoundedCornerShape(30.dp))
                    .clickable { haptics.confirm(); onStart() },
                contentAlignment = Alignment.Center
            ) { Text("BEGIN", fontSize = 15.sp, letterSpacing = 6.sp, color = Color.White, fontWeight = FontWeight.Light) }
        }
    }
}
