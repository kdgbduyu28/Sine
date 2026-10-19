package com.sine.player

import android.app.Application
import com.sine.player.data.AppSettings
import com.sine.player.data.ResumeStore
import com.sine.player.data.SmbServers
import com.sine.player.smb.SmbConnections

class SineApp : Application() {
    lateinit var settings: AppSettings
        private set
    lateinit var resume: ResumeStore
        private set
    lateinit var smbServers: SmbServers
        private set
    lateinit var smb: SmbConnections
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = AppSettings(this)
        resume = ResumeStore(this)
        smbServers = SmbServers(this)
        smb = SmbConnections(smbServers)
    }

    companion object {
        lateinit var instance: SineApp
            private set
    }
}
