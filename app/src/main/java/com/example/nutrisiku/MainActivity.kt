package com.example.nutrisiku

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.nutrisiku.ui.theme.NutrisikuTheme
import java.io.File
import java.util.concurrent.Executors


class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NutrisikuTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavigator(viewModel)
                }
            }
        }
    }
}

// Komponen untuk mengatur navigasi antar halaman
@Composable
fun AppNavigator(viewModel: MainViewModel) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

    val context = LocalContext.current
    val yoloDetectorHelper = remember {
        YoloDetectorHelper(
            context = context,
            modelPath = "best_int8.tflite",
            onResults = { results, time ->
                (context as MainActivity).runOnUiThread{
                    viewModel.onResults(results, time)
                }
            }
        )
    }

    when (currentScreen) {
        Screen.Home -> {
            viewModel.clearResults()
            HomeScreen(
                onNavigate = { destination -> currentScreen = destination },
                onImageSelected = { bitmap ->
                    viewModel.runDetection(bitmap, yoloDetectorHelper)
                    currentScreen = Screen.Result
                }
            )
        }
        Screen.RealTime -> {
            RealTimeScreen(
                detector = yoloDetectorHelper,
                viewModel = viewModel,
                onNavigateBack = { currentScreen = Screen.Home }
            )
        }
        Screen.Result -> {
            ResultScreen(
                viewModel = viewModel,
                onNavigateBack = { currentScreen = Screen.Home }
            )
        }
    }
}

// Halaman Utama dengan 3 Tombol
@Composable
fun HomeScreen(onNavigate: (Screen) -> Unit, onImageSelected: (Bitmap) -> Unit) {
    val context = LocalContext.current

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            uri?.let { onImageSelected(uriToBitmap(context, it)) }
        }
    )

    val cameraImageUri = remember { createImageUri(context) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { success ->
            if (success) { onImageSelected(uriToBitmap(context, cameraImageUri)) }
        }
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("NutrisiKu", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(
            "Pilih Mode Deteksi",
            style = MaterialTheme.typography.titleMedium,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 48.dp)
        )

        Button(
            onClick = { onNavigate(Screen.RealTime) },
            modifier = Modifier.fillMaxWidth().height(60.dp)
        ) {
            Icon(Icons.Default.Videocam, contentDescription = "Real-time", modifier = Modifier.padding(end = 8.dp))
            Text("Deteksi Real-time", fontSize = 16.sp)
        }
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = { cameraLauncher.launch(cameraImageUri) },
            modifier = Modifier.fillMaxWidth().height(60.dp)
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = "Kamera", modifier = Modifier.padding(end = 8.dp))
            Text("Ambil Foto dari Kamera", fontSize = 16.sp)
        }
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = { galleryLauncher.launch("image/*") },
            modifier = Modifier.fillMaxWidth().height(60.dp)
        ) {
            Icon(Icons.Default.PhotoLibrary, contentDescription = "Galeri", modifier = Modifier.padding(end = 8.dp))
            Text("Pilih dari Galeri", fontSize = 16.sp)
        }
    }
}

// ==========================================================
// PERBAIKAN PADA HALAMAN REAL-TIME
// ==========================================================
@SuppressLint("UnrememberedMutableState")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RealTimeScreen(detector: YoloDetectorHelper, viewModel: MainViewModel, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val detectionResults by viewModel.detectionResults
    val totalCalories by viewModel.totalCalories
    val inferenceTime by viewModel.inferenceTime

    // State untuk menyimpan dimensi gambar yang dianalisis
    var imageWidth by remember { mutableStateOf(1) }
    var imageHeight by remember { mutableStateOf(1) }

    LaunchedEffect(Unit) {
        viewModel.clearResults()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Deteksi Real-time") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    factory = {
                        val previewView = PreviewView(it)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(it)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also { p ->
                                p.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val imageAnalyzer = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                .also { analyzer ->
                                    analyzer.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                                        // Simpan dimensi gambar sebelum dianalisis
                                        imageWidth = imageProxy.width
                                        imageHeight = imageProxy.height

                                        val bitmap = imageProxy.toBitmap()
                                        if (bitmap != null) { detector.detect(bitmap) }
                                        imageProxy.close()
                                    }
                                }
                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }, ContextCompat.getMainExecutor(it))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )
                // Gunakan dimensi gambar yang sebenarnya untuk menggambar kotak
                DetectionCanvas(results = detectionResults, imageWidth = imageWidth, imageHeight = imageHeight)
            }
            ResultPanel(foodList = detectionResults, totalCalories = totalCalories, inferenceTime = inferenceTime, isDetecting = false, onCancel = {})
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(viewModel: MainViewModel, onNavigateBack: () -> Unit) {
    val imageBitmap by viewModel.imageBitmap
    val detectionResults by viewModel.detectionResults
    val totalCalories by viewModel.totalCalories
    val inferenceTime by viewModel.inferenceTime
    val isDetecting by viewModel.isDetecting

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hasil Analisis") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
                imageBitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Gambar yang dianalisis",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                    DetectionCanvas(results = detectionResults, imageWidth = it.width, imageHeight = it.height)
                }
            }
            ResultPanel(
                foodList = detectionResults,
                totalCalories = totalCalories,
                inferenceTime = inferenceTime,
                isDetecting = isDetecting,
                onCancel = onNavigateBack
            )
        }
    }
}

// ==========================================================
// PERBAIKAN PADA DETECTION CANVAS
// ==========================================================
@Composable
fun DetectionCanvas(results: List<DetectionResult>, imageWidth: Int, imageHeight: Int) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Logika penskalaan yang lebih baik untuk menangani rasio aspek yang berbeda
        val canvasWidth = size.width
        val canvasHeight = size.height

        val widthRatio = canvasWidth / imageWidth
        val heightRatio = canvasHeight / imageHeight

        // Pilih rasio terkecil untuk memastikan gambar pas di dalam canvas (letterboxing/pillarboxing)
        val scale = minOf(widthRatio, heightRatio)

        // Hitung offset untuk menengahkan gambar yang sudah diskalakan
        val offsetX = (canvasWidth - imageWidth * scale) / 2
        val offsetY = (canvasHeight - imageHeight * scale) / 2

        results.forEach { result ->
            val box = result.boundingBox
            drawRect(
                color = Color.Red,
                // Terapkan skala dan offset ke setiap koordinat
                topLeft = Offset(box.left * imageWidth * scale + offsetX, box.top * imageHeight * scale + offsetY),
                size = Size(box.width() * imageWidth * scale, box.height() * imageHeight * scale),
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

// Panel Hasil yang Ditingkatkan dengan Opsi Batal
@Composable
fun ResultPanel(foodList: List<DetectionResult>, totalCalories: Int, inferenceTime: Long, isDetecting: Boolean, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Hasil Deteksi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (!isDetecting && foodList.isNotEmpty()) {
                Text("$inferenceTime ms", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (isDetecting) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Menganalisis gambar...", color = Color.Gray)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(onClick = onCancel) {
                    Text("Batal")
                }
            }
        } else {
            if (foodList.isEmpty()) {
                Text("Tidak ada makanan terdeteksi.", modifier = Modifier.align(Alignment.CenterHorizontally), color = Color.Gray)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 150.dp)) {
                    items(foodList) { food ->
                        FoodItemRow(food = food)
                    }
                }
            }
        }

        Divider(modifier = Modifier.padding(vertical = 12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Total Estimasi Kalori", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("$totalCalories kkal", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

sealed class Screen {
    object Home : Screen()
    object RealTime : Screen()
    object Result : Screen()
}

fun uriToBitmap(context: Context, uri: Uri): Bitmap {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri))
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
    }.copy(Bitmap.Config.ARGB_8888, true)
}

fun createImageUri(context: Context): Uri {
    val imageFile = File(context.cacheDir, "camera_image.jpg")
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.provider",
        imageFile
    )
}

@Composable
fun FoodItemRow(food: DetectionResult) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(food.text, fontWeight = FontWeight.Medium)
            Text(food.portion, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        Text("${food.calories} kkal", fontWeight = FontWeight.Medium)
    }
}
