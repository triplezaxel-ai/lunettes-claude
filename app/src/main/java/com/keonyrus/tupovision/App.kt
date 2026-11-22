package com.keonyrus.tupovision

import android.app.Application

class App : Application() {
  override fun onCreate() {
    super.onCreate()
    Engine.attach(this)
  }
}
