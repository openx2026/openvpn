package com.v2ray.ang

import android.app.Application
import androidx.multidex.MultiDexApplication

open class AngApplication : MultiDexApplication() {
    companion object {
        @JvmStatic
        lateinit var application: Application
            private set
    }

    override fun onCreate() {
        super.onCreate()
        application = this
    }
}
