package com.typoposter.app.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.typoposter.app.TypoPosterApp
import com.typoposter.app.data.Note
import com.typoposter.app.data.NoteStore
import com.typoposter.app.theme.ThemeEngine
import com.typoposter.app.widget.NoteWidget
import kotlinx.coroutines.launch

class WidgetConfigureActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Set default result to CANCELED so if the user backs out, the widget isn't placed
        setResult(Activity.RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val noteStore = (application as TypoPosterApp).noteStore

        setContent {
            val scope = rememberCoroutineScope()
            val notes by noteStore.notes.collectAsState(initial = emptyList())

            WidgetConfigureScreen(
                notes = notes,
                onNoteSelected = { note ->
                    scope.launch {
                        val glanceId = GlanceAppWidgetManager(this@WidgetConfigureActivity)
                            .getGlanceIdBy(appWidgetId)
                        NoteWidget.bindWidgetToNote(this@WidgetConfigureActivity, glanceId, note)

                        val resultValue = Intent().apply {
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        }
                        setResult(Activity.RESULT_OK, resultValue)
                        finish()
                    }
                },
                onCreateNewNote = {
                    scope.launch {
                        val newNote = noteStore.createNote()
                        val glanceId = GlanceAppWidgetManager(this@WidgetConfigureActivity)
                            .getGlanceIdBy(appWidgetId)
                        NoteWidget.bindWidgetToNote(this@WidgetConfigureActivity, glanceId, newNote)

                        val resultValue = Intent().apply {
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        }
                        setResult(Activity.RESULT_OK, resultValue)
                        finish()
                    }
                }
            )
        }
    }
}

@Composable
fun WidgetConfigureScreen(
    notes: List<Note>,
    onNoteSelected: (Note) -> Unit,
    onCreateNewNote: () -> Unit,
) {
    val executive = ThemeEngine.Executive

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(executive.background)
            .systemBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "ASSIGN TO WIDGET",
                color = executive.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Select a note to display",
                color = executive.textPrimary,
                fontSize = 24.sp,
                fontFamily = executive.headerFont,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(24.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(notes) { note ->
                    val noteTheme = ThemeEngine.getTheme(note.themeIndex)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(noteTheme.background)
                            .clickable { onNoteSelected(note) }
                            .padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(32.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(noteTheme.accent)
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = note.title,
                                color = noteTheme.textPrimary,
                                fontSize = 16.sp,
                                fontFamily = noteTheme.headerFont,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = note.content.ifBlank { "(empty note)" },
                                color = noteTheme.textSecondary,
                                fontSize = 13.sp,
                                fontFamily = noteTheme.bodyFont,
                                maxLines = 1,
                            )
                        }
                    }
                }

                item {
                    // Create new note card
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(executive.surfaceVariant)
                            .clickable(onClick = onCreateNewNote)
                            .padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "+ Create New Note",
                            color = executive.accent,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 1.sp,
                        )
                    }
                }
            }
        }
    }
}
