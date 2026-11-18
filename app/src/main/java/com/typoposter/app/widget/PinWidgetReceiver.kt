package com.typoposter.app.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.typoposter.app.TypoPosterApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles the callback when a user accepts the "Add to Home Screen" system prompt.
 * Binds the newly created widget ID to the target note immediately.
 */
class PinWidgetReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        val noteId = intent.getStringExtra("EXTRA_NOTE_ID") ?: return

        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val store = (context.applicationContext as TypoPosterApp).noteStore
                    val note = store.getNoteById(noteId)
                    if (note != null) {
                        val glanceId = GlanceAppWidgetManager(context)
                            .getGlanceIdBy(appWidgetId)
                        NoteWidget.bindWidgetToNote(context, glanceId, note)
                    }
                } catch (_: Exception) {
                    // Ignore errors during widget binding
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
