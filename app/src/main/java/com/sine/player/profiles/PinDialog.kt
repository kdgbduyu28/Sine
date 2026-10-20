package com.sine.player.profiles

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.sine.player.ui.theme.SineColors

/**
 * Four-digit PIN entry that works with any remote: number keys if it has them, otherwise ▲▼ to
 * change a digit and ▶ / OK to move on.
 */
@Composable
fun PinDialog(title: String, subtitle: String?, onSubmit: (String) -> Boolean, onDismiss: () -> Unit) {
    val digits = remember { mutableStateListOf(-1, -1, -1, -1) }
    var position by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler(onBack = onDismiss)

    fun submit() {
        val pin = digits.joinToString("")
        if (!onSubmit(pin)) {
            error = "Wrong PIN"
            for (i in 0 until 4) digits[i] = -1
            position = 0
        }
    }

    fun enter(digit: Int) {
        digits[position] = digit
        error = null
        if (position == 3) submit() else position++
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(SineColors.surface)
                .padding(32.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val code = event.nativeKeyEvent.keyCode
                    when {
                        code in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> { enter(code - AndroidKeyEvent.KEYCODE_0); true }
                        code in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> { enter(code - AndroidKeyEvent.KEYCODE_NUMPAD_0); true }
                        event.key == Key.DirectionUp -> { digits[position] = (digits[position].coerceAtLeast(-1) + 1) % 10; true }
                        event.key == Key.DirectionDown -> { digits[position] = if (digits[position] <= 0) 9 else digits[position] - 1; true }
                        event.key == Key.DirectionRight -> { if (digits[position] < 0) digits[position] = 0; if (position < 3) position++; true }
                        event.key == Key.DirectionLeft -> { if (position > 0) position--; true }
                        event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                            if (digits[position] < 0) digits[position] = 0
                            if (digits.all { it >= 0 }) submit() else if (position < 3) position++
                            true
                        }
                        event.key == Key.Backspace || code == AndroidKeyEvent.KEYCODE_DEL -> {
                            digits[position] = -1
                            if (position > 0) position--
                            true
                        }
                        else -> false
                    }
                }
                .focusRequester(focus)
                .focusable(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = SineColors.text)
            subtitle?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodyLarge, color = SineColors.muted)
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (i in 0 until 4) {
                    val current = i == position
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SineColors.surfaceHigh)
                            .border(2.dp, if (current) SineColors.accent else Color.Transparent, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        val d = digits[i]
                        Text(
                            when {
                                d < 0 -> ""
                                current -> d.toString()
                                else -> "•"
                            },
                            style = MaterialTheme.typography.headlineMedium,
                            color = SineColors.text,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                error ?: "Number keys, or ▲▼ to change a digit and OK to go on",
                style = MaterialTheme.typography.bodyMedium,
                color = if (error != null) SineColors.danger else SineColors.muted,
            )
        }
    }
}
