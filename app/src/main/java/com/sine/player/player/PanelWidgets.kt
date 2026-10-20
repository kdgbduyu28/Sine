package com.sine.player.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.sine.player.ui.theme.SineColors
import kotlinx.coroutines.delay

// Building blocks for the player's right-hand panels, shared by every panel file.

/** Remembers which row should take focus when a panel opens. */
internal class PanelFocus(val index: Int, val requester: FocusRequester)

internal val LocalPanelFocus = androidx.compose.runtime.compositionLocalOf<PanelFocus?> { null }

@Composable
internal fun focusFor(index: Int): Modifier {
    val focus = LocalPanelFocus.current ?: return Modifier
    return if (focus.index == index) Modifier.focusRequester(focus.requester) else Modifier
}

@Composable
internal fun SidePanel(title: String, focusIndex: Int, focusKey: Any = title, content: LazyListScope.() -> Unit) {
    val requester = remember(title) { FocusRequester() }
    val listState = rememberLazyListState()
    // Re-focus when the panel's content is replaced (e.g. search results arriving).
    LaunchedEffect(title, focusKey) {
        runCatching { listState.scrollToItem(focusIndex.coerceAtLeast(0)) }
        delay(50)
        runCatching { requester.requestFocus() }
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(460.dp)
                .background(SineColors.surface.copy(alpha = 0.97f))
                .padding(horizontal = 20.dp, vertical = 28.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = SineColors.text, modifier = Modifier.padding(start = 12.dp, bottom = 16.dp))
            androidx.compose.runtime.CompositionLocalProvider(LocalPanelFocus provides PanelFocus(focusIndex, requester)) {
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
            }
        }
    }
}

@Composable
internal fun ChoiceRow(label: String, detail: String?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        headlineContent = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = detail?.let { { Text(it) } },
        leadingContent = {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (selected) SineColors.accent else Color.Transparent)
                    .border(2.dp, if (selected) SineColors.accent else SineColors.muted, CircleShape),
            )
        },
    )
}

@Composable
internal fun ActionRow(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ListItem(selected = false, onClick = onClick, modifier = modifier, headlineContent = { Text(label) })
}

@Composable
internal fun InfoRow(label: String, value: String?, modifier: Modifier = Modifier) {
    ListItem(
        selected = false,
        onClick = {},
        modifier = modifier,
        headlineContent = { Text(value ?: label, style = MaterialTheme.typography.bodyLarge) },
        overlineContent = if (value != null) { { Text(label) } } else null,
    )
}

@Composable
internal fun StepperRow(
    label: String,
    value: String,
    onStep: (direction: Int, repeat: Int) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "◀ ▶ to change",
) {
    ListItem(
        selected = false,
        onClick = { onStep(1, 0) },
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionLeft -> { onStep(-1, event.nativeKeyEvent.repeatCount); true }
                Key.DirectionRight -> { onStep(1, event.nativeKeyEvent.repeatCount); true }
                else -> false
            }
        },
        headlineContent = { Text(label) },
        supportingContent = { Text(hint) },
        trailingContent = { Text(value, style = MaterialTheme.typography.titleMedium, fontSize = 18.sp) },
    )
}

internal fun cycle(values: List<Float>, current: Float, direction: Int): Float {
    val index = values.indexOfFirst { kotlin.math.abs(it - current) < 0.001f }.let { if (it < 0) values.size / 2 else it }
    return values[(index + direction).coerceIn(0, values.lastIndex)]
}

/** Steps through [values] and wraps around (for choices like colours rather than sizes). */
internal fun <T> cycle(values: List<T>, current: T, direction: Int): T {
    val index = values.indexOf(current).coerceAtLeast(0)
    return values[Math.floorMod(index + direction, values.size)]
}
