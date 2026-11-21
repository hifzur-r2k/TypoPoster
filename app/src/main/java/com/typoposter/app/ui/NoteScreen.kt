package com.typoposter.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.speech.RecognizerIntent
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.typoposter.app.R
import com.typoposter.app.calc.CalcSuggestion
import com.typoposter.app.calc.SmartCalculatorHelper
import com.typoposter.app.data.Note
import com.typoposter.app.date.CountdownBadge
import com.typoposter.app.date.CountdownHelper
import com.typoposter.app.date.UrgencyTier
import com.typoposter.app.theme.FormatType
import com.typoposter.app.theme.PosterTheme
import com.typoposter.app.theme.RichTextVisualTransformation
import com.typoposter.app.theme.TextFormatHelper
import com.typoposter.app.voice.SpeechState
import com.typoposter.app.voice.VoiceLanguage
import com.typoposter.app.voice.VoiceToNoteEngine

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteScreen(
    uiState: NoteUiState,
    onTextChanged: (String) -> Unit,
    onNoteSelected: (String) -> Unit,
    onCreateNewNote: () -> Unit,
    onDeleteNote: () -> Unit,
    onRenameNote: (String) -> Unit,
    onPinWidgetClicked: () -> Unit,
    onScanClicked: () -> Unit = {},
    onScanSheetDismissed: () -> Unit = {},
    onApplyScannedText: (String, ScanApplyMode, Boolean) -> Unit = { _, _, _ -> },
    onThemeButtonClicked: () -> Unit,
    onThemeSelected: (Int) -> Unit,
    onThemeSheetDismissed: () -> Unit,
    onRenameDialogDismissed: () -> Unit,
    onShowRenameDialog: () -> Unit,
    onUnlockRequested: (String) -> Unit = {},
    onToggleLockRequested: (String, Boolean) -> Unit = { _, _ -> },
    launchWithKeyboard: Boolean = false,
) {
    val theme = uiState.theme
    val activeNote = uiState.activeNote
    val focusRequester = remember { FocusRequester() }

    // Maintain selection state so bold/italic/highlight operations can target the selection
    var textFieldValue by remember(activeNote.id) {
        mutableStateOf(TextFieldValue(activeNote.content, TextRange(activeNote.content.length)))
    }

    var lastEmittedText by remember(activeNote.id) {
        mutableStateOf(activeNote.content)
    }

    LaunchedEffect(activeNote.content) {
        // Prevent typing echo: only update if activeNote.content changed externally,
        // never clobber active typing/deleting
        if (activeNote.content != lastEmittedText && activeNote.content != textFieldValue.text) {
            lastEmittedText = activeNote.content
            val newCursor = activeNote.content.length
            textFieldValue = TextFieldValue(
                text = activeNote.content,
                selection = TextRange(newCursor)
            )
        }
    }

    // Inline Smart Calculator suggestion derived from current text and cursor
    val calcSuggestion = remember(textFieldValue) {
        SmartCalculatorHelper.detectCalcSuggestion(textFieldValue)
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var speechState by remember { mutableStateOf<SpeechState>(SpeechState.Idle) }
    var voiceLanguage by remember { mutableStateOf(VoiceLanguage.ENGLISH) }
    var showAudioPermissionDialog by remember { mutableStateOf(false) }
    // Fallback system speech recognition launcher (100% device compatibility)
    val speechIntentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            if (result.resultCode == Activity.RESULT_OK) {
                val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val spoken = matches?.firstOrNull() ?: ""
                if (spoken.isNotBlank()) {
                    val converted = com.typoposter.app.voice.SpokenDateParser.convert(spoken)
                    val mathParsed = com.typoposter.app.voice.SpokenMathParser.parse(converted)
                    val updated = insertSpeechText(textFieldValue, mathParsed)
                    textFieldValue = updated
                    lastEmittedText = updated.text
                    onTextChanged(updated.text)
                }
            }
        } catch (t: Throwable) {
            Log.e("NoteScreen", "Error handling speech intent result", t)
        }
    }

    // Fallback: Launch system speech dialog without redirecting to Settings
    val launchSystemSpeechOrPrompt: () -> Unit = {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, voiceLanguage.tag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, voiceLanguage.tag)
                if (voiceLanguage == VoiceLanguage.ENGLISH) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                } else {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                }
                putExtra(
                    RecognizerIntent.EXTRA_PROMPT,
                    if (voiceLanguage == VoiceLanguage.HINDI) "बोलिए (Spoken note or deadline)..."
                    else "Speak note or deadline..."
                )
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                speechIntentLauncher.launch(intent)
            } else {
                speechState = SpeechState.Error("Voice recognition service not available (Error 5)")
            }
        } catch (t: Throwable) {
            Log.w("NoteScreen", "System speech recognition unavailable: ${t.message}")
            speechState = SpeechState.Error("Voice error: ${t.localizedMessage ?: "unavailable"} (Error 5)")
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            try {
                VoiceToNoteEngine.startListening(
                    context = context,
                    language = voiceLanguage,
                    onTier3Fallback = launchSystemSpeechOrPrompt,
                    onStateChange = { newState ->
                        speechState = newState
                    }
                )
            } catch (t: Throwable) {
                Log.e("NoteScreen", "Permission granted listener failed", t)
                launchSystemSpeechOrPrompt()
            }
        } else {
            showAudioPermissionDialog = true
        }
    }

    val isListening = speechState is SpeechState.Listening || speechState is SpeechState.InterimResult

    val onToggleVoice: () -> Unit = {
        try {
            if (isListening) {
                VoiceToNoteEngine.stopListening()
            } else {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    VoiceToNoteEngine.startListening(
                        context = context,
                        language = voiceLanguage,
                        onTier3Fallback = launchSystemSpeechOrPrompt,
                        onStateChange = { newState ->
                            speechState = newState
                        }
                    )
                } else {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        } catch (t: Throwable) {
            Log.e("NoteScreen", "onToggleVoice failed", t)
            launchSystemSpeechOrPrompt()
        }
    }

    // Handle recognized voice text insertion at cursor without corrupting existing spans
    LaunchedEffect(speechState) {
        val state = speechState
        if (state is SpeechState.FinalResult && state.convertedText.isNotBlank()) {
            val updated = insertSpeechText(textFieldValue, state.convertedText)
            textFieldValue = updated
            lastEmittedText = updated.text
            onTextChanged(updated.text)
            speechState = SpeechState.Idle
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            VoiceToNoteEngine.cancelListening()
        }
    }

    val backgroundColor = animateColorAsState(
        targetValue = theme.background,
        animationSpec = tween(400),
        label = "bg",
    )
    val textColor = animateColorAsState(
        targetValue = theme.textPrimary,
        animationSpec = tween(400),
        label = "text",
    )
    val secondaryColor = animateColorAsState(
        targetValue = theme.textSecondary,
        animationSpec = tween(400),
        label = "secondary",
    )
    val accentColor = animateColorAsState(
        targetValue = theme.accent,
        animationSpec = tween(400),
        label = "accent",
    )

    LaunchedEffect(launchWithKeyboard) {
        if (launchWithKeyboard) {
            focusRequester.requestFocus()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor.value)
            .systemBarsPadding()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // ── Top Bar ─────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // App Logo
                Text(
                    text = "typo",
                    color = secondaryColor.value.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    fontFamily = theme.headerFont,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 4.sp,
                )

                // Actions: Scan button + Voice button + Add to Widget button + Theme button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Direct "SCAN" button with Camera icon
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(theme.surfaceVariant.copy(alpha = 0.6f))
                            .clickable(onClick = onScanClicked)
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_camera),
                                contentDescription = "Scan Text",
                                tint = textColor.value,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "SCAN",
                                color = textColor.value,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.sp,
                            )
                        }
                    }

                    // Direct "VOICE" button with Mic icon
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (isListening) accentColor.value.copy(alpha = 0.25f)
                                else theme.surfaceVariant.copy(alpha = 0.6f)
                            )
                            .clickable(onClick = onToggleVoice)
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_mic),
                                contentDescription = "Voice to Note",
                                tint = if (isListening) accentColor.value else textColor.value,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = if (isListening) "LISTENING" else "VOICE",
                                color = if (isListening) accentColor.value else textColor.value,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.sp,
                            )
                        }
                    }

                    // Direct "Add to Widget" button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(accentColor.value.copy(alpha = 0.12f))
                            .clickable(onClick = onPinWidgetClicked)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "+ WIDGET",
                            color = accentColor.value,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp,
                        )
                    }

                    // Theme color button
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(accentColor.value.copy(alpha = 0.15f))
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = onThemeButtonClicked,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(accentColor.value),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Notes Switcher Tabs ─────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                uiState.notes.forEach { note ->
                    val isSelected = note.id == activeNote.id
                    val tabBg = if (isSelected) accentColor.value.copy(alpha = 0.15f)
                    else theme.surfaceVariant.copy(alpha = 0.5f)
                    val tabText = if (isSelected) textColor.value else secondaryColor.value

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(tabBg)
                            .combinedClickable(
                                onClick = { onNoteSelected(note.id) },
                                onLongClick = {
                                    if (isSelected) onShowRenameDialog()
                                }
                            )
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (note.isLocked) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_lock),
                                    contentDescription = "Locked",
                                    tint = if (isSelected) accentColor.value else secondaryColor.value.copy(alpha = 0.6f),
                                    modifier = Modifier.size(10.dp)
                                )
                            }
                            Text(
                                text = note.title.uppercase(),
                                color = tabText,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                letterSpacing = 1.sp,
                            )

                            // Tiny pill for nearest countdown (Strict privacy: never for locked notes)
                            if (!note.isLocked) {
                                val nearest = remember(note.content) { CountdownHelper.findNearest(note.content) }
                                if (nearest != null) {
                                    val tier = nearest.urgencyTier
                                    val bg = when (tier) {
                                        UrgencyTier.IMMINENT -> accentColor.value.copy(alpha = 0.28f)
                                        UrgencyTier.APPROACHING -> accentColor.value.copy(alpha = 0.16f)
                                        UrgencyTier.CALM -> theme.surfaceVariant.copy(alpha = 0.8f)
                                    }
                                    val hPad = when (tier) {
                                        UrgencyTier.IMMINENT -> 6.dp
                                        UrgencyTier.APPROACHING -> 5.5.dp
                                        UrgencyTier.CALM -> 5.dp
                                    }
                                    val vPad = when (tier) {
                                        UrgencyTier.IMMINENT -> 2.dp
                                        UrgencyTier.APPROACHING -> 1.5.dp
                                        UrgencyTier.CALM -> 1.5.dp
                                    }
                                    val fSize = when (tier) {
                                        UrgencyTier.IMMINENT -> 9.5.sp
                                        UrgencyTier.APPROACHING -> 9.sp
                                        UrgencyTier.CALM -> 8.5.sp
                                    }
                                    val fWeight = when (tier) {
                                        UrgencyTier.IMMINENT -> FontWeight.SemiBold
                                        UrgencyTier.APPROACHING -> FontWeight.Medium
                                        UrgencyTier.CALM -> FontWeight.Normal
                                    }
                                    val fColor = when (tier) {
                                        UrgencyTier.IMMINENT, UrgencyTier.APPROACHING -> accentColor.value
                                        UrgencyTier.CALM -> secondaryColor.value
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(bg)
                                            .padding(horizontal = hPad, vertical = vPad)
                                    ) {
                                        Text(
                                            text = nearest.compactLabel,
                                            color = fColor,
                                            fontSize = fSize,
                                            fontWeight = fWeight,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Plus button to add note
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(theme.surfaceVariant.copy(alpha = 0.5f))
                        .clickable(onClick = onCreateNewNote),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "+",
                        color = secondaryColor.value,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Light,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Accent Bar & Title ──────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accentColor.value),
                )

                Text(
                    text = activeNote.title,
                    color = secondaryColor.value.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    fontFamily = theme.headerFont,
                    modifier = Modifier.clickable(onClick = onShowRenameDialog)
                )

                if (activeNote.isLocked) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_lock),
                        contentDescription = "Private Note",
                        tint = accentColor.value,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Text Editor ─────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (activeNote.isLocked && !uiState.isCurrentNoteUnlocked) {
                    RedactedPosterLockView(
                        theme = theme,
                        textColor = textColor.value,
                        secondaryColor = secondaryColor.value,
                        accentColor = accentColor.value,
                        onAuthenticate = { onUnlockRequested(activeNote.id) }
                    )
                } else {
                    if (activeNote.content.isEmpty()) {
                        Text(
                            text = "What's on your mind today?",
                            color = secondaryColor.value.copy(alpha = 0.4f),
                            fontSize = 20.sp,
                            fontFamily = theme.bodyFont,
                            fontWeight = theme.bodyWeight,
                            lineHeight = 32.sp,
                        )
                    }

                    BasicTextField(
                        value = textFieldValue,
                        onValueChange = { newValue ->
                            textFieldValue = newValue
                            if (newValue.text != lastEmittedText) {
                                lastEmittedText = newValue.text
                                onTextChanged(newValue.text)
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(focusRequester),
                        visualTransformation = remember(theme) {
                            RichTextVisualTransformation(theme)
                        },
                        textStyle = TextStyle(
                            color = textColor.value,
                            fontSize = autoScaleFontSize(textFieldValue.text.length),
                            fontFamily = theme.bodyFont,
                            fontWeight = if (textFieldValue.text.length <= 50) FontWeight.Medium
                            else theme.bodyWeight,
                            lineHeight = autoScaleLineHeight(textFieldValue.text.length),
                            letterSpacing = 0.3.sp,
                        ),
                        cursorBrush = SolidColor(accentColor.value),
                    )
                }
            }

            // ── Inline Smart Calculator Pill (Theme-matched & Animated) ──
            AnimatedVisibility(
                visible = calcSuggestion != null,
                enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 2 },
                exit = fadeOut(tween(120)) + slideOutVertically(tween(120)) { it / 2 },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
            ) {
                if (calcSuggestion != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CalculatorPill(
                            suggestion = calcSuggestion,
                            theme = theme,
                            textColor = textColor.value,
                            secondaryColor = secondaryColor.value,
                            onApply = {
                                val updated = SmartCalculatorHelper.applySuggestion(textFieldValue, calcSuggestion)
                                textFieldValue = updated
                                lastEmittedText = updated.text
                                onTextChanged(updated.text)
                            }
                        )
                    }
                }
            }

            // ── Offline Voice-to-Note Listening Pill (EN / HI Language Toggle) ──
            AnimatedVisibility(
                visible = isListening || speechState is SpeechState.Error || speechState is SpeechState.DownloadingModel,
                enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 2 },
                exit = fadeOut(tween(120)) + slideOutVertically(tween(120)) { it / 2 },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    VoiceListeningPill(
                        state = speechState,
                        language = voiceLanguage,
                        theme = theme,
                        accentColor = accentColor.value,
                        textColor = textColor.value,
                        secondaryColor = secondaryColor.value,
                        onToggleLanguage = {
                            val nextLang = if (voiceLanguage == VoiceLanguage.ENGLISH) VoiceLanguage.HINDI else VoiceLanguage.ENGLISH
                            voiceLanguage = nextLang
                            if (isListening) {
                                VoiceToNoteEngine.startListening(
                                    context = context,
                                    language = nextLang,
                                    onTier3Fallback = launchSystemSpeechOrPrompt,
                                    onStateChange = { speechState = it }
                                )
                            }
                        },
                        onStop = { VoiceToNoteEngine.stopListening() },
                        onDismiss = { speechState = SpeechState.Idle },
                    )
                }
            }

            // ── Formatting Toolbar (Bold, Italic, 3 Highlights, Mic, EN/HI Toggle) ──
            FormattingToolbar(
                theme = theme,
                isLocked = activeNote.isLocked,
                isListening = isListening,
                voiceLanguage = voiceLanguage,
                onFormat = { format ->
                    val updated = TextFormatHelper.toggleFormat(textFieldValue, format)
                    textFieldValue = updated
                    lastEmittedText = updated.text
                    onTextChanged(updated.text)
                },
                onClearFormat = {
                    val updated = TextFormatHelper.clearFormatting(textFieldValue)
                    textFieldValue = updated
                    lastEmittedText = updated.text
                    onTextChanged(updated.text)
                },
                onToggleLock = {
                    onToggleLockRequested(activeNote.id, !activeNote.isLocked)
                },
                onToggleMic = onToggleVoice,
                onToggleLanguage = {
                    val nextLang = if (voiceLanguage == VoiceLanguage.ENGLISH) VoiceLanguage.HINDI else VoiceLanguage.ENGLISH
                    voiceLanguage = nextLang
                    if (isListening) {
                        VoiceToNoteEngine.safeCancel()
                        coroutineScope.launch {
                            kotlinx.coroutines.delay(300L)
                            VoiceToNoteEngine.startListening(
                                context = context,
                                language = nextLang,
                                onTier3Fallback = launchSystemSpeechOrPrompt,
                                onStateChange = { speechState = it }
                            )
                        }
                    }
                }
            )

            // ── Live Countdown Pills (Note Footer) ──────
            val countdowns = remember(activeNote.content, activeNote.isLocked, uiState.isCurrentNoteUnlocked) {
                if (!activeNote.isLocked || uiState.isCurrentNoteUnlocked) {
                    CountdownHelper.parseAll(activeNote.content)
                } else emptyList()
            }

            AnimatedVisibility(
                visible = countdowns.isNotEmpty(),
                enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 2 },
                exit = fadeOut(tween(120)) + slideOutVertically(tween(120)) { it / 2 },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    countdowns.forEach { badge ->
                        CountdownPill(
                            badge = badge,
                            theme = theme,
                            accentColor = accentColor.value,
                            textColor = textColor.value
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                }
            }

            // ── Bottom status ───────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${activeNote.content.length} chars",
                    color = secondaryColor.value.copy(alpha = 0.35f),
                    fontSize = 11.sp,
                    fontFamily = theme.bodyFont,
                    letterSpacing = 1.sp,
                )

                Text(
                    text = theme.name.uppercase(),
                    color = secondaryColor.value.copy(alpha = 0.35f),
                    fontSize = 11.sp,
                    fontFamily = theme.headerFont,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.5.sp,
                )
            }
        }

        // ── Theme Picker Sheet ──────────────────────────
        if (uiState.isThemeSheetVisible) {
            ThemePickerSheet(
                currentThemeIndex = activeNote.themeIndex,
                activeTheme = theme,
                onThemeSelected = onThemeSelected,
                onDismiss = onThemeSheetDismissed,
            )
        }

        // ── Camera Scan & OCR Sheet ─────────────────────
        if (uiState.isScanSheetVisible) {
            CameraScanSheet(
                theme = theme,
                autoLaunchCamera = uiState.autoLaunchCameraOnScan,
                onDismiss = onScanSheetDismissed,
                onApplyText = onApplyScannedText,
            )
        }

        // ── Note Rename / Delete Dialog ─────────────────
        if (uiState.isRenameDialogVisible) {
            NoteActionDialog(
                currentTitle = activeNote.title,
                canDelete = uiState.notes.size > 1,
                theme = theme,
                onRename = onRenameNote,
                onDelete = onDeleteNote,
                onPinWidget = onPinWidgetClicked,
                onDismiss = onRenameDialogDismissed,
            )
        }

        // ── Microphone Permission Rationale Dialog ─────
        if (showAudioPermissionDialog) {
            AlertDialog(
                onDismissRequest = { showAudioPermissionDialog = false },
                containerColor = theme.surfaceVariant,
                title = {
                    Text(
                        text = "Microphone Access",
                        color = theme.onSurface,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                text = {
                    Text(
                        text = "Typo Poster transcribes your voice 100% offline directly on your device in English and Hindi. No audio is ever recorded or uploaded to any server.",
                        color = secondaryColor.value,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showAudioPermissionDialog = false
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    ) {
                        Text("Grant Permission", color = accentColor.value, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAudioPermissionDialog = false }) {
                        Text("Not Now", color = secondaryColor.value)
                    }
                }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Typography Formatting Toolbar Component
// ─────────────────────────────────────────────────────────────

@Composable
private fun FormattingToolbar(
    theme: PosterTheme,
    isLocked: Boolean,
    isListening: Boolean,
    voiceLanguage: VoiceLanguage,
    onFormat: (FormatType) -> Unit,
    onClearFormat: () -> Unit,
    onToggleLock: () -> Unit,
    onToggleMic: () -> Unit,
    onToggleLanguage: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(theme.surfaceVariant.copy(alpha = 0.9f))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Bold button
            Text(
                text = "B",
                color = theme.onSurface,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 15.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onFormat(FormatType.BOLD) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )

            // Italic button
            Text(
                text = "I",
                color = theme.onSurface,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onFormat(FormatType.ITALIC) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )

            // Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(14.dp)
                    .background(theme.textSecondary.copy(alpha = 0.3f))
            )

            // Highlight 1: Acid Lime
            HighlightDot(
                color = TextFormatHelper.Highlights.SwatchLime,
                onClick = { onFormat(FormatType.HIGHLIGHT_1) }
            )

            // Highlight 2: Sunset Coral
            HighlightDot(
                color = TextFormatHelper.Highlights.SwatchCoral,
                onClick = { onFormat(FormatType.HIGHLIGHT_2) }
            )

            // Highlight 3: Cyber Sky
            HighlightDot(
                color = TextFormatHelper.Highlights.SwatchSky,
                onClick = { onFormat(FormatType.HIGHLIGHT_3) }
            )

            // Highlight 4: Deep Plum (Darker shade 1)
            HighlightDot(
                color = TextFormatHelper.Highlights.SwatchPlum,
                onClick = { onFormat(FormatType.HIGHLIGHT_4) }
            )

            // Highlight 5: Forest Pine (Darker shade 2)
            HighlightDot(
                color = TextFormatHelper.Highlights.SwatchPine,
                onClick = { onFormat(FormatType.HIGHLIGHT_5) }
            )

            // Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(14.dp)
                    .background(theme.textSecondary.copy(alpha = 0.3f))
            )

            // Clear formatting
            Icon(
                painter = painterResource(id = R.drawable.ic_close),
                contentDescription = "Clear format",
                tint = theme.textSecondary.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClearFormat)
                    .padding(2.dp),
            )

            // Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(14.dp)
                    .background(theme.textSecondary.copy(alpha = 0.3f))
            )

            // Lock / Unlock toggle
            Icon(
                painter = painterResource(
                    id = if (isLocked) R.drawable.ic_lock else R.drawable.ic_lock_open
                ),
                contentDescription = if (isLocked) "Private Note (Locked)" else "Public Note (Unlocked)",
                tint = if (isLocked) theme.accent else theme.textSecondary.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleLock)
                    .padding(1.dp),
            )

            // Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(14.dp)
                    .background(theme.textSecondary.copy(alpha = 0.3f))
            )

            // Language Toggle [ EN | HI ] right on the toolbar
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(theme.background.copy(alpha = 0.5f))
                    .clickable(onClick = onToggleLanguage)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = "EN",
                    color = if (voiceLanguage == VoiceLanguage.ENGLISH) theme.accent else theme.textSecondary.copy(alpha = 0.45f),
                    fontSize = 10.sp,
                    fontWeight = if (voiceLanguage == VoiceLanguage.ENGLISH) FontWeight.ExtraBold else FontWeight.Normal
                )
                Text(
                    text = "|",
                    color = theme.textSecondary.copy(alpha = 0.3f),
                    fontSize = 9.sp
                )
                Text(
                    text = "HI",
                    color = if (voiceLanguage == VoiceLanguage.HINDI) theme.accent else theme.textSecondary.copy(alpha = 0.45f),
                    fontSize = 10.sp,
                    fontWeight = if (voiceLanguage == VoiceLanguage.HINDI) FontWeight.ExtraBold else FontWeight.Normal
                )
            }

            // Minimal Mic Button (Voice-to-Note)
            Icon(
                painter = painterResource(id = R.drawable.ic_mic),
                contentDescription = if (isListening) "Stop listening" else "Voice to Note (${voiceLanguage.shortLabel})",
                tint = if (isListening) theme.accent else theme.textSecondary.copy(alpha = 0.75f),
                modifier = Modifier
                    .size(17.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleMic)
                    .padding(1.dp),
            )
        }
    }
}

@Composable
private fun HighlightDot(
    color: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick)
    )
}

@Composable
private fun NoteActionDialog(
    currentTitle: String,
    canDelete: Boolean,
    theme: PosterTheme,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onPinWidget: () -> Unit,
    onDismiss: () -> Unit,
) {
    var titleInput by remember { mutableStateOf(currentTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.surfaceVariant,
        title = {
            Text(
                text = "Note Options",
                color = theme.onSurface,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "Rename Note:",
                    color = theme.textSecondary,
                    fontSize = 12.sp,
                )
                BasicTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = theme.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(theme.background)
                        .padding(12.dp),
                    cursorBrush = SolidColor(theme.accent),
                )

                // Quick action: Add to home screen widget
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(theme.accent.copy(alpha = 0.15f))
                        .clickable {
                            onPinWidget()
                            onDismiss()
                        }
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_pin),
                        contentDescription = null,
                        tint = theme.accent,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Add Widget to Home Screen",
                        color = theme.accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(titleInput) },
            ) {
                Text(text = "Save", color = theme.accent)
            }
        },
        dismissButton = {
            Row {
                if (canDelete) {
                    TextButton(
                        onClick = {
                            onDelete()
                            onDismiss()
                        },
                    ) {
                        Text(text = "Delete", color = Color(0xFFFF5252))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(text = "Cancel", color = theme.textSecondary)
                }
            }
        }
    )
}

private fun autoScaleFontSize(textLength: Int) = when {
    textLength <= 20  -> 28.sp
    textLength <= 50  -> 24.sp
    textLength <= 120 -> 20.sp
    textLength <= 250 -> 17.sp
    else              -> 15.sp
}

private fun autoScaleLineHeight(textLength: Int) = when {
    textLength <= 20  -> 40.sp
    textLength <= 50  -> 36.sp
    textLength <= 120 -> 30.sp
    textLength <= 250 -> 26.sp
    else              -> 23.sp
}

@Composable
private fun CalculatorPill(
    suggestion: CalcSuggestion,
    theme: PosterTheme,
    textColor: Color,
    secondaryColor: Color,
    onApply: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .shadow(6.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onApply),
        shape = RoundedCornerShape(22.dp),
        color = theme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, theme.accent),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (suggestion) {
                is CalcSuggestion.InlineMath -> {
                    Text(
                        text = "=",
                        color = theme.accent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        text = suggestion.formattedResult,
                        color = textColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                    )
                }
                is CalcSuggestion.AutoSum -> {
                    Text(
                        text = "∑",
                        color = theme.accent,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        text = "Total: ${suggestion.formattedTotal}",
                        color = textColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(14.dp)
                    .background(secondaryColor.copy(alpha = 0.35f))
            )

            Text(
                text = "Tap to insert ↵",
                color = theme.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
            )
        }
    }
}

@Composable
private fun CountdownPill(
    badge: CountdownBadge,
    theme: PosterTheme,
    accentColor: Color,
    textColor: Color,
) {
    val tier = badge.urgencyTier
    val pillBg = when (tier) {
        UrgencyTier.IMMINENT -> accentColor.copy(alpha = 0.26f)
        UrgencyTier.APPROACHING -> accentColor.copy(alpha = 0.16f)
        UrgencyTier.CALM -> theme.surfaceVariant.copy(alpha = 0.85f)
    }
    val pillBorder = when (tier) {
        UrgencyTier.IMMINENT -> accentColor.copy(alpha = 0.70f)
        UrgencyTier.APPROACHING -> accentColor.copy(alpha = 0.45f)
        UrgencyTier.CALM -> theme.textSecondary.copy(alpha = 0.22f)
    }
    val borderWidth = when (tier) {
        UrgencyTier.IMMINENT -> 1.2.dp
        UrgencyTier.APPROACHING -> 0.9.dp
        UrgencyTier.CALM -> 0.7.dp
    }
    val dotColor = when (tier) {
        UrgencyTier.IMMINENT -> accentColor
        UrgencyTier.APPROACHING -> accentColor
        UrgencyTier.CALM -> if (badge.isOverdue) theme.textSecondary.copy(alpha = 0.5f) else accentColor.copy(alpha = 0.7f)
    }
    val dotSize = when (tier) {
        UrgencyTier.IMMINENT -> 7.dp
        UrgencyTier.APPROACHING -> 6.dp
        UrgencyTier.CALM -> 5.dp
    }
    val hPadding = when (tier) {
        UrgencyTier.IMMINENT -> 12.dp
        UrgencyTier.APPROACHING -> 10.dp
        UrgencyTier.CALM -> 8.dp
    }
    val vPadding = when (tier) {
        UrgencyTier.IMMINENT -> 5.dp
        UrgencyTier.APPROACHING -> 4.dp
        UrgencyTier.CALM -> 3.5.dp
    }
    val fontSize = when (tier) {
        UrgencyTier.IMMINENT -> 11.5.sp
        UrgencyTier.APPROACHING -> 11.sp
        UrgencyTier.CALM -> 10.5.sp
    }
    val fontWeight = when (tier) {
        UrgencyTier.IMMINENT -> FontWeight.SemiBold
        UrgencyTier.APPROACHING -> FontWeight.Medium
        UrgencyTier.CALM -> FontWeight.Normal
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(pillBg)
            .border(borderWidth, pillBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = hPadding, vertical = vPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(dotSize)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = "${badge.tag.removePrefix("@")}: ${badge.label}",
            color = textColor,
            fontSize = fontSize,
            fontFamily = theme.bodyFont,
            fontWeight = fontWeight,
            letterSpacing = 0.3.sp,
        )
    }
}

@Composable
private fun RedactedPosterLockView(
    theme: PosterTheme,
    textColor: Color,
    secondaryColor: Color,
    accentColor: Color,
    onAuthenticate: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onAuthenticate),
        contentAlignment = Alignment.Center,
    ) {
        // Aesthetic geometric censor bars matching line rhythm
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val barColor = secondaryColor.copy(alpha = 0.16f)
            val widths = listOf(0.85f, 0.68f, 0.94f, 0.52f, 0.78f, 0.62f, 0.88f, 0.45f)

            widths.forEach { w ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth(w)
                        .height(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(barColor)
                )
            }
        }

        // Center authentication badge
        Surface(
            modifier = Modifier
                .shadow(8.dp, RoundedCornerShape(22.dp))
                .clip(RoundedCornerShape(22.dp)),
            shape = RoundedCornerShape(22.dp),
            color = theme.surfaceVariant.copy(alpha = 0.96f),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, accentColor.copy(alpha = 0.6f)),
        ) {
            Row(
                modifier = Modifier
                    .clickable(onClick = onAuthenticate)
                    .padding(horizontal = 18.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_lock),
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )

                Column {
                    Text(
                        text = "REDACTED NOTE",
                        color = textColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.5.sp,
                    )
                    Text(
                        text = "Tap to unlock with fingerprint / PIN",
                        color = secondaryColor.copy(alpha = 0.75f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

private fun insertSpeechText(current: TextFieldValue, recognized: String): TextFieldValue {
    val text = current.text
    val start = current.selection.min.coerceIn(0, text.length)
    val end = current.selection.max.coerceIn(0, text.length)

    val prefix = if (start > 0 && !text[start - 1].isWhitespace()) " " else ""
    val toInsert = prefix + recognized.trim() + " "
    val newText = text.substring(0, start) + toInsert + text.substring(end)
    val newCursor = start + toInsert.length
    return TextFieldValue(text = newText, selection = TextRange(newCursor))
}

@Composable
private fun VoiceListeningPill(
    state: SpeechState,
    language: VoiceLanguage,
    theme: PosterTheme,
    accentColor: Color,
    textColor: Color,
    secondaryColor: Color,
    onToggleLanguage: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .shadow(6.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp)),
        shape = RoundedCornerShape(22.dp),
        color = theme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.2.dp, accentColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (state) {
                is SpeechState.Error -> {
                    Text(
                        text = "!",
                        color = accentColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = state.message,
                        color = textColor,
                        fontSize = 12.sp,
                        fontFamily = theme.bodyFont,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = "Dismiss",
                        color = secondaryColor,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
                is SpeechState.DownloadingModel -> {
                    Text(
                        text = "Downloading offline pack: ${state.progress}%",
                        color = textColor,
                        fontSize = 12.sp,
                        fontFamily = theme.bodyFont
                    )
                }
                else -> {
                    // Pulsing Red / Accent Recording Dot
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )

                    // Interim speech or listening prompt
                    val displayText = if (state is SpeechState.InterimResult && state.partialText.isNotBlank()) {
                        "\"${state.partialText}\""
                    } else {
                        if (language == VoiceLanguage.HINDI) "Listening (बोलिए)..." else "Listening..."
                    }

                    Text(
                        text = displayText,
                        color = textColor,
                        fontSize = 13.sp,
                        fontFamily = theme.bodyFont,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    // Tiny Language Toggle: [ EN | HI ]
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(theme.background.copy(alpha = 0.6f))
                            .clickable(onClick = onToggleLanguage)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "EN",
                            color = if (language == VoiceLanguage.ENGLISH) accentColor else secondaryColor.copy(alpha = 0.5f),
                            fontSize = 10.sp,
                            fontWeight = if (language == VoiceLanguage.ENGLISH) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(
                            text = "|",
                            color = secondaryColor.copy(alpha = 0.3f),
                            fontSize = 9.sp
                        )
                        Text(
                            text = "HI",
                            color = if (language == VoiceLanguage.HINDI) accentColor else secondaryColor.copy(alpha = 0.5f),
                            fontSize = 10.sp,
                            fontWeight = if (language == VoiceLanguage.HINDI) FontWeight.Bold else FontWeight.Normal
                        )
                    }

                    // Prominent Done button to click when speech is over
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onStop),
                        color = accentColor,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (language == VoiceLanguage.HINDI) "Done ✓" else "Done ✓",
                            color = theme.background,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
