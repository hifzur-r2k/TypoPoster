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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class NoteUiState(
    val notes: List<Note> = emptyList(),
    val activeNote: Note = Note(),
    val theme: PosterTheme = ThemeEngine.Executive,
    val isThemeSheetVisible: Boolean = false,
    val isRenameDialogVisible: Boolean = false,
    val isScanSheetVisible: Boolean = false,
    val autoLaunchCameraOnScan: Boolean = false,
    val unlockedNoteIds: Set<String> = emptySet(),
) {
    val isCurrentNoteUnlocked: Boolean
        get() = !activeNote.isLocked || unlockedNoteIds.contains(activeNote.id)
}

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
                val currentActive = _uiState.value.activeNote
                val resolvedActive = if (active.id == currentActive.id && currentActive.updatedAt > active.updatedAt) {
                    currentActive
                } else {
                    active
                }
                _uiState.value.copy(
                    notes = notesList,
                    activeNote = resolvedActive,
                    theme = ThemeEngine.getTheme(resolvedActive.themeIndex),
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun selectNoteById(noteId: String) {
        // Automatically re-lock private notes when switching to another note
        _uiState.update { it.copy(unlockedNoteIds = emptySet()) }
        viewModelScope.launch {
            store.setActiveNoteId(noteId)
        }
    }

    fun unlockNote(noteId: String) {
        _uiState.update { it.copy(unlockedNoteIds = it.unlockedNoteIds + noteId) }
    }

    fun relockAllNotes() {
        _uiState.update { it.copy(unlockedNoteIds = emptySet()) }
    }

    fun toggleNoteLock(noteId: String, makeLocked: Boolean) {
        val currentNotes = _uiState.value.notes
        val target = currentNotes.firstOrNull { it.id == noteId } ?: return
        val updated = target.copy(isLocked = makeLocked, updatedAt = System.currentTimeMillis())

        _uiState.update { state ->
            val newUnlocked = if (makeLocked) state.unlockedNoteIds - noteId else state.unlockedNoteIds
            state.copy(
                activeNote = if (state.activeNote.id == noteId) updated else state.activeNote,
                unlockedNoteIds = newUnlocked
            )
        }

        viewModelScope.launch {
            store.saveNote(updated)
            NoteWidget.updateWidgetForNote(getApplication(), updated)
        }
    }

    private var saveTextJob: Job? = null

    fun updateNoteText(text: String) {
        val current = _uiState.value.activeNote.copy(
            content = text,
            updatedAt = System.currentTimeMillis()
        )
        // Instant in-memory state update for 0ms editing latency
        _uiState.update { it.copy(activeNote = current) }

        // Debounce disk persistence and widget broadcast by 250ms
        // so rapid typing or holding down backspace runs at full 120Hz without I/O stutter
        saveTextJob?.cancel()
        saveTextJob = viewModelScope.launch {
            delay(250L)
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
        pinNote(_uiState.value.activeNote)
    }

    fun pinNote(note: Note) {
        val context = getApplication<Application>()
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

    fun applyScannedText(text: String, mode: ScanApplyMode, pinToWidget: Boolean = false) {
        if (text.isBlank()) return
        viewModelScope.launch {
            when (mode) {
                ScanApplyMode.NEW_NOTE -> {
                    val firstLine = text.lineSequence()
                        .firstOrNull { it.isNotBlank() }
                        ?.trim()
                        ?.take(20)
                        ?: "Scanned Note"
                    val newNote = store.createNote(title = firstLine)
                    val updated = newNote.copy(
                        content = text,
                        updatedAt = System.currentTimeMillis()
                    )
                    store.saveNote(updated)
                    _uiState.update { it.copy(activeNote = updated) }
                    NoteWidget.updateWidgetForNote(getApplication(), updated)
                    if (pinToWidget) {
                        pinNote(updated)
                    }
                }
                ScanApplyMode.REPLACE -> {
                    val current = _uiState.value.activeNote.copy(
                        content = text,
                        updatedAt = System.currentTimeMillis()
                    )
                    _uiState.update { it.copy(activeNote = current) }
                    store.saveNote(current)
                    NoteWidget.updateWidgetForNote(getApplication(), current)
                    if (pinToWidget) {
                        pinNote(current)
                    }
                }
                ScanApplyMode.APPEND -> {
                    val curText = _uiState.value.activeNote.content
                    val combined = if (curText.isBlank()) text else "$curText\n\n$text"
                    val current = _uiState.value.activeNote.copy(
                        content = combined,
                        updatedAt = System.currentTimeMillis()
                    )
                    _uiState.update { it.copy(activeNote = current) }
                    store.saveNote(current)
                    NoteWidget.updateWidgetForNote(getApplication(), current)
                    if (pinToWidget) {
                        pinNote(current)
                    }
                }
            }
        }
    }

    fun showScanSheet(autoLaunchCamera: Boolean = false) {
        _uiState.update { it.copy(isScanSheetVisible = true, autoLaunchCameraOnScan = autoLaunchCamera) }
    }

    fun hideScanSheet() {
        _uiState.update { it.copy(isScanSheetVisible = false, autoLaunchCameraOnScan = false) }
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
