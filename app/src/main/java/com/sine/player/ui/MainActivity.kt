package com.sine.player.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sine.player.data.ServerKind
import com.sine.player.downloads.DownloadsScreen
import com.sine.player.music.MusicRemote
import com.sine.player.music.MusicScreen
import com.sine.player.photos.PhotoViewerScreen
import com.sine.player.player.PlayerActivity
import com.sine.player.ui.theme.SineColors
import com.sine.player.ui.theme.SineTheme
import kotlinx.coroutines.launch

@UnstableApi
class MainActivity : ComponentActivity() {
    /** Set when the music notification or "Now playing" card opens the app. */
    private var openMusic by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openMusic = intent?.getBooleanExtra(EXTRA_OPEN_MUSIC, false) == true
        setContent {
            SineTheme {
                Box(Modifier.fillMaxSize().background(SineColors.background)) {
                    val nav = rememberNavController()
                    val scope = rememberCoroutineScope()
                    val play: (String, String?, String?) -> Unit = { uri, title, folder ->
                        startActivity(PlayerActivity.intent(this@MainActivity, uri, title, folder))
                    }
                    val playMusic: (String, String?) -> Unit = { folder, start ->
                        scope.launch {
                            runCatching { MusicRemote.playFolder(this@MainActivity, folder, start.orEmpty()) }
                            nav.navigate("music") { launchSingleTop = true }
                        }
                    }
                    val openPhotos: (String, String?) -> Unit = { folder, start ->
                        nav.navigate("photos?folder=${Uri.encode(folder)}&start=${Uri.encode(start.orEmpty())}")
                    }
                    LaunchedEffect(openMusic) {
                        if (openMusic) {
                            openMusic = false
                            nav.navigate("music") { launchSingleTop = true }
                        }
                    }
                    val profiles = com.sine.player.SineApp.instance.profiles
                    val start = remember { if (profiles.list.size > 1 && profiles.askOnStart && !openMusic) "profiles" else "home" }
                    val playFrom: (String, String?, String?, Long, String?) -> Unit = { uri, title, folder, position, follow ->
                        startActivity(PlayerActivity.intent(this@MainActivity, uri, title, folder, position, follow))
                    }
                    NavHost(navController = nav, startDestination = start) {
                        composable("profiles") {
                            com.sine.player.profiles.ProfilePickerScreen(onDone = {
                                nav.navigate("home") { popUpTo(nav.graph.id) { inclusive = true } }
                            })
                        }
                        composable("profiles/manage") {
                            com.sine.player.profiles.ProfilesScreen(onEdit = { id -> nav.navigate("profile?id=${Uri.encode(id ?: "")}") })
                        }
                        composable("profile?id={id}") { entry ->
                            com.sine.player.profiles.ProfileEditScreen(entry.arguments?.getString("id")?.ifEmpty { null }, onDone = { nav.popBackStack() })
                        }
                        composable("household") { com.sine.player.household.HouseholdScreen() }
                        composable("home") {
                            HomeScreen(
                                onBrowse = { nav.navigate("browse/${Uri.encode(it)}") },
                                onPlay = play,
                                onEditServer = { id -> nav.navigate("smb?id=${Uri.encode(id ?: "")}") },
                                onEditRemote = { kind, id -> nav.navigate("server/${kind.name}?id=${Uri.encode(id)}") },
                                onAddServer = { nav.navigate("addserver") },
                                onSettings = { nav.navigate("settings") },
                                onCapabilities = { nav.navigate("capabilities") },
                                onMusic = { nav.navigate("music") { launchSingleTop = true } },
                                onDownloads = { nav.navigate("downloads") },
                                onProfiles = { nav.navigate("profiles") { popUpTo(nav.graph.id) { inclusive = true } } },
                                onManageProfiles = { nav.navigate("profiles/manage") },
                                onOtherTvs = { nav.navigate("household") },
                                onPlayFrom = playFrom,
                                onLibrary = { nav.navigate("library/${it.name}") },
                                onMovie = { nav.navigate("movie/${Uri.encode(it)}") },
                                onShow = { nav.navigate("show/${Uri.encode(it)}") },
                            )
                        }
                        composable("browse/{uri}") { entry ->
                            val uri = Uri.decode(entry.arguments?.getString("uri").orEmpty())
                            BrowseScreen(
                                uri = uri,
                                onOpenFolder = { nav.navigate("browse/${Uri.encode(it)}") },
                                onPlay = play,
                                onPlayMusic = playMusic,
                                onOpenPhotos = openPhotos,
                            )
                        }
                        composable("music") { MusicScreen() }
                        composable("downloads") { DownloadsScreen(onPlay = play) }
                        composable("photos?folder={folder}&start={start}") { entry ->
                            PhotoViewerScreen(
                                folder = entry.arguments?.getString("folder").orEmpty(),
                                startUri = entry.arguments?.getString("start")?.ifEmpty { null },
                            )
                        }
                        composable("smb?id={id}") { entry ->
                            SmbServerScreen(
                                serverId = entry.arguments?.getString("id")?.ifEmpty { null },
                                onDone = { nav.popBackStack() },
                            )
                        }
                        composable("addserver") {
                            AddServerScreen(onPick = { kind ->
                                nav.popBackStack()
                                nav.navigate(if (kind == null) "smb?id=" else "server/${kind.name}?id=")
                            })
                        }
                        composable("server/{kind}?id={id}") { entry ->
                            val kind = ServerKind.entries.firstOrNull { it.name == entry.arguments?.getString("kind") } ?: ServerKind.WEBDAV
                            ServerScreen(
                                kind = kind,
                                serverId = entry.arguments?.getString("id")?.ifEmpty { null },
                                onDone = { nav.popBackStack() },
                            )
                        }
                        composable("opensubtitles") { OpenSubtitlesScreen(onDone = { nav.popBackStack() }) }
                        composable("settings") {
                            SettingsScreen(
                                onOpenSubtitles = { nav.navigate("opensubtitles") },
                                onRemoteButtons = { nav.navigate("remote") },
                                onLibrary = { nav.navigate("library/settings") },
                            )
                        }
                        composable("remote") { RemoteButtonsScreen() }
                        composable("library/settings") { com.sine.player.library.LibrarySettingsScreen() }
                        composable("library/{kind}") { entry ->
                            val kind = com.sine.player.library.LibraryKind.entries.firstOrNull { it.name == entry.arguments?.getString("kind") } ?: com.sine.player.library.LibraryKind.MOVIES
                            com.sine.player.library.LibraryGridScreen(kind, onMovie = { nav.navigate("movie/${Uri.encode(it)}") }, onShow = { nav.navigate("show/${Uri.encode(it)}") })
                        }
                        composable("movie/{key}") { entry ->
                            com.sine.player.library.MovieDetailScreen(Uri.decode(entry.arguments?.getString("key").orEmpty())) { uri, title, folder, position ->
                                playFrom(uri, title, folder, position ?: 0, null)
                            }
                        }
                        composable("show/{key}") { entry ->
                            com.sine.player.library.ShowDetailScreen(Uri.decode(entry.arguments?.getString("key").orEmpty())) { uri, title, folder, position ->
                                playFrom(uri, title, folder, position ?: 0, null)
                            }
                        }
                        composable("capabilities") { CapabilitiesScreen() }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_MUSIC, false)) openMusic = true
    }

    companion object {
        const val EXTRA_OPEN_MUSIC = "com.sine.player.open_music"
    }
}
