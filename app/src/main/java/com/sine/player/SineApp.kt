package com.sine.player

import android.app.Application
import androidx.media3.common.util.UnstableApi
import com.sine.player.data.AppSettings
import com.sine.player.data.RemoteServers
import com.sine.player.data.ResumeStore
import com.sine.player.data.SmbServers
import com.sine.player.downloads.DownloadStore
import com.sine.player.downloads.Downloads
import com.sine.player.household.Household
import com.sine.player.profiles.Profiles
import com.sine.player.mediaserver.MediaServers
import com.sine.player.net.Dlna
import com.sine.player.net.NfsConnections
import com.sine.player.net.SftpConnections
import com.sine.player.player.SchemeDataSource
import com.sine.player.player.StreamProxy
import com.sine.player.smb.SmbConnections
import java.io.File
import kotlinx.coroutines.launch

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
    lateinit var downloads: DownloadStore
        private set
    lateinit var profiles: Profiles
        private set
    lateinit var household: Household
        private set
    lateinit var fileMemory: com.sine.player.data.FileMemory
        private set
    lateinit var library: com.sine.player.library.LibraryStore
        private set
    lateinit var keyMap: com.sine.player.data.KeyMap
        private set

    /** For work that should outlive a screen (syncing progress after playback ends). */
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    /** Lets mpv read every source Sine can; started on first use. */
    val streamProxy: StreamProxy by lazy { StreamProxy(SchemeDataSource.Factory(this)) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = AppSettings(this)
        profiles = Profiles(this)
        resume = ResumeStore(this) { profiles.activeId }
        smbServers = SmbServers(this)
        smb = SmbConnections(smbServers)
        remoteServers = RemoteServers(this)
        sftp = SftpConnections({ remoteServers.servers.toList() }, File(filesDir, "ssh/known_hosts"))
        nfs = NfsConnections { remoteServers.servers.toList() }
        dlna = Dlna(this)
        mediaServers = MediaServers(remoteServers, resume) { settings.deviceId() }
        downloads = DownloadStore(File(filesDir, "downloads.json"))
        Downloads.cleanup()
        if (downloads.nextQueued() != null) Downloads.schedule(this)
        fileMemory = com.sine.player.data.FileMemory(this)
        library = com.sine.player.library.LibraryStore(File(filesDir, "library.json"))
        if (!library.isEmpty && System.currentTimeMillis() - library.scannedAt > 12 * 3_600_000L) {
            scope.launch { runCatching { com.sine.player.library.Library.scan() } }
        }
        keyMap = com.sine.player.data.KeyMap(this)
        household = Household(this)
        runCatching { household.start() }
        if (settings.castReceiver) runCatching { com.sine.player.cast.CastReceiverService.start(this) }
    }

    companion object {
        lateinit var instance: SineApp
            private set
    }
}
