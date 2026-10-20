package com.sine.player

import android.app.Application
import androidx.media3.common.util.UnstableApi
import com.sine.player.data.AppSettings
import com.sine.player.data.RemoteServers
import com.sine.player.data.ResumeStore
import com.sine.player.data.SmbServers
import com.sine.player.mediaserver.MediaServers
import com.sine.player.net.Dlna
import com.sine.player.net.NfsConnections
import com.sine.player.net.SftpConnections
import com.sine.player.player.SchemeDataSource
import com.sine.player.player.StreamProxy
import com.sine.player.smb.SmbConnections
import java.io.File

@UnstableApi
class SineApp : Application() {
    lateinit var settings: AppSettings
        private set
    lateinit var resume: ResumeStore
        private set
    lateinit var smbServers: SmbServers
        private set
    lateinit var smb: SmbConnections
        private set
    lateinit var remoteServers: RemoteServers
        private set
    lateinit var sftp: SftpConnections
        private set
    lateinit var nfs: NfsConnections
        private set
    lateinit var dlna: Dlna
        private set
    lateinit var mediaServers: MediaServers
        private set

    /** Lets mpv read every source Sine can; started on first use. */
    val streamProxy: StreamProxy by lazy { StreamProxy(SchemeDataSource.Factory(this)) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = AppSettings(this)
        resume = ResumeStore(this)
        smbServers = SmbServers(this)
        smb = SmbConnections(smbServers)
        remoteServers = RemoteServers(this)
        sftp = SftpConnections({ remoteServers.servers.toList() }, File(filesDir, "ssh/known_hosts"))
        nfs = NfsConnections { remoteServers.servers.toList() }
        dlna = Dlna(this)
        mediaServers = MediaServers(remoteServers, resume) { settings.deviceId() }
    }

    companion object {
        lateinit var instance: SineApp
            private set
    }
}
