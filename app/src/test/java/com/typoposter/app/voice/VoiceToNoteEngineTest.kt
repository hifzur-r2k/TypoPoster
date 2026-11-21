package com.typoposter.app.voice

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceToNoteEngineTest {

    @Test
    fun testVoiceLanguagesSupported() {
        assertEquals("en-IN", VoiceLanguage.ENGLISH.tag)
        assertEquals("EN", VoiceLanguage.ENGLISH.shortLabel)
        assertEquals("English (India)", VoiceLanguage.ENGLISH.displayName)

        assertEquals("hi-IN", VoiceLanguage.HINDI.tag)
        assertEquals("HI", VoiceLanguage.HINDI.shortLabel)
        assertEquals("हिंदी (India)", VoiceLanguage.HINDI.displayName)
    }

    @Test
    fun testSpeechStateTransitions() {
        val idle: SpeechState = SpeechState.Idle
        assertTrue(idle is SpeechState.Idle)

        val listening: SpeechState = SpeechState.Listening(VoiceLanguage.ENGLISH)
        assertTrue(listening is SpeechState.Listening)
        assertEquals(VoiceLanguage.ENGLISH, (listening as SpeechState.Listening).language)

        val interim: SpeechState = SpeechState.InterimResult("kal meeting", VoiceLanguage.HINDI)
        assertEquals("kal meeting", (interim as SpeechState.InterimResult).partialText)
        assertEquals(VoiceLanguage.HINDI, interim.language)

        val finalRes: SpeechState = SpeechState.FinalResult(
            convertedText = "meeting @tomorrow",
            rawText = "meeting kal"
        )
        assertEquals("meeting @tomorrow", (finalRes as SpeechState.FinalResult).convertedText)
        assertEquals("meeting kal", finalRes.rawText)

        val errorServiceMissing: SpeechState = SpeechState.Error(
            message = "Voice service unavailable",
            isServiceUnavailable = true
        )
        assertTrue((errorServiceMissing as SpeechState.Error).isServiceUnavailable)
        assertFalse(errorServiceMissing.isModelMissing)

        val errorModelMissing: SpeechState = SpeechState.Error(
            message = "Offline pack missing",
            isModelMissing = true
        )
        assertTrue((errorModelMissing as SpeechState.Error).isModelMissing)
        assertFalse(errorModelMissing.isServiceUnavailable)

        val downloading: SpeechState = SpeechState.DownloadingModel(45)
        assertEquals(45, (downloading as SpeechState.DownloadingModel).progress)
    }

    @Test
    fun testRecognizerTiersDefined() {
        assertEquals(2, RecognizerTier.values().size)
        assertEquals("TIER_1_ON_DEVICE", RecognizerTier.TIER_1_ON_DEVICE.name)
        assertEquals("TIER_2_STANDARD", RecognizerTier.TIER_2_STANDARD.name)
    }

    @Test
    fun testMapErrorCodeWithNumericCodes() {
        val hindiNetErr = VoiceToNoteEngine.mapErrorCode(SpeechRecognizer.ERROR_NETWORK, VoiceLanguage.HINDI)
        assertEquals("No internet for Hindi (Error 2)", hindiNetErr)

        val enNetErr = VoiceToNoteEngine.mapErrorCode(SpeechRecognizer.ERROR_NETWORK, VoiceLanguage.ENGLISH)
        assertEquals("Network connection error (Error 2)", enNetErr)

        val micErr = VoiceToNoteEngine.mapErrorCode(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, VoiceLanguage.ENGLISH)
        assertEquals("Microphone permission required (Error 9)", micErr)
    }

    @Test
    fun testOlderDeviceAndClientErrorHandling() {
        val clientErr = VoiceToNoteEngine.mapErrorCode(SpeechRecognizer.ERROR_CLIENT, VoiceLanguage.ENGLISH)
        assertEquals("Speech recognition client error (Error 5)", clientErr)

        val serverDiscErr = VoiceToNoteEngine.mapErrorCode(SpeechRecognizer.ERROR_SERVER_DISCONNECTED, VoiceLanguage.ENGLISH)
        assertEquals("Server disconnected (Error 11)", serverDiscErr)

        val busyErr = VoiceToNoteEngine.mapErrorCode(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, VoiceLanguage.ENGLISH)
        assertEquals("Speech recognizer busy (Error 8)", busyErr)

        val errorState = SpeechState.Error(
            message = clientErr,
            errorCode = SpeechRecognizer.ERROR_CLIENT,
            isServiceUnavailable = true
        )
        assertTrue(errorState.isServiceUnavailable)
        assertEquals(SpeechRecognizer.ERROR_CLIENT, errorState.errorCode)
    }

    @Test
    fun testLanguageOfflineSettingsForDeviceCompatibility() {
        // EN & HI are online by default to leverage full Indian proper noun vocabulary
        assertFalse(VoiceLanguage.ENGLISH.isOfflinePreferred)
        assertFalse(VoiceLanguage.HINDI.isOfflinePreferred)
    }

    @Test
    fun testContinuousDictationErrorCodes() {
        // Code 6 (Speech timeout) and 7 (No match) error messages
        val timeoutMsg = VoiceToNoteEngine.mapErrorCode(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, VoiceLanguage.ENGLISH)
        assertEquals("No speech detected (Error 6)", timeoutMsg)

        val noMatchMsg = VoiceToNoteEngine.mapErrorCode(SpeechRecognizer.ERROR_NO_MATCH, VoiceLanguage.ENGLISH)
        assertEquals("No speech recognized (Error 7)", noMatchMsg)
    }
}
