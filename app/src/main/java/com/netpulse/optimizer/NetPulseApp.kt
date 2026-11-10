package com.netpulse.optimizer

import android.app.Application
import com.netpulse.optimizer.core.di.AppContainer

class NetPulseApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
