package com.typoposter.app

import android.app.Application
import com.typoposter.app.data.NoteStore
import com.typoposter.app.widget.WidgetUpdateWorker

class TypoPosterApp : Application() {

    lateinit var noteStore: NoteStore
        private set

    override fun onCreate() {
        super.onCreate()
        noteStore = NoteStore(this)
        WidgetUpdateWorker.enqueueDaily(this)
    }
}
