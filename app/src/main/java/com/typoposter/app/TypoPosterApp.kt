package com.typoposter.app

import android.app.Application
import android.util.Log
import com.typoposter.app.data.NoteStore
import com.typoposter.app.widget.WidgetUpdateWorker
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class TypoPosterApp : Application() {

    lateinit var noteStore: NoteStore
        private set

    override fun onCreate() {
        super.onCreate()

        // Global crash guard & logger
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val crashText = "CRASH on ${thread.name} [${System.currentTimeMillis()}]:\n$sw"
                Log.e("TypoPosterCrash", crashText)

                val dir = getExternalFilesDir(null) ?: filesDir
                File(dir, "typoposter_crash.txt").writeText(crashText)
                try {
                    File("/sdcard/Download/typoposter_crash.txt").writeText(crashText)
                } catch (_: Throwable) {}
            } catch (_: Throwable) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        noteStore = NoteStore(this)
        WidgetUpdateWorker.enqueueDaily(this)
    }
}
