package com.sine.player.mediaserver

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.sine.player.images.SineImages
import com.sine.player.ui.theme.SineColors

/** Landscape cards for what the media servers would offer as Continue watching / Next up. */
@Composable
fun ServerHomeRow(items: List<ServerHomeItem>, markNextUp: Boolean = true, onPlay: (ServerHomeItem) -> Unit) {
    val context = LocalContext.current
    val showServer = items.map { it.serverName }.distinct().size > 1
    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(items, key = { it.entry.uri }) { item ->
            Column(Modifier.width(300.dp)) {
                Surface(
                    onClick = { onPlay(item) },
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(containerColor = SineColors.surface, focusedContainerColor = SineColors.surfaceHigh),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        if (item.image != null) {
                            AsyncImage(item.image, null, SineImages.loader(context), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else {
                            Icon(Icons.Filled.Movie, null, tint = SineColors.muted, modifier = Modifier.align(Alignment.Center))
                        }
                        if (item.progress > 0f) {
                            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(SineColors.background.copy(alpha = 0.6f))) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(item.progress).background(SineColors.accent))
                            }
                        }
                    }
                }
                Text(item.title, style = MaterialTheme.typography.titleSmall, color = SineColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                val detail = listOfNotNull(item.subtitle, if (markNextUp && item.progress == 0f) "Next up" else null, item.serverName.takeIf { showServer }).joinToString(" · ")
                if (detail.isNotEmpty()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = SineColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
