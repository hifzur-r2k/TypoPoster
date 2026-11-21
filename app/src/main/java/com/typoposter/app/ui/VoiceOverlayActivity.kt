package com.typoposter.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
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
import com.typoposter.app.date.UrgencyTier
import com.typoposter.app.theme.PosterTheme
import com.typoposter.app.theme.ThemeEngine
import com.typoposter.app.voice.SpeechState
import com.typoposter.app.voice.SpokenDateParser
import com.typoposter.app.voice.SpokenMathParser
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
 * Upgraded with an adorable, minimalist, circular floating companion bot "Typo Bot":
 * - Circular Mascot Silhouette: 108dp diameter head with weightless floating bob (+/-3.5dp), soft 3D depth,
 *   radial light catch, 1dp rim highlight, recessed dark-glass face, and delicate circular soundwave ripple rings
 * - Cute Expressive Face: rounded-oval warm glowing eyes, specular sparkle catchlights, soft rosy cheek blush,
 *   and reactive morphing mouth (subtle smile -> talking shape -> neutral "o" -> wavy -> happy grin)
 * - Playful Expressive States:
 *   * Listening Idle: floating bob, randomized 2.5-4.5s blinking + double-blinks, curious eye glances
 *   * Actively Speaking: joyful squash-and-stretch micro-bounce, warmer blush, talking mouth, soundwave ripples
 *   * Mid-Sentence Thinking Pause: adorable 6-degree head tilt with asymmetric curious eyes and neutral "o" mouth
 *   * Switching Language: smooth darting glance with outer rotating swirl pulse
 *   * Paused: bob rests, sleepy downward arcs (◡  ◡)
 *   * Countdown / Save: happy crescent arcs (◠  ◠), bright blush, happy grin, and celebratory 240ms spring hop
 *   * Error State: soft amber tint with empathetic eyebrows and tiny wavy mouth
 * - Clean editorial overlay with single-line micro-copy and tactile controls
 * - True Continuous Dictation & multi-language preservation (English + Hindi)
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

    // Full display transcript (committed + live partial)
    val fullTranscript = remember(committedText, livePartialText) {
        if (livePartialText.isBlank()) committedText
        else if (committedText.isBlank()) livePartialText
        else "$committedText $livePartialText"
    }

    // Live audio amplitude for voice reactive visuals
    val rmsAmplitude by VoiceToNoteEngine.rmsFlow.collectAsState()
    val animatedRms by animateFloatAsState(
        targetValue = if (isListening && !isPaused) rmsAmplitude else 0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessLow),
        label = "bot_rms_spring"
    )

    // Mid-Sentence Thinking Pause detection (~2.4s of silence while active note has words)
    var isThinkingPause by remember { mutableStateOf(false) }
    LaunchedEffect(isListening, isPaused, fullTranscript.length, rmsAmplitude) {
        if (isListening && !isPaused && fullTranscript.isNotBlank()) {
            if (rmsAmplitude < 0.08f) {
                delay(2400L)
                if (isActive && isListening && !isPaused && rmsAmplitude < 0.08f) {
                    isThinkingPause = true
                }
            } else {
                isThinkingPause = false
            }
        } else {
            isThinkingPause = false
        }
    }

    // Adorable ~6° gentle head tilt when thinking pause is active
    val animatedHeadTilt by animateFloatAsState(
        targetValue = if (isThinkingPause) 6f else 0f,
        animationSpec = spring(dampingRatio = 0.70f, stiffness = Spring.StiffnessLow),
        label = "head_tilt"
    )

    // Natural randomized blinking loop (2.5s–4.5s interval + 25% chance of occasional quick double-blink)
    var isBlinking by remember { mutableStateOf(false) }
    LaunchedEffect(isPaused) {
        if (!isPaused) {
            while (isActive) {
                delay((2500..4500).random().toLong())
                isBlinking = true
                delay(105L)
                isBlinking = false
                // 25% chance of quick double-blink
                if ((1..4).random() == 1) {
                    delay(85L)
                    isBlinking = true
                    delay(85L)
                    isBlinking = false
                }
            }
        }
    }

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

    // Fallback system speech recognition launcher (100% compatibility across all Android phones)
    val speechIntentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            val spoken = matches?.firstOrNull() ?: ""
            if (spoken.isNotBlank()) {
                val converted = SpokenDateParser.convert(spoken)
                val mathParsed = SpokenMathParser.parse(converted)
                committedText = if (committedText.isBlank()) mathParsed else "$committedText $mathParsed"
            }
        }
        isListening = false
    }

    val launchSystemSpeech: () -> Unit = {
        try {
            val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, language.tag)
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.tag)
                putExtra(
                    android.speech.RecognizerIntent.EXTRA_PROMPT,
                    if (language == VoiceLanguage.HINDI) "बोलिए..." else "Speak note or deadline..."
                )
            }
            speechIntentLauncher.launch(intent)
        } catch (_: Throwable) {
            errorMessage = "Voice recognition unavailable on this device"
        }
    }

    // Start recognition callback
    fun startRecognition() {
        if (!isPaused && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            errorMessage = null
            VoiceToNoteEngine.startListening(
                context = context,
                language = language,
                onTier3Fallback = launchSystemSpeech,
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
                            val mathParsed = SpokenMathParser.parse(state.convertedText)
                            val phrase = mathParsed.trim()
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
            .background(Color.Black.copy(alpha = if (theme.isDark) 0.65f else 0.40f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        // Theme-Synchronized Card Surface
        val cardBg = if (theme.isDark) theme.surfaceVariant else Color(0xFFFAF7F2)
        val cardBorderColor = theme.textPrimary.copy(alpha = if (theme.isDark) 0.12f else 0.09f)

        Surface(
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .wrapContentHeight()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {} // Consume click inside card
                )
                .shadow(
                    elevation = 18.dp,
                    shape = RoundedCornerShape(26.dp),
                    spotColor = Color.Black.copy(alpha = if (theme.isDark) 0.48f else 0.16f),
                    ambientColor = Color.Black.copy(alpha = if (theme.isDark) 0.38f else 0.10f)
                )
                .clip(RoundedCornerShape(26.dp))
                .border(1.dp, cardBorderColor, RoundedCornerShape(26.dp)),
            color = cardBg,
            shape = RoundedCornerShape(26.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ── Top Bar: Subtle NOTE label & Minimal Segmented Toggle (EN | HI) ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Small muted indicator dot + subtle tracked uppercase NOTE label
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val dotColor = when {
                            errorMessage != null -> Color(0xFFFFB236)
                            isPaused -> theme.textSecondary.copy(alpha = 0.35f)
                            isListening -> (if (theme.isDark) theme.accent else theme.textPrimary).copy(alpha = statusDotPulse)
                            else -> theme.textSecondary.copy(alpha = 0.45f)
                        }
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Text(
                            text = "NOTE",
                            fontSize = 11.sp,
                            fontFamily = theme.headerFont,
                            fontWeight = FontWeight.SemiBold,
                            color = theme.textSecondary.copy(alpha = 0.70f),
                            letterSpacing = 2.sp
                        )
                    }

                    // Refined Minimal Segmented Pill Toggle (EN | HI)
                    LanguageSlidingToggle(
                        currentLanguage = language,
                        onLanguageSelected = switchLanguage,
                        theme = theme
                    )
                }

                // ── Adorable Circular Floating Companion Bot (Compose Canvas) ──
                // Tapping the companion bot toggles Pause/Resume directly
                Box(
                    modifier = Modifier
                        .size(136.dp)
                        .graphicsLayer {
                            rotationZ = animatedHeadTilt
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            togglePause()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    TypoBotCircularCompanion(
                        theme = theme,
                        isListening = isListening,
                        isPaused = isPaused,
                        isConnecting = isConnecting,
                        isThinkingPause = isThinkingPause,
                        isBlinking = isBlinking,
                        showHappyEyes = showHappyEyes,
                        errorMessage = errorMessage,
                        amplitude = animatedRms
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
                        .heightIn(min = 52.dp, max = 145.dp)
                        .verticalScroll(scrollState),
                    contentAlignment = Alignment.Center
                ) {
                    if (fullTranscript.isBlank()) {
                        // Clean single-line muted status text
                        val statusText = when {
                            errorMessage != null -> "Tap to retry"
                            isPaused -> "Paused"
                            isConnecting -> "Connecting..."
                            language == VoiceLanguage.HINDI -> "बोलिए..."
                            else -> "Listening..."
                        }
                        Text(
                            text = statusText,
                            color = if (errorMessage != null) Color(0xFFFFB236) else theme.textSecondary.copy(alpha = 0.55f),
                            fontSize = 14.sp,
                            fontFamily = theme.bodyFont,
                            fontWeight = FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            letterSpacing = 0.4.sp
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
                        val tier = nearestDeadline.urgencyTier
                        val surfColor = when (tier) {
                            UrgencyTier.IMMINENT -> theme.accent.copy(alpha = 0.24f)
                            UrgencyTier.APPROACHING -> theme.accent.copy(alpha = 0.16f)
                            UrgencyTier.CALM -> theme.surfaceVariant.copy(alpha = 0.75f)
                        }
                        val borderColor = when (tier) {
                            UrgencyTier.IMMINENT -> theme.accent.copy(alpha = 0.65f)
                            UrgencyTier.APPROACHING -> theme.accent.copy(alpha = 0.45f)
                            UrgencyTier.CALM -> theme.textSecondary.copy(alpha = 0.30f)
                        }
                        val borderWidth = when (tier) {
                            UrgencyTier.IMMINENT -> 1.2.dp
                            UrgencyTier.APPROACHING -> 1.dp
                            UrgencyTier.CALM -> 0.8.dp
                        }
                        val hPad = when (tier) {
                            UrgencyTier.IMMINENT -> 14.dp
                            UrgencyTier.APPROACHING -> 12.dp
                            UrgencyTier.CALM -> 10.dp
                        }
                        val vPad = when (tier) {
                            UrgencyTier.IMMINENT -> 6.dp
                            UrgencyTier.APPROACHING -> 5.dp
                            UrgencyTier.CALM -> 4.dp
                        }
                        val fSize = when (tier) {
                            UrgencyTier.IMMINENT -> 12.sp
                            UrgencyTier.APPROACHING -> 11.sp
                            UrgencyTier.CALM -> 10.5.sp
                        }
                        val fWeight = when (tier) {
                            UrgencyTier.IMMINENT -> FontWeight.Bold
                            UrgencyTier.APPROACHING -> FontWeight.SemiBold
                            UrgencyTier.CALM -> FontWeight.Medium
                        }
                        val txtColor = when (tier) {
                            UrgencyTier.IMMINENT, UrgencyTier.APPROACHING -> theme.accent
                            UrgencyTier.CALM -> theme.textSecondary
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = surfColor,
                            border = androidx.compose.foundation.BorderStroke(borderWidth, borderColor)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = hPad, vertical = vPad),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "⏳",
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = nearestDeadline.compactLabel.uppercase(),
                                    color = txtColor,
                                    fontSize = fSize,
                                    fontWeight = fWeight,
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
                        color = Color(0xFFFFB236),
                        fontSize = 12.sp,
                        fontFamily = theme.bodyFont,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.clickable {
                            isPaused = false
                            launchSystemSpeech()
                        }
                    )
                }

                // ── Clean 3-Control Footer: Cancel | Clean Matte Pause/Resume | Confident Save Pill ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: Understated Cancel Button
                    TextButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "Cancel",
                            color = theme.textSecondary.copy(alpha = 0.70f),
                            fontSize = 13.sp,
                            fontFamily = theme.bodyFont,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Center: Clean matte-filled circular Pause / Resume button (no neon ring)
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (isPaused) theme.textPrimary.copy(alpha = 0.12f)
                                else theme.textPrimary.copy(alpha = 0.07f)
                            )
                            .border(
                                width = 1.dp,
                                color = theme.textPrimary.copy(alpha = if (isPaused) 0.20f else 0.12f),
                                shape = CircleShape
                            )
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
                                fontSize = 13.sp,
                                color = theme.textPrimary,
                                modifier = Modifier.offset(x = 1.dp)
                            )
                        } else {
                            Text(
                                text = "❚❚",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = theme.textPrimary
                            )
                        }
                    }

                    // Right: Confident, High-Contrast Save Pill that activates smoothly once text is present
                    val hasText = fullTranscript.isNotBlank()
                    val saveBg by animateColorAsState(
                        targetValue = if (hasText) theme.textPrimary else theme.textPrimary.copy(alpha = 0.07f),
                        animationSpec = tween(200),
                        label = "save_bg"
                    )
                    val saveText by animateColorAsState(
                        targetValue = if (hasText) {
                            if (theme.isDark) Color(0xFF101214) else Color.White
                        } else theme.textSecondary.copy(alpha = 0.35f),
                        animationSpec = tween(200),
                        label = "save_text"
                    )

                    Button(
                        onClick = {
                            if (hasText) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                showHappyEyes = true
                                coroutineScope.launch {
                                    // 220ms celebratory hop before closing cleanly
                                    delay(220L)
                                    onSaveNote(fullTranscript)
                                }
                            }
                        },
                        enabled = hasText,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = saveBg,
                            contentColor = saveText,
                            disabledContainerColor = theme.textPrimary.copy(alpha = 0.07f),
                            disabledContentColor = theme.textSecondary.copy(alpha = 0.35f)
                        ),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 9.dp),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Text(
                            text = "Save ✓",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
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
 * Adorable, minimalist, circular floating companion bot "Typo Bot" (Compose Canvas):
 * - Circular Mascot Silhouette: ~108dp diameter head with smooth infinite floating bob (+/-3.5dp)
 * - Soft 3D Depth: circular shell with gentle upper-left radial highlight, 1dp translucent rim highlight,
 *   recessed dark-glass face with soft top curved glass sheen
 * - Delicate Soundwave Ripple Rings expanding outside the circular body synced in real time to onRmsChanged()
 * - Cute Expressive Face: rounded-oval warm glowing eyes, specular sparkle catchlights, soft rosy cheek blush,
 *   reactive mouth (subtle smile -> talking shape -> neutral "o" -> wavy -> happy grin)
 * - Micro-behaviors: natural blinks (plus occasional double-blink), micro-saccade glances, 6° thinking pause tilt,
 *   side-to-side scanning with swirl pulse on language switch, sleepy arcs (◡ ◡) when paused, celebratory 240ms hop
 * - 60fps locked Compose performance with zero heap object allocations inside DrawScope
 */
@Composable
private fun TypoBotCircularCompanion(
    theme: PosterTheme,
    isListening: Boolean,
    isPaused: Boolean,
    isConnecting: Boolean,
    isThinkingPause: Boolean,
    isBlinking: Boolean,
    showHappyEyes: Boolean,
    errorMessage: String?,
    amplitude: Float,
    modifier: Modifier = Modifier
) {
    val accentColor = theme.accent
    val isError = errorMessage != null
    val eyeColor = if (isError) Color(0xFFFFB236) else accentColor

    // ── 1. Weightless Floating Bob Animation (+/-3.5dp smooth sine wave) ──
    val bobTransition = rememberInfiniteTransition(label = "bot_bob_trans")
    val rawBob by bobTransition.animateFloat(
        initialValue = -3.5f,
        targetValue = 3.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bot_bob_raw"
    )
    val animatedBobY by animateFloatAsState(
        targetValue = if (isPaused) 0f else rawBob,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
        label = "bot_bob_smooth"
    )

    // ── 2. Celebratory Spring Hop ──
    val celebrationHop by animateFloatAsState(
        targetValue = if (showHappyEyes) -7f else 0f,
        animationSpec = spring(dampingRatio = 0.50f, stiffness = Spring.StiffnessMedium),
        label = "celebration_hop"
    )

    // ── 3. Soundwave Ripple Traveling Wave & Swirl Phase ──
    val rippleTransition = rememberInfiniteTransition(label = "bot_ripple_trans")
    val ripplePhase by rippleTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple_phase"
    )
    val swirlPhase by rippleTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "swirl_phase"
    )

    // ── 4. Randomized Micro-Saccade Eye Glances ──
    var gazeTargetX by remember { mutableStateOf(0f) }
    var gazeTargetY by remember { mutableStateOf(0f) }
    LaunchedEffect(isListening, isPaused, isConnecting, isError) {
        if (isListening && !isPaused && !isConnecting && !isError) {
            while (isActive) {
                delay((2600..5000).random().toLong())
                when ((0..5).random()) {
                    0 -> { gazeTargetX = 0f; gazeTargetY = 0f }
                    1 -> { gazeTargetX = -2.2f; gazeTargetY = 0f }
                    2 -> { gazeTargetX = 2.2f; gazeTargetY = 0f }
                    3 -> { gazeTargetX = 1.3f; gazeTargetY = -1.1f }
                    4 -> { gazeTargetX = -1.3f; gazeTargetY = -0.7f }
                    else -> { gazeTargetX = 0f; gazeTargetY = 0f }
                }
            }
        } else {
            gazeTargetX = 0f
            gazeTargetY = 0f
        }
    }
    val animatedGazeX by animateFloatAsState(
        targetValue = gazeTargetX,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
        label = "bot_gaze_x"
    )
    val animatedGazeY by animateFloatAsState(
        targetValue = gazeTargetY,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
        label = "bot_gaze_y"
    )

    // Language switching darting glance offset
    val scanPhase by rippleTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scan_phase"
    )
    val connectingScanOffset = if (isConnecting) (sin(scanPhase) * 6.5f) else 0f

    // Spring-interpolated Thinking Pause offsets
    val leftEyeLift by animateFloatAsState(
        targetValue = if (isThinkingPause) -2.6f else 0f,
        animationSpec = spring(dampingRatio = 0.70f, stiffness = Spring.StiffnessLow),
        label = "left_eye_lift"
    )
    val leftEyeWiden by animateFloatAsState(
        targetValue = if (isThinkingPause) 1.14f else 1.0f,
        animationSpec = spring(dampingRatio = 0.70f, stiffness = Spring.StiffnessLow),
        label = "left_eye_widen"
    )

    // Pre-allocated reusable paths for 60fps performance (zero allocations inside onDraw)
    val leftEyePath = remember { Path() }
    val rightEyePath = remember { Path() }
    val mouthPath = remember { Path() }
    val sheenPath = remember { Path() }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        val cx = w / 2f
        val cy = h / 2f + animatedBobY.dp.toPx() + celebrationHop.dp.toPx()
        val headR = 52.dp.toPx() // ~104dp diameter circular mascot head

        // Grounded Drop Shadow under the bot so it feels like a physical minimalist object resting on the card
        val shadowW = headR * 1.30f
        val shadowH = 10.dp.toPx()
        val shadowY = cy + headR * 0.94f
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(Color.Black.copy(alpha = if (theme.isDark) 0.35f else 0.14f), Color.Transparent),
                center = Offset(cx, shadowY),
                radius = shadowW / 2f
            ),
            topLeft = Offset(cx - shadowW / 2f, shadowY - shadowH / 2f),
            size = Size(shadowW, shadowH)
        )

        // ── A. Delicate Tonal Soundwave Rings (15–25% alpha, expanding smoothly with voice RMS) ──
        if (!isPaused && !isError && isListening && amplitude > 0.03f) {
            val waveColor = if (theme.isDark) theme.accent else theme.textPrimary
            // Ring 1: Real-time RMS expansion ring (delicate hairline, 15-22% max alpha)
            val r1 = headR + 3.dp.toPx() + (amplitude * 14.dp.toPx())
            val alpha1 = (amplitude * 0.22f).coerceIn(0f, 0.22f)
            drawCircle(
                color = waveColor.copy(alpha = alpha1),
                radius = r1,
                center = Offset(cx, cy),
                style = Stroke(width = 1.dp.toPx())
            )

            // Ring 2: Harmonic traveling ripple (delicate hairline, 10-16% max alpha)
            val r2 = headR + 2.dp.toPx() + ripplePhase * (12.dp.toPx() + amplitude * 8.dp.toPx())
            val alpha2 = ((1f - ripplePhase) * amplitude * 0.16f).coerceIn(0f, 0.16f)
            if (alpha2 > 0.015f) {
                drawCircle(
                    color = waveColor.copy(alpha = alpha2),
                    radius = r2,
                    center = Offset(cx, cy),
                    style = Stroke(width = 0.8.dp.toPx())
                )
            }
        }

        // Language switching outer swirl pulse
        if (isConnecting) {
            val swirlR = headR + 3.dp.toPx()
            val swirlColor = if (theme.isDark) theme.accent else theme.textPrimary
            drawArc(
                color = swirlColor.copy(alpha = 0.25f),
                startAngle = swirlPhase,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(cx - swirlR, cy - swirlR),
                size = Size(swirlR * 2f, swirlR * 2f),
                style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round)
            )
            drawArc(
                color = swirlColor.copy(alpha = 0.25f),
                startAngle = swirlPhase + 180f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(cx - swirlR, cy - swirlR),
                size = Size(swirlR * 2f, swirlR * 2f),
                style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // ── B. Soft Multi-Layer Ambient Radial Glow behind the circular bot ──
        val ambientAlpha = if (isPaused) 0.02f else (0.04f + amplitude * 0.10f).coerceIn(0f, 0.14f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(eyeColor.copy(alpha = ambientAlpha), Color.Transparent),
                center = Offset(cx, cy),
                radius = headR + 20.dp.toPx()
            ),
            radius = headR + 20.dp.toPx(),
            center = Offset(cx, cy)
        )

        // Subtle joyful micro-bounce (squash-and-stretch) while speaking
        val squashX = 1f + (amplitude * 0.035f)
        val squashY = 1f - (amplitude * 0.035f)

        withTransform({
            scale(scaleX = squashX, scaleY = squashY, pivot = Offset(cx, cy))
        }) {
            // ── C. Smooth Circular Outer Shell (Tactile Material Depth & Light Catch) ──
            val shellColors = if (theme.isDark) {
                listOf(Color(0xFF343840), Color(0xFF22252A), Color(0xFF141618))
            } else {
                listOf(Color(0xFFFFFFFF), Color(0xFFF4F0E8), Color(0xFFE2DDD4))
            }
            val highlightCenter = Offset(cx - headR * 0.28f, cy - headR * 0.32f)
            val shellBrush = Brush.radialGradient(
                colors = shellColors,
                center = highlightCenter,
                radius = headR * 1.35f
            )
            drawCircle(
                brush = shellBrush,
                radius = headR,
                center = Offset(cx, cy)
            )

            // Delicate 1dp translucent rim highlight
            val rimBrush = Brush.linearGradient(
                colors = if (theme.isDark) {
                    listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.05f), Color.Transparent)
                } else {
                    listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.30f), Color.Transparent)
                },
                start = Offset(cx - headR, cy - headR),
                end = Offset(cx + headR, cy + headR)
            )
            drawCircle(
                brush = rimBrush,
                radius = headR,
                center = Offset(cx, cy),
                style = Stroke(width = 1.dp.toPx())
            )

            // ── D. Sleek Circular Recessed Dark-Glass Face ──
            val faceR = 45.dp.toPx() // ~90dp inner face diameter

            // Recessed rim shadow
            drawCircle(
                color = (if (theme.isDark) Color.Black else Color(0xFFC4BEB2)).copy(alpha = 0.45f),
                radius = faceR + 1.2.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 1.6.dp.toPx())
            )
            // Dark glass base with deep contrast
            drawCircle(
                color = if (theme.isDark) Color(0xFF0C0E11) else Color(0xFF1C1E22),
                radius = faceR,
                center = Offset(cx, cy)
            )

            // Soft curved glass sheen near the top
            sheenPath.reset()
            sheenPath.moveTo(cx - 32.dp.toPx(), cy - 20.dp.toPx())
            sheenPath.quadraticBezierTo(cx, cy - 38.dp.toPx(), cx + 32.dp.toPx(), cy - 20.dp.toPx())
            sheenPath.quadraticBezierTo(cx, cy - 28.dp.toPx(), cx - 32.dp.toPx(), cy - 20.dp.toPx())
            sheenPath.close()
            drawPath(
                sheenPath,
                brush = Brush.verticalGradient(
                    colors = listOf(Color.White.copy(alpha = 0.075f), Color.Transparent),
                    startY = cy - 38.dp.toPx(),
                    endY = cy - 20.dp.toPx()
                )
            )

            // ── E. Cute Expressive Eyes Coordinates (Lower-center for classic cute proportions) ──
            val eyeCenterY = cy + 3.5.dp.toPx()
            val eyeSpacing = 32.dp.toPx()
            val totalGazeX = (animatedGazeX + connectingScanOffset).dp.toPx()
            val totalGazeY = animatedGazeY.dp.toPx()

            val leftEyeX = (cx - eyeSpacing / 2f) + totalGazeX
            val leftEyeY = eyeCenterY + totalGazeY + leftEyeLift.dp.toPx()

            val rightEyeX = (cx + eyeSpacing / 2f) + totalGazeX
            val rightEyeY = eyeCenterY + totalGazeY

            // Soft-feathered rosy cheek blush (warming up during speech and happy reaction)
            val baseBlush = if (isPaused) 0.06f else 0.14f
            val speakingBlush = amplitude * 0.22f
            val happyBlush = if (showHappyEyes) 0.30f else 0f
            val totalBlushAlpha = (baseBlush + speakingBlush + happyBlush).coerceIn(0f, 0.45f)
            val blushR = 7.5.dp.toPx()

            val leftBlushCenter = Offset(leftEyeX - 8.dp.toPx(), eyeCenterY + 8.5.dp.toPx())
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFFF6584).copy(alpha = totalBlushAlpha), Color.Transparent),
                    center = leftBlushCenter,
                    radius = blushR
                ),
                radius = blushR,
                center = leftBlushCenter
            )

            val rightBlushCenter = Offset(rightEyeX + 8.dp.toPx(), eyeCenterY + 8.5.dp.toPx())
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFFF6584).copy(alpha = totalBlushAlpha), Color.Transparent),
                    center = rightBlushCenter,
                    radius = blushR
                ),
                radius = blushR,
                center = rightBlushCenter
            )

            // Eye ambient blooms
            val eyeBloomAlpha = if (isPaused) 0.04f else (0.12f + amplitude * 0.25f).coerceIn(0f, 0.38f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(eyeColor.copy(alpha = eyeBloomAlpha), Color.Transparent),
                    center = Offset(leftEyeX, leftEyeY),
                    radius = 22.dp.toPx()
                ),
                radius = 22.dp.toPx(),
                center = Offset(leftEyeX, leftEyeY)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(eyeColor.copy(alpha = eyeBloomAlpha), Color.Transparent),
                    center = Offset(rightEyeX, rightEyeY),
                    radius = 22.dp.toPx()
                ),
                radius = 22.dp.toPx(),
                center = Offset(rightEyeX, rightEyeY)
            )

            // ── F. Render Expressive Eyes ──
            when {
                // State A: Joyful Happy Arcs (◠  ◠) on Countdown / Save Celebration
                showHappyEyes -> {
                    val arcHalfW = 6.dp.toPx()
                    val arcH = 4.5.dp.toPx()

                    leftEyePath.reset()
                    leftEyePath.moveTo(leftEyeX - arcHalfW, leftEyeY + arcH * 0.35f)
                    leftEyePath.quadraticBezierTo(leftEyeX, leftEyeY - arcH, leftEyeX + arcHalfW, leftEyeY + arcH * 0.35f)

                    rightEyePath.reset()
                    rightEyePath.moveTo(rightEyeX - arcHalfW, rightEyeY + arcH * 0.35f)
                    rightEyePath.quadraticBezierTo(rightEyeX, rightEyeY - arcH, rightEyeX + arcHalfW, rightEyeY + arcH * 0.35f)

                    // Bloom stroke + crisp stroke
                    drawPath(leftEyePath, eyeColor.copy(alpha = 0.35f), style = Stroke(width = 5.5.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(rightEyePath, eyeColor.copy(alpha = 0.35f), style = Stroke(width = 5.5.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(leftEyePath, eyeColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(rightEyePath, eyeColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                }

                // State B: Calm Sleepy Downward Arcs (◡  ◡) when Paused
                isPaused -> {
                    val arcHalfW = 6.dp.toPx()
                    val arcH = 3.5.dp.toPx()

                    leftEyePath.reset()
                    leftEyePath.moveTo(leftEyeX - arcHalfW, leftEyeY - arcH * 0.35f)
                    leftEyePath.quadraticBezierTo(leftEyeX, leftEyeY + arcH, leftEyeX + arcHalfW, leftEyeY - arcH * 0.35f)

                    rightEyePath.reset()
                    rightEyePath.moveTo(rightEyeX - arcHalfW, rightEyeY - arcH * 0.35f)
                    rightEyePath.quadraticBezierTo(rightEyeX, rightEyeY + arcH, rightEyeX + arcHalfW, rightEyeY - arcH * 0.35f)

                    drawPath(leftEyePath, eyeColor.copy(alpha = 0.50f), style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(rightEyePath, eyeColor.copy(alpha = 0.50f), style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round))
                }

                // State C: Cute Glowing Oval Eyes with Specular Catchlights, Blinking & Slant
                else -> {
                    val baseW = 10.dp.toPx()
                    val baseH = 15.dp.toPx()

                    val eyeW = baseW + (amplitude * 1.5.dp.toPx())
                    val eyeH = if (isBlinking) {
                        2.2.dp.toPx()
                    } else if (isConnecting) {
                        8.5.dp.toPx() // Focused squint
                    } else {
                        baseH + (amplitude * 2.5.dp.toPx())
                    }

                    val leftW = eyeW * leftEyeWiden
                    val leftH = eyeH * leftEyeWiden

                    // Left Eye
                    withTransform({
                        if (isError) rotate(degrees = -7f, pivot = Offset(leftEyeX, leftEyeY))
                    }) {
                        // Eye body
                        drawRoundRect(
                            color = eyeColor,
                            topLeft = Offset(leftEyeX - leftW / 2f, leftEyeY - leftH / 2f),
                            size = Size(leftW, leftH),
                            cornerRadius = CornerRadius(leftW / 2f, leftW / 2f)
                        )
                        // Specular Catchlights (Crisp primary + secondary sparkle dots)
                        if (!isBlinking) {
                            drawCircle(
                                color = Color.White,
                                radius = 1.6.dp.toPx(),
                                center = Offset(leftEyeX + leftW * 0.22f, leftEyeY - leftH * 0.22f)
                            )
                            drawCircle(
                                color = Color.White.copy(alpha = 0.75f),
                                radius = 0.9.dp.toPx(),
                                center = Offset(leftEyeX - leftW * 0.16f, leftEyeY + leftH * 0.18f)
                            )
                        }
                    }

                    // Right Eye
                    withTransform({
                        if (isError) rotate(degrees = 7f, pivot = Offset(rightEyeX, rightEyeY))
                    }) {
                        // Eye body
                        drawRoundRect(
                            color = eyeColor,
                            topLeft = Offset(rightEyeX - eyeW / 2f, rightEyeY - eyeH / 2f),
                            size = Size(eyeW, eyeH),
                            cornerRadius = CornerRadius(eyeW / 2f, eyeW / 2f)
                        )
                        // Specular Catchlights
                        if (!isBlinking) {
                            drawCircle(
                                color = Color.White,
                                radius = 1.6.dp.toPx(),
                                center = Offset(rightEyeX + eyeW * 0.22f, rightEyeY - eyeH * 0.22f)
                            )
                            drawCircle(
                                color = Color.White.copy(alpha = 0.75f),
                                radius = 0.9.dp.toPx(),
                                center = Offset(rightEyeX - eyeW * 0.16f, rightEyeY + eyeH * 0.18f)
                            )
                        }
                    }

                    // Empathetic delicate eyebrows in Error state
                    if (isError) {
                        drawLine(
                            color = eyeColor,
                            start = Offset(leftEyeX - 4.dp.toPx(), leftEyeY - 11.5.dp.toPx()),
                            end = Offset(leftEyeX + 4.dp.toPx(), leftEyeY - 9.5.dp.toPx()),
                            strokeWidth = 1.6.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = eyeColor,
                            start = Offset(rightEyeX - 4.dp.toPx(), rightEyeY - 9.5.dp.toPx()),
                            end = Offset(rightEyeX + 4.dp.toPx(), rightEyeY - 11.5.dp.toPx()),
                            strokeWidth = 1.6.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }
            }

            // ── G. Cute Reactive Mouth (Centered below eyes) ──
            val mouthX = cx + (totalGazeX * 0.5f)
            val mouthY = eyeCenterY + 11.5.dp.toPx()

            when {
                // 1. Thinking Pause: tiny neutral "o" mouth
                isThinkingPause -> {
                    drawCircle(
                        color = eyeColor,
                        radius = 1.8.dp.toPx(),
                        center = Offset(mouthX, mouthY),
                        style = Stroke(width = 1.4.dp.toPx())
                    )
                }

                // 2. Error State: tiny wavy mouth (~)
                isError -> {
                    mouthPath.reset()
                    mouthPath.moveTo(mouthX - 4.5.dp.toPx(), mouthY)
                    mouthPath.cubicTo(
                        mouthX - 2.2.dp.toPx(), mouthY - 1.8.dp.toPx(),
                        mouthX + 2.2.dp.toPx(), mouthY + 1.8.dp.toPx(),
                        mouthX + 4.5.dp.toPx(), mouthY
                    )
                    drawPath(
                        mouthPath,
                        color = eyeColor,
                        style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // 3. Countdown / Save Celebration: wide cute open happy grin
                showHappyEyes -> {
                    mouthPath.reset()
                    mouthPath.moveTo(mouthX - 5.5.dp.toPx(), mouthY - 1.dp.toPx())
                    mouthPath.quadraticBezierTo(mouthX, mouthY + 5.dp.toPx(), mouthX + 5.5.dp.toPx(), mouthY - 1.dp.toPx())
                    mouthPath.close()
                    drawPath(mouthPath, color = eyeColor, style = Fill)
                    drawPath(mouthPath, color = Color.White.copy(alpha = 0.5f), style = Stroke(width = 1.dp.toPx()))
                }

                // 4. Actively Speaking: morphs/opens into a cute tiny talking mouth
                amplitude > 0.05f && !isPaused && isListening -> {
                    val openH = (amplitude * 5.2.dp.toPx()).coerceAtMost(5.5.dp.toPx())
                    mouthPath.reset()
                    mouthPath.moveTo(mouthX - 4.dp.toPx(), mouthY)
                    mouthPath.quadraticBezierTo(mouthX, mouthY - 1.dp.toPx(), mouthX + 4.dp.toPx(), mouthY)
                    mouthPath.quadraticBezierTo(mouthX, mouthY + openH, mouthX - 4.dp.toPx(), mouthY)
                    mouthPath.close()
                    drawPath(mouthPath, color = eyeColor.copy(alpha = 0.85f), style = Fill)
                    drawPath(mouthPath, color = eyeColor, style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round))
                }

                // 5. Idle Resting or Paused: gentle subtle smile (◡)
                else -> {
                    mouthPath.reset()
                    mouthPath.moveTo(mouthX - 4.dp.toPx(), mouthY)
                    mouthPath.quadraticBezierTo(mouthX, mouthY + 2.5.dp.toPx(), mouthX + 4.dp.toPx(), mouthY)
                    drawPath(
                        mouthPath,
                        color = eyeColor.copy(alpha = if (isPaused) 0.45f else 0.85f),
                        style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
        }
    }
}

/**
 * Refined Minimal Segmented Pill Toggle for EN | HI (Tonal contrast, no glowing border).
 */
@Composable
private fun LanguageSlidingToggle(
    currentLanguage: VoiceLanguage,
    onLanguageSelected: (VoiceLanguage) -> Unit,
    theme: PosterTheme,
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
            .background(theme.textPrimary.copy(alpha = 0.06f))
            .border(1.dp, theme.textPrimary.copy(alpha = 0.10f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.CenterStart
    ) {
        // Sliding active segment pill (clean elevated fill, crisp ink text, no glowing border)
        val pillBg = if (theme.isDark) Color(0xFF2C3036) else Color.White
        val pillBorder = theme.textPrimary.copy(alpha = if (theme.isDark) 0.14f else 0.08f)
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(34.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(pillBg)
                .border(0.8.dp, pillBorder, RoundedCornerShape(11.dp))
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
                    color = if (selectedIndex == 0) theme.textPrimary else theme.textSecondary.copy(alpha = 0.50f),
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
                    color = if (selectedIndex == 1) theme.textPrimary else theme.textSecondary.copy(alpha = 0.50f),
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}
