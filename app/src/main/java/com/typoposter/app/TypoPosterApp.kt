package com.typoposter.app

import android.app.Application
import com.typoposter.app.data.NoteStore

class TypoPosterApp : Application() {

    lateinit var noteStore: NoteStore
        private set

    override fun onCreate() {
        super.onCreate()
        noteStore = NoteStore(this)
    }
}
