package com.typoposter.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.typoposter.app.R
import com.typoposter.app.ocr.OcrResult
import com.typoposter.app.ocr.TextRecognitionHelper
import com.typoposter.app.theme.PosterTheme
import com.typoposter.app.theme.RichTextVisualTransformation
import kotlinx.coroutines.launch
import java.io.File

enum class ScanApplyMode {
    NEW_NOTE,
    REPLACE,
    APPEND
}

enum class OcrFormatMode {
    POINTWISE,   // Point-wise table & structured document (Default)
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

    var activeUri by remember { mutableStateOf<Uri?>(null) }
    var manualRotation by remember { mutableStateOf(0) }
    var ocrResult by remember { mutableStateOf<OcrResult?>(null) }
    var formatMode by remember { mutableStateOf(OcrFormatMode.POINTWISE) }
    var editedText by remember { mutableStateOf("") }
    var isScanning by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var tempPhotoUri by remember { mutableStateOf<Uri?>(null) }

    fun processImage(uri: Uri, additionalRotation: Int = 0) {
        activeUri = uri
        manualRotation = additionalRotation
        isScanning = true
        errorMessage = null
        scope.launch {
            val result = TextRecognitionHelper.extractText(context, uri, additionalRotation)
            isScanning = false
            result.onSuccess { res ->
                ocrResult = res
                val chosenText = when (formatMode) {
                    OcrFormatMode.POINTWISE -> res.pointwiseText
                    OcrFormatMode.GRID_TABLE -> res.gridTableText
                    OcrFormatMode.SMART_FLOW -> res.smartFlowText
                    OcrFormatMode.EXACT_LINES -> res.exactText
                }
                if (chosenText.isBlank()) {
                    errorMessage = "No readable text detected. If the image is sideways or blurry, try the Rotate button or take a closer photo."
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
        if (success && tempPhotoUri != null) {
            processImage(tempPhotoUri!!, 0)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val uri = createTempImageUri(context)
            tempPhotoUri = uri
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
            tempPhotoUri = uri
            cameraLauncher.launch(uri)
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            processImage(uri, 0)
        }
    }

    // Auto-launch camera when requested (e.g. from Widget camera icon tap)
    LaunchedEffect(autoLaunchCamera) {
        if (autoLaunchCamera) {
            startCameraCapture()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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

                // Dismiss / Close
                Text(
                    text = "CLOSE",
                    color = theme.textSecondary.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Error Message Banner ─────────────────────
            if (errorMessage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF3B1818))
                        .padding(12.dp),
                ) {
                    Text(
                        text = errorMessage ?: "",
                        color = Color(0xFFFF8B8B),
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

            // ── Capture Options (Camera & Gallery) ─────────
            if (editedText.isEmpty() && !isScanning) {
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
                            .clickable { startCameraCapture() }
                            .padding(vertical = 22.dp, horizontal = 12.dp),
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
                                text = "Take Photo",
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
                            .clickable { galleryLauncher.launch("image/*") }
                            .padding(vertical = 22.dp, horizontal = 12.dp),
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
                // Image preview & Orientation helper row
                val previewBitmap = ocrResult?.bitmap
                if (previewBitmap != null) {
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
                            Image(
                                bitmap = previewBitmap.asImageBitmap(),
                                contentDescription = "Captured Document",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, theme.textSecondary.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                            )
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

                        // Rotate button (critical if shot in landscape/desk orientation)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(theme.surfaceVariant)
                                .clickable {
                                    activeUri?.let { processImage(it, (manualRotation + 90) % 360) }
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

                // Mode Selector: Smart Flow vs Exact Lines
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

                        // Grid Table
                        val isGrid = formatMode == OcrFormatMode.GRID_TABLE
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isGrid) theme.accent.copy(alpha = 0.22f) else theme.background)
                                .clickable {
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
                                .heightIn(min = 100.dp, max = 240.dp),
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

                            // Re-take photo button
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.clickable { startCameraCapture() }
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

                Spacer(modifier = Modifier.height(16.dp))

                // ── Direct "+ ADD TO WIDGET" Button ─────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(theme.accent)
                        .clickable {
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
    val tempFile = File.createTempFile("scan_", ".jpg", context.cacheDir).apply {
        createNewFile()
        deleteOnExit()
    }
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        tempFile
    )
}
