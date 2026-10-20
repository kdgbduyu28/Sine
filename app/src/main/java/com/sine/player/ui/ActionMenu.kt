package com.sine.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.sine.player.ui.theme.SineColors

data class MenuAction(val label: String, val detail: String? = null, val run: () -> Unit)

/** The "hold OK" menu: a short list of things to do with a file or folder. */
@Composable
fun ActionMenu(title: String, actions: List<MenuAction>, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(SineColors.surface)
                .padding(24.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = SineColors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, bottom = 12.dp),
            )
            LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(actions) { i, action ->
                    ListItem(
                        selected = false,
                        onClick = {
                            onDismiss()
                            action.run()
                        },
                        modifier = if (i == 0) Modifier.focusRequester(focus) else Modifier,
                        headlineContent = { Text(action.label) },
                        supportingContent = action.detail?.let { { Text(it) } },
                    )
                }
            }
        }
    }
}
