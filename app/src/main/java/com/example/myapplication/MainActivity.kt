package digitalink.to.text.hobby.project

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.*
import java.util.ArrayDeque
import java.util.Locale

// --- Data Class for UI List ---
data class LanguageOption(
    val label: String,
    val tag: String,
    val isDownloaded: Boolean
)

// --- VIEW MODEL: HOLDS STATE ACROSS ROTATION/THEME SWITCH ---
class HandwritingViewModel : ViewModel() {
    // Models & Selection
    var availableModels by mutableStateOf(listOf<LanguageOption>())
        private set
    var selectedLanguageTag by mutableStateOf("en")
        private set
    var selectedLanguageLabel by mutableStateOf("English")
        private set

    // ML Kit Status
    var statusText by mutableStateOf("Initializing...")
        private set
    private var recognizer: DigitalInkRecognizer? = null

    // Drawing State (Persisted)
    var paths = mutableStateListOf<Path>()
        private set
    private val strokeHistory = mutableListOf<Ink.Stroke>()

    // Text State
    var suggestions by mutableStateOf(listOf<String>())
        private set
    var textFieldValue by mutableStateOf(TextFieldValue(""))
        private set

    // --- UNDO / REDO STATE ---
    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    init {
        refreshModelList()
        downloadAndInitModel(selectedLanguageTag)
    }

    // --- Logic: Model Management ---
    fun refreshModelList() {
        val allIds = DigitalInkRecognitionModelIdentifier.allModelIdentifiers()
        RemoteModelManager.getInstance()
            .getDownloadedModels(DigitalInkRecognitionModel::class.java)
            .addOnSuccessListener { downloadedSet ->
                val downloadedTags = downloadedSet.mapNotNull { it.modelIdentifier.languageTag }.toSet()

                val options = allIds.mapNotNull { id ->
                    val tag = id.languageTag
                    if (tag.endsWith("-x-gesture")) return@mapNotNull null
                    val isDownloaded = downloadedTags.contains(tag)
                    val label = getFormattedLanguageName(tag)
                    LanguageOption(label, tag, isDownloaded)
                }
                    .sortedWith(compareByDescending<LanguageOption> { it.isDownloaded }.thenBy { it.label })

                availableModels = options
                options.find { it.tag == selectedLanguageTag }?.let {
                    selectedLanguageLabel = it.label
                }
            }
    }

    fun selectLanguage(option: LanguageOption) {
        selectedLanguageTag = option.tag
        selectedLanguageLabel = option.label
        clearCanvas()
        downloadAndInitModel(option.tag)
    }

    private fun downloadAndInitModel(tag: String) {
        statusText = "Switching..."
        recognizer?.close()
        recognizer = null

        val modelIdentifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag) ?: return
        val m = DigitalInkRecognitionModel.builder(modelIdentifier).build()
        val remoteModelManager = RemoteModelManager.getInstance()

        remoteModelManager.isModelDownloaded(m).addOnSuccessListener { downloaded ->
            statusText = if (downloaded) "Ready" else "Downloading..."
        }

        remoteModelManager.download(m, DownloadConditions.Builder().build())
            .addOnSuccessListener {
                recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(m).build())
                statusText = "Ready"
                refreshModelList()
            }
            .addOnFailureListener {
                statusText = "Error downloading"
            }
    }

    // --- Logic: Drawing & Recognition ---
    fun addStroke(path: Path, stroke: Ink.Stroke) {
        paths.add(path)
        strokeHistory.add(stroke)
        performRecognition()
    }

    private fun performRecognition() {
        if (recognizer == null || strokeHistory.isEmpty()) return
        val inkBuilder = Ink.builder()
        strokeHistory.forEach { inkBuilder.addStroke(it) }
        val ink = inkBuilder.build()

        recognizer?.recognize(ink)
            ?.addOnSuccessListener { result ->
                if (result.candidates.isNotEmpty()) {
                    val candidates = result.candidates.take(3).map { it.text }
                    suggestions = when {
                        candidates.size >= 2 -> listOf(candidates[1], candidates[0]) + candidates.drop(2)
                        else -> candidates
                    }
                }
            }
            ?.addOnFailureListener { Log.e("Ink", "Error", it) }
    }

    fun clearCanvas() {
        strokeHistory.clear()
        paths.clear()
        suggestions = listOf()
    }

    // --- Logic: Text Manipulation & Undo/Redo ---

    private fun saveToHistory() {
        if (undoStack.size > 50) undoStack.removeFirst()
        undoStack.push(textFieldValue)
        redoStack.clear()
        updateUndoRedoFlags()
    }

    private fun updateUndoRedoFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    fun updateText(newValue: TextFieldValue) {
        if (newValue.text != textFieldValue.text) {
            saveToHistory()
        }
        textFieldValue = newValue
    }

    fun insertTextAtCursor(textToInsert: String) {
        saveToHistory()
        val currentText = textFieldValue.text
        val selection = textFieldValue.selection
        val start = if (selection.start < 0) 0 else selection.start
        val end = if (selection.end < 0) 0 else selection.end
        val newText = currentText.substring(0, start) + textToInsert + currentText.substring(end)
        val newCursorPosition = start + textToInsert.length

        textFieldValue = TextFieldValue(newText, TextRange(newCursorPosition))
    }

    /**
     * NEW: Handle Backspace (Delete character before cursor OR delete selection)
     */
    fun handleBackspace() {
        val currentText = textFieldValue.text
        if (currentText.isEmpty()) return

        val selection = textFieldValue.selection
        val start = if (selection.start < 0) 0 else selection.start
        val end = if (selection.end < 0) 0 else selection.end

        // If nothing is selected and cursor is at start, nothing to delete
        if (start == 0 && end == 0) return

        saveToHistory()

        val newText: String
        val newCursorPosition: Int

        if (start != end) {
            // Case 1: Text is selected -> Delete the selection
            newText = currentText.removeRange(start, end)
            newCursorPosition = start
        } else {
            // Case 2: No selection -> Delete character before cursor
            newText = currentText.removeRange(start - 1, start)
            newCursorPosition = start - 1
        }

        textFieldValue = TextFieldValue(newText, TextRange(newCursorPosition))
    }

    fun clearText() {
        if (textFieldValue.text.isNotEmpty()) {
            saveToHistory()
            textFieldValue = TextFieldValue("")
        }
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            redoStack.push(textFieldValue)
            textFieldValue = undoStack.pop()
            updateUndoRedoFlags()
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            undoStack.push(textFieldValue)
            textFieldValue = redoStack.pop()
            updateUndoRedoFlags()
        }
    }

    override fun onCleared() {
        super.onCleared()
        recognizer?.close()
    }

    private fun getFormattedLanguageName(tag: String): String {
        if (tag == "zxx-Zsye-x-emoji") return "Emoji"
        if (tag == "zxx-Zsym-x-autodraw") return "Autodraw"
        val locale = Locale.forLanguageTag(tag)
        val displayName = locale.getDisplayName(Locale.US)
        val nativeName = locale.getDisplayName(locale)
        return if (displayName.equals(nativeName, ignoreCase = true)) displayName else "$displayName ($nativeName)"
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HandwritingApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HandwritingApp(
    modifier: Modifier = Modifier,
    viewModel: HandwritingViewModel = viewModel()
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val isDarkTheme = isSystemInDarkTheme()

    // --- Bottom Sheet State ---
    val scaffoldState = rememberBottomSheetScaffoldState()

    // --- UI State ---
    var showLanguageDialog by remember { mutableStateOf(false) }

    // --- Drawing Temporary State ---
    var currentPath by remember { mutableStateOf(Path()) }
    var triggerRedraw by remember { mutableLongStateOf(0L) }
    var activeStrokeBuilder by remember { mutableStateOf<Ink.Stroke.Builder?>(null) }
    var motionEventX by remember { mutableFloatStateOf(0f) }
    var motionEventY by remember { mutableFloatStateOf(0f) }

    // --- MAIN SCAFFOLD WITH BOTTOM SHEET ---
    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        sheetContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                // 1. SUGGESTIONS
                if (viewModel.suggestions.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (index in viewModel.suggestions.indices) {
                            val text = viewModel.suggestions[index]
                            val isCenter = (viewModel.suggestions.size >= 2 && index == 1) || (viewModel.suggestions.size == 1 && index == 0)

                            Button(
                                onClick = {
                                    viewModel.insertTextAtCursor("$text ")
                                    viewModel.clearCanvas()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = if (isCenter) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                            ) {
                                Text(text, maxLines = 1)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // 2. EDITABLE TEXT FIELD
                OutlinedTextField(
                    value = viewModel.textFieldValue,
                    onValueChange = { viewModel.updateText(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 250.dp),
                    placeholder = { Text("Write here or type...") },
                    shape = RoundedCornerShape(16.dp),
                    trailingIcon = {
                        if (viewModel.textFieldValue.text.isNotEmpty()) {
                            IconButton(onClick = { viewModel.clearText() }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear all text")
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 3. ACTIONS (Undo/Redo & Backspace/Enter/Copy)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // LEFT SIDE: Undo / Redo
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalIconButton(
                            onClick = { viewModel.undo() },
                            enabled = viewModel.canUndo,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, "Undo")
                        }

                        FilledTonalIconButton(
                            onClick = { viewModel.redo() },
                            enabled = viewModel.canRedo,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Redo, "Redo")
                        }
                    }

                    // RIGHT SIDE: Backspace / Enter / Copy
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                        // --- NEW BACKSPACE BUTTON ---
                        FilledTonalIconButton(
                            onClick = { viewModel.handleBackspace() },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Backspace, "Backspace")
                        }

                        FilledTonalIconButton(
                            onClick = { viewModel.insertTextAtCursor("\n") },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardReturn, "New Line")
                        }

                        FilledIconButton(
                            onClick = {
                                if (viewModel.textFieldValue.text.isNotEmpty()) {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Handwriting", viewModel.textFieldValue.text)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Filled.ContentCopy, "Copy")
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(top = 40.dp, start = 16.dp, end = 16.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { focusManager.clearFocus() }
        ) {
            // HEADER
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Language Search Box
                Box(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    OutlinedTextField(
                        value = viewModel.selectedLanguageLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = {
                            Text(
                                if (viewModel.statusText == "Ready") "Language" else "Language • ${viewModel.statusText}",
                                color = if (viewModel.statusText.contains("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = false,
                        shape = RoundedCornerShape(24.dp),
                        trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, "Select") },
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    Box(
                        Modifier.matchParentSize()
                            .clip(RoundedCornerShape(24.dp))
                            .clickable { showLanguageDialog = true }
                    )
                }

                // Clear Canvas
                FilledIconButton(
                    onClick = { viewModel.clearCanvas() },
                    modifier = Modifier.size(54.dp)
                ) {
                    Icon(Icons.Default.Delete, "Clear Canvas", modifier = Modifier.size(24.dp))
                }
            }

            if (showLanguageDialog) {
                LanguageSelectionDialog(
                    allModels = viewModel.availableModels,
                    onDismiss = { showLanguageDialog = false },
                    onLanguageSelected = {
                        viewModel.selectLanguage(it)
                        showLanguageDialog = false
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // CANVAS AREA
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(2.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .pointerInteropFilter { event ->
                        val x = event.x
                        val y = event.y
                        val t = System.currentTimeMillis()

                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                motionEventX = x
                                motionEventY = y
                                currentPath.moveTo(x, y)
                                activeStrokeBuilder = Ink.Stroke.builder().apply { addPoint(Ink.Point.create(x, y, t)) }
                                triggerRedraw++
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val x2 = (x + motionEventX) / 2
                                val y2 = (y + motionEventY) / 2
                                currentPath.quadraticBezierTo(motionEventX, motionEventY, x2, y2)
                                motionEventX = x
                                motionEventY = y
                                activeStrokeBuilder?.addPoint(Ink.Point.create(x, y, t))
                                triggerRedraw++
                                true
                            }
                            MotionEvent.ACTION_UP -> {
                                currentPath.lineTo(motionEventX, motionEventY)
                                activeStrokeBuilder?.addPoint(Ink.Point.create(x, y, t))
                                activeStrokeBuilder?.let {
                                    viewModel.addStroke(currentPath, it.build())
                                }
                                currentPath = Path()
                                activeStrokeBuilder = null
                                triggerRedraw++
                                true
                            }
                            else -> false
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val unused = triggerRedraw
                    val strokeStyle = Stroke(width = 12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val inkColor = if (isDarkTheme) Color.White else Color.Black
                    viewModel.paths.forEach { drawPath(it, color = inkColor, style = strokeStyle) }
                    drawPath(currentPath, color = inkColor, style = strokeStyle)
                }
            }
        }
    }
}

// --- SEARCHABLE LANGUAGE DIALOG ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSelectionDialog(
    allModels: List<LanguageOption>,
    onDismiss: () -> Unit,
    onLanguageSelected: (LanguageOption) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredModels = remember(searchQuery, allModels) {
        if (searchQuery.isBlank()) allModels
        else allModels.filter { it.label.contains(searchQuery, ignoreCase = true) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Select Language", style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search") },
                    leadingIcon = { Icon(Icons.Default.Search, "Search") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn {
                    items(filteredModels) { language ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onLanguageSelected(language) }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = language.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (language.isDownloaded) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                            if (language.isDownloaded) {
                                Text(
                                    text = "[Downloaded]",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                    if(filteredModels.isEmpty()) {
                        item {
                            Text(
                                "No languages found.",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}