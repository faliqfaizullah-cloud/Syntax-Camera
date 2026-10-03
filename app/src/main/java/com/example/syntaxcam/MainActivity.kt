package com.example.syntaxcam

import android.Manifest
import android.content.ContentValues
import android.content.Context
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
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

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
                var gallery by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    if (!welcome) CameraScreen(onOpenGallery = { gallery = true })
                    if (gallery) GalleryScreen(onClose = { gallery = false })
                    if (welcome) WelcomeScreen { welcome = false }
                }
            }
        }
    }
}

@Composable
fun CameraScreen(onOpenGallery: () -> Unit = {}) {
    val ctx = LocalContext.current
    val activity = ctx as ComponentActivity
    val prefs = remember { ctx.getSharedPreferences("syntaxcam", Context.MODE_PRIVATE) }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(Manifest.permission.CAMERA) }

    val params = remember { Params() }
    val view = LocalView.current
    val haptics = remember(view) { Haptics(view) }
    var hapticsOn by remember { mutableStateOf(prefs.getBoolean("haptics", true)) }
    haptics.enabled = hapticsOn

    // settings
    var wantRaw by remember { mutableStateOf(prefs.getBoolean("raw", false)) }
    var maxQuality by remember { mutableStateOf(prefs.getBoolean("maxq", true)) }
    var showSettings by remember { mutableStateOf(false) }
    var rawSupported by remember { mutableStateOf(false) }
    var rawActive by remember { mutableStateOf(false) }

    // camera state
    var cam by remember { mutableStateOf<Camera?>(null) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var zoomMin by remember { mutableFloatStateOf(1f) }
    var zoomMax by remember { mutableFloatStateOf(1f) }
    var ev by remember { mutableIntStateOf(0) }
    var evLo by remember { mutableIntStateOf(0) }
    var evHi by remember { mutableIntStateOf(0) }
    var focusPt by remember { mutableStateOf<Offset?>(null) }
    var flashing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf<RevealData?>(null) }
    var revealOn by remember { mutableStateOf(prefs.getBoolean("reveal", true)) }
    val onReveal: (RevealData) -> Unit = { r -> if (revealOn) reveal = r else toast(activity, "Saved ${r.note}") }

    var selected by remember { mutableStateOf(listOf(Effect.TIME)) }
    var amount by remember { mutableFloatStateOf(0.5f) }
    var seed by remember { mutableLongStateOf(1L) }
    var front by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Bitmap?>(null) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var thumbs by remember { mutableStateOf<Map<Effect, Bitmap>>(emptyMap()) }
    params.effects = selected; params.amount = amount; params.seed = seed; params.picked = picked
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val ioExecutor = remember { Executors.newSingleThreadExecutor() }

    // Gallery import
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runCatching {
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, _, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            picked = Bitmap.createScaledBitmap(bmp, 540, (bmp.height * 540f / bmp.width).toInt(), true)
        }
    }
    LaunchedEffect(picked, selected, amount, seed) {
        val p = picked ?: return@LaunchedEffect
        frame = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Effects.applyAll(p, selected, amount, seed) }
    }
    LaunchedEffect(torch, cam) { runCatching { cam?.cameraControl?.enableTorch(torch) } }
    LaunchedEffect(focusPt) { if (focusPt != null) { delay(900); focusPt = null } }
    LaunchedEffect(flashing) { if (flashing) { delay(130); flashing = false } }

    // Bind camera: live analysis (effects preview) + full-resolution ImageCapture (JPEG or RAW+JPEG)
    DisposableEffect(granted, front, wantRaw, maxQuality) {
        if (!granted) return@DisposableEffect onDispose {}
        val future = ProcessCameraProvider.getInstance(ctx)
        var counter = 0
        future.addListener({
            runCatching {
                val provider = future.get()
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

                fun makeAnalysis(): ImageAnalysis {
                    val a = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                    a.setAnalyzer(analysisExecutor) { proxy ->
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
                    return a
                }

                val info = provider.getCameraInfo(selector)
                val canRaw = ImageCapture.getImageCaptureCapabilities(info)
                    .supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
                rawSupported = canRaw

                fun buildCapture(raw: Boolean) = ImageCapture.Builder()
                    .setCaptureMode(if (maxQuality) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(97)
                    .apply { if (raw) setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG) }
                    .build()

                var useRaw = wantRaw && canRaw
                var cap = buildCapture(useRaw)
                provider.unbindAll()
                val camera: Camera = try {
                    provider.bindToLifecycle(activity, selector, makeAnalysis(), cap)
                } catch (e: Exception) {
                    if (!useRaw) throw e
                    useRaw = false
                    Toast.makeText(ctx, "RAW can't run with live effects on this camera. Using JPEG.", Toast.LENGTH_LONG).show()
                    provider.unbindAll()
                    cap = buildCapture(false)
                    provider.bindToLifecycle(activity, selector, makeAnalysis(), cap)
                }
                capture = cap
                rawActive = useRaw
                cam = camera
                zoom = 1f
                camera.cameraInfo.zoomState.value?.let { zoomMin = it.minZoomRatio; zoomMax = it.maxZoomRatio }
                val es = camera.cameraInfo.exposureState
                if (es.isExposureCompensationSupported) {
                    evLo = es.exposureCompensationRange.lower; evHi = es.exposureCompensationRange.upper
                } else { evLo = 0; evHi = 0 }
                ev = 0
            }.onFailure {
                Toast.makeText(ctx, "Camera error: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { runCatching { future.get().unbindAll() } }
    }

    // Shutter: full-resolution capture, effects applied at up to ~3000 px, optional DNG alongside
    val shoot: () -> Unit = shoot@{
        haptics.shutter()
        flashing = true
        val cap = capture
        val f = frame
        if (picked != null || cap == null) {
            if (f != null) ioExecutor.execute {
                save(activity, f)
                val r = makeReveal(f, "${f.width}×${f.height}")
                activity.runOnUiThread { onReveal(r) }
            }
            return@shoot
        }
        if (busy) return@shoot
        busy = true
        val mirror = front
        val effects = selected; val amt = amount; val sd = seed
        val ts = System.currentTimeMillis()

        if (rawActive) {
            val tmp = File(activity.cacheDir, "raw_$ts.jpg")
            val jpgOpts = ImageCapture.OutputFileOptions.Builder(tmp).build()
            val dngValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "SyntaxCam_$ts.dng")
                put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SyntaxCam")
            }
            val dngOpts = ImageCapture.OutputFileOptions.Builder(
                activity.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, dngValues).build()
            cap.takePicture(dngOpts, jpgOpts, ioExecutor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    if (results.savedUri?.path != tmp.absolutePath) return   // the DNG finished
                    try {
                        var bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(tmp)) { d, _, _ ->
                            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                        if (mirror) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height,
                            Matrix().apply { postScale(-1f, 1f) }, true)
                        processAndSave(activity, bmp, effects, amt, sd, " + RAW", onReveal)
                    } catch (e: Throwable) {
                        toast(activity, "Save failed: ${e.message}")
                    } finally { tmp.delete(); busy = false }
                }
                override fun onError(exception: ImageCaptureException) {
                    busy = false; toast(activity, "Capture failed: ${exception.message}")
                }
            })
        } else {
            cap.takePicture(ioExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val raw = image.toBitmap()
                        val m = Matrix().apply {
                            postRotate(image.imageInfo.rotationDegrees.toFloat())
                            if (mirror) postScale(-1f, 1f)
                        }
                        val up = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                        processAndSave(activity, up, effects, amt, sd, "", onReveal)
                    } catch (e: Throwable) {
                        toast(activity, "Save failed: ${e.message}")
                    } finally { image.close(); busy = false }
                }
                override fun onError(exception: ImageCaptureException) {
                    busy = false; toast(activity, "Capture failed: ${exception.message}")
                }
            })
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Preview: pinch to zoom, tap to focus
            Box(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color(0xFF0E0E0E))
                    .pointerInput(cam, picked) {
                        detectTransformGestures { _, _, z, _ ->
                            val c = cam ?: return@detectTransformGestures
                            if (picked != null) return@detectTransformGestures
                            val zs = c.cameraInfo.zoomState.value ?: return@detectTransformGestures
                            zoomMin = zs.minZoomRatio; zoomMax = zs.maxZoomRatio
                            val nz = (zoom * z).coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
                            if (nz.toInt() != zoom.toInt()) haptics.tick()
                            zoom = nz
                            c.cameraControl.setZoomRatio(nz)
                        }
                    }
                    .pointerInput(cam, picked) {
                        detectTapGestures { off ->
                            val c = cam ?: return@detectTapGestures
                            if (picked != null) return@detectTapGestures
                            focusPt = off
                            haptics.tick()
                            val pt = SurfaceOrientedMeteringPointFactory(size.width.toFloat(), size.height.toFloat())
                                .createPoint(off.x, off.y)
                            runCatching { c.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(pt).build()) }
                        }
                    }
            ) {
                frame?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                if (grid) Canvas(Modifier.fillMaxSize()) {
                    val c = Color(0x88FFFFFF)
                    for (i in 1..2) {
                        drawLine(c, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), 2f)
                        drawLine(c, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), 2f)
                    }
                }
                focusPt?.let { p -> Canvas(Modifier.fillMaxSize()) { drawCircle(Color.White, 34.dp.toPx(), p, style = Stroke(3f)) } }
                if (flashing) Box(Modifier.fillMaxSize().background(Color(0x99FFFFFF)))

                if (picked != null) RoundBtn("✕", Modifier.align(Alignment.TopStart).padding(12.dp)) { haptics.select(); picked = null }
                if (rawActive && picked == null) Text(
                    "RAW", Modifier.align(Alignment.TopCenter).padding(top = 18.dp).clip(RoundedCornerShape(10.dp))
                        .background(Color(0xCC1C1C1C)).padding(horizontal = 10.dp, vertical = 4.dp),
                    color = Color(0xFFFFD23F), fontSize = 12.sp)
                Column(Modifier.align(Alignment.TopEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RoundBtn("🗓") { haptics.select(); onOpenGallery() }
                    RoundBtn("⚙") { haptics.select(); showSettings = true }
                }

                // zoom presets + readout
                if (picked == null && zoomMax > zoomMin + 0.01f) Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).clip(RoundedCornerShape(20.dp))
                        .background(Color(0xAA000000)).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(0.5f, 1f, 2f, 5f).filter { it >= zoomMin - 0.01f && it <= zoomMax + 0.01f }.forEach { p ->
                        val on = kotlin.math.abs(zoom - p) < 0.15f
                        Box(
                            Modifier.clip(CircleShape).background(if (on) Color(0xFF2F3340) else Color.Transparent)
                                .clickable {
                                    haptics.tick(); zoom = p
                                    cam?.cameraControl?.setZoomRatio(p)
                                }.padding(horizontal = 12.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) { Text(if (p < 1f) ".5" else "${p.toInt()}×", fontSize = 13.sp, color = if (on) Color(0xFFFFD23F) else Color.White) }
                    }
                    Text(String.format("%.1f×", zoom), Modifier.padding(horizontal = 8.dp), fontSize = 11.sp, color = Color(0xFFAAAAB5))
                }
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
                    Slider(amount, { v -> if ((v * 10).toInt() != (amount * 10).toInt()) haptics.tick(); amount = v },
                        Modifier.weight(1f).padding(horizontal = 6.dp),
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.DarkGray))
                    Text("MAX", fontSize = 10.sp, color = Color.Gray)
                }
                RoundBtn("🎲") { haptics.heavy(); seed = System.nanoTime() }
            }
            Spacer(Modifier.height(14.dp))

            // grid | import | shutter | flip | flash
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                RoundBtn("#", active = grid) { grid = !grid; haptics.toggle(grid) }
                RoundBtn("🖼") { haptics.select(); galleryLauncher.launch("image/*") }
                Box(
                    Modifier.size(72.dp).clip(CircleShape).border(4.dp, if (busy) Color.Gray else Color.White, CircleShape).padding(7.dp)
                        .clip(CircleShape).background(if (busy) Color.Gray else Color.White)
                        .clickable { shoot() }
                )
                RoundBtn("⟲") { haptics.select(); picked = null; front = !front }
                RoundBtn("💡", active = torch) { torch = !torch; haptics.toggle(torch) }
            }
        }

        reveal?.let { CaptureReveal(it, haptics) { reveal = null } }

        if (showSettings) Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable { showSettings = false }) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Color(0xFF121218))
                    .pointerInput(Unit) { detectTapGestures { } }
                    .navigationBarsPadding().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Camera settings", color = Color.White, fontSize = 20.sp)

                Label("FORMAT")
                Seg(listOf("JPEG", "RAW + JPEG"), if (wantRaw) 1 else 0, listOf(true, rawSupported)) {
                    wantRaw = it == 1; prefs.edit().putBoolean("raw", wantRaw).apply(); haptics.tick()
                }
                Text(
                    when {
                        !rawSupported -> "RAW isn't available on this camera."
                        wantRaw && !rawActive -> "RAW couldn't start together with live effects; saving JPEG."
                        wantRaw -> "Saves a .dng file in Pictures/SyntaxCam next to the processed JPEG."
                        else -> "Photos are captured at the camera's full resolution."
                    }, color = Color(0xFF7C7C88), fontSize = 12.sp
                )

                Label("QUALITY")
                Seg(listOf("Maximum", "Fast"), if (maxQuality) 0 else 1, listOf(true, true)) {
                    maxQuality = it == 0; prefs.edit().putBoolean("maxq", maxQuality).apply(); haptics.tick()
                }

                if (evHi > evLo) {
                    Label("EXPOSURE   ${if (ev > 0) "+" else ""}$ev")
                    Slider(
                        ev.toFloat(),
                        { v ->
                            val i = v.roundToInt()
                            if (i != ev) { ev = i; cam?.cameraControl?.setExposureCompensationIndex(i); haptics.tick() }
                        },
                        valueRange = evLo.toFloat()..evHi.toFloat(),
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.DarkGray)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("CAPTURE GLOW"); Spacer(Modifier.weight(1f))
                    Switch(revealOn, { revealOn = it; prefs.edit().putBoolean("reveal", it).apply() })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("HAPTICS"); Spacer(Modifier.weight(1f))
                    Switch(hapticsOn, { hapticsOn = it; prefs.edit().putBoolean("haptics", it).apply() })
                }
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF22222A))
                        .clickable { haptics.select(); showSettings = false }.padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Done", color = Color.White, fontSize = 15.sp) }
            }
        }
    }
}

@Composable
private fun Label(t: String) = Text(t, color = Color(0xFF8A8A96), fontSize = 11.sp, letterSpacing = 1.5.sp)

@Composable
private fun Seg(options: List<String>, selected: Int, enabled: List<Boolean>, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C1C24)).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                    .background(if (on) Color(0xFF2F3340) else Color.Transparent)
                    .clickable(enabled = enabled[i]) { onSelect(i) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, fontSize = 13.sp,
                    color = if (!enabled[i]) Color(0xFF555560) else if (on) Color.White else Color(0xFF9A9AA6))
            }
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

private fun toast(activity: ComponentActivity, msg: String) =
    activity.runOnUiThread { Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show() }

/** Applies the chosen effects (capped at ~3000 px so memory stays safe) and saves to Pictures/SyntaxCam + the in-app gallery. */
private fun processAndSave(activity: ComponentActivity, src: Bitmap, effects: List<Effect>, amount: Float, seed: Long, note: String, onDone: (RevealData) -> Unit) {
    var bmp = src
    val m = maxOf(bmp.width, bmp.height)
    if (effects.isNotEmpty() && m > 3072) {
        val s = 3072f / m
        bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
    }
    val result = if (effects.isEmpty()) bmp else Effects.applyAll(bmp, effects, amount, seed)
    save(activity, result)
    val r = makeReveal(result, "${result.width}×${result.height}$note")
    activity.runOnUiThread { onDone(r) }
}

private fun save(activity: ComponentActivity, bmp: Bitmap) {
    Shots.add(activity, bmp)
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "SyntaxCam_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SyntaxCam")
    }
    val uri = activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    if (uri != null) activity.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 97, it) }
}
