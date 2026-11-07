package com.oakever.sudokuoverlay

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private data class SudokuUiState(
    val puzzle: List<Int> = List(81) { 0 },
    val solution: List<Int>? = null,
    val status: String = "Ready. Open Oakever Sudoku, then scan the puzzle.",
    val busy: Boolean = false,
    val clueCount: Int = 0
)

class MainActivity : ComponentActivity() {
    private var uiState by mutableStateOf(SudokuUiState())
    private val textRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startCaptureService(result.resultCode, result.data!!)
        } else {
            uiState = uiState.copy(status = "Screen capture was cancelled.", busy = false)
        }
    }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            if (bitmap == null) {
                uiState = uiState.copy(status = "That image could not be opened.")
            } else {
                processScreenshot(bitmap)
            }
        } catch (error: Exception) {
            uiState = uiState.copy(status = "Could not read image: ${error.message ?: "unknown error"}")
        }
    }

    private val overlaySettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        showFloatingPanelIfAllowed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF6FC2FF),
                onPrimary = Color(0xFF07111F),
                secondary = Color(0xFFB7C9E6),
                background = Color(0xFF0A0F18),
                surface = Color(0xFF151D2B),
                onSurface = Color(0xFFF1F5FB)
            )) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0F18)) {
                    SudokuHomeScreen(
                        state = uiState,
                        onCapture = ::requestScreenCapture,
                        onImport = { imagePicker.launch("image/*") },
                        onCellChange = ::updateCell,
                        onSolve = ::solvePuzzle,
                        onShowPanel = ::requestOverlayPermissionOrShow,
                        onOpenDoku = ::openDokuSolver,
                        onClear = ::clearPuzzle
                    )
                }
            }
        }
    }

    private fun requestScreenCapture() {
        uiState = uiState.copy(busy = true, status = "Opening Android screen-capture permission…")
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        uiState = uiState.copy(busy = true, status = "Capture starting. Switch to Oakever Sudoku now if Android did not let you select its app window.")
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle) {
                if (resultCode == CaptureService.RESULT_OK) {
                    val path = resultData.getString(CaptureService.KEY_PATH)
                    if (path.isNullOrBlank()) {
                        uiState = uiState.copy(busy = false, status = "Capture returned an empty image path.")
                    } else {
                        val bitmap = BitmapFactory.decodeFile(path)
                        if (bitmap == null) {
                            uiState = uiState.copy(busy = false, status = "Captured image could not be decoded.")
                        } else {
                            processScreenshot(bitmap)
                        }
                    }
                } else {
                    uiState = uiState.copy(
                        busy = false,
                        status = resultData.getString(CaptureService.KEY_ERROR) ?: "Screen capture failed."
                    )
                }
            }
        }
        val serviceIntent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_CAPTURE
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            putExtra(CaptureService.EXTRA_RECEIVER, receiver)
        }
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (error: Exception) {
            uiState = uiState.copy(busy = false, status = "Could not start screen capture: ${error.message ?: "service error"}")
        }
    }

    private fun processScreenshot(bitmap: Bitmap) {
        uiState = uiState.copy(busy = true, status = "Reading the Sudoku grid…")
        lifecycleScope.launch {
            try {
                val grid = withContext(Dispatchers.Default) { GridDetector.findGrid(bitmap) }
                val recognized = textRecognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
                val puzzle = if (grid != null) GridDetector.mapDigits(recognized, grid) else IntArray(81)
                val count = puzzle.count { it != 0 }
                uiState = uiState.copy(
                    puzzle = puzzle.toList(),
                    solution = null,
                    clueCount = count,
                    busy = false,
                    status = if (grid == null) {
                        "I couldn't locate the 9×9 grid automatically. Use the cells below to enter or correct the puzzle, or import a tighter screenshot."
                    } else {
                        "Detected $count clues. Please check the grid below before solving; OCR can misread small digits."
                    }
                )
            } catch (error: Exception) {
                uiState = uiState.copy(busy = false, status = "Recognition failed: ${error.message ?: "unknown error"}")
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    private fun updateCell(index: Int, value: Int) {
        if (index !in 0..80) return
        val updated = uiState.puzzle.toMutableList()
        updated[index] = value.coerceIn(0, 9)
        uiState = uiState.copy(puzzle = updated, solution = null, clueCount = updated.count { it != 0 })
    }

    private fun solvePuzzle() {
        val result = SudokuSolver.solve(uiState.puzzle.toIntArray())
        uiState = when (result.status) {
            SudokuSolver.Status.UNIQUE -> uiState.copy(
                solution = result.solution!!.toList(),
                status = result.message,
                clueCount = uiState.puzzle.count { it != 0 }
            )
            SudokuSolver.Status.MULTIPLE -> uiState.copy(
                solution = result.solution!!.toList(),
                status = result.message + " The panel will show the first valid solution found.",
                clueCount = uiState.puzzle.count { it != 0 }
            )
            else -> uiState.copy(solution = null, status = result.message)
        }
    }

    private fun clearPuzzle() {
        uiState = SudokuUiState(status = "Board cleared. You can scan again or enter the clues manually.")
    }

    private fun requestOverlayPermissionOrShow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            overlaySettingsLauncher.launch(intent)
        } else {
            showFloatingPanelIfAllowed()
        }
    }

    private fun showFloatingPanelIfAllowed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            uiState = uiState.copy(status = "Allow ‘Display over other apps’ in Android settings, then tap Show floating panel again.")
            return
        }
        val solution = uiState.solution
        if (solution == null) {
            Toast.makeText(this, "Solve the puzzle first.", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW
            putExtra(OverlayService.EXTRA_PUZZLE, uiState.puzzle.toIntArray())
            putExtra(OverlayService.EXTRA_SOLUTION, solution.toIntArray())
        }
        try {
            startService(intent)
            uiState = uiState.copy(status = "Floating solution panel is active. Drag its title bar to move it.")
        } catch (error: Exception) {
            uiState = uiState.copy(status = "Could not start the floating panel: ${error.message ?: "overlay error"}")
        }
    }

    private fun openDokuSolver() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.dokusolver.com/")))
        } catch (_: Exception) {
            Toast.makeText(this, "No browser is available to open DokuSolver.", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
private fun SudokuHomeScreen(
    state: SudokuUiState,
    onCapture: () -> Unit,
    onImport: () -> Unit,
    onCellChange: (Int, Int) -> Unit,
    onSolve: () -> Unit,
    onShowPanel: () -> Unit,
    onOpenDoku: () -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("SUDOKU OVERLAY", color = Color(0xFF6FC2FF), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text("Your puzzle.\nThe full solution.", fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF3F6FC))
        Text("Companion for Sudoku by Oakever Games", color = Color(0xFFAAB8CC), fontSize = 14.sp)

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF151D2B)),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFF28364A))
        ) {
            Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("1  ·  Capture the game", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text(
                    "Open the Oakever Sudoku puzzle first. Tap Scan screen and approve Android's capture prompt. On older Android versions, switch to the game during the short capture delay.",
                    color = Color(0xFFB2BED0), fontSize = 12.sp, lineHeight = 18.sp
                )
                Button(onClick = onCapture, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
                    Text("Scan Sudoku screen")
                }
                OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
                    Text("Import screenshot from gallery")
                }
            }
        }

        if (state.busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(state.status, color = Color(0xFFB8C8DB), fontSize = 12.sp)
            }
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111A27)), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("STATUS", color = Color(0xFF6FC2FF), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text(state.status, color = Color(0xFFD4DCE8), fontSize = 12.sp, lineHeight = 18.sp)
                    Text("${state.clueCount} / 81 clues detected or entered", color = Color(0xFF92A4BC), fontSize = 11.sp)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("2  ·  Check the clues", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text("Tap a cell to correct a digit. Blank = empty cell.", color = Color(0xFF9DADC2), fontSize = 11.sp)
            }
            OutlinedButton(onClick = onClear, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Text("Clear", fontSize = 12.sp)
            }
        }

        SudokuGridEditor(state.puzzle, onCellChange, Modifier.fillMaxWidth())

        Button(onClick = onSolve, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
            Text("Solve puzzle")
        }

        if (state.solution != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF142638)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFF2D6388))
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("3  ·  Solution ready", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFFEAF6FF))
                    Text("The floating panel shows the complete 9×9 answer. Original clues and solved cells use different colours.", color = Color(0xFFB9C9D9), fontSize = 12.sp, lineHeight = 18.sp)
                    Button(onClick = onShowPanel, modifier = Modifier.fillMaxWidth()) {
                        Text("Show floating solution panel")
                    }
                }
            }
        }

        OutlinedButton(onClick = onOpenDoku, modifier = Modifier.fillMaxWidth()) {
            Text("Open DokuSolver website")
        }
        Text(
            "This first build solves the 9×9 puzzle on-device. DokuSolver can be opened for a separate check; automatic website submission/result extraction is not connected because no public developer API was verified.",
            color = Color(0xFF788BA4), fontSize = 10.sp, lineHeight = 15.sp
        )
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun SudokuGridEditor(
    puzzle: List<Int>,
    onCellChange: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF101927))
            .border(1.dp, Color(0xFF60728C), RoundedCornerShape(8.dp))
            .padding(1.dp)
    ) {
        for (row in 0 until 9) {
            Row(Modifier.weight(1f)) {
                for (col in 0 until 9) {
                    val index = row * 9 + col
                    val value = puzzle.getOrElse(index) { 0 }
                    val rightEdge = if (col == 2 || col == 5 || col == 8) 1.5.dp else 0.5.dp
                    val bottomEdge = if (row == 2 || row == 5 || row == 8) 1.5.dp else 0.5.dp
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .border(
                                BorderStroke(rightEdge, Color(0xFF4E6079))
                            )
                            .border(
                                BorderStroke(bottomEdge, Color(0xFF4E6079))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        BasicTextField(
                            value = if (value == 0) "" else value.toString(),
                            onValueChange = { input ->
                                val digit = input.filter { it.isDigit() }.takeLast(1).toIntOrNull() ?: 0
                                onCellChange(index, digit)
                            },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = if (value == 0) Color(0xFF6FC2FF) else Color(0xFFF4F7FB),
                                fontSize = 16.sp,
                                fontWeight = if (value == 0) FontWeight.Normal else FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            ),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 1.dp),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.Center) {
                                    if (value == 0) {
                                        Text("·", color = Color(0xFF41536A), fontSize = 15.sp)
                                    }
                                    inner()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
