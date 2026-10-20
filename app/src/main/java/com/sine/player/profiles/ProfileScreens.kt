package com.sine.player.profiles

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.sine.player.SineApp
import com.sine.player.source.Browsers
import com.sine.player.ui.Page
import com.sine.player.ui.SectionTitle
import com.sine.player.ui.SettingRow
import com.sine.player.ui.TvTextField
import com.sine.player.ui.theme.SineColors

@Composable
fun Avatar(profile: Profile, size: Int) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Profile.colors[profile.color % Profile.colors.size]),
        contentAlignment = Alignment.Center,
    ) {
        Text(profile.name.take(1).uppercase(), fontSize = (size * 0.45).sp, color = Color(0xFF1A1405))
    }
}

/**
 * Switches to [target], asking for a PIN when it's locked, or when leaving a kids profile for a
 * grown-up one while a parent PIN exists.
 */
@Composable
fun rememberProfileSwitcher(onSwitched: () -> Unit): (Profile) -> Unit {
    val profiles = SineApp.instance.profiles
    var asking by remember { mutableStateOf<Profile?>(null) }
    asking?.let { target ->
        val leavingKids = profiles.active.kids && !target.kids
        PinDialog(
            title = if (target.locked) "${target.name}'s PIN" else "Parent PIN",
            subtitle = if (!target.locked && leavingKids) "Needed to leave ${profiles.active.name}'s profile" else null,
            onSubmit = { pin ->
                val ok = if (target.locked) target.checkPin(pin) else profiles.checkParentPin(pin)
                if (ok) {
                    asking = null
                    profiles.switchTo(target.id)
                    SineApp.instance.resume.profileChanged()
                    onSwitched()
                }
                ok
            },
            onDismiss = { asking = null },
        )
    }
    return { target ->
        val leavingKids = profiles.active.kids && !target.kids && target.id != profiles.activeId
        if (target.id != profiles.activeId && (target.locked || (leavingKids && profiles.hasParentPin))) {
            asking = target
        } else {
            profiles.switchTo(target.id)
            SineApp.instance.resume.profileChanged()
            onSwitched()
        }
    }
}

/** "Who's watching?" */
@Composable
fun ProfilePickerScreen(onDone: () -> Unit) {
    val profiles = SineApp.instance.profiles
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val switchTo = rememberProfileSwitcher(onDone)
    Box(Modifier.fillMaxSize().background(SineColors.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Who's watching?", style = MaterialTheme.typography.displaySmall, color = SineColors.text)
            Spacer(Modifier.height(40.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                profiles.list.forEach { profile ->
                    Surface(
                        onClick = { switchTo(profile) },
                        modifier = (if (profile.id == profiles.activeId) Modifier.focusRequester(focus) else Modifier).width(180.dp),
                        shape = ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = SineColors.surfaceHigh),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
                    ) {
                        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Avatar(profile, 120)
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(profile.name, style = MaterialTheme.typography.titleLarge, color = SineColors.text, textAlign = TextAlign.Center)
                                if (profile.locked) Icon(Icons.Filled.Lock, null, tint = SineColors.muted, modifier = Modifier.padding(start = 6.dp).size(18.dp))
                                if (profile.kids) Icon(Icons.Filled.ChildCare, null, tint = SineColors.accent, modifier = Modifier.padding(start = 6.dp).size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Settings → Profiles. */
@Composable
fun ProfilesScreen(onEdit: (String?) -> Unit) {
    val profiles = SineApp.instance.profiles
    val focus = remember { FocusRequester() }
    var ask by remember { mutableStateOf(profiles.askOnStart) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Page("Profiles", "Each profile has its own Continue watching and watched marks") {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            profiles.list.forEachIndexed { i, p ->
                item {
                    ListItem(
                        selected = p.id == profiles.activeId,
                        onClick = { onEdit(p.id) },
                        modifier = if (i == 0) Modifier.focusRequester(focus) else Modifier,
                        leadingContent = { Avatar(p, 40) },
                        headlineContent = { Text(p.name) },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    if (p.kids) "Kids" else null,
                                    if (p.locked) "PIN" else null,
                                    if (p.kids && p.dailyMinutes > 0) "${p.dailyMinutes} min a day" else null,
                                    if (p.kids) "${p.allowedRoots.size} folders" else null,
                                    if (p.id == profiles.activeId) "Watching now" else null,
                                ).joinToString(" · ").ifEmpty { "Grown-up" },
                            )
                        },
                    )
                }
            }
            item {
                ListItem(
                    selected = false,
                    onClick = { onEdit(null) },
                    leadingContent = { Icon(Icons.Filled.Add, null, tint = SineColors.accent) },
                    headlineContent = { Text("Add profile") },
                )
            }
            item {
                SettingRow("Ask who's watching when Sine opens", "Only when there's more than one profile", if (ask) "On" else "Off", {
                    ask = !ask
                    profiles.askOnStart = ask
                })
            }
        }
    }
}

@Composable
fun ProfileEditScreen(profileId: String?, onDone: () -> Unit) {
    val profiles = SineApp.instance.profiles
    val original = remember(profileId) { profileId?.let(profiles::get) }
    var profile by remember { mutableStateOf(original ?: Profile(name = "", color = profiles.list.size % Profile.colors.size)) }
    var name by remember { mutableStateOf(profile.name) }
    var settingPin by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun <T> cycle(values: List<T>, current: T): T = values[(values.indexOf(current) + 1) % values.size]
    val limits = listOf(0, 30, 60, 90, 120, 180, 240)
    val bedtimes = listOf<Int?>(null, 19, 20, 21, 22, 23)
    val wakes = listOf(5, 6, 7, 8, 9)

    Box(Modifier.fillMaxSize()) {
        Page(if (original == null) "Add profile" else "Edit ${original.name}") {
            LazyColumn(Modifier.width(820.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item { TvTextField("Name", name, { name = it }, Modifier.focusRequester(focus).padding(horizontal = 12.dp, vertical = 6.dp)) }
                item {
                    ListItem(
                        selected = false,
                        onClick = { profile = profile.copy(color = (profile.color + 1) % Profile.colors.size) },
                        leadingContent = { Avatar(profile.copy(name = name.ifBlank { "?" }), 40) },
                        headlineContent = { Text("Colour") },
                        supportingContent = { Text("Click to change") },
                    )
                }
                item {
                    SettingRow(
                        "PIN",
                        "Locks this profile. A grown-up's PIN also unlocks kids' time limits.",
                        if (profile.locked) "Set" else "None",
                        { if (profile.locked) profile = profiles.setPin(profile, null) else settingPin = true },
                    )
                }
                item {
                    SettingRow("Kids profile", "Only allowed folders, with optional daily limit and bedtime", if (profile.kids) "On" else "Off", {
                        profile = profile.copy(kids = !profile.kids)
                    })
                }
                if (profile.kids) {
                    item {
                        SettingRow("Daily limit", "Watching time per day", if (profile.dailyMinutes == 0) "No limit" else "${profile.dailyMinutes} min", {
                            profile = profile.copy(dailyMinutes = cycle(limits, profile.dailyMinutes))
                        })
                    }
                    item {
                        SettingRow("Bedtime", "No watching from this hour", profile.bedtimeHour?.let { "%d:00".format(it) } ?: "Off", {
                            profile = profile.copy(bedtimeHour = cycle(bedtimes, profile.bedtimeHour))
                        })
                    }
                    if (profile.bedtimeHour != null) {
                        item {
                            SettingRow("Until", "Watching is allowed again from", "%d:00".format(profile.wakeHour), {
                                profile = profile.copy(wakeHour = cycle(wakes, profile.wakeHour))
                            })
                        }
                    }
                    item { SectionTitle("Allowed folders") }
                    if (profile.allowedRoots.isEmpty()) {
                        item { Text("None yet. While browsing as a grown-up, hold OK on a folder → Allow for ${name.ifBlank { "this profile" }}.", color = SineColors.muted, modifier = Modifier.padding(12.dp)) }
                    }
                    profile.allowedRoots.forEach { root ->
                        item {
                            ListItem(
                                selected = false,
                                onClick = { profile = profile.copy(allowedRoots = profile.allowedRoots - root) },
                                headlineContent = { Text(Browsers.displayName(root)) },
                                supportingContent = { Text("Click to remove · ${android.net.Uri.decode(root)}", maxLines = 1) },
                            )
                        }
                    }
                }
                item {
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(enabled = name.isNotBlank(), onClick = {
                            profiles.upsert(profile.copy(name = name.trim()))
                            onDone()
                        }) { Text("Save") }
                        if (original != null && original.id != Profiles.DEFAULT_ID) {
                            OutlinedButton(onClick = {
                                profiles.remove(original.id)
                                onDone()
                            }) { Text("Delete profile") }
                        }
                    }
                }
            }
        }
        if (settingPin) {
            PinDialog("Choose a 4-digit PIN", "for ${name.ifBlank { "this profile" }}", onSubmit = { pin ->
                profile = profiles.setPin(profile, pin)
                settingPin = false
                true
            }, onDismiss = { settingPin = false })
        }
    }
}
