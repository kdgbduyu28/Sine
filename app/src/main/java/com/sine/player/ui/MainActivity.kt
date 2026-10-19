package com.sine.player.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sine.player.player.PlayerActivity
import com.sine.player.ui.theme.SineColors
import com.sine.player.ui.theme.SineTheme

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SineTheme {
                Box(Modifier.fillMaxSize().background(SineColors.background)) {
                    val nav = rememberNavController()
                    val play: (String, String?, String?) -> Unit = { uri, title, folder ->
                        startActivity(PlayerActivity.intent(this@MainActivity, uri, title, folder))
                    }
                    NavHost(navController = nav, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                onBrowse = { nav.navigate("browse/${Uri.encode(it)}") },
                                onPlay = play,
                                onEditServer = { id -> nav.navigate("smb?id=${Uri.encode(id ?: "")}") },
                                onSettings = { nav.navigate("settings") },
                                onCapabilities = { nav.navigate("capabilities") },
                            )
                        }
                        composable("browse/{uri}") { entry ->
                            val uri = Uri.decode(entry.arguments?.getString("uri").orEmpty())
                            BrowseScreen(
                                uri = uri,
                                onOpenFolder = { nav.navigate("browse/${Uri.encode(it)}") },
                                onPlay = play,
                            )
                        }
                        composable("smb?id={id}") { entry ->
                            SmbServerScreen(
                                serverId = entry.arguments?.getString("id")?.ifEmpty { null },
                                onDone = { nav.popBackStack() },
                            )
                        }
                        composable("settings") { SettingsScreen() }
                        composable("capabilities") { CapabilitiesScreen() }
                    }
                }
            }
        }
    }
}
