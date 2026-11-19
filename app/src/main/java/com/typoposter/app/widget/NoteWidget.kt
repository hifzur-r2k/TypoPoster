package com.typoposter.app.widget

import android.content.Context
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import com.typoposter.app.MainActivity
import com.typoposter.app.R
import com.typoposter.app.data.Note
import com.typoposter.app.data.NoteStore
import com.typoposter.app.theme.TextFormatHelper
import com.typoposter.app.theme.ThemeEngine
import kotlinx.coroutines.flow.first

// ─────────────────────────────────────────────────────────────
//  Widget Size Breakpoints
// ─────────────────────────────────────────────────────────────
private val SMALL = DpSize(180.dp, 80.dp)
private val MEDIUM = DpSize(250.dp, 110.dp)
private val LARGE = DpSize(320.dp, 200.dp)
private val EXTRA_LARGE = DpSize(400.dp, 300.dp)

class NoteWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override val sizeMode = SizeMode.Responsive(
        setOf(SMALL, MEDIUM, LARGE, EXTRA_LARGE)
    )

    companion object {
        val KEY_NOTE_ID = stringPreferencesKey("widget_note_id")
        val KEY_NOTE_TEXT = stringPreferencesKey("widget_note_text")
        val KEY_THEME_INDEX = intPreferencesKey("widget_theme_index")
        val KEY_NOTE_TITLE = stringPreferencesKey("widget_note_title")

        val PARAM_NOTE_ID = ActionParameters.Key<String>("EXTRA_NOTE_ID")
        val PARAM_ACTION = ActionParameters.Key<String>("EXTRA_ACTION")

        /**
         * Real-time update: updates all widget instances associated with the given note.
         * Pushes RemoteViews immediately so no tap is needed to refresh.
         */
        suspend fun updateWidgetForNote(context: Context, note: Note) {
            val manager = GlanceAppWidgetManager(context)
            val glanceIds = manager.getGlanceIds(NoteWidget::class.java)

            glanceIds.forEach { glanceId ->
                val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
                val boundNoteId = prefs[KEY_NOTE_ID]

                // Update if specifically bound to this note, or if unbound
                if (boundNoteId == note.id || boundNoteId == null) {
                    updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { p ->
                        p.toMutablePreferences().apply {
                            this[KEY_NOTE_ID] = note.id
                            this[KEY_NOTE_TEXT] = note.content
                            this[KEY_THEME_INDEX] = note.themeIndex
                            this[KEY_NOTE_TITLE] = note.title
                        }
                    }
                    NoteWidget().update(context, glanceId)
                }
            }
        }

        /**
         * Binds a specific widget ID to a specific note.
         */
        suspend fun bindWidgetToNote(context: Context, glanceId: GlanceId, note: Note) {
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { p ->
                p.toMutablePreferences().apply {
                    this[KEY_NOTE_ID] = note.id
                    this[KEY_NOTE_TEXT] = note.content
                    this[KEY_THEME_INDEX] = note.themeIndex
                    this[KEY_NOTE_TITLE] = note.title
                }
            }
            NoteWidget().update(context, glanceId)
        }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initialPrefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        if (initialPrefs[KEY_NOTE_ID] == null) {
            val store = NoteStore(context)
            val activeId = store.activeNoteId.first()
            val note = store.getNoteById(activeId) ?: store.notes.first().firstOrNull()
            if (note != null) {
                updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { p ->
                    p.toMutablePreferences().apply {
                        this[KEY_NOTE_ID] = note.id
                        this[KEY_NOTE_TEXT] = note.content
                        this[KEY_THEME_INDEX] = note.themeIndex
                        this[KEY_NOTE_TITLE] = note.title
                    }
                }
            }
        }

        provideContent {
            val prefs = currentState<Preferences>()
            val noteId = prefs[KEY_NOTE_ID] ?: "default_1"
            val noteText = prefs[KEY_NOTE_TEXT] ?: ""
            val themeIndex = prefs[KEY_THEME_INDEX] ?: 0
            val noteTitle = prefs[KEY_NOTE_TITLE] ?: "Note"

            NoteWidgetContent(
                context = LocalContext.current,
                noteId = noteId,
                noteTitle = noteTitle,
                noteText = noteText,
                themeIndex = themeIndex,
            )
        }
    }
}

@Composable
private fun NoteWidgetContent(
    context: Context,
    noteId: String,
    noteTitle: String,
    noteText: String,
    themeIndex: Int,
) {
    val size = LocalSize.current
    val theme = ThemeEngine.getTheme(themeIndex)

    val textLength = noteText.length
    val baseFontSize = when {
        size.width >= EXTRA_LARGE.width -> 22f
        size.width >= LARGE.width -> 18f
        size.width >= MEDIUM.width -> 15f
        else -> 13f
    }

    val scaledFontSize = when {
        textLength > 200 -> baseFontSize * 0.75f
        textLength > 120 -> baseFontSize * 0.85f
        textLength > 60 -> baseFontSize * 0.92f
        else -> baseFontSize
    }.sp

    val accentBarHeight = when {
        size.height >= LARGE.height -> 4.dp
        else -> 3.dp
    }

    val isPlaceholder = noteText.isBlank()

    // Build true rich Spannable with real BackgroundColorSpan & StyleSpan (no bullet symbols)
    val spannable = TextFormatHelper.buildSpannableForWidget(
        rawText = if (isPlaceholder) "Tap to write…" else noteText,
        theme = theme
    )

    val rv = RemoteViews(context.packageName, R.layout.widget_note_content)
    rv.setTextViewText(R.id.widget_note_text, spannable)
    rv.setTextViewTextSize(R.id.widget_note_text, TypedValue.COMPLEX_UNIT_SP, scaledFontSize.value)
    if (isPlaceholder) {
        rv.setTextColor(R.id.widget_note_text, theme.textSecondary.toArgb())
    } else {
        rv.setTextColor(R.id.widget_note_text, theme.textPrimary.toArgb())
    }

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(theme.background)
            .cornerRadius(16.dp)
            .clickable(
                actionStartActivity<MainActivity>(
                    actionParametersOf(NoteWidget.PARAM_NOTE_ID to noteId)
                )
            ),
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(
                    horizontal = if (size.width >= LARGE.width) 20.dp else 14.dp,
                    vertical = if (size.height >= LARGE.height) 16.dp else 10.dp,
                ),
        ) {
            // ── Top header bar inside widget ────────────
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Accent indicator
                Box(
                    modifier = GlanceModifier
                        .width(24.dp)
                        .height(accentBarHeight)
                        .background(theme.accent)
                        .cornerRadius(2.dp),
                ) {}

                Spacer(modifier = GlanceModifier.defaultWeight())

                // 1-tap Camera Scan button directly inside widget header
                Image(
                    provider = ImageProvider(R.drawable.ic_camera),
                    contentDescription = "Scan Text",
                    colorFilter = ColorFilter.tint(ColorProvider(theme.textSecondary)),
                    modifier = GlanceModifier
                        .size(15.dp)
                        .clickable(
                            actionStartActivity<MainActivity>(
                                actionParametersOf(
                                    NoteWidget.PARAM_NOTE_ID to noteId,
                                    NoteWidget.PARAM_ACTION to "ACTION_SCAN"
                                )
                            )
                        )
                )

                if (size.width >= MEDIUM.width) {
                    Spacer(modifier = GlanceModifier.width(8.dp))
                    // Note label
                    Text(
                        text = noteTitle.uppercase(),
                        style = TextStyle(
                            color = ColorProvider(theme.textSecondary),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    )
                }
            }

            Spacer(modifier = GlanceModifier.height(8.dp))

            // ── Note content with real background highlight & bold/italics ─
            AndroidRemoteViews(
                remoteViews = rv,
                modifier = GlanceModifier.fillMaxWidth().defaultWeight()
            )
        }
    }
}
