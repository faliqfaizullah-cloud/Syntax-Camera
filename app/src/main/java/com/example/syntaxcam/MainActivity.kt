package com.example.syntaxcam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private class Params {
    @Volatile var effects: List<Effect> = listOf(Effect.TIME)
    @Volatile var amount = 0.5f
    @Volatile var seed = 1L
    @Volatile var picked: Bitmap? = null
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var welcome by remember { mutableStateOf(true) }
                if (welcome) WelcomeScreen { welcome = false } else CameraScreen()
            }
        }
    }
}

@Composable
fun CameraScreen() {
    val ctx = LocalContext.current
    val activity = ctx as ComponentActivity
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(Manifest.permission.CAMERA) }

    val params = remember { Params() }
    val view = LocalView.current
    val haptics = remember(view) { Haptics(view) }
    var selected by remember { mutableStateOf(listOf(Effect.TIME)) }
    var amount by remember { mutableFloatStateOf(0.5f) }
    var seed by remember { mutableLongStateOf(1L) }
    var front by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Bitmap?>(null) }
    var cam by remember { mutableStateOf<Camera?>(null) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var thumbs by remember { mutableStateOf<Map<Effect, Bitmap>>(emptyMap()) }
    params.effects = selected; params.amount = amount; params.seed = seed; params.picked = picked
    val executor = remember { Executors.newSingleThreadExecutor() }

    // Gallery import
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runCatching {
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, _, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            picked = Bitmap.createScaledBitmap(bmp, 540, (bmp.height * 540f / bmp.width).toInt(), true)
        }
    }

    // Re-render a gallery photo whenever settings change
    LaunchedEffect(picked, selected, amount, seed) {
        val p = picked ?: return@LaunchedEffect
        frame = withContext(Dispatchers.Default) { Effects.applyAll(p, selected, amount, seed) }
    }
    LaunchedEffect(torch, cam) { runCatching { cam?.cameraControl?.enableTorch(torch) } }

    DisposableEffect(granted, front) {
        if (!granted) return@DisposableEffect onDispose {}
        val future = ProcessCameraProvider.getInstance(ctx)
        var counter = 0
        future.addListener({
            val provider = future.get()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                if (params.picked != null) { proxy.close(); return@setAnalyzer }
                val raw = proxy.toBitmap()
                val m = Matrix().apply {
                    postRotate(proxy.imageInfo.rotationDegrees.toFloat())
                    if (front) postScale(-1f, 1f)
                }
                val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                proxy.close()
                val small = Bitmap.createScaledBitmap(upright, 540, (upright.height * 540f / upright.width).toInt(), true)
                frame = Effects.applyAll(small, params.effects, params.amount, params.seed)
                if (counter++ % 10 == 0) {
                    val tiny = Bitmap.createScaledBitmap(small, 120, (small.height * 120f / small.width).toInt(), true)
                    thumbs = Effect.values().associateWith { Effects.apply(tiny, it, 0.5f, 7L) }
                }
            }
            provider.unbindAll()
            cam = provider.bindToLifecycle(
                activity,
                if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                analysis
            )
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { runCatching { future.get().unbindAll() } }
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Preview
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color(0xFF0E0E0E))) {
            frame?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            if (grid) Canvas(Modifier.fillMaxSize()) {
                val c = Color(0x88FFFFFF)
                for (i in 1..2) {
                    drawLine(c, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), 2f)
                    drawLine(c, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), 2f)
                }
            }
            if (picked != null) RoundBtn("✕", Modifier.align(Alignment.TopStart).padding(12.dp)) { haptics.select(); picked = null }
        }
        Spacer(Modifier.height(14.dp))

        // Effect carousel: tap to add/remove; number = order applied
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(Effect.values().toList()) { e ->
                val idx = selected.indexOf(e)
                val sel = idx >= 0
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { haptics.tick(); selected = if (sel) selected - e else selected + e }) {
                    Box(Modifier.size(78.dp)) {
                        Box(
                            Modifier.fillMaxSize().clip(CircleShape)
                                .border(if (sel) 2.dp else 0.dp, Color.White, CircleShape)
                                .background(Color(0xFF222222))
                        ) {
                            thumbs[e]?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            Box(Modifier.size(14.dp).clip(CircleShape).background(Color(0xFF111111)).align(Alignment.Center))
                        }
                        if (sel) Box(
                            Modifier.size(22.dp).clip(CircleShape).background(Color(0xFF2F7BFF)).align(Alignment.BottomEnd),
                            contentAlignment = Alignment.Center
                        ) { Text("${idx + 1}", fontSize = 11.sp, color = Color.White) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(e.label, fontSize = 11.sp, color = if (sel) Color.White else Color.Gray)
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        // MIN—MAX slider + shuffle
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp)).background(Color(0xFF1C1C1C)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("MIN", fontSize = 10.sp, color = Color.Gray)
                Slider(amount, { v -> if ((v * 10).toInt() != (amount * 10).toInt()) haptics.tick(); amount = v }, Modifier.weight(1f).padding(horizontal = 6.dp),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.DarkGray))
                Text("MAX", fontSize = 10.sp, color = Color.Gray)
            }
            RoundBtn("🎲") { haptics.heavy(); seed = System.nanoTime() }
        }
        Spacer(Modifier.height(14.dp))

        // Bottom bar: grid | gallery | shutter | flip | flash
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RoundBtn("#", active = grid) { grid = !grid; haptics.toggle(grid) }
            RoundBtn("🖼") { haptics.select(); galleryLauncher.launch("image/*") }
            Box(
                Modifier.size(72.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(7.dp)
                    .clip(CircleShape).background(Color.White)
                    .clickable { haptics.shutter(); frame?.let { save(activity, it) } }
            )
            RoundBtn("⟲") { haptics.select(); picked = null; front = !front }
            RoundBtn("💡", active = torch) { torch = !torch; haptics.toggle(torch) }
        }
    }
}

@Composable
private fun RoundBtn(label: String, modifier: Modifier = Modifier, active: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.size(52.dp).clip(CircleShape)
            .background(if (active) Color(0xFF2F7BFF) else Color(0xCC1C1C1C)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, fontSize = 20.sp, color = Color.White) }
}

private fun save(activity: ComponentActivity, bmp: Bitmap) {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "SyntaxCam_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SyntaxCam")
    }
    val uri = activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    if (uri != null) {
        activity.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        Toast.makeText(activity, "Saved to Pictures/SyntaxCam", Toast.LENGTH_SHORT).show()
    }
}
