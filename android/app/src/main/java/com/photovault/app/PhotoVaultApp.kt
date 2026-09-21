package com.photovault.app

import android.app.Application
import com.photovault.app.util.Notifications
import com.photovault.app.work.UploadScheduler

class PhotoVaultApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        UploadScheduler.ensurePeriodic(this)
    }
}
