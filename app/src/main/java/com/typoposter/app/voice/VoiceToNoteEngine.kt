package com.typoposter.app.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Supported speech recognition languages.
 * - EN: "en-IN" (English India) with full proper-noun cloud vocabulary when online,
 *   and offline pack when offline (with automatic en-US fallback).
 * - HI: "hi-IN" (Hindi India) running online for high-accuracy Hinglish & Hindi.
 */
enum class VoiceLanguage(
    val tag: String,
    val displayName: String,
    val shortLabel: String,
    val isOfflinePreferred: Boolean = false
) {
    ENGLISH("en-IN", "English (India)", "EN", isOfflinePreferred = false),
    HINDI("hi-IN", "हिंदी (India)", "HI", isOfflinePreferred = false)
}

/**
 * Live state of the Voice-to-Note engine.
 */
sealed class SpeechState {
    object Idle : SpeechState()
    data class Listening(val language: VoiceLanguage) : SpeechState()
    data class InterimResult(val partialText: String, val language: VoiceLanguage) : SpeechState()
    data class FinalResult(val convertedText: String, val rawText: String) : SpeechState()
    data class Error(
        val message: String,
        val errorCode: Int? = null,
        val isModelMissing: Boolean = false,
        val isServiceUnavailable: Boolean = false
    ) : SpeechState()
    data class DownloadingModel(val progress: Int) : SpeechState()
}

/**
 * Recognizer tier used for speech-to-note transcription.
 */
enum class RecognizerTier {
    TIER_1_ON_DEVICE,
    TIER_2_STANDARD
}

data class RecognizerResult(
    val recognizer: SpeechRecognizer?,
    val tier: RecognizerTier?
)

/**
 * Zero-bloat Voice-to-Note engine binding explicitly to installed RecognitionService:
 * - Queries packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
 * - For HI: prefers com.google.android.googlequicksearchbox (cloud Hindi), then com.google.android.tts
 * - For EN: prefers com.google.android.tts (offline US English)
 * - Passes ComponentName directly to SpeechRecognizer.createSpeechRecognizer(context, componentName)
 * - Extended silence delay times (5000ms min, 4000ms silence) so speech doesn't cut off prematurely
 * - Fallback to last partial text when user manually taps "Done ✓"
 */
object VoiceToNoteEngine {

    private const val TAG = "VoiceToNoteEngine"
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private var activeRecognizer: SpeechRecognizer? = null
    private var stateListener: ((SpeechState) -> Unit)? = null
    private var lastPartialText: String = ""

    private val _rmsFlow = MutableStateFlow(0f)
    val rmsFlow: StateFlow<Float> = _rmsFlow.asStateFlow()

    fun logDiagnostic(@Suppress("UNUSED_PARAMETER") context: Context, msg: String) {
        Log.i(TAG, msg)
    }

    /**
     * Checks if any Google speech or recognition service is installed on this device.
     */
    fun hasGoogleSpeechEngine(context: Context): Boolean {
        val pm = context.packageManager
        val known = listOf(
            "com.google.android.tts",
            "com.google.android.googlequicksearchbox",
            "com.google.android.as",
            "com.google.android.inputmethod.latin"
        )
        for (pkg in known) {
            try {
                pm.getPackageInfo(pkg, 0)
                return true
            } catch (_: Throwable) {}
        }
        return false
    }

    fun isGoogleSpeechInstalled(context: Context): Boolean = hasGoogleSpeechEngine(context)

    /**
     * Queries packageManager for RecognitionService.SERVICE_INTERFACE.
     * Selects best service depending on language:
     * - For Hindi: prefers Google App (googlequicksearchbox) for online Hindi, then com.google.android.tts
     * - For English: prefers com.google.android.tts for 100% offline US English
     */
    fun findRecognitionServiceComponent(context: Context, language: VoiceLanguage): ComponentName? {
        val pm = context.packageManager
        try {
            val intent = Intent(RecognitionService.SERVICE_INTERFACE)
            val services = pm.queryIntentServices(intent, 0)
            logDiagnostic(context, "queryIntentServices found ${services.size} service(s) for $language")

            val preferred = services.firstOrNull { it.serviceInfo?.packageName == "com.google.android.googlequicksearchbox" }
                ?: services.firstOrNull { it.serviceInfo?.packageName == "com.google.android.tts" }
                ?: services.firstOrNull { it.serviceInfo?.packageName?.contains("google", ignoreCase = true) == true }
                ?: services.firstOrNull()

            if (preferred?.serviceInfo != null) {
                val service = preferred.serviceInfo
                val comp = ComponentName(service.packageName, service.name)
                logDiagnostic(context, "Selected recognition service ComponentName for $language: $comp")
                return comp
            }
        } catch (t: Throwable) {
            logDiagnostic(context, "queryIntentServices failed: ${t.message}")
        }
        return null
    }

    fun findRecognitionServiceComponent(context: Context): ComponentName? =
        findRecognitionServiceComponent(context, VoiceLanguage.ENGLISH)

    /**
     * Checks if speech recognition can be initialized on this device.
     */
    fun isAvailable(context: Context): Boolean {
        val appContext = context.applicationContext ?: context
        if (findRecognitionServiceComponent(context) != null) return true
        if (hasGoogleSpeechEngine(context)) return true
        if (try {
            SpeechRecognizer.isRecognitionAvailable(context) ||
                    SpeechRecognizer.isRecognitionAvailable(appContext)
        } catch (_: Throwable) {
            false
        }) return true

        return try {
            val pm = context.packageManager
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            pm.queryIntentActivities(intent, 0).isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Creates a SpeechRecognizer explicitly bound to the preferred RecognitionService ComponentName,
     * or falls back to default createSpeechRecognizer.
     * When preferDefault is true (e.g. after Error 5), uses the device's system-default engine directly.
     */
    fun createRecognizer(
        context: Context,
        language: VoiceLanguage = VoiceLanguage.ENGLISH,
        preferDefault: Boolean = false
    ): SpeechRecognizer? {
        val appContext = context.applicationContext ?: context

        // 1. If preferDefault is requested (e.g. on auto-retry after Error 5), bind directly to system default
        if (preferDefault) {
            try {
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                    ?: SpeechRecognizer.createSpeechRecognizer(appContext)
                if (recognizer != null) {
                    logDiagnostic(context, "Created default system SpeechRecognizer (preferDefault=true)")
                    return recognizer
                }
            } catch (t: Throwable) {
                logDiagnostic(context, "preferDefault createSpeechRecognizer failed: ${t.message}")
            }
        }

        // 2. Try explicit ComponentName
        val component = findRecognitionServiceComponent(context, language)
        if (component != null && !preferDefault) {
            try {
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context, component)
                    ?: SpeechRecognizer.createSpeechRecognizer(appContext, component)
                if (recognizer != null) {
                    logDiagnostic(context, "SpeechRecognizer explicitly bound to $component for $language")
                    return recognizer
                }
            } catch (t: Throwable) {
                logDiagnostic(context, "createSpeechRecognizer with $component failed: ${t.message}")
            }
        }

        // 3. Fallback to default SpeechRecognizer
        try {
            val defaultRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                ?: SpeechRecognizer.createSpeechRecognizer(appContext)
            if (defaultRecognizer != null) {
                logDiagnostic(context, "Created default SpeechRecognizer fallback for $language")
                return defaultRecognizer
            }
        } catch (t: Throwable) {
            logDiagnostic(context, "Default createSpeechRecognizer failed: ${t.message}")
        }
        return null
    }

    /**
     * Backward-compatible helper returning RecognizerResult.
     */
    fun createSafeRecognizer(context: Context): RecognizerResult {
        val recognizer = createRecognizer(context, VoiceLanguage.ENGLISH)
        val tier = if (recognizer != null) RecognizerTier.TIER_1_ON_DEVICE else null
        return RecognizerResult(recognizer, tier)
    }

    /**
     * Checks if internet connectivity is active on this device.
     */
    fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Starts listening for spoken input using hybrid language settings:
     * - EN: "en-IN" with cloud recognition when online (for Indian proper nouns & places),
     *   and automatic offline pack fallback when offline.
     * - HI: "hi-IN" with online recognition.
     * - Silent continuous dictation restart on timeout (6) and no-match (7).
     */
    fun startListening(
        context: Context,
        language: VoiceLanguage,
        onTier3Fallback: (() -> Unit)? = null,
        onStateChange: (SpeechState) -> Unit
    ) {
        startListeningInternal(context, language, onTier3Fallback, isRetry = false, onStateChange = onStateChange)
    }

    private fun startListeningInternal(
        context: Context,
        language: VoiceLanguage,
        onTier3Fallback: (() -> Unit)?,
        isRetry: Boolean,
        onStateChange: (SpeechState) -> Unit,
        useOfflineFallbackUs: Boolean = false
    ) {
        stateListener = onStateChange

        val task = Runnable {
            try {
                safeCancel()
                lastPartialText = ""

                val recognizer = createRecognizer(context, language, preferDefault = isRetry)
                if (recognizer == null) {
                    logDiagnostic(context, "Recognizer is null, invoking fallback")
                    if (onTier3Fallback != null) {
                        onTier3Fallback()
                    } else {
                        onStateChange(
                            SpeechState.Error(
                                message = "Speech service unavailable (Error 5)",
                                errorCode = SpeechRecognizer.ERROR_CLIENT,
                                isServiceUnavailable = true
                            )
                        )
                    }
                    return@Runnable
                }
                activeRecognizer = recognizer

                val isOnline = isNetworkAvailable(context)

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        logDiagnostic(context, "onReadyForSpeech ($language, isRetry=$isRetry, online=$isOnline, usFallback=$useOfflineFallbackUs)")
                        onStateChange(SpeechState.Listening(language))
                    }

                    override fun onBeginningOfSpeech() {
                        logDiagnostic(context, "onBeginningOfSpeech ($language)")
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        // Normalize typical Android rmsdB range (-2 dB to 10 dB) to 0f..1f for fluid Canvas animations
                        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                        _rmsFlow.value = normalized
                    }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        logDiagnostic(context, "onEndOfSpeech ($language)")
                    }

                    override fun onError(error: Int) {
                        logDiagnostic(context, "onError code: $error ($language, isRetry=$isRetry, online=$isOnline)")

                        // 1. Uninterrupted continuous dictation: if no-match (7) or speech timeout (6) occurs
                        // during active speaking/listening, silently commit any captured partial text and release recognizer.
                        // VoiceOverlayActivity owns the continuous restart loop (with single 200ms debounce).
                        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            val raw = lastPartialText
                            lastPartialText = ""
                            if (raw.isNotBlank()) {
                                val converted = SpokenDateParser.convert(raw)
                                val mathParsed = SpokenMathParser.parse(converted)
                                onStateChange(SpeechState.FinalResult(convertedText = mathParsed, rawText = raw))
                            } else {
                                // Emit empty FinalResult so VoiceOverlayActivity seamlessly re-arms mic
                                onStateChange(SpeechState.FinalResult("", ""))
                            }
                            logDiagnostic(context, "Continuous dictation: code $error handled, recognizer released")
                            safeCancel()
                            return
                        }

                        // 2. Offline en-IN fallback to en-US if en-IN offline pack is not installed/fails
                        if (language == VoiceLanguage.ENGLISH && !isOnline && !useOfflineFallbackUs &&
                            (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                             error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                             error == SpeechRecognizer.ERROR_CLIENT)) {
                            logDiagnostic(context, "en-IN offline error ($error); falling back to en-US offline...")
                            safeCancel()
                            mainHandler.postDelayed({
                                startListeningInternal(
                                    context = context,
                                    language = language,
                                    onTier3Fallback = onTier3Fallback,
                                    isRetry = false,
                                    onStateChange = onStateChange,
                                    useOfflineFallbackUs = true
                                )
                            }, 200L)
                            return
                        }

                        // 3. 1-time silent auto-retry (after 250ms recreate with preferDefault=true) specifically for
                        // ERROR_CLIENT (5), ERROR_SERVER_DISCONNECTED (11), and ERROR_RECOGNIZER_BUSY (8)
                        if (!isRetry && (error == SpeechRecognizer.ERROR_CLIENT ||
                                error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
                                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)) {
                            logDiagnostic(context, "Silent auto-retry triggered for error $error in 250ms...")
                            safeCancel()
                            mainHandler.postDelayed({
                                startListeningInternal(
                                    context = context,
                                    language = language,
                                    onTier3Fallback = onTier3Fallback,
                                    isRetry = true,
                                    onStateChange = onStateChange,
                                    useOfflineFallbackUs = useOfflineFallbackUs
                                )
                            }, 250L)
                            return
                        }

                        // 4. If ERROR_CLIENT (5) persists after retry, automatically invoke Tier 3 fallback (system speech dialog)
                        if (error == SpeechRecognizer.ERROR_CLIENT && onTier3Fallback != null) {
                            logDiagnostic(context, "ERROR_CLIENT persisted after retry; invoking onTier3Fallback")
                            safeCancel()
                            onTier3Fallback()
                            return
                        }

                        val message = mapErrorCode(error, language)
                        val isMissing = (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                                error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
                        onStateChange(
                            SpeechState.Error(
                                message = message,
                                errorCode = error,
                                isModelMissing = isMissing,
                                isServiceUnavailable = (error == SpeechRecognizer.ERROR_CLIENT ||
                                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
                            )
                        )
                        mainHandler.post {
                            safeCancel()
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val raw = matches?.firstOrNull()?.takeIf { it.isNotBlank() } ?: lastPartialText
                        logDiagnostic(context, "onResults raw text: '$raw' ($language)")
                        lastPartialText = ""
                        if (raw.isNotBlank()) {
                            val converted = SpokenDateParser.convert(raw)
                            val mathParsed = SpokenMathParser.parse(converted)
                            onStateChange(SpeechState.FinalResult(convertedText = mathParsed, rawText = raw))
                        } else {
                            // Emit empty FinalResult instead of Idle so VoiceOverlayActivity re-arms mic
                            onStateChange(SpeechState.FinalResult("", ""))
                        }
                        mainHandler.post {
                            safeCancel()
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partial = matches?.firstOrNull() ?: ""
                        if (partial.isNotBlank()) {
                            lastPartialText = partial
                            onStateChange(SpeechState.InterimResult(partial, language))
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val langTag = if (language == VoiceLanguage.ENGLISH) {
                    if (useOfflineFallbackUs) "en-US" else "en-IN"
                } else {
                    language.tag
                }

                val preferOffline = if (language == VoiceLanguage.ENGLISH) {
                    if (isOnline) {
                        // When online, disable EXTRA_PREFER_OFFLINE so en-IN uses full cloud proper-noun vocabulary
                        false
                    } else {
                        // When offline, automatically enable EXTRA_PREFER_OFFLINE = true
                        !isRetry
                    }
                } else {
                    false
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langTag)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }

                logDiagnostic(context, "Calling recognizer.startListening with lang=$langTag, preferOffline=$preferOffline, online=$isOnline (isRetry=$isRetry)")
                recognizer.startListening(intent)
            } catch (t: Throwable) {
                logDiagnostic(context, "Exception during startListening: ${t.message}\n${Log.getStackTraceString(t)}")
                safeCancel()
                if (!isRetry) {
                    logDiagnostic(context, "Retrying with preferDefault=true after startListening exception")
                    mainHandler.postDelayed({
                        startListeningInternal(
                            context = context,
                            language = language,
                            onTier3Fallback = onTier3Fallback,
                            isRetry = true,
                            onStateChange = onStateChange,
                            useOfflineFallbackUs = useOfflineFallbackUs
                        )
                    }, 200L)
                } else if (onTier3Fallback != null) {
                    logDiagnostic(context, "Invoking onTier3Fallback after startListening exception on retry")
                    onTier3Fallback()
                } else {
                    onStateChange(
                        SpeechState.Error(
                            message = "Voice error: ${t.localizedMessage ?: t.javaClass.simpleName} (Error 5)",
                            errorCode = SpeechRecognizer.ERROR_CLIENT,
                            isServiceUnavailable = true
                        )
                    )
                }
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run()
        } else {
            mainHandler.post(task)
        }
    }

    /**
     * Backward-compatible overload without onTier3Fallback parameter.
     */
    fun startListening(
        context: Context,
        language: VoiceLanguage,
        onStateChange: (SpeechState) -> Unit
    ) {
        startListening(context, language, null, onStateChange)
    }

    /**
     * Stops listening and lets recognizer finalize current spoken sentence.
     * If recognizer results do not arrive within 700ms, commits the last recorded partial text.
     */
    fun stopListening() {
        try {
            activeRecognizer?.stopListening()
        } catch (t: Throwable) {
            Log.w(TAG, "Error during stopListening: ${t.message}")
        }
        mainHandler.postDelayed({
            if (activeRecognizer != null && lastPartialText.isNotBlank()) {
                val raw = lastPartialText
                lastPartialText = ""
                val converted = SpokenDateParser.convert(raw)
                val mathParsed = SpokenMathParser.parse(converted)
                stateListener?.invoke(SpeechState.FinalResult(convertedText = mathParsed, rawText = raw))
                safeCancel()
            }
        }, 700L)
    }

    /**
     * Safely cancels and destroys the active recognizer, clearing any pending delayed tasks.
     */
    fun safeCancel() {
        try {
            mainHandler.removeCallbacksAndMessages(null)
        } catch (_: Throwable) {}
        lastPartialText = ""
        _rmsFlow.value = 0f
        val recognizer = activeRecognizer
        activeRecognizer = null
        if (recognizer != null) {
            try {
                recognizer.cancel()
                recognizer.destroy()
            } catch (t: Throwable) {
                Log.w(TAG, "Error destroying recognizer: ${t.message}")
            }
        }
    }

    /**
     * Cancels active recognition and resets to Idle without inserting.
     */
    fun cancelListening() {
        try {
            mainHandler.removeCallbacksAndMessages(null)
        } catch (_: Throwable) {}
        safeCancel()
        stateListener?.invoke(SpeechState.Idle)
    }

    /**
     * Checks if the offline model for the given language is installed on Android 13+ (API 33+).
     */
    fun checkOfflineModelSupport(
        context: Context,
        language: VoiceLanguage,
        onResult: (installed: Boolean, supported: Boolean) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Handler(Looper.getMainLooper()).post {
                try {
                    val recognizer = createRecognizer(context, language)
                    if (recognizer == null) {
                        onResult(true, true)
                        return@post
                    }
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag)
                    }
                    val executor = ContextCompat.getMainExecutor(context)
                    recognizer.checkRecognitionSupport(intent, executor, object : RecognitionSupportCallback {
                        override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                            val installed = recognitionSupport.installedOnDeviceLanguages.any {
                                it.equals(language.tag, ignoreCase = true) ||
                                        it.startsWith(language.tag.substringBefore("-"), ignoreCase = true)
                            }
                            val supported = recognitionSupport.supportedOnDeviceLanguages.any {
                                it.equals(language.tag, ignoreCase = true) ||
                                        it.startsWith(language.tag.substringBefore("-"), ignoreCase = true)
                            }
                            onResult(installed, supported)
                            try { recognizer.destroy() } catch (_: Throwable) {}
                        }

                        override fun onError(errorCode: Int) {
                            onResult(true, true)
                            try { recognizer.destroy() } catch (_: Throwable) {}
                        }
                    })
                } catch (t: Throwable) {
                    onResult(true, true)
                }
            }
        } else {
            onResult(true, true)
        }
    }

    /**
     * Triggers offline model download for the given language pack on Android 13+ (API 33+).
     */
    fun triggerModelDownload(
        context: Context,
        language: VoiceLanguage,
        onProgress: (Int) -> Unit,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Handler(Looper.getMainLooper()).post {
                try {
                    val recognizer = createRecognizer(context, language)
                    if (recognizer == null) {
                        onError("Speech recognition service unavailable (Error 5)")
                        return@post
                    }
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag)
                    }
                    val executor = ContextCompat.getMainExecutor(context)
                    recognizer.triggerModelDownload(intent, executor, object : ModelDownloadListener {
                        override fun onProgress(completedPercent: Int) {
                            onProgress(completedPercent)
                        }

                        override fun onSuccess() {
                            onSuccess()
                            try { recognizer.destroy() } catch (_: Throwable) {}
                        }

                        override fun onScheduled() {
                            onProgress(10)
                        }

                        override fun onError(error: Int) {
                            onError("Download error code: $error")
                            try { recognizer.destroy() } catch (_: Throwable) {}
                        }
                    })
                } catch (t: Throwable) {
                    onError("Failed to download model: ${t.message} (Error 5)")
                }
            }
        } else {
            onError("Offline model download requires Android 13+")
        }
    }

    /**
     * Maps Android SpeechRecognizer error codes to readable in-app messages with numeric error code.
     */
    fun mapErrorCode(error: Int, language: VoiceLanguage): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout (Error 1)"
        SpeechRecognizer.ERROR_NETWORK -> {
            if (language == VoiceLanguage.HINDI) "No internet for Hindi (Error 2)"
            else "Network connection error (Error 2)"
        }
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error (Error 3)"
        SpeechRecognizer.ERROR_SERVER -> "Recognition server error (Error 4)"
        SpeechRecognizer.ERROR_CLIENT -> "Speech recognition client error (Error 5)"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected (Error 6)"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized (Error 7)"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy (Error 8)"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required (Error 9)"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many requests (Error 10)"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Server disconnected (Error 11)"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Language not supported (Error 12)"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
            if (language == VoiceLanguage.ENGLISH) "Offline English US pack missing (Error 13)"
            else "Offline pack missing (Error 13)"
        }
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "Cannot check language support (Error 14)"
        SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> "Download event error (Error 15)"
        else -> "Speech recognition error (Error $error)"
    }

    fun mapErrorCode(error: Int): String = mapErrorCode(error, VoiceLanguage.ENGLISH)
}
