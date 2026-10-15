package com.example.smartstepcounter

import android.app.Application

class SmartStepApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
