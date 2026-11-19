package com.cleansweep

import android.app.Application
import com.cleansweep.diagnostics.ErrorJournal

class CleanSweepApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ErrorJournal.install(this)
    }
}
