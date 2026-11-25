package com.keonyrus.tupovision

import android.app.Application

class App : Application() {
  override fun onCreate() {
    super.onCreate()
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
      // The crash goes to the journal first, so it can be read after the app restarts.
      runCatching { Engine.recordCrash(error) }
      previous?.uncaughtException(thread, error)
    }
    Engine.attach(this)
  }
}
