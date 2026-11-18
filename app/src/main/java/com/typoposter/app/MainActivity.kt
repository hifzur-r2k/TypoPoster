package com.typoposter.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.typoposter.app.ui.NoteScreen
import com.typoposter.app.ui.NoteViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: NoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleWidgetIntent(intent)

        val launchWithKeyboard = intent?.action == Intent.ACTION_VIEW || isTaskRoot

        setContent {
            val uiState by viewModel.uiState.collectAsState()

            NoteScreen(
                uiState = uiState,
                onTextChanged = viewModel::updateNoteText,
                onNoteSelected = viewModel::selectNoteById,
                onCreateNewNote = viewModel::createNewNote,
                onDeleteNote = viewModel::deleteActiveNote,
                onRenameNote = viewModel::renameActiveNote,
                onPinWidgetClicked = viewModel::pinActiveNoteToWidget,
                onThemeButtonClicked = viewModel::showThemeSheet,
                onThemeSelected = viewModel::selectTheme,
                onThemeSheetDismissed = viewModel::hideThemeSheet,
                onRenameDialogDismissed = viewModel::hideRenameDialog,
                onShowRenameDialog = viewModel::showRenameDialog,
                launchWithKeyboard = launchWithKeyboard,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetIntent(intent)
    }

    private fun handleWidgetIntent(intent: Intent?) {
        val targetNoteId = intent?.getStringExtra("EXTRA_NOTE_ID")
        if (!targetNoteId.isNullOrBlank()) {
            viewModel.selectNoteById(targetNoteId)
        }
    }
}
