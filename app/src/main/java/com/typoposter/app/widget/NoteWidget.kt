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
import androidx.datastore.preferences.core.booleanPreferencesKey
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
import com.typoposter.app.date.CountdownHelper
import com.typoposter.app.date.UrgencyTier
import com.typoposter.app.theme.TextFormatHelper
import com.typoposter.app.theme.ThemeEngine
import com.typoposter.app.ui.VoiceOverlayActivity
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
        val KEY_IS_LOCKED = booleanPreferencesKey("widget_is_locked")

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
                            this[KEY_IS_LOCKED] = note.isLocked
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
                    this[KEY_IS_LOCKED] = note.isLocked
                }
            }
            NoteWidget().update(context, glanceId)
        }

        /**
         * Refreshes all active widgets from current notes in NoteStore.
         * Used by daily background worker to update live countdown days.
         */
        suspend fun updateAllWidgets(context: Context) {
            val manager = GlanceAppWidgetManager(context)
            val glanceIds = manager.getGlanceIds(NoteWidget::class.java)
            if (glanceIds.isEmpty()) return

            val store = NoteStore(context)
            val notes = store.notes.first()
            val activeId = store.activeNoteId.first()

            glanceIds.forEach { glanceId ->
                val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
                val boundNoteId = prefs[KEY_NOTE_ID] ?: activeId
                val note = notes.firstOrNull { it.id == boundNoteId } ?: notes.firstOrNull()
                if (note != null) {
                    updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { p ->
                        p.toMutablePreferences().apply {
                            this[KEY_NOTE_ID] = note.id
                            this[KEY_NOTE_TEXT] = note.content
                            this[KEY_THEME_INDEX] = note.themeIndex
                            this[KEY_NOTE_TITLE] = note.title
                            this[KEY_IS_LOCKED] = note.isLocked
                        }
                    }
                    NoteWidget().update(context, glanceId)
                }
            }
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
                        this[KEY_IS_LOCKED] = note.isLocked
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
            val isLocked = prefs[KEY_IS_LOCKED] ?: false

            NoteWidgetContent(
                context = LocalContext.current,
                noteId = noteId,
                noteTitle = noteTitle,
                noteText = noteText,
                themeIndex = themeIndex,
                isLocked = isLocked,
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
    isLocked: Boolean = false,
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
                    actionParametersOf(
                        NoteWidget.PARAM_NOTE_ID to noteId,
                        NoteWidget.PARAM_ACTION to if (isLocked) "ACTION_UNLOCK" else "ACTION_VIEW"
                    )
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

                // Offline Live Countdown Badge (Strict privacy: never for locked notes)
                val countdown = if (!isLocked) CountdownHelper.findNearest(noteText) else null
                if (countdown != null) {
                    val tier = countdown.urgencyTier
                    val bg = when (tier) {
                        UrgencyTier.IMMINENT -> theme.accent.copy(alpha = 0.32f)
                        UrgencyTier.APPROACHING -> theme.accent.copy(alpha = 0.18f)
                        UrgencyTier.CALM -> theme.surfaceVariant.copy(alpha = 0.8f)
                    }
                    val hPad = when (tier) {
                        UrgencyTier.IMMINENT -> 7.dp
                        UrgencyTier.APPROACHING -> 6.dp
                        UrgencyTier.CALM -> 5.dp
                    }
                    val vPad = when (tier) {
                        UrgencyTier.IMMINENT -> 2.5.dp
                        UrgencyTier.APPROACHING -> 2.dp
                        UrgencyTier.CALM -> 1.5.dp
                    }
                    val fSize = when (tier) {
                        UrgencyTier.IMMINENT -> 10.sp
                        UrgencyTier.APPROACHING -> 9.sp
                        UrgencyTier.CALM -> 8.5.sp
                    }
                    val fColor = when (tier) {
                        UrgencyTier.IMMINENT, UrgencyTier.APPROACHING -> theme.accent
                        UrgencyTier.CALM -> theme.textSecondary
                    }
                    Spacer(modifier = GlanceModifier.width(8.dp))
                    Box(
                        modifier = GlanceModifier
                            .background(bg)
                            .cornerRadius(4.dp)
                            .padding(horizontal = hPad, vertical = vPad),
                    ) {
                        Text(
                            text = countdown.compactLabel.uppercase(),
                            style = TextStyle(
                                color = ColorProvider(fColor),
                                fontSize = fSize,
                                fontWeight = FontWeight.Bold,
                            )
                        )
                    }
                }

                Spacer(modifier = GlanceModifier.defaultWeight())

                if (isLocked) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_lock),
                        contentDescription = "Locked Note",
                        colorFilter = ColorFilter.tint(ColorProvider(theme.accent)),
                        modifier = GlanceModifier.size(14.dp)
                    )
                    Spacer(modifier = GlanceModifier.width(10.dp))
                    Box(
                        modifier = GlanceModifier
                            .size(40.dp)
                            .background(theme.surfaceVariant.copy(alpha = 0.65f))
                            .cornerRadius(20.dp)
                            .clickable(
                                actionStartActivity<VoiceOverlayActivity>(
                                    actionParametersOf(
                                        NoteWidget.PARAM_NOTE_ID to noteId
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            provider = ImageProvider(R.drawable.ic_mic),
                            contentDescription = "Voice Note",
                            colorFilter = ColorFilter.tint(ColorProvider(theme.textSecondary)),
                            modifier = GlanceModifier.size(18.dp)
                        )
                    }
                } else {
                    // 1-tap Voice-to-Note Floating Overlay trigger (40dp x 40dp touch target chip)
                    Box(
                        modifier = GlanceModifier
                            .size(40.dp)
                            .background(theme.surfaceVariant.copy(alpha = 0.65f))
                            .cornerRadius(20.dp)
                            .clickable(
                                actionStartActivity<VoiceOverlayActivity>(
                                    actionParametersOf(
                                        NoteWidget.PARAM_NOTE_ID to noteId
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            provider = ImageProvider(R.drawable.ic_mic),
                            contentDescription = "Voice Note",
                            colorFilter = ColorFilter.tint(ColorProvider(theme.accent)),
                            modifier = GlanceModifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = GlanceModifier.width(10.dp))
                    // 1-tap Camera Scan button (40dp x 40dp touch target chip)
                    Box(
                        modifier = GlanceModifier
                            .size(40.dp)
                            .background(theme.surfaceVariant.copy(alpha = 0.65f))
                            .cornerRadius(20.dp)
                            .clickable(
                                actionStartActivity<MainActivity>(
                                    actionParametersOf(
                                        NoteWidget.PARAM_NOTE_ID to noteId,
                                        NoteWidget.PARAM_ACTION to "ACTION_SCAN"
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            provider = ImageProvider(R.drawable.ic_camera),
                            contentDescription = "Scan Text",
                            colorFilter = ColorFilter.tint(ColorProvider(theme.textSecondary)),
                            modifier = GlanceModifier.size(18.dp)
                        )
                    }
                }

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

            if (isLocked) {
                // Redacted Poster Censor Bars matching theme colors & line rhythm
                Column(
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val barColor = ColorProvider(theme.textPrimary.copy(alpha = 0.22f))
                    val barHeight = when {
                        size.height >= LARGE.height -> 16.dp
                        size.height >= MEDIUM.height -> 13.dp
                        else -> 10.dp
                    }
                    val barSpacing = when {
                        size.height >= LARGE.height -> 10.dp
                        else -> 7.dp
                    }

                    Box(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .height(barHeight)
                            .background(barColor)
                            .cornerRadius(3.dp)
                    ) {}
                    Spacer(modifier = GlanceModifier.height(barSpacing))
                    Box(
                        modifier = GlanceModifier
                            .width(if (size.width >= LARGE.width) 240.dp else 140.dp)
                            .height(barHeight)
                            .background(barColor)
                            .cornerRadius(3.dp)
                    ) {}
                    Spacer(modifier = GlanceModifier.height(barSpacing))
                    Box(
                        modifier = GlanceModifier
                            .width(if (size.width >= LARGE.width) 190.dp else 110.dp)
                            .height(barHeight)
                            .background(barColor)
                            .cornerRadius(3.dp)
                    ) {}
                    if (size.height >= LARGE.height) {
                        Spacer(modifier = GlanceModifier.height(barSpacing))
                        Box(
                            modifier = GlanceModifier
                                .width(150.dp)
                                .height(barHeight)
                                .background(barColor)
                                .cornerRadius(3.dp)
                        ) {}
                    }
                }
            } else {
                // ── Note content with real background highlight & bold/italics ─
                AndroidRemoteViews(
                    remoteViews = rv,
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight()
                )
            }
        }
    }
}
