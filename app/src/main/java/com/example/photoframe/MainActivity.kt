package com.example.photoframe

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.exifinterface.media.ExifInterface
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs

import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class PhotoDbHelper(context: Context) : SQLiteOpenHelper(context, "photos.db", null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE photos (path TEXT PRIMARY KEY, width INTEGER, height INTEGER, last_modified INTEGER, exif_date INTEGER, lat REAL, lng REAL)")
        createIndexes(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) db.execSQL("ALTER TABLE photos ADD COLUMN exif_date INTEGER DEFAULT 0")
        if (oldV < 3) { db.execSQL("ALTER TABLE photos ADD COLUMN lat REAL DEFAULT 0.0"); db.execSQL("ALTER TABLE photos ADD COLUMN lng REAL DEFAULT 0.0") }
        if (oldV < 4) createIndexes(db)
    }
    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_date ON photos(exif_date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_location ON photos(lat, lng)")
    }
}

data class SimplePhoto(val path: String, val width: Int, val height: Int, val exifDate: Long, val lat: Double, val lng: Double)
enum class TransitionType { FADE, SLIDE_HORIZONTAL, SLIDE_VERTICAL, SCALE }
enum class OverlayPosition { AUTO, BOTTOM_LEFT, BOTTOM_RIGHT, TOP_LEFT, TOP_RIGHT }

data class PhotoMetadata(
    val fileName: String, val date: String? = null, val time: String? = null,
    val location: String? = null, val ageInfo: String? = null,
    val isFetchingLocation: Boolean = false, val latitude: Double? = null, val longitude: Double? = null,
    val rawExifDate: Long = 0L, val city: String? = null,
    val detectedAlignment: Alignment = Alignment.BottomStart,
    val themeColor: Color = Color.Black.copy(alpha = 0.6f),
    val textColor: Color = Color.White
)

data class AppSettings(val isShuffle: Boolean = true, val durationSeconds: Int = 10, val isHighlightMode: Boolean = false, val overlayPos: OverlayPosition = OverlayPosition.AUTO)

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var prefs: SharedPreferences
    private val JAE_HYUN_BIRTH_CAL = Calendar.getInstance().apply { set(2017, Calendar.DECEMBER, 11) }
    private var sensorManager: SensorManager? = null
    private var isCharging by mutableStateOf(false)
    private var isFlat by mutableStateOf(false)
    private var isSleepTime by mutableStateOf(false)

    val customImageLoader by lazy {
        ImageLoader.Builder(this).memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
            .diskCache { DiskCache.Builder().directory(this.cacheDir.resolve("image_cache")).maxSizeBytes(512L * 1024 * 1024).build() }.build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("photo_frame_settings", Context.MODE_PRIVATE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        registerReceiver(powerReceiver, IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED); addAction(Intent.ACTION_BATTERY_CHANGED) })
        setContent {
            CompositionLocalProvider(coil.compose.LocalImageLoader provides customImageLoader) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    LaunchedEffect(Unit) { while (true) { isSleepTime = checkIsSleepTime(); updateSystemKeepScreenOn(); delay(60000L) } }
                    val initialSettings = remember { AppSettings(prefs.getBoolean("is_shuffle", true), prefs.getInt("duration_seconds", 10), prefs.getBoolean("is_highlight", false), OverlayPosition.valueOf(prefs.getString("overlay_pos", "AUTO") ?: "AUTO")) }
                    PhotoFrameScreen(initialSettings, JAE_HYUN_BIRTH_CAL, ::saveSettings, customImageLoader, isSleepTime, isCharging, isFlat)
                }
            }
        }
    }
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val s = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            isCharging = s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL; updateSystemKeepScreenOn()
        }
    }
    override fun onResume() { super.onResume(); sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) } }
    override fun onPause() { super.onPause(); sensorManager?.unregisterListener(this) }
    override fun onDestroy() { super.onDestroy(); unregisterReceiver(powerReceiver) }
    override fun onSensorChanged(event: SensorEvent?) { if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) { isFlat = abs(event.values[2]) > 9.0 && abs(event.values[0]) < 2.0 && abs(event.values[1]) < 2.0; updateSystemKeepScreenOn() } }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    private fun checkIsSleepTime(): Boolean = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) in 1..5
    private fun updateSystemKeepScreenOn() { if (!isSleepTime && isCharging && !isFlat) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    private fun saveSettings(s: AppSettings) { prefs.edit().apply { putBoolean("is_shuffle", s.isShuffle); putInt("duration_seconds", s.durationSeconds); putBoolean("is_highlight", s.isHighlightMode); putString("overlay_pos", s.overlayPos.name); apply() } }
}

@Composable
fun PhotoFrameScreen(initialSettings: AppSettings, birthCal: Calendar, onSaveSettings: (AppSettings) -> Unit, imageLoader: ImageLoader, isSleepTime: Boolean, isCharging: Boolean, isFlat: Boolean) {
    val context = LocalContext.current
    val requiredPermissions = mutableListOf<String>().apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.READ_MEDIA_IMAGES) else add(Manifest.permission.READ_EXTERNAL_STORAGE); add(Manifest.permission.ACCESS_MEDIA_LOCATION) }
    var permissionsGranted by remember { mutableStateOf(requiredPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results -> permissionsGranted = results.values.all { it } }
    LaunchedEffect(Unit) { if (!permissionsGranted) launcher.launch(requiredPermissions.toTypedArray()) }
    var settings by remember { mutableStateOf(initialSettings) }
    var isSettingsOpen by remember { mutableStateOf(false) }
    var resetTrigger by remember { mutableStateOf(0) }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (permissionsGranted) {
            if (isSleepTime || !isCharging || isFlat) StandbyScreen(isSleepTime, !isCharging, isFlat)
            else {
                PhotoSlideshow(settings, birthCal, { isSettingsOpen = true }, resetTrigger, imageLoader)
                if (isSettingsOpen) SettingsDialog(settings, { settings = it; onSaveSettings(it) }, { isSettingsOpen = false }, { resetTrigger++ })
            }
        } else Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("권한 필요", color = Color.White) }
    }
}

@Composable
fun StandbyScreen(isSleep: Boolean, isNotCharging: Boolean, isFlat: Boolean) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val icon = if (isSleep) Icons.Default.NightsStay else if (isNotCharging) Icons.Default.BatteryAlert else Icons.Default.StayCurrentPortrait
            Icon(icon, null, tint = Color.Gray, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            Text(if (isSleep) "취침 모드" else if (isNotCharging) "충전기 연결 필요" else "보관 모드 (바닥)", color = Color.Gray, fontSize = 20.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PhotoSlideshow(settings: AppSettings, birthCal: Calendar, onOpenSettings: () -> Unit, resetTrigger: Int, imageLoader: ImageLoader) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val dbHelper = remember { PhotoDbHelper(context) }
    var photoPool by remember { mutableStateOf<List<SimplePhoto>>(listOf()) }
    var isIndexing by remember { mutableStateOf(false) }
    var currentPhotoFile by remember { mutableStateOf<File?>(null) }
    var transitionType by remember { mutableStateOf(TransitionType.FADE) }
    var metadata by remember { mutableStateOf<PhotoMetadata?>(null) }
    var isPaused by remember { mutableStateOf(false) }
    var currentPoolIndex by remember { mutableStateOf(-1) }
    var showUI by remember { mutableStateOf(false) }
    var slideProgress by remember { mutableStateOf(0f) }
    var relatedModeEndTime by remember { mutableStateOf(0L) }
    val isRelatedModeActive = remember(relatedModeEndTime) { System.currentTimeMillis() < relatedModeEndTime }
    var relatedPhotoPaths by remember { mutableStateOf<Set<String>>(setOf()) }

    var isLongPressing by remember { mutableStateOf(false) }
    val effectivePaused = isPaused || isLongPressing
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { z, o, _ -> if (effectivePaused) { scale *= z; offset += o } }
    val relatedEffectScale by animateFloatAsState(targetValue = if (isRelatedModeActive) 1.05f else 1.0f, animationSpec = tween(1000))
    val animScale = remember { Animatable(1f) }; val animTranslationX = remember { Animatable(0f) }; val animTranslationY = remember { Animatable(0f) }; val animRotation = remember { Animatable(0f) }

    val faceDetector = remember { FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build()) }
    var lastCornerIndex by remember { mutableStateOf(0) }

    LaunchedEffect(Unit, resetTrigger) {
        isIndexing = true
        withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            if (resetTrigger > 0) db.delete("photos", null, null)
            val initialList = mutableListOf<SimplePhoto>()
            db.rawQuery("SELECT path, width, height, exif_date, lat, lng FROM photos", null).use { c -> while (c.moveToNext()) initialList.add(SimplePhoto(c.getString(0), c.getInt(1), c.getInt(2), c.getLong(3), c.getDouble(4), c.getDouble(5))) }
            withContext(Dispatchers.Main) { photoPool = initialList }
            val photoDir = File(Environment.getExternalStorageDirectory(), "Media/Photoframe")
            if (photoDir.exists()) {
                photoDir.walk().filter { it.isFile && it.extension.lowercase() in listOf("jpg", "jpeg", "png") }.forEach { file ->
                    val c = db.rawQuery("SELECT last_modified FROM photos WHERE path = ?", arrayOf(file.absolutePath))
                    if (!c.moveToFirst() || c.getLong(0) != file.lastModified()) {
                        val exif = ExifInterface(file.absolutePath); val (w, h) = getImageDimensions(file); val exifD = getExifDate(file); val ll = FloatArray(2); exif.getLatLong(ll)
                        db.insertWithOnConflict("photos", null, ContentValues().apply { put("path", file.absolutePath); put("width", w); put("height", h); put("last_modified", file.lastModified()); put("exif_date", exifD); put("lat", ll[0].toDouble()); put("lng", ll[1].toDouble()) }, SQLiteDatabase.CONFLICT_REPLACE)
                    }
                    c.close()
                }
                val finalList = mutableListOf<SimplePhoto>()
                db.rawQuery("SELECT path, width, height, exif_date, lat, lng FROM photos", null).use { c -> while (c.moveToNext()) finalList.add(SimplePhoto(c.getString(0), c.getInt(1), c.getInt(2), c.getLong(3), c.getDouble(4), c.getDouble(5))) }
                withContext(Dispatchers.Main) { photoPool = finalList }
            }
        }
        isIndexing = false
    }

    val changePhoto = {
        var base = if (isRelatedModeActive) photoPool.filter { it.path in relatedPhotoPaths } else photoPool
        if (settings.isHighlightMode && !isRelatedModeActive) {
            val today = Calendar.getInstance()
            base = base.filter { p -> val pCal = Calendar.getInstance().apply { timeInMillis = p.exifDate }; pCal.get(Calendar.MONTH) == today.get(Calendar.MONTH) && pCal.get(Calendar.DAY_OF_MONTH) == today.get(Calendar.DAY_OF_MONTH) }
        }
        val filtered = base.filter { if (isLandscape) it.width >= it.height else it.height > it.width }.sortedBy { it.path }
        if (filtered.isNotEmpty()) {
            currentPoolIndex = if (settings.isShuffle) filtered.indices.random() else (currentPoolIndex + 1) % filtered.size
            currentPhotoFile = File(filtered[currentPoolIndex].path); transitionType = TransitionType.values().random(); slideProgress = 0f
            scale = 1f; offset = Offset.Zero
            if (filtered.size > 1) { val nIdx = (currentPoolIndex + 1) % filtered.size; imageLoader.enqueue(ImageRequest.Builder(context).data(File(filtered[nIdx].path)).size(2000).build()) }
        }
    }

    val scope = rememberCoroutineScope()
    LaunchedEffect(currentPhotoFile) { 
        currentPhotoFile?.let { f -> 
            val res = withContext(Dispatchers.IO) {
                val baseMeta = extractEnhancedMetadata(context, f, birthCal)
                var align = Alignment.BottomStart
                var tColor = Color.Black.copy(alpha = 0.6f)
                var txtColor = Color.White
                try {
                    val req = ImageRequest.Builder(context).data(f).size(500).build()
                    val r = imageLoader.execute(req)
                    if (r is SuccessResult) {
                        val bmp = r.drawable.toBitmap()
                        val faces = faceDetector.process(InputImage.fromBitmap(bmp, 0)).await()
                        align = selectSmartCorner(bmp.width, bmp.height, faces, lastCornerIndex)
                        lastCornerIndex = (lastCornerIndex + 1) % 4
                        val palette = Palette.from(bmp).generate()
                        val dominant = palette.getVibrantColor(AndroidColor.BLACK)
                        tColor = Color(dominant).copy(alpha = 0.7f)
                        txtColor = if (AndroidColor.red(dominant) * 0.299 + AndroidColor.green(dominant) * 0.587 + AndroidColor.blue(dominant) * 0.114 > 150) Color.Black else Color.White
                    }
                } catch(e:Exception) { }
                baseMeta.copy(detectedAlignment = align, themeColor = tColor, textColor = txtColor)
            }
            metadata = res
            scope.launch {
                animScale.snapTo(1f); animTranslationX.snapTo(0f); animTranslationY.snapTo(0f); animRotation.snapTo(0f)
                when ((0..3).random()) {
                    0 -> { launch { animScale.animateTo(1.15f, tween(settings.durationSeconds * 1000, easing = LinearEasing)) }; launch { animTranslationX.animateTo(50f, tween(settings.durationSeconds * 1000, easing = LinearEasing)) } }
                    1 -> { animScale.snapTo(1.15f); launch { animScale.animateTo(1f, tween(settings.durationSeconds * 1000, easing = LinearEasing)) }; launch { animRotation.animateTo(5f, tween(settings.durationSeconds * 1000, easing = LinearEasing)) } }
                    2 -> { animScale.snapTo(1.1f); animTranslationX.snapTo(-50f); launch { animTranslationX.animateTo(50f, tween(settings.durationSeconds * 1000, easing = LinearEasing)) } }
                    3 -> { animScale.snapTo(1.1f); animTranslationY.snapTo(-50f); launch { animTranslationY.animateTo(50f, tween(settings.durationSeconds * 1000, easing = LinearEasing)) }; launch { animRotation.animateTo(-3f, tween(settings.durationSeconds * 1000, easing = LinearEasing)) } }
                }
            }
        } 
    }
    LaunchedEffect(photoPool, isLandscape, isRelatedModeActive, settings.isHighlightMode) { if (currentPhotoFile == null && photoPool.isNotEmpty()) changePhoto() }
    LaunchedEffect(effectivePaused, settings, isLandscape) { if (!effectivePaused) { val steps = 100; val stepD = (settings.durationSeconds * 1000L) / steps; while (true) { if (slideProgress >= 1f) { slideProgress = 0f; changePhoto() }; delay(stepD); slideProgress += 1f / steps } } }
    LaunchedEffect(showUI) { if (showUI) { delay(3000L); showUI = false } }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(
                onPress = { val pTime = System.currentTimeMillis(); isLongPressing = true; try { awaitRelease() } finally { isLongPressing = false
                        if (System.currentTimeMillis() - pTime > 500 && scale == 1f) {
                            val f = currentPhotoFile; val m = metadata
                            if (f != null && m != null) { val rel = photoPool.filter { p -> (File(p.path).parent == f.parent) || (m.rawExifDate > 0 && p.exifDate > 0 && abs(p.exifDate - m.rawExifDate) <= TimeUnit.DAYS.toMillis(30)) || (m.city != null && p.lat != 0.0 && calculateDistance(m.latitude ?: 0.0, m.longitude ?: 0.0, p.lat, p.lng) < 50000) }.map { it.path }.toSet()
                                if (rel.isNotEmpty()) { relatedPhotoPaths = rel; relatedModeEndTime = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(2) } }
                        } } },
                onTap = { showUI = !showUI }
            )
        }.transformable(state = transformState)) {
            AnimatedContent(targetState = currentPhotoFile, transitionSpec = { getTransitionSpec(transitionType) }, label = "PhotoTransition", modifier = Modifier.scale(relatedEffectScale)) { file ->
                Box(Modifier.fillMaxSize()) {
                    if (file != null) {
                        AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(file).memoryCacheKey(file.absolutePath).build(), contentDescription = null,
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                if (isLongPressing || scale > 1f) { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
                                else { scaleX = animScale.value; scaleY = animScale.value; translationX = animTranslationX.value; translationY = animTranslationY.value; rotationZ = animRotation.value }
                            }, contentScale = ContentScale.Fit)
                    }
                }
            }
        }
        metadata?.let { PhotoInfoOverlay(it) }
        LinearProgressIndicator(progress = slideProgress, modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomCenter), color = Color(0xFFFFEB3B).copy(alpha = 0.8f), trackColor = Color.Transparent)
        if (showUI) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxHeight().weight(1f).clickable { val f = photoPool.filter { if (isLandscape) it.width >= it.height else it.height > it.width }.sortedBy { it.path }; if (f.isNotEmpty()) { currentPoolIndex = if (currentPoolIndex <= 0) f.size - 1 else currentPoolIndex - 1; currentPhotoFile = File(f[currentPoolIndex].path); transitionType = TransitionType.FADE; slideProgress = 0f } })
                Spacer(Modifier.weight(3f)); Box(Modifier.fillMaxHeight().weight(1f).clickable { changePhoto() })
            }
            Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Color.Black.copy(alpha = 0.5f)).clickable {}.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { isPaused = !isPaused }) { Icon(if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, null, tint = Color.White) }
                Row(verticalAlignment = Alignment.CenterVertically) { if (isIndexing) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White); Spacer(Modifier.width(8.dp)); Text("${photoPool.size}장", color = Color.White); Spacer(Modifier.width(16.dp)); IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, null, tint = Color.White) } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(s: AppSettings, onC: (AppSettings) -> Unit, onClose: () -> Unit, onR: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Surface(Modifier.width(450.dp).clickable(enabled = false) {}, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text("액자 설정", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(s.isShuffle, { onC(s.copy(isShuffle = it)) }); Text("랜덤 재생") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(s.isHighlightMode, { onC(s.copy(isHighlightMode = it)) }); Text("과거 오늘 사진만 보기") }
                Spacer(Modifier.height(16.dp)); Text("정보창 위치 (Smart Placement)")
                Row(Modifier.selectableGroup()) {
                    listOf(OverlayPosition.AUTO, OverlayPosition.BOTTOM_LEFT, OverlayPosition.BOTTOM_RIGHT).forEach { pos ->
                        Row(Modifier.weight(1f).selectable(selected = s.overlayPos == pos, onClick = { onC(s.copy(overlayPos = pos)) }, role = Role.RadioButton), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = s.overlayPos == pos, onClick = null); Text(pos.name.replace("_", " ").lowercase(), fontSize = 12.sp) }
                    }
                }
                Spacer(Modifier.height(16.dp)); Text("노출 시간")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(3, 10, 20).forEach { t -> FilterChip(selected = s.durationSeconds == t, onClick = { onC(s.copy(durationSeconds = t)) }, label = { Text("${t}초") }) } }
                Spacer(Modifier.height(24.dp)); Button(onClick = { onR(); onClose() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("DB 초기화") }
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("닫기") }
            }
        }
    }
}

@Composable
fun PhotoInfoOverlay(meta: PhotoMetadata) {
    Box(Modifier.fillMaxSize().padding(32.dp)) {
        Surface(modifier = Modifier.align(meta.detectedAlignment).animateContentSize(), color = meta.themeColor, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(16.dp)) {
                meta.ageInfo?.let { Text(it, color = Color(0xFFFFEB3B), fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                Text(meta.fileName, color = meta.textColor.copy(alpha = 0.6f), fontSize = 12.sp)
                Text("${meta.date} ${meta.time}", color = meta.textColor, fontSize = 16.sp)
                meta.location?.let { Text(it, color = meta.textColor.copy(alpha = 0.9f), fontSize = 14.sp) }
            }
        }
    }
}

private fun selectSmartCorner(w: Int, h: Int, faces: List<com.google.mlkit.vision.face.Face>, lastIdx: Int): Alignment {
    val corners = listOf(Alignment.BottomStart, Alignment.BottomEnd, Alignment.TopStart, Alignment.TopEnd)
    for (i in 0..3) {
        val nextIdx = (lastIdx + i + 1) % 4
        val cornerRect = when(nextIdx) { 0 -> Rect(0, h-300, 400, h); 1 -> Rect(w-400, h-300, w, h); 2 -> Rect(0, 0, 400, 300); 3 -> Rect(w-400, 0, w, 300); else -> Rect(0, 0, 0, 0) }
        if (!faces.any { Rect.intersects(it.boundingBox, cornerRect) }) return corners[nextIdx]
    }
    return corners[(lastIdx + 1) % 4]
}

private fun getLatLong(file: File): Pair<Double, Double> {
    val exif = ExifInterface(file.absolutePath); val ll = FloatArray(2); return if (exif.getLatLong(ll)) Pair(ll[0].toDouble(), ll[1].toDouble()) else Pair(0.0, 0.0)
}
private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371e3; val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val da = Math.sin(Math.toRadians(lat2 - lat1) / 2).let { it * it } + Math.cos(p1) * Math.cos(p2) * Math.sin(Math.toRadians(lon2 - lon1) / 2).let { it * it }; return r * 2 * Math.atan2(Math.sqrt(da), Math.sqrt(1 - da))
}
private fun getExifDate(file: File): Long = try { ExifInterface(file.absolutePath).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { SimpleDateFormat("yyyy:MM:dd HH:mm:ss").parse(it).time } ?: file.lastModified() } catch(e:Exception) { file.lastModified() }
private fun getImageDimensions(f: File): Pair<Int, Int> {
    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }; android.graphics.BitmapFactory.decodeFile(f.absolutePath, o); return if (ExifInterface(f.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1).let { it == 6 || it == 8 }) Pair(o.outHeight, o.outWidth) else Pair(o.outWidth, o.outHeight)
}
private fun extractEnhancedMetadata(context: Context, f: File, b: Calendar): PhotoMetadata {
    val exif = ExifInterface(f.absolutePath); val dStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
    var fD = ""; var fT = ""; var age = ""; var rD = 0L; var city: String? = null; var lat: Double? = null; var lng: Double? = null; var loc: String? = null
    dStr?.let { try { val d = SimpleDateFormat("yyyy:MM:dd HH:mm:ss").parse(it); rD = d.time; fD = SimpleDateFormat("yyyy년 M월 d일").format(d); fT = SimpleDateFormat("HH:mm").format(d)
        val pC = Calendar.getInstance().apply { time = d }; val pY = pC.get(Calendar.YEAR); age = when { pY >= 2021 -> "재현 ${pY - 2017 + 1}살"; pY >= 2019 -> "재현 ${((pY - 2017) * 12) + (pC.get(Calendar.MONTH) - b.get(Calendar.MONTH))}개월"; else -> "재현 D+${TimeUnit.MILLISECONDS.toDays(pC.timeInMillis - b.timeInMillis) + 1}" } } catch(e:Exception) {} }
    val ll = FloatArray(2); if (exif.getLatLong(ll)) { lat = ll[0].toDouble(); lng = ll[1].toDouble()
        if (Geocoder.isPresent()) { try { val addr = Geocoder(context, Locale.KOREA).getFromLocation(lat, lng, 1)
                if (!addr.isNullOrEmpty()) { val a = addr[0]; city = a.locality ?: a.adminArea; loc = listOfNotNull(a.countryName, city, a.subLocality, a.thoroughfare).joinToString("/") } } catch(e:Exception) {} } }
    return PhotoMetadata(f.name, fD, fT, loc, age, false, lat, lng, rD, city)
}
private fun getTransitionSpec(t: TransitionType): ContentTransform {
    val d = 1500; return when (t) { TransitionType.FADE -> fadeIn(tween(d)) togetherWith fadeOut(tween(d)); TransitionType.SCALE -> scaleIn(tween(d), 0.8f) + fadeIn() togetherWith scaleOut(tween(d), 1.2f) + fadeOut(); else -> slideInHorizontally(tween(d), { it }) + fadeIn() togetherWith slideOutHorizontally(tween(d), { -it }) + fadeOut() }
}
fun scanPhotos(d: File): List<File> = if (d.exists()) d.walk().filter { it.isFile && it.extension.lowercase() in listOf("jpg", "jpeg", "png") }.toList() else listOf()
