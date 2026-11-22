package com.typoposter.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.typoposter.app.R
import com.typoposter.app.ocr.OcrLanguage
import com.typoposter.app.ocr.OcrResult
import com.typoposter.app.ocr.TextRecognitionHelper
import com.typoposter.app.theme.PosterTheme
import com.typoposter.app.theme.RichTextVisualTransformation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

enum class ScanApplyMode {
    NEW_NOTE,
    REPLACE,
    APPEND
}

enum class OcrFormatMode {
    POINTWISE,   // Point-wise table & structured document (Default)
    CLEAN,       // Clean structured document for receipts, notes, rates, lists
    GRID_TABLE,  // Markdown grid table & structured document
    SMART_FLOW,  // Clean paragraph flow with list points
    EXACT_LINES  // Raw physical lines as detected
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScanSheet(
    theme: PosterTheme,
    autoLaunchCamera: Boolean = false,
    onDismiss: () -> Unit,
    onApplyText: (text: String, mode: ScanApplyMode, pinToWidget: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    fun tick() = haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val textScrollState = rememberScrollState()

    val textEditorNestedScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                // Absorb remaining vertical scroll delta within the text editor so scrolling
                // long OCR text feels effortless without dragging down or dismissing the bottom sheet
                return Offset(0f, available.y)
            }
        }
    }

    // State persisted across configuration / process recreation
    var activeUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var tempPhotoUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var manualRotation by rememberSaveable { mutableStateOf(0) }
    var selectedLanguage by rememberSaveable { mutableStateOf(OcrLanguage.ENGLISH) }
    var editedText by rememberSaveable { mutableStateOf("") }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }

    val activeUri = activeUriString?.let { Uri.parse(it) }

    var ocrResult by remember { mutableStateOf<OcrResult?>(null) }
    var formatMode by remember { mutableStateOf(OcrFormatMode.POINTWISE) }
    var isScanning by remember { mutableStateOf(false) }
    var isCopied by remember { mutableStateOf(false) }

    fun processImage(
        uri: Uri,
        additionalRotation: Int = 0,
        language: OcrLanguage = selectedLanguage
    ) {
        activeUriString = uri.toString()
        manualRotation = additionalRotation
        selectedLanguage = language
        isScanning = true
        errorMessage = null
        scope.launch {
            val result = TextRecognitionHelper.extractText(context, uri, additionalRotation, language)
            isScanning = false
            result.onSuccess { res ->
                ocrResult = res
                val chosenText = when (formatMode) {
                    OcrFormatMode.POINTWISE -> res.pointwiseText
                    OcrFormatMode.CLEAN -> res.cleanText
                    OcrFormatMode.GRID_TABLE -> res.gridTableText
                    OcrFormatMode.SMART_FLOW -> res.smartFlowText
                    OcrFormatMode.EXACT_LINES -> res.exactText
                }
                if (chosenText.isBlank()) {
                    errorMessage = "No readable text detected. If the image is sideways or blurry, tap ROTATE 90° or switch language."
                } else {
                    editedText = chosenText
                }
            }.onFailure { err ->
                errorMessage = "Scan failed: ${err.localizedMessage ?: "Unknown error"}"
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempPhotoUriString != null) {
            val uri = Uri.parse(tempPhotoUriString)
            processImage(uri, 0, selectedLanguage)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val uri = createTempImageUri(context)
            tempPhotoUriString = uri.toString()
            cameraLauncher.launch(uri)
        } else {
            errorMessage = "Camera permission is required to capture photos."
        }
    }

    fun startCameraCapture() {
        errorMessage = null
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            val uri = createTempImageUri(context)
            tempPhotoUriString = uri.toString()
            cameraLauncher.launch(uri)
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            processImage(uri, 0, selectedLanguage)
        }
    }

    // Auto-launch camera when requested (e.g. from Widget camera icon tap)
    LaunchedEffect(autoLaunchCamera) {
        if (autoLaunchCamera && activeUriString == null) {
            startCameraCapture()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = theme.surfaceVariant,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(theme.textSecondary.copy(alpha = 0.4f))
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Top Title Row ────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(20.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(theme.accent)
                    )
                    Text(
                        text = "CAMERA SCAN & OCR",
                        color = theme.onSurface,
                        fontSize = 12.sp,
                        fontFamily = theme.headerFont,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.5.sp,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // EN | HI Script Toggle
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(theme.background)
                            .border(1.dp, theme.textSecondary.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 3.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val isEn = selectedLanguage == OcrLanguage.ENGLISH
                        Text(
                            text = "EN",
                            color = if (isEn) theme.accent else theme.textSecondary.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                            fontFamily = theme.headerFont,
                            fontWeight = if (isEn) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(if (isEn) theme.accent.copy(alpha = 0.16f) else Color.Transparent)
                                .clickable {
                                    if (selectedLanguage != OcrLanguage.ENGLISH) {
                                        tick()
                                        selectedLanguage = OcrLanguage.ENGLISH
                                        activeUri?.let { processImage(it, manualRotation, OcrLanguage.ENGLISH) }
                                    }
                                }
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                        Text(
                            text = "•",
                            color = theme.textSecondary.copy(alpha = 0.3f),
                            fontSize = 8.sp,
                            modifier = Modifier.padding(horizontal = 1.dp)
                        )
                        val isHi = selectedLanguage == OcrLanguage.HINDI
                        Text(
                            text = "HI",
                            color = if (isHi) theme.accent else theme.textSecondary.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                            fontFamily = theme.headerFont,
                            fontWeight = if (isHi) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(if (isHi) theme.accent.copy(alpha = 0.16f) else Color.Transparent)
                                .clickable {
                                    if (selectedLanguage != OcrLanguage.HINDI) {
                                        tick()
                                        selectedLanguage = OcrLanguage.HINDI
                                        activeUri?.let { processImage(it, manualRotation, OcrLanguage.HINDI) }
                                    }
                                }
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                    }

                    // Dismiss / Close
                    Text(
                        text = "CLOSE",
                        color = theme.textSecondary.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp,
                        modifier = Modifier.clickable {
                            tick()
                            onDismiss()
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Theme-Matched Error Message Banner ───────
            if (errorMessage != null) {
                val errorBg = if (theme.isDark) Color(0xFF382218) else Color(0xFFFDEEE6)
                val errorBorder = if (theme.isDark) Color(0xFF6E3E28) else Color(0xFFF5C6B0)
                val errorTextColor = if (theme.isDark) Color(0xFFFFB288) else Color(0xFF9E3812)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(errorBg)
                        .border(1.dp, errorBorder, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                ) {
                    Text(
                        text = errorMessage ?: "",
                        color = errorTextColor,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            // ── Scanning Indicator ───────────────────────
            AnimatedVisibility(
                visible = isScanning,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(
                        color = theme.accent,
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Scanning & aligning image with on-device OCR…",
                        color = theme.textSecondary,
                        fontSize = 13.sp,
                        fontFamily = theme.bodyFont,
                    )
                }
            }

            // ── Scanned Image Preview & ROTATE 90° Bar ─────
            // Visible whenever activeUri != null && !isScanning (even if OCR returned no text!)
            if (activeUri != null && !isScanning) {
                val previewBitmap = ocrResult?.bitmap
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(theme.background)
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (previewBitmap != null) {
                            Image(
                                bitmap = previewBitmap.asImageBitmap(),
                                contentDescription = "Captured Document",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, theme.textSecondary.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(theme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_camera),
                                    contentDescription = null,
                                    tint = theme.textSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Scanned Image",
                                color = theme.textPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = if (manualRotation != 0) "Rotated +$manualRotation°" else "Auto-oriented",
                                color = theme.textSecondary,
                                fontSize = 10.sp,
                            )
                        }
                    }

                    // Rotate 90° button: immediately accessible to re-scan sideways photos
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(theme.surfaceVariant)
                            .clickable {
                                tick()
                                processImage(activeUri, (manualRotation + 90) % 360, selectedLanguage)
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_rotate),
                                contentDescription = "Rotate",
                                tint = theme.accent,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "ROTATE 90°",
                                color = theme.accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            // ── Capture Options (Camera & Gallery) ─────────
            // Visible when no photo is active, or if photo yielded no text
            if ((activeUri == null || editedText.isEmpty()) && !isScanning) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Take Photo Button
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(theme.background)
                            .border(1.dp, theme.accent.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                            .clickable {
                                tick()
                                startCameraCapture()
                            }
                            .padding(vertical = 20.dp, horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_camera),
                                contentDescription = "Camera",
                                tint = theme.accent,
                                modifier = Modifier.size(26.dp)
                            )
                            Text(
                                text = if (activeUri != null) "Retake Photo" else "Take Photo",
                                color = theme.textPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Capture books, notes & signs",
                                color = theme.textSecondary,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 15.sp,
                            )
                        }
                    }

                    // Pick Image Button
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(theme.background)
                            .clickable {
                                tick()
                                galleryLauncher.launch("image/*")
                            }
                            .padding(vertical = 20.dp, horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_image),
                                contentDescription = "Gallery",
                                tint = theme.accent,
                                modifier = Modifier.size(26.dp)
                            )
                            Text(
                                text = "Pick Image",
                                color = theme.textPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Import from gallery or files",
                                color = theme.textSecondary,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 15.sp,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // ── Scanned Text Result & Typographic Controls ──
            if (editedText.isNotEmpty()) {
                // Mode Selector: Points, Grid, Flow, Raw
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "AESTHETIC CANVAS",
                        color = theme.textSecondary.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        fontFamily = theme.headerFont,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp,
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Point-wise Table & Structured Notes (Default)
                        val isPoints = formatMode == OcrFormatMode.POINTWISE
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isPoints) theme.accent.copy(alpha = 0.22f) else theme.background)
                                .clickable {
                                    tick()
                                    formatMode = OcrFormatMode.POINTWISE
                                    ocrResult?.let { editedText = it.pointwiseText }
                                }
                                .padding(horizontal = 7.dp, vertical = 4.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_format_list),
                                    contentDescription = null,
                                    tint = if (isPoints) theme.accent else theme.textSecondary,
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = "Points",
                                    color = if (isPoints) theme.accent else theme.textSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = if (isPoints) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }

                        // Clean Structured Mode
                        val isClean = formatMode == OcrFormatMode.CLEAN
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isClean) theme.accent.copy(alpha = 0.22f) else theme.background)
                                .clickable {
                                    tick()
                                    formatMode = OcrFormatMode.CLEAN
                                    ocrResult?.let { editedText = it.cleanText }
                                }
                                .padding(horizontal = 7.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = "Clean",
                                color = if (isClean) theme.accent else theme.textSecondary,
                                fontSize = 10.sp,
                                fontWeight = if (isClean) FontWeight.Bold else FontWeight.Normal,
                            )
                        }

                        // Grid Table
                        val isGrid = formatMode == OcrFormatMode.GRID_TABLE
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isGrid) theme.accent.copy(alpha = 0.22f) else theme.background)
                                .clickable {
                                    tick()
                                    formatMode = OcrFormatMode.GRID_TABLE
                                    ocrResult?.let { editedText = it.gridTableText }
                                }
                                .padding(horizontal = 7.dp, vertical = 4.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_grid),
                                    contentDescription = null,
                                    tint = if (isGrid) theme.accent else theme.textSecondary,
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = "Grid",
                                    color = if (isGrid) theme.accent else theme.textSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = if (isGrid) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }

                        // Smart Flow Button
                        val isFlow = formatMode == OcrFormatMode.SMART_FLOW
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isFlow) theme.accent.copy(alpha = 0.22f) else theme.background)
                                .clickable {
                                    tick()
                                    formatMode = OcrFormatMode.SMART_FLOW
                                    ocrResult?.let { editedText = it.smartFlowText }
                                }
                                .padding(horizontal = 7.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = "Flow",
                                color = if (isFlow) theme.accent else theme.textSecondary,
                                fontSize = 10.sp,
                                fontWeight = if (isFlow) FontWeight.Bold else FontWeight.Normal,
                            )
                        }

                        // Exact Lines Button
                        val isExact = formatMode == OcrFormatMode.EXACT_LINES
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isExact) theme.accent.copy(alpha = 0.22f) else theme.background)
                                .clickable {
                                    tick()
                                    formatMode = OcrFormatMode.EXACT_LINES
                                    ocrResult?.let { editedText = it.exactText }
                                }
                                .padding(horizontal = 7.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = "Raw",
                                color = if (isExact) theme.accent else theme.textSecondary,
                                fontSize = 10.sp,
                                fontWeight = if (isExact) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Themed Editable Preview Canvas
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(theme.background)
                        .border(1.dp, theme.accent.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                        .nestedScroll(textEditorNestedScroll)
                        .padding(18.dp),
                ) {
                    Column {
                        // Mini accent bar inside card
                        Box(
                            modifier = Modifier
                                .width(24.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(theme.accent)
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        BasicTextField(
                            value = editedText,
                            onValueChange = { editedText = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 100.dp, max = 260.dp)
                                .verticalScroll(textScrollState),
                            textStyle = TextStyle(
                                color = theme.textPrimary,
                                fontSize = if (editedText.length <= 80) 18.sp else 15.sp,
                                fontFamily = theme.bodyFont,
                                fontWeight = theme.bodyWeight,
                                lineHeight = if (editedText.length <= 80) 26.sp else 22.sp,
                            ),
                            visualTransformation = remember(theme) {
                                RichTextVisualTransformation(theme)
                            },
                            cursorBrush = SolidColor(theme.accent),
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${editedText.length} chars • ${editedText.split(Regex("\\s+")).filter { it.isNotBlank() }.size} words",
                                color = theme.textSecondary.copy(alpha = 0.5f),
                                fontSize = 11.sp,
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                // 1-Tap Copy Button (Flips to COPIED ✓ for 1.5s)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.clickable {
                                        tick()
                                        clipboardManager.setText(AnnotatedString(editedText))
                                        isCopied = true
                                        scope.launch {
                                            delay(1500L)
                                            isCopied = false
                                        }
                                    }
                                ) {
                                    Text(
                                        text = if (isCopied) "COPIED ✓" else "COPY",
                                        color = if (isCopied) theme.accent else theme.textSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 1.sp,
                                    )
                                }

                                // Re-take photo button
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.clickable {
                                        tick()
                                        startCameraCapture()
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_refresh),
                                        contentDescription = "Retake",
                                        tint = theme.accent,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "RETAKE",
                                        color = theme.accent,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 1.sp,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Direct "+ ADD TO WIDGET" Button ─────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(theme.accent)
                        .clickable {
                            tick()
                            onApplyText(editedText, ScanApplyMode.NEW_NOTE, true)
                            onDismiss()
                        }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_pin),
                            contentDescription = null,
                            tint = if (theme.isDark) Color.Black else Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "CREATE & ADD TO WIDGET",
                            color = if (theme.isDark) Color.Black else Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // ── Note Options: Save as New / Replace / Append ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // New Note
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(theme.background)
                            .clickable {
                                tick()
                                onApplyText(editedText, ScanApplyMode.NEW_NOTE, false)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "NEW NOTE",
                            color = theme.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp,
                        )
                    }

                    // Replace Active Note
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(theme.background)
                            .clickable {
                                tick()
                                onApplyText(editedText, ScanApplyMode.REPLACE, false)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "REPLACE",
                            color = theme.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp,
                        )
                    }

                    // Append to Active Note
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(theme.background)
                            .clickable {
                                tick()
                                onApplyText(editedText, ScanApplyMode.APPEND, false)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "APPEND",
                            color = theme.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

private fun createTempImageUri(context: Context): Uri {
    cleanupStaleScanFiles(context)
    val tempFile = File.createTempFile("scan_", ".jpg", context.cacheDir).apply {
        createNewFile()
    }
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        tempFile
    )
}

private fun cleanupStaleScanFiles(context: Context) {
    try {
        val oneHourAgo = System.currentTimeMillis() - 3600_000L
        context.cacheDir.listFiles { file ->
            file.name.startsWith("scan_") && file.name.endsWith(".jpg") && file.lastModified() < oneHourAgo
        }?.forEach { staleFile ->
            staleFile.delete()
        }
    } catch (_: Exception) {}
}
