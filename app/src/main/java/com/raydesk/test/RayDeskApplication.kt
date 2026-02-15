package com.raydesk.test

import android.app.Application
import com.ffalcon.mercury.android.sdk.MercurySDK

class RayDeskApplication : Application() {
    companion object {
        lateinit var appContext: Application
    }

    override fun onCreate() {
        super.onCreate()
        appContext = this
        MercurySDK.init(this)
    }
}
