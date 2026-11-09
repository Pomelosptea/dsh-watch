package com.deepseek.dshwatch

import android.app.Application

class DshWatchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
