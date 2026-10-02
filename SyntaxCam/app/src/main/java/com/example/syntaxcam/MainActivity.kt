package com.example.syntaxcam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

private class Params {
    @Volatile var effect = Effect.TIME
    @Volatile var amount = 0.5f
    @Volatile var seed = 1L
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { CameraScreen() } }
    }
}

@Composable
fun CameraScreen() {
    val ctx = LocalContext.current
    val activity = ctx as ComponentActivity
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    val params = remember { Params() }
    var effect by remember { mutableStateOf(Effect.TIME) }
    var amount by remember { mutableFloatStateOf(0.5f) }
    var front by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var thumbs by remember { mutableStateOf<Map<Effect, Bitmap>>(emptyMap()) }
    params.effect = effect; params.amount = amount
    val executor = remember { Executors.newSingleThreadExecutor() }

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
                val raw = proxy.toBitmap()
                val m = Matrix().apply {
                    postRotate(proxy.imageInfo.rotationDegrees.toFloat())
                    if (front) postScale(-1f, 1f)
                }
                val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                proxy.close()
                val scale = 540f / upright.width
                val small = Bitmap.createScaledBitmap(upright, 540, (upright.height * scale).toInt(), true)
                frame = Effects.apply(small, params.effect, params.amount, params.seed)
                if (counter++ % 10 == 0) {
                    val tiny = Bitmap.createScaledBitmap(small, 120, (small.height * 120f / small.width).toInt(), true)
                    thumbs = Effect.values().associateWith { Effects.apply(tiny, it, 0.5f, 7L) }
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(
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
        // Preview card with corner buttons
        Box(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color(0xFF0E0E0E))
        ) {
            frame?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            RoundBtn("⟲", Modifier.align(Alignment.BottomEnd).padding(12.dp)) { front = !front }
        }
        Spacer(Modifier.height(14.dp))

        // Effect carousel (circular thumbs + label)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(Effect.values().toList()) { e ->
                val sel = e == effect
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { effect = e }) {
                    Box(
                        Modifier.size(78.dp).clip(CircleShape)
                            .border(if (sel) 2.dp else 0.dp, Color.White, CircleShape)
                            .background(Color(0xFF222222))
                    ) {
                        thumbs[e]?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        Box(Modifier.size(14.dp).clip(CircleShape).background(Color(0xFF111111)).align(Alignment.Center))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(e.label, fontSize = 11.sp, color = if (sel) Color.White else Color.Gray)
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        // Controls: MIN—slider—MAX + shuffle + shutter
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp)).background(Color(0xFF1C1C1C)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("MIN", fontSize = 10.sp, color = Color.Gray)
                Slider(amount, { amount = it }, Modifier.weight(1f).padding(horizontal = 6.dp),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.DarkGray))
                Text("MAX", fontSize = 10.sp, color = Color.Gray)
            }
            RoundBtn("🎲") { params.seed = System.nanoTime() }
        }
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier.size(72.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(7.dp)
                .clip(CircleShape).background(Color.White)
                .clickable { frame?.let { save(activity, it) } }
        )
    }
}

@Composable
private fun RoundBtn(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(52.dp).clip(CircleShape).background(Color(0xCC1C1C1C)).clickable(onClick = onClick),
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
