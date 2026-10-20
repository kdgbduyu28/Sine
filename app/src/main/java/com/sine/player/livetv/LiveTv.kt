package com.sine.player.livetv

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.sine.player.SineApp
import com.sine.player.net.Net
import com.sine.player.source.MediaEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.GZIPInputStream

enum class SourceKind(val label: String) { M3U("M3U playlist"), HDHOMERUN("HDHomeRun tuner"), MEDIA_SERVER("Media server Live TV") }

/** An IPTV playlist (URL or file) with an optional XMLTV guide, an HDHomeRun tuner, or a Jellyfin/Emby/Plex server ([LiveSource.address] = its id). */

data class LiveSource(val id: String, val kind: SourceKind, val name: String, val address: String, val guideUrl: String?) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind.name).put("name", name).put("address", address).put("guide", guideUrl ?: "")

    companion object {
        fun fromJson(o: JSONObject) = LiveSource(
            o.getString("id"),
            SourceKind.valueOf(o.getString("kind")),
            o.optString("name"),
            o.getString("address"),
            o.optString("guide").ifEmpty { null },
        )

        fun new(kind: SourceKind, name: String, address: String, guideUrl: String?) =
            LiveSource(UUID.randomUUID().toString().take(8), kind, name, address.trim(), guideUrl?.trim()?.ifEmpty { null })
    }
}

/**
 * Live TV: sources, the merged channel list and ~a day of guide, cached on disk so the channel
 * list opens instantly; refreshed on demand and when the cache is older than six hours.
 */
class LiveTv(private val context: Context) {
    private val prefs = context.getSharedPreferences("live_tv", Context.MODE_PRIVATE)
    private val cache = File(context.filesDir, "livetv.json")

    var sources by mutableStateOf(loadSources())
        private set
    var channels by mutableStateOf<List<Channel>>(emptyList())
        private set
    /** Programmes by channel id, sorted by start. */
    var guide by mutableStateOf<Map<String, List<Programme>>>(emptyMap())
        private set
    var favourites by mutableStateOf(prefs.getStringSet("favourites", emptySet()).orEmpty())
        private set
    var refreshedAt = 0L
        private set
    var lastError by mutableStateOf<String?>(null)
        private set

    init {
        runCatching { readCache() }
    }

    val groups: List<String> get() = channels.mapNotNull { it.group }.distinct()

    fun channel(id: String) = channels.firstOrNull { it.id == id }

    fun nowAndNext(channelId: String, now: Long = System.currentTimeMillis()): Pair<Programme?, Programme?> {
        val list = guide[channelId].orEmpty()
        val i = list.indexOfFirst { it.isOn(now) }
        return if (i >= 0) list[i] to list.getOrNull(i + 1) else null to list.firstOrNull { it.startMs > now }
    }

    fun addSource(source: LiveSource) {
        sources = sources.filter { it.id != source.id } + source
        saveSources()
    }

    fun removeSource(id: String) {
        sources = sources.filter { it.id != id }
        saveSources()
        channels = channels.filter { it.sourceId != id }
        writeCache()
    }

    fun toggleFavourite(channelId: String) {
        favourites = if (channelId in favourites) favourites - channelId else favourites + channelId
        prefs.edit { putStringSet("favourites", favourites) }
    }

    var lastChannel: String?
        get() = prefs.getString("last_channel", null)
        set(value) = prefs.edit { putString("last_channel", value) }

    val isStale get() = System.currentTimeMillis() - refreshedAt > 6 * 3_600_000L

    /** Re-reads every source and guide; keeps what worked if some fail. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val from = now - 2 * 3_600_000L
        val to = now + 26 * 3_600_000L
        val allChannels = mutableListOf<Channel>()
        val allGuide = mutableMapOf<String, MutableList<Programme>>()
        val errors = mutableListOf<String>()
        for (source in sources) {
            runCatching {
                when (source.kind) {
                    SourceKind.M3U -> {
                        val playlist = M3u.parse(readText(source.address), source.id)
                        allChannels += playlist.channels
                        (source.guideUrl ?: playlist.guideUrl)?.let { url ->
                            runCatching {
                                val parsed = open(url).use { Xmltv.parse(it, from, to) }
                                matchGuide(playlist.channels, parsed).forEach { (id, list) -> allGuide.getOrPut(id) { mutableListOf() } += list }
                            }.onFailure { errors += "${source.name} guide: ${it.message}" }
                        }
                    }
                    SourceKind.MEDIA_SERVER -> {
                        val server = SineApp.instance.remoteServers.get(source.address) ?: error("server removed")
                        val deviceId = SineApp.instance.settings.deviceId()
                        if (server.kind == com.sine.player.data.ServerKind.PLEX) {
                            val plex = com.sine.player.mediaserver.PlexLive(server, deviceId)
                            val list = plex.channels(source.id)
                            runCatching {
                                // Plex guide entries name the channel by identifier (or number): match on guideId.
                                val byGuide = plex.guide(from, to).groupBy { it.channel }
                                list.forEach { c -> byGuide[c.guideId]?.let { allGuide.getOrPut(c.id) { mutableListOf() } += it } }
                            }.onFailure { errors += "${source.name} guide: ${it.message}" }
                            allChannels += list
                        } else {
                            val client = com.sine.player.mediaserver.JellyfinClient(server, deviceId)
                            val list = client.liveChannels(source.id)
                            runCatching {
                                client.livePrograms(from, to).groupBy { it.channel }.forEach { (channelId, p) ->
                                    allGuide.getOrPut("${source.id}:$channelId") { mutableListOf() } += p
                                }
                            }.onFailure { errors += "${source.name} guide: ${it.message}" }
                            allChannels += list
                        }
                    }
                    SourceKind.HDHOMERUN -> {
                        val device = HdHomeRun.device(source.address)
                        var list = HdHomeRun.channels(device, source.id)
                        runCatching {
                            val (logos, programmes) = HdHomeRun.guide(device)
                            list = list.map { c -> c.copy(logo = logos[c.number]) }
                            programmes.filter { it.endMs > from && it.startMs < to }.groupBy { it.channel }.forEach { (number, p) ->
                                allGuide.getOrPut("${source.id}:$number") { mutableListOf() } += p
                            }
                        }.onFailure { errors += "${source.name} guide: ${it.message}" }
                        allChannels += list
                    }
                }
            }.onFailure {
                errors += "${source.name}: ${it.message}"
                allChannels += channels.filter { c -> c.sourceId == source.id }
            }
        }
        channels = allChannels
        registerHeaders()
        guide = allGuide.mapValues { (_, list) -> list.sortedBy { it.startMs }.distinctBy { it.startMs } }
        refreshedAt = now
        lastError = errors.firstOrNull()?.let { if (errors.size > 1) "$it (and ${errors.size - 1} more)" else it }
        writeCache()
    }

    private fun registerHeaders() {
        com.sine.player.net.HostHeaders.clear()
        channels.forEach { com.sine.player.net.HostHeaders.register(it.url, it.headers) }
    }

    /** XMLTV ids first, then display names (so playlists without tvg-id still get a guide). */
    private fun matchGuide(channels: List<Channel>, parsed: Xmltv.Guide): Map<String, List<Programme>> {
        val byGuideId = parsed.programmes.groupBy { it.channel }
        val idByName = parsed.names.flatMap { (id, names) -> names.map { Xmltv.normalize(it) to id } }.toMap()
        return channels.mapNotNull { c ->
            val id = c.guideId?.takeIf { it in byGuideId } ?: idByName[Xmltv.normalize(c.name)] ?: return@mapNotNull null
            byGuideId[id]?.let { c.id to it }
        }.toMap()
    }

    private fun open(address: String): InputStream {
        val raw: InputStream = when {
            address.startsWith("http://") || address.startsWith("https://") ->
                Net.execute(Request.Builder().url(address).build()).body?.byteStream() ?: error("Empty response")
            address.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(address)) ?: error("Can't open $address")
            else -> File(Uri.parse(address).path ?: address).inputStream()
        }
        val buffered = raw.buffered()
        buffered.mark(2)
        val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
        buffered.reset()
        return if (gzip) GZIPInputStream(buffered) else buffered
    }

    private fun readText(address: String) = open(address).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun loadSources(): List<LiveSource> = runCatching {
        val array = JSONArray(prefs.getString("sources", "[]"))
        (0 until array.length()).map { LiveSource.fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun saveSources() = prefs.edit { putString("sources", JSONArray(sources.map { it.toJson() }).toString()) }

    private fun writeCache() {
        val root = JSONObject().put("at", refreshedAt)
        root.put("channels", JSONArray(channels.map { c ->
            JSONObject().put("id", c.id).put("name", c.name).put("number", c.number ?: "").put("group", c.group ?: "")
                .put("logo", c.logo ?: "").put("url", c.url).put("guideId", c.guideId ?: "").put("source", c.sourceId)
                .put("headers", JSONObject(c.headers))
        }))
        val g = JSONObject()
        guide.forEach { (id, list) ->
            g.put(id, JSONArray(list.map { p ->
                JSONObject().put("s", p.startMs).put("e", p.endMs).put("t", p.title).put("st", p.subtitle ?: "").put("d", p.description ?: "")
            }))
        }
        root.put("guide", g)
        runCatching { cache.writeText(root.toString()) }
    }

    private fun readCache() {
        if (!cache.exists()) return
        val root = JSONObject(cache.readText())
        refreshedAt = root.optLong("at")
        val array = root.getJSONArray("channels")
        channels = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Channel(
                o.getString("id"), o.getString("name"), o.optString("number").ifEmpty { null }, o.optString("group").ifEmpty { null },
                o.optString("logo").ifEmpty { null }, o.getString("url"), o.optString("guideId").ifEmpty { null }, o.getString("source"),
                o.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.getString(it) } }.orEmpty(),
            )
        }
        registerHeaders()
        val g = root.optJSONObject("guide") ?: return
        val cutoff = System.currentTimeMillis() - 3_600_000L
        guide = g.keys().asSequence().associateWith { id ->
            val list = g.getJSONArray(id)
            (0 until list.length()).map { i ->
                val o = list.getJSONObject(i)
                Programme(id, o.getLong("s"), o.getLong("e"), o.getString("t"), o.optString("st").ifEmpty { null }, o.optString("d").ifEmpty { null })
            }.filter { it.endMs > cutoff }
        }
    }

    // region Browsing (livetv:// URIs), so the player's playlist is the channel list

    fun entries(folder: Uri): List<MediaEntry> {
        val group = folder.getQueryParameter("group")
        val list = when {
            folder.getQueryParameter("fav") == "1" -> channels.filter { it.id in favourites }
            group != null -> channels.filter { it.group == group }
            else -> channels
        }
        return list.map(::entry)
    }

    fun entry(channel: Channel) = MediaEntry(
        name = listOfNotNull(channel.number, channel.name).joinToString(" "),
        uri = uriFor(channel),
        isDirectory = false,
        playUri = channel.url,
        playable = true,
    )

    fun resolve(uri: String): MediaEntry? = channelFor(uri)?.let(::entry)

    fun channelFor(uri: String): Channel? = Uri.parse(uri).takeIf { it.scheme == SCHEME && it.host == "ch" }?.lastPathSegment?.let(::channel)

    // endregion

    companion object {
        const val SCHEME = "livetv"

        fun uriFor(channel: Channel): String = "$SCHEME://ch/${Uri.encode(channel.id)}"

        fun folderFor(group: String?, favourites: Boolean): String = when {
            favourites -> "$SCHEME://channels?fav=1"
            group != null -> "$SCHEME://channels?group=${Uri.encode(group)}"
            else -> "$SCHEME://channels"
        }

        val instance: LiveTv get() = SineApp.instance.liveTv
    }
}
