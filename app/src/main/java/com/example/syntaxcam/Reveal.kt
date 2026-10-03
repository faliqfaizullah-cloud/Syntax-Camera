package com.example.syntaxcam

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** What the post-capture animation shows: a display-size photo, a pre-blurred copy, and a caption. */
class RevealData(val sharp: Bitmap, val blurred: Bitmap, val note: String)

fun makeReveal(src: Bitmap, note: String): RevealData {
    val m = maxOf(src.width, src.height)
    val s = if (m > 1080) 1080f / m else 1f
    val sharp = if (s < 1f)
        Bitmap.createScaledBitmap(src, (src.width * s).toInt().coerceAtLeast(1), (src.height * s).toInt().coerceAtLeast(1), true)
    else src
    // cheap gaussian-style blur: shrink hard, then enlarge with bilinear filtering (works on every Android version)
    var b = Bitmap.createScaledBitmap(sharp, (sharp.width / 18).coerceAtLeast(8), (sharp.height / 18).coerceAtLeast(8), true)
    b = Bitmap.createScaledBitmap(b, (sharp.width / 4).coerceAtLeast(16), (sharp.height / 4).coerceAtLeast(16), true)
    return RevealData(sharp, b, note)
}

private fun ss(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private val auraBase = listOf(
    Color(0xFFFFB020), Color(0xFFFF7A18), Color(0xFFFF4FD8),
    Color(0xFF9B5CFF), Color(0xFF5C7CFF), Color(0xFFFFB020)
)

private fun auraColors(t: Float): List<Color> {
    val n = auraBase.size
    val ph = ((t * 0.35f) % 1f) * n
    val k = ph.toInt(); val f = ph - k
    val out = List(n) { i -> lerp(auraBase[(i + k) % n], auraBase[(i + k + 1) % n], f) }
    return out + out[0]
}

/**
 * Frosted-glass capture reveal: photo blurred under drifting mesh-gradient blobs and a glowing
 * edge aura, then the blur resolves into the sharp photo and everything fades out. Tap to skip.
 */
@Composable
fun CaptureReveal(data: RevealData, haptics: Haptics, onDone: () -> Unit) {
    val p = remember { Animatable(0f) }
    var time by remember { mutableFloatStateOf(0f) }
    val sharp = remember(data) { data.sharp.asImageBitmap() }
    val blurred = remember(data) { data.blurred.asImageBitmap() }

    LaunchedEffect(Unit) {
        val clock = launch {
            val t0 = withFrameNanos { it }
            while (true) withFrameNanos { time = (it - t0) / 1_000_000_000f }
        }
        p.animateTo(0.5f, tween(1300, easing = LinearEasing))
        haptics.confirm()                                   // blur starts resolving
        p.animateTo(1f, tween(1300, easing = LinearEasing))
        clock.cancel()
        onDone()
    }

    fun overall() = ss(0f, 0.08f, p.value) * (1f - ss(0.9f, 1f, p.value))
    fun aura() = ss(0f, 0.12f, p.value) * (1f - ss(0.5f, 0.85f, p.value))

    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDone() } }) {
        Canvas(Modifier.fillMaxSize()) {
            val ov = overall(); val au = aura(); val sh = ss(0.45f, 0.85f, p.value)
            drawRect(Color.Black, alpha = ov)
            drawPhoto(blurred, cover = true, alpha = ov, zoom = 1.08f)
            drawAura(time, au * ov)
            drawPhoto(sharp, cover = false, alpha = sh * ov, zoom = 1.03f - 0.03f * sh)
        }
        Column(
            Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 28.dp, top = 56.dp)
                .graphicsLayer { alpha = aura() }
        ) {
            Text("Saved", fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(data.note, fontSize = 16.sp, color = Color(0xCCFFFFFF))
        }
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp).width(240.dp).height(64.dp)
                .graphicsLayer { alpha = aura() }
                .clip(RoundedCornerShape(32.dp))
                .background(Brush.verticalGradient(listOf(Color(0x66FFFFFF), Color(0x22C77DFF))))
                .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(32.dp)),
            contentAlignment = Alignment.Center
        ) { Text("Tap to continue", color = Color.White, fontSize = 14.sp) }
    }
}

private fun DrawScope.drawPhoto(img: ImageBitmap, cover: Boolean, alpha: Float, zoom: Float) {
    if (alpha <= 0.001f) return
    val w = size.width; val h = size.height
    val sc = (if (cover) max(w / img.width, h / img.height) else min(w / img.width, h / img.height)) * zoom
    val dw = img.width * sc; val dh = img.height * sc
    drawImage(
        img,
        dstOffset = IntOffset(((w - dw) / 2f).toInt(), ((h - dh) / 2f).toInt()),
        dstSize = IntSize(dw.toInt(), dh.toInt()),
        alpha = alpha.coerceIn(0f, 1f),
        filterQuality = FilterQuality.High
    )
}

private fun DrawScope.drawAura(time: Float, a: Float) {
    if (a <= 0.001f) return
    val w = size.width; val h = size.height
    // drifting mesh-gradient blobs
    val blobs = listOf(Color(0xFFFF4FD8), Color(0xFF9B5CFF), Color(0xFF5C7CFF), Color(0xFFFFB020), Color(0xFFFF6A3D))
    blobs.forEachIndexed { i, c ->
        val cx = w * (0.5f + 0.42f * sin(time * 0.8f + i * 1.7f))
        val cy = h * (0.5f + 0.42f * cos(time * 0.6f + i * 2.1f))
        val r = w * 0.75f
        drawCircle(Brush.radialGradient(listOf(c.copy(alpha = (0.55f * a).coerceIn(0f, 1f)), Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))
    }
    // frosted veil
    drawRect(Color.White.copy(alpha = (0.10f * a).coerceIn(0f, 1f)))
    // violet glow under the glass button
    val gc = Offset(w / 2f, h * 0.86f)
    drawCircle(Brush.radialGradient(listOf(Color(0xFFB36BFF).copy(alpha = (0.8f * a).coerceIn(0f, 1f)), Color.Transparent), gc, w * 0.5f), w * 0.5f, gc)
    // glowing edge aura: stacked soft strokes with a rotating colour sweep
    val brush = Brush.sweepGradient(auraColors(time), Offset(w / 2f, h / 2f))
    listOf(130f to 0.07f, 90f to 0.11f, 58f to 0.17f, 34f to 0.27f, 16f to 0.45f).forEach { (sw, al) ->
        drawRoundRect(
            brush = brush,
            topLeft = Offset(sw / 2f, sw / 2f),
            size = Size(w - sw, h - sw),
            cornerRadius = CornerRadius(110f),
            alpha = (al * a * 1.6f).coerceIn(0f, 1f),
            style = Stroke(sw)
        )
    }
}
