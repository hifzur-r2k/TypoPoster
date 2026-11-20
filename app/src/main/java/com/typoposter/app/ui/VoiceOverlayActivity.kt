package com.typoposter.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.typoposter.app.data.Note
import com.typoposter.app.data.NoteStore
import com.typoposter.app.date.CountdownHelper
import com.typoposter.app.theme.PosterTheme
import com.typoposter.app.theme.ThemeEngine
import com.typoposter.app.voice.SpeechState
import com.typoposter.app.voice.SpokenDateParser
import com.typoposter.app.voice.VoiceLanguage
import com.typoposter.app.voice.VoiceToNoteEngine
import com.typoposter.app.widget.NoteWidget
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lightweight, floating transparent Activity triggered in 1 tap from the Glance home screen widget.
 * Upgraded into an expressive minimalist "Typo Bot" with:
 * - True Continuous Dictation (never cutting off mid-thought; auto-restarts on silence/utterance boundaries)
 * - Pause / Resume control with instant mic release
 * - Expressive Bot Visor with blinking glowing capsule eyes, audio volume reaction, sideways glances,
 *   calm dashes when paused, happy arcs on deadline detection/save, and a 5-bar precision audio equalizer
 * - Scrollable editorial transcript area with capped height
 * - Clean 3-control footer: Cancel | Tactile Pause/Resume | Solid Save pill
 * - Multilingual dictation: preserves committed text when switching between English and Hindi
 * - Safe saving (strict biometric lock confidentiality)
 */
class VoiceOverlayActivity : ComponentActivity() {

    private lateinit var store: NoteStore
    private var boundNoteId: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NoteStore(applicationContext)
        boundNoteId = intent.getStringExtra(NoteWidget.PARAM_NOTE_ID.name) ?: intent.getStringExtra("EXTRA_NOTE_ID")

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            var activeNote by remember { mutableStateOf<Note?>(null) }
            var theme by remember { mutableStateOf(ThemeEngine.Executive) }
            var isLoaded by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                val notes = store.notes.first()
                val activeId = boundNoteId ?: store.activeNoteId.first()
                val found = notes.firstOrNull { it.id == activeId } ?: notes.firstOrNull() ?: store.createNote()
                activeNote = found
                theme = ThemeEngine.getTheme(found.themeIndex)
                isLoaded = true
            }

            if (isLoaded) {
                VoiceOverlayScreen(
                    theme = theme,
                    boundNote = activeNote,
                    onDismiss = {
                        VoiceToNoteEngine.cancelListening()
                        finish()
                    },
                    onSaveNote = { spokenText ->
                        saveAndFinish(spokenText, activeNote)
                    }
                )
            }
        }
    }

    private fun saveAndFinish(spokenText: String, boundNote: Note?) {
        if (spokenText.isBlank()) {
            VoiceToNoteEngine.cancelListening()
            finish()
            return
        }

        lifecycleScope.launch {
            VoiceToNoteEngine.cancelListening()

            // Strict privacy guarantee:
            // If the bound note is locked, NEVER modify or append to it without biometric unlock!
            // Cleanly create a new unlocked note instead so private content remains 100% confidential.
            if (boundNote != null && !boundNote.isLocked) {
                val newContent = if (boundNote.content.isBlank()) {
                    spokenText.trim()
                } else {
                    "${boundNote.content.trim()}\n\n${spokenText.trim()}"
                }
                val updated = boundNote.copy(
                    content = newContent,
                    updatedAt = System.currentTimeMillis()
                )
                store.saveNote(updated)
                NoteWidget.updateWidgetForNote(applicationContext, updated)
            } else {
                val newNote = store.createNote(title = "Voice Note")
                val updated = newNote.copy(
                    content = spokenText.trim(),
                    themeIndex = boundNote?.themeIndex ?: 0,
                    updatedAt = System.currentTimeMillis()
                )
                store.saveNote(updated)
                NoteWidget.updateWidgetForNote(applicationContext, updated)
            }

            finish()
            overridePendingTransition(0, android.R.anim.fade_out)
        }
    }

    override fun onPause() {
        super.onPause()
        VoiceToNoteEngine.cancelListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        VoiceToNoteEngine.cancelListening()
    }
}

@Composable
private fun VoiceOverlayScreen(
    theme: PosterTheme,
    boundNote: Note?,
    onDismiss: () -> Unit,
    onSaveNote: (String) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()

    // Language state
    var language by remember { mutableStateOf(VoiceLanguage.ENGLISH) }

    // Continuous Dictation state: committed text accumulates finalized phrases, livePartialText streams live words
    var committedText by remember { mutableStateOf("") }
    var livePartialText by remember { mutableStateOf("") }

    // Bot states
    var isListening by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var showHappyEyes by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Jobs
    var languageSwitchJob by remember { mutableStateOf<Job?>(null) }
    var autoRestartJob by remember { mutableStateOf<Job?>(null) }

    // Natural blinking loop: blinks every 3.2s to 4.8s when not paused
    var isBlinking by remember { mutableStateOf(false) }
    LaunchedEffect(isPaused) {
        if (!isPaused) {
            while (isActive) {
                delay((3200..4800).random().toLong())
                isBlinking = true
                delay(140L)
                isBlinking = false
            }
        }
    }

    // Live audio amplitude for equalizer and eye volume reaction
    val rmsAmplitude by VoiceToNoteEngine.rmsFlow.collectAsState()
    val animatedRms by animateFloatAsState(
        targetValue = if (isListening && !isPaused) rmsAmplitude else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow),
        label = "bot_rms_spring"
    )

    // Pulsing alpha for live status dot
    val infiniteTransition = rememberInfiniteTransition(label = "status_pulse")
    val statusDotPulse by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "status_dot_pulse"
    )

    // Start recognition callback
    fun startRecognition() {
        if (!isPaused && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            errorMessage = null
            VoiceToNoteEngine.startListening(
                context = context,
                language = language,
                onStateChange = { state ->
                    when (state) {
                        is SpeechState.Listening -> {
                            isListening = true
                            isConnecting = false
                        }
                        is SpeechState.InterimResult -> {
                            isListening = true
                            isConnecting = false
                            if (state.partialText.isNotBlank()) {
                                livePartialText = SpokenDateParser.convert(state.partialText)
                            }
                        }
                        is SpeechState.FinalResult -> {
                            // Append finalized phrase smoothly to committedText with proper spacing
                            val phrase = state.convertedText.trim()
                            if (phrase.isNotBlank()) {
                                committedText = if (committedText.isBlank()) phrase else "$committedText $phrase"
                            }
                            livePartialText = ""

                            // Silently restart continuous listening after ~200ms
                            if (!isPaused) {
                                autoRestartJob?.cancel()
                                autoRestartJob = coroutineScope.launch {
                                    delay(200L)
                                    if (!isPaused) {
                                        startRecognition()
                                    }
                                }
                            }
                        }
                        is SpeechState.Error -> {
                            val code = state.errorCode
                            // Silently restart for timeouts (6) and no-match (7) during continuous dictation
                            if ((code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || code == SpeechRecognizer.ERROR_NO_MATCH) && !isPaused) {
                                livePartialText = ""
                                autoRestartJob?.cancel()
                                autoRestartJob = coroutineScope.launch {
                                    delay(200L)
                                    if (!isPaused) {
                                        startRecognition()
                                    }
                                }
                            } else {
                                isListening = false
                                errorMessage = state.message
                            }
                        }
                        is SpeechState.Idle -> {
                            isListening = false
                        }
                        else -> {}
                    }
                }
            )
        }
    }

    // Toggle Pause/Resume: stops and releases mic immediately when paused, restarts and appends when resumed
    val togglePause: () -> Unit = {
        if (isPaused) {
            isPaused = false
            errorMessage = null
            startRecognition()
        } else {
            isPaused = true
            isListening = false
            autoRestartJob?.cancel()
            VoiceToNoteEngine.safeCancel()
        }
    }

    // Language switch handler:
    // Destroys active recognizer, preserves committedText, waits ~300ms, then creates fresh recognizer in new language
    val switchLanguage: (VoiceLanguage) -> Unit = { nextLang ->
        if (nextLang != language) {
            language = nextLang
            isListening = false
            isConnecting = true
            livePartialText = ""
            errorMessage = null
            autoRestartJob?.cancel()
            VoiceToNoteEngine.safeCancel()

            languageSwitchJob?.cancel()
            languageSwitchJob = coroutineScope.launch {
                delay(300L)
                if (!isPaused) {
                    startRecognition()
                } else {
                    isConnecting = false
                }
            }
        }
    }

    // Initial launch: start listening immediately
    LaunchedEffect(Unit) {
        startRecognition()
    }

    // Full display transcript (committed + live partial)
    val fullTranscript = remember(committedText, livePartialText) {
        if (livePartialText.isBlank()) committedText
        else if (committedText.isBlank()) livePartialText
        else "$committedText $livePartialText"
    }

    // Detect countdown deadline tags and trigger snap-in haptic tick + happy eyes
    val hasDateTag = remember(fullTranscript) {
        fullTranscript.contains("@")
    }
    var previousHadDateTag by remember { mutableStateOf(false) }
    LaunchedEffect(hasDateTag) {
        if (hasDateTag && !previousHadDateTag) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            showHappyEyes = true
            delay(1200L)
            showHappyEyes = false
        }
        previousHadDateTag = hasDateTag
    }

    // Soft dimmed backdrop; tapping outside dismisses
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        // Floating Minimalist Matte Card (28dp corners, 1dp low-opacity border, soft elevation — zero neon clutter)
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .wrapContentHeight()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {} // Consume click inside card
                )
                .shadow(
                    elevation = 16.dp,
                    shape = RoundedCornerShape(28.dp),
                    spotColor = Color.Black.copy(alpha = 0.40f),
                    ambientColor = Color.Black.copy(alpha = 0.50f)
                )
                .clip(RoundedCornerShape(28.dp))
                .border(1.dp, theme.textSecondary.copy(alpha = 0.14f), RoundedCornerShape(28.dp)),
            color = theme.surfaceVariant,
            shape = RoundedCornerShape(28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ── Top Bar: Active Note Title & Animated Sliding Language Toggle (EN | HI) ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Status dot + Note title
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        val dotColor = when {
                            errorMessage != null -> theme.accent
                            isPaused -> theme.textSecondary.copy(alpha = 0.4f)
                            isListening -> theme.accent.copy(alpha = statusDotPulse)
                            else -> theme.accent.copy(alpha = 0.6f)
                        }
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Text(
                            text = if (boundNote?.isLocked == true) "NEW VOICE NOTE" else (boundNote?.title?.uppercase() ?: "TYPO BOT"),
                            fontSize = 11.sp,
                            fontFamily = theme.headerFont,
                            fontWeight = FontWeight.Bold,
                            color = theme.textSecondary.copy(alpha = 0.8f),
                            letterSpacing = 1.2.sp
                        )
                    }

                    // Sleek Sliding Pill Toggle (EN | HI)
                    LanguageSlidingToggle(
                        currentLanguage = language,
                        onLanguageSelected = switchLanguage,
                        accentColor = theme.accent,
                        textColor = theme.textPrimary,
                        backgroundColor = theme.background
                    )
                }

                // ── Expressive Typo Bot Visor (Compose Canvas) ──
                // Tapping the visor toggles Pause/Resume directly
                Box(
                    modifier = Modifier
                        .width(144.dp)
                        .height(68.dp)
                        .clip(RoundedCornerShape(34.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            togglePause()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    TypoBotVisor(
                        theme = theme,
                        isListening = isListening,
                        isPaused = isPaused,
                        isConnecting = isConnecting,
                        isBlinking = isBlinking,
                        showHappyEyes = showHappyEyes,
                        amplitude = animatedRms,
                        language = language
                    )
                }

                // ── Scrollable Editorial Transcript Area (Height Capped) ──
                val scrollState = rememberScrollState()
                LaunchedEffect(fullTranscript) {
                    scrollState.animateScrollTo(scrollState.maxValue)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp, max = 145.dp)
                        .verticalScroll(scrollState),
                    contentAlignment = Alignment.Center
                ) {
                    if (fullTranscript.isBlank()) {
                        val placeholder = when {
                            isPaused -> "Paused. Tap Bot visor or Resume to speak..."
                            isConnecting -> "Connecting to ${language.displayName}..."
                            language == VoiceLanguage.HINDI -> "टाइपो बॉट सुन रहा है... बोलिए..."
                            else -> "Typo Bot is listening... speak continuously..."
                        }
                        Text(
                            text = placeholder,
                            color = theme.textSecondary.copy(alpha = 0.42f),
                            fontSize = 15.sp,
                            fontFamily = theme.bodyFont,
                            textAlign = TextAlign.Center,
                            lineHeight = 22.sp
                        )
                    } else {
                        Text(
                            text = buildAnnotatedString {
                                if (committedText.isNotBlank()) {
                                    append(committedText)
                                }
                                if (livePartialText.isNotBlank()) {
                                    if (committedText.isNotBlank()) append(" ")
                                    withStyle(SpanStyle(color = theme.textPrimary.copy(alpha = 0.70f))) {
                                        append(livePartialText)
                                    }
                                }
                            },
                            color = theme.textPrimary,
                            fontSize = if (fullTranscript.length > 100) 15.sp else if (fullTranscript.length > 40) 17.sp else 20.sp,
                            fontFamily = theme.headerFont,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            lineHeight = if (fullTranscript.length > 100) 22.sp else 26.sp
                        )
                    }
                }

                // ── Countdown Snap-In Flash Badge ──
                val nearestDeadline = remember(fullTranscript) {
                    if (hasDateTag) CountdownHelper.findNearest(fullTranscript) else null
                }
                AnimatedVisibility(
                    visible = nearestDeadline != null,
                    enter = scaleIn(spring(dampingRatio = 0.65f)) + fadeIn(),
                    exit = scaleOut() + fadeOut()
                ) {
                    if (nearestDeadline != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = theme.accent.copy(alpha = 0.16f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, theme.accent.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "⏳",
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = nearestDeadline.compactLabel.uppercase(),
                                    color = theme.accent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = theme.headerFont,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                }

                // ── Diagnostic Error (if any) ──
                if (errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        color = theme.accent,
                        fontSize = 12.sp,
                        fontFamily = theme.bodyFont,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.clickable {
                            isPaused = false
                            startRecognition()
                        }
                    )
                }

                // ── Clean 3-Control Footer: Cancel | Tactile Pause/Resume | Solid Save Pill ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: Subtle Cancel Button
                    TextButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = "Cancel",
                            color = theme.textSecondary.copy(alpha = 0.75f),
                            fontSize = 13.sp,
                            fontFamily = theme.bodyFont
                        )
                    }

                    // Center: Tactile Circular Pause / Resume Button
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(if (isPaused) theme.accent.copy(alpha = 0.22f) else theme.surfaceVariant)
                            .border(1.2.dp, theme.accent.copy(alpha = if (isPaused) 0.8f else 0.35f), CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                togglePause()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isPaused) {
                            Text(
                                text = "▶",
                                fontSize = 14.sp,
                                color = theme.accent,
                                modifier = Modifier.offset(x = 1.dp)
                            )
                        } else {
                            Text(
                                text = "❚❚",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = theme.accent
                            )
                        }
                    }

                    // Right: Solid High-Contrast Save Pill (Disabled if transcript is empty)
                    val hasText = fullTranscript.isNotBlank()
                    Button(
                        onClick = {
                            if (hasText) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                showHappyEyes = true
                                coroutineScope.launch {
                                    delay(280L)
                                    onSaveNote(fullTranscript)
                                }
                            }
                        },
                        enabled = hasText,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = theme.accent,
                            contentColor = theme.background,
                            disabledContainerColor = theme.textSecondary.copy(alpha = 0.12f),
                            disabledContentColor = theme.textSecondary.copy(alpha = 0.35f)
                        ),
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 9.dp),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Text(
                            text = "Save ✓",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = theme.headerFont,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Expressive Typo Bot Visor (Compose Canvas):
 * - Dark recessed pill visor container
 * - Two capsule glowing eyes that blink naturally, react to RMS volume, glance sideways,
 *   turn to calm dashes when paused, and curve into happy arcs (^ ^)
 * - 5-bar precision audio equalizer settling flat when silent or paused
 */
@Composable
private fun TypoBotVisor(
    theme: PosterTheme,
    isListening: Boolean,
    isPaused: Boolean,
    isConnecting: Boolean,
    isBlinking: Boolean,
    showHappyEyes: Boolean,
    amplitude: Float,
    language: VoiceLanguage
) {
    val accentColor = theme.accent

    // Glancing horizontal offset when switching language or connecting
    val glanceTarget = when {
        isConnecting && language == VoiceLanguage.HINDI -> 6f
        isConnecting && language == VoiceLanguage.ENGLISH -> -6f
        else -> 0f
    }
    val glanceOffset by animateFloatAsState(
        targetValue = glanceTarget,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
        label = "bot_glance"
    )

    // Continuous subtle equalizer phase animation
    val infiniteTransition = rememberInfiniteTransition(label = "eq_phase")
    val eqPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "eq_phase"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // 1. Recessed dark visor pill background
        drawRoundRect(
            color = Color(0xFF0F1012),
            size = Size(w, h),
            cornerRadius = CornerRadius(h / 2f, h / 2f)
        )
        // Visor border
        drawRoundRect(
            color = accentColor.copy(alpha = 0.18f),
            size = Size(w, h),
            cornerRadius = CornerRadius(h / 2f, h / 2f),
            style = Stroke(width = 1.dp.toPx())
        )

        // 2. Eyes Setup
        val eyeCenterY = h * 0.38f
        val eyeSpacing = 36.dp.toPx()
        val leftEyeX = (w / 2f - eyeSpacing / 2f) + glanceOffset.dp.toPx()
        val rightEyeX = (w / 2f + eyeSpacing / 2f) + glanceOffset.dp.toPx()

        when {
            // State A: Happy Arcs (^ ^) on deadline snap-in or Save
            showHappyEyes -> {
                val arcHalfW = 6.5.dp.toPx()
                val arcH = 5.dp.toPx()
                val leftPath = Path().apply {
                    moveTo(leftEyeX - arcHalfW, eyeCenterY + arcH / 2f)
                    quadraticBezierTo(leftEyeX, eyeCenterY - arcH, leftEyeX + arcHalfW, eyeCenterY + arcHalfW / 2f)
                }
                val rightPath = Path().apply {
                    moveTo(rightEyeX - arcHalfW, eyeCenterY + arcH / 2f)
                    quadraticBezierTo(rightEyeX, eyeCenterY - arcH, rightEyeX + arcHalfW, eyeCenterY + arcHalfW / 2f)
                }
                drawPath(leftPath, accentColor, style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round))
                drawPath(rightPath, accentColor, style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round))
            }

            // State B: Calm Dashes (— —) when paused
            isPaused -> {
                val dashHalfW = 6.5.dp.toPx()
                drawLine(
                    color = accentColor.copy(alpha = 0.65f),
                    start = Offset(leftEyeX - dashHalfW, eyeCenterY),
                    end = Offset(leftEyeX + dashHalfW, eyeCenterY),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = accentColor.copy(alpha = 0.65f),
                    start = Offset(rightEyeX - dashHalfW, eyeCenterY),
                    end = Offset(rightEyeX + dashHalfW, eyeCenterY),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // State C: Glowing Capsule Eyes (Blinks & Volume Reactivity)
            else -> {
                val eyeW = 11.5.dp.toPx() + (amplitude * 2.5.dp.toPx())
                val eyeH = if (isBlinking) 2.5.dp.toPx() else (19.dp.toPx() + (amplitude * 5.5.dp.toPx()))

                // Left Eye Glow + Capsule
                drawRoundRect(
                    color = accentColor.copy(alpha = 0.22f),
                    topLeft = Offset(leftEyeX - (eyeW + 3.dp.toPx()) / 2f, eyeCenterY - (eyeH + 3.dp.toPx()) / 2f),
                    size = Size(eyeW + 3.dp.toPx(), eyeH + 3.dp.toPx()),
                    cornerRadius = CornerRadius((eyeW + 3.dp.toPx()) / 2f, (eyeW + 3.dp.toPx()) / 2f)
                )
                drawRoundRect(
                    color = accentColor,
                    topLeft = Offset(leftEyeX - eyeW / 2f, eyeCenterY - eyeH / 2f),
                    size = Size(eyeW, eyeH),
                    cornerRadius = CornerRadius(eyeW / 2f, eyeW / 2f)
                )

                // Right Eye Glow + Capsule
                drawRoundRect(
                    color = accentColor.copy(alpha = 0.22f),
                    topLeft = Offset(rightEyeX - (eyeW + 3.dp.toPx()) / 2f, eyeCenterY - (eyeH + 3.dp.toPx()) / 2f),
                    size = Size(eyeW + 3.dp.toPx(), eyeH + 3.dp.toPx()),
                    cornerRadius = CornerRadius((eyeW + 3.dp.toPx()) / 2f, (eyeW + 3.dp.toPx()) / 2f)
                )
                drawRoundRect(
                    color = accentColor,
                    topLeft = Offset(rightEyeX - eyeW / 2f, eyeCenterY - eyeH / 2f),
                    size = Size(eyeW, eyeH),
                    cornerRadius = CornerRadius(eyeW / 2f, eyeW / 2f)
                )
            }
        }

        // 3. 5-Bar Precision Audio Equalizer (beneath the eyes, settling flat when paused)
        val eqCenterY = h * 0.77f
        val numBars = 5
        val barWidth = 2.8.dp.toPx()
        val barSpacing = 4.5.dp.toPx()
        val totalEqW = (numBars * barWidth) + ((numBars - 1) * barSpacing)
        val startX = w / 2f - totalEqW / 2f + barWidth / 2f

        val barFactors = floatArrayOf(0.45f, 0.85f, 1.0f, 0.80f, 0.50f)
        for (i in 0 until numBars) {
            val barX = startX + i * (barWidth + barSpacing)
            val barH = if (isPaused || !isListening) {
                2.5.dp.toPx() // Settled flat indicator dash
            } else {
                val wave = sin(eqPhase + i * 1.1f).coerceAtLeast(0f)
                2.5.dp.toPx() + (amplitude * 12.dp.toPx() * barFactors[i]) + (wave * amplitude * 3.dp.toPx())
            }

            drawLine(
                color = if (isPaused) accentColor.copy(alpha = 0.25f) else accentColor.copy(alpha = 0.80f),
                start = Offset(barX, eqCenterY - barH / 2f),
                end = Offset(barX, eqCenterY + barH / 2f),
                strokeWidth = barWidth,
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * Sleek Animated Sliding Pill Toggle for EN | HI.
 */
@Composable
private fun LanguageSlidingToggle(
    currentLanguage: VoiceLanguage,
    onLanguageSelected: (VoiceLanguage) -> Unit,
    accentColor: Color,
    textColor: Color,
    backgroundColor: Color,
) {
    val selectedIndex = if (currentLanguage == VoiceLanguage.ENGLISH) 0 else 1
    val indicatorOffset by animateDpAsState(
        targetValue = if (selectedIndex == 0) 3.dp else 37.dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "lang_indicator_offset"
    )

    Box(
        modifier = Modifier
            .width(74.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(backgroundColor.copy(alpha = 0.65f))
            .border(1.dp, textColor.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.CenterStart
    ) {
        // Sliding indicator pill
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(34.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(accentColor.copy(alpha = 0.20f))
                .border(1.dp, accentColor.copy(alpha = 0.70f), RoundedCornerShape(11.dp))
        )

        // Options
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onLanguageSelected(VoiceLanguage.ENGLISH)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "EN",
                    fontSize = 11.sp,
                    fontWeight = if (selectedIndex == 0) FontWeight.Bold else FontWeight.Medium,
                    color = if (selectedIndex == 0) accentColor else textColor.copy(alpha = 0.45f),
                    letterSpacing = 0.5.sp
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onLanguageSelected(VoiceLanguage.HINDI)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "HI",
                    fontSize = 11.sp,
                    fontWeight = if (selectedIndex == 1) FontWeight.Bold else FontWeight.Medium,
                    color = if (selectedIndex == 1) accentColor else textColor.copy(alpha = 0.45f),
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}
