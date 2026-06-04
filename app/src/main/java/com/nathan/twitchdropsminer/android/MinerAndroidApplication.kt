package com.nathan.twitchdropsminer.android

import android.app.Application
import com.nathan.twitchdropsminer.android.di.AppGraph

class MinerAndroidApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.from(this)
    }
}
