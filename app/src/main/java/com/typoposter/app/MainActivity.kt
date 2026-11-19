package com.typoposter.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import com.typoposter.app.auth.BiometricHelper
import com.typoposter.app.ui.NoteScreen
import com.typoposter.app.ui.NoteViewModel

class MainActivity : FragmentActivity() {

    private val viewModel: NoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleWidgetIntent(intent)

        val launchWithKeyboard = intent?.action == Intent.ACTION_VIEW || isTaskRoot

        setContent {
            val uiState by viewModel.uiState.collectAsState()

            // Apply FLAG_SECURE only while viewing an unlocked private note (remove for normal notes)
            LaunchedEffect(uiState.activeNote.isLocked, uiState.isCurrentNoteUnlocked) {
                if (uiState.activeNote.isLocked && uiState.isCurrentNoteUnlocked) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            NoteScreen(
                uiState = uiState,
                onTextChanged = viewModel::updateNoteText,
                onNoteSelected = { noteId ->
                    viewModel.selectNoteById(noteId)
                    val target = uiState.notes.firstOrNull { it.id == noteId }
                    if (target?.isLocked == true) {
                        requestUnlockNote(noteId)
                    }
                },
                onCreateNewNote = viewModel::createNewNote,
                onDeleteNote = viewModel::deleteActiveNote,
                onRenameNote = viewModel::renameActiveNote,
                onPinWidgetClicked = viewModel::pinActiveNoteToWidget,
                onScanClicked = { viewModel.showScanSheet(autoLaunchCamera = false) },
                onScanSheetDismissed = viewModel::hideScanSheet,
                onApplyScannedText = viewModel::applyScannedText,
                onThemeButtonClicked = viewModel::showThemeSheet,
                onThemeSelected = viewModel::selectTheme,
                onThemeSheetDismissed = viewModel::hideThemeSheet,
                onRenameDialogDismissed = viewModel::hideRenameDialog,
                onShowRenameDialog = viewModel::showRenameDialog,
                onUnlockRequested = ::requestUnlockNote,
                onToggleLockRequested = ::requestToggleLock,
                launchWithKeyboard = launchWithKeyboard,
            )
        }
    }

    override fun onStop() {
        super.onStop()
        // Automatically re-lock private notes when sending the app to the background
        viewModel.relockAllNotes()
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
        val action = intent?.getStringExtra("EXTRA_ACTION")
        if (action == "ACTION_SCAN") {
            viewModel.showScanSheet(autoLaunchCamera = true)
        } else if (action == "ACTION_UNLOCK" && !targetNoteId.isNullOrBlank()) {
            requestUnlockNote(targetNoteId)
        }
    }

    private fun requestUnlockNote(noteId: String) {
        BiometricHelper.authenticate(
            activity = this,
            title = "Unlock Private Note",
            subtitle = "Confirm fingerprint or device PIN",
            onSuccess = {
                viewModel.unlockNote(noteId)
            },
            onError = { errorMsg ->
                Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun requestToggleLock(noteId: String, makeLocked: Boolean) {
        if (makeLocked) {
            // Locking does not require prior authentication
            viewModel.toggleNoteLock(noteId, true)
            Toast.makeText(this, "Note locked with fingerprint/PIN", Toast.LENGTH_SHORT).show()
        } else {
            // Turning off the lock requires biometric authentication
            BiometricHelper.authenticate(
                activity = this,
                title = "Remove Note Lock",
                subtitle = "Authenticate to make this note public",
                onSuccess = {
                    viewModel.toggleNoteLock(noteId, false)
                    Toast.makeText(this, "Lock removed", Toast.LENGTH_SHORT).show()
                },
                onError = { errorMsg ->
                    Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}
