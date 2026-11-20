package com.typoposter.app.voice

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceToNoteEngineTest {

    @Test
    fun testVoiceLanguagesSupported() {
        assertEquals("en-US", VoiceLanguage.ENGLISH.tag)
        assertEquals("EN", VoiceLanguage.ENGLISH.shortLabel)
        assertTrue(VoiceLanguage.ENGLISH.isOfflinePreferred)

        assertEquals("hi-IN", VoiceLanguage.HINDI.tag)
        assertEquals("HI", VoiceLanguage.HINDI.shortLabel)
        assertFalse(VoiceLanguage.HINDI.isOfflinePreferred)
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
}
