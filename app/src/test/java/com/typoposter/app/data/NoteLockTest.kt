package com.typoposter.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteLockTest {

    @Test
    fun testDefaultNoteIsUnlocked() {
        val note = Note(title = "General Note", content = "Public content")
        assertFalse("New note must default to unlocked", note.isLocked)
    }

    @Test
    fun testLegacyJsonWithoutLockKeyDefaultsToUnlocked() {
        val legacyJson = JSONObject().apply {
            put("id", "note_123")
            put("title", "Legacy Note")
            put("content", "Saved before lock feature was added")
            put("themeIndex", 2)
            put("updatedAt", 1700000000000L)
            // Notice: "isLocked" key is omitted intentionally
        }

        val deserialized = Note.fromJson(legacyJson)
        assertEquals("note_123", deserialized.id)
        assertEquals("Legacy Note", deserialized.title)
        assertEquals("Saved before lock feature was added", deserialized.content)
        assertEquals(2, deserialized.themeIndex)
        assertFalse("Legacy notes must safely default to isLocked = false", deserialized.isLocked)
    }

    @Test
    fun testLockedNoteSerializationAndDeserialization() {
        val lockedNote = Note(
            id = "private_99",
            title = "My Secret Note",
            content = "Sensitive password or journal",
            themeIndex = 1,
            isLocked = true
        )

        val json = lockedNote.toJson()
        assertTrue("JSON must include isLocked = true", json.optBoolean("isLocked"))

        val restored = Note.fromJson(json)
        assertEquals("private_99", restored.id)
        assertEquals("My Secret Note", restored.title)
        assertEquals("Sensitive password or journal", restored.content)
        assertEquals(1, restored.themeIndex)
        assertTrue("Restored note must retain isLocked = true", restored.isLocked)
    }

    @Test
    fun testTogglingLockPreservesAllOtherFields() {
        val note = Note(
            id = "note_42",
            title = "Ideas",
            content = "Cool ideas",
            themeIndex = 3,
            isLocked = false
        )

        val locked = note.copy(isLocked = true)
        assertEquals(note.id, locked.id)
        assertEquals(note.title, locked.title)
        assertEquals(note.content, locked.content)
        assertEquals(note.themeIndex, locked.themeIndex)
        assertTrue(locked.isLocked)

        val unlockedAgain = locked.copy(isLocked = false)
        assertFalse(unlockedAgain.isLocked)
    }
}
