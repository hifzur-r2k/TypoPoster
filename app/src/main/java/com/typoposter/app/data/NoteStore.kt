package com.typoposter.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "typo_poster_prefs"
)

/**
 * Manages multiple notes, active selection, and local persistence.
 */
class NoteStore(private val context: Context) {

    companion object Keys {
        val NOTES_JSON = stringPreferencesKey("notes_json")
        val ACTIVE_NOTE_ID = stringPreferencesKey("active_note_id")
        
        // Legacy keys for migration
        val LEGACY_NOTE_TEXT = stringPreferencesKey("note_text")
        val LEGACY_THEME_INDEX = intPreferencesKey("theme_index")
    }

    val notes: Flow<List<Note>> = context.dataStore.data.map { prefs ->
        val jsonStr = prefs[NOTES_JSON]
        if (jsonStr.isNullOrBlank()) {
            // Check legacy data for backward compatibility
            val legacyText = prefs[LEGACY_NOTE_TEXT] ?: ""
            val legacyTheme = prefs[LEGACY_THEME_INDEX] ?: 0
            val initialNote = Note(
                id = "default_1",
                title = "Note 1",
                content = legacyText,
                themeIndex = legacyTheme
            )
            listOf(initialNote)
        } else {
            val list = mutableListOf<Note>()
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                list.add(Note.fromJson(array.getJSONObject(i)))
            }
            if (list.isEmpty()) {
                listOf(Note(id = "default_1", title = "Note 1", content = ""))
            } else {
                list
            }
        }
    }

    val activeNoteId: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[ACTIVE_NOTE_ID] ?: "default_1"
    }

    suspend fun saveNote(updatedNote: Note) {
        val currentNotes = notes.first().toMutableList()
        val index = currentNotes.indexOfFirst { it.id == updatedNote.id }
        if (index >= 0) {
            currentNotes[index] = updatedNote
        } else {
            currentNotes.add(updatedNote)
        }
        persistNotes(currentNotes)
    }

    suspend fun createNote(title: String? = null): Note {
        val currentNotes = notes.first().toMutableList()
        val nextNumber = currentNotes.size + 1
        val newNote = Note(
            title = title ?: "Note $nextNumber",
            content = "",
            themeIndex = currentNotes.lastOrNull()?.themeIndex ?: 0
        )
        currentNotes.add(newNote)
        persistNotes(currentNotes)
        setActiveNoteId(newNote.id)
        return newNote
    }

    suspend fun deleteNote(noteId: String) {
        val currentNotes = notes.first().toMutableList()
        if (currentNotes.size <= 1) return // Keep at least one note
        currentNotes.removeAll { it.id == noteId }
        persistNotes(currentNotes)
        setActiveNoteId(currentNotes.first().id)
    }

    suspend fun setActiveNoteId(id: String) {
        context.dataStore.edit { prefs ->
            prefs[ACTIVE_NOTE_ID] = id
        }
    }

    suspend fun getNoteById(id: String): Note? {
        return notes.first().firstOrNull { it.id == id }
    }

    private suspend fun persistNotes(noteList: List<Note>) {
        val array = JSONArray()
        noteList.forEach { array.put(it.toJson()) }
        context.dataStore.edit { prefs ->
            prefs[NOTES_JSON] = array.toString()
        }
    }
}
