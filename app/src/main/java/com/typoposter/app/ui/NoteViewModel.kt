package com.typoposter.app.ui

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.typoposter.app.TypoPosterApp
import com.typoposter.app.data.Note
import com.typoposter.app.data.NoteStore
import com.typoposter.app.theme.PosterTheme
import com.typoposter.app.theme.ThemeEngine
import com.typoposter.app.widget.NoteWidget
import com.typoposter.app.widget.NoteWidgetReceiver
import com.typoposter.app.widget.PinWidgetReceiver
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class NoteUiState(
    val notes: List<Note> = emptyList(),
    val activeNote: Note = Note(),
    val theme: PosterTheme = ThemeEngine.Executive,
    val isThemeSheetVisible: Boolean = false,
    val isRenameDialogVisible: Boolean = false,
)

class NoteViewModel(application: Application) : AndroidViewModel(application) {

    private val store: NoteStore = (application as TypoPosterApp).noteStore

    private val _uiState = MutableStateFlow(NoteUiState())
    val uiState: StateFlow<NoteUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(store.notes, store.activeNoteId) { notesList, activeId ->
                val active = notesList.firstOrNull { it.id == activeId }
                    ?: notesList.firstOrNull()
                    ?: Note()
                NoteUiState(
                    notes = notesList,
                    activeNote = active,
                    theme = ThemeEngine.getTheme(active.themeIndex),
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun selectNoteById(noteId: String) {
        viewModelScope.launch {
            store.setActiveNoteId(noteId)
        }
    }

    fun updateNoteText(text: String) {
        val current = _uiState.value.activeNote.copy(
            content = text,
            updatedAt = System.currentTimeMillis()
        )
        _uiState.update { it.copy(activeNote = current) }

        viewModelScope.launch {
            store.saveNote(current)
            NoteWidget.updateWidgetForNote(getApplication(), current)
        }
    }

    fun selectTheme(index: Int) {
        val current = _uiState.value.activeNote.copy(
            themeIndex = index,
            updatedAt = System.currentTimeMillis()
        )
        _uiState.update {
            it.copy(
                activeNote = current,
                theme = ThemeEngine.getTheme(index),
                isThemeSheetVisible = false
            )
        }

        viewModelScope.launch {
            store.saveNote(current)
            NoteWidget.updateWidgetForNote(getApplication(), current)
        }
    }

    fun createNewNote() {
        viewModelScope.launch {
            val newNote = store.createNote()
            NoteWidget.updateWidgetForNote(getApplication(), newNote)
        }
    }

    fun deleteActiveNote() {
        val noteToDelete = _uiState.value.activeNote.id
        viewModelScope.launch {
            store.deleteNote(noteToDelete)
        }
    }

    fun renameActiveNote(newTitle: String) {
        if (newTitle.isBlank()) return
        val current = _uiState.value.activeNote.copy(title = newTitle.trim())
        _uiState.update {
            it.copy(
                activeNote = current,
                isRenameDialogVisible = false
            )
        }
        viewModelScope.launch {
            store.saveNote(current)
            NoteWidget.updateWidgetForNote(getApplication(), current)
        }
    }

    /**
     * Directly prompts the user to place a home screen widget bound to the current note.
     */
    fun pinActiveNoteToWidget() {
        val context = getApplication<Application>()
        val note = _uiState.value.activeNote
        val appWidgetManager = AppWidgetManager.getInstance(context)

        if (appWidgetManager.isRequestPinAppWidgetSupported) {
            val provider = ComponentName(context, NoteWidgetReceiver::class.java)
            val callbackIntent = Intent(context, PinWidgetReceiver::class.java).apply {
                putExtra("EXTRA_NOTE_ID", note.id)
            }
            val successCallback = PendingIntent.getBroadcast(
                context,
                note.id.hashCode(),
                callbackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            appWidgetManager.requestPinAppWidget(provider, null, successCallback)
        } else {
            Toast.makeText(
                context,
                "Your launcher does not support direct pinning. Long-press home screen to add.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun showThemeSheet() {
        _uiState.update { it.copy(isThemeSheetVisible = true) }
    }

    fun hideThemeSheet() {
        _uiState.update { it.copy(isThemeSheetVisible = false) }
    }

    fun showRenameDialog() {
        _uiState.update { it.copy(isRenameDialogVisible = true) }
    }

    fun hideRenameDialog() {
        _uiState.update { it.copy(isRenameDialogVisible = false) }
    }
}
