package com.openvpn.client

import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkManager
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.GeoAssetsManager
import com.v2ray.ang.handler.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OpenVpnApplication : AngApplication() {
    companion object {
        lateinit var instance: OpenVpnApplication
            private set
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        instance = this
    }

    override fun onCreate() {
        super.onCreate()
        AppConfig.hostPackageName = packageName

        MMKV.initialize(this)

        val workManagerConfiguration = Configuration.Builder()
            .setDefaultProcessName("$packageName:bg")
            .build()
        WorkManager.initialize(this, workManagerConfiguration)

        SettingsManager.initApp(this)
        SettingsManager.initAssets(this, assets)
        SettingsManager.setNightMode()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            GeoAssetsManager.ensureReady(this@OpenVpnApplication)
        }

        es.dmoral.toasty.Toasty.Config.getInstance()
            .setGravity(android.view.Gravity.BOTTOM, 0, 300)
            .apply()
    }
}
