package io.github.superthom196.matv.ma

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Data shapes for the Music Assistant WebSocket API.
 *
 * Field names come from the server's own /api-docs/schemas.json (MA 2.10.1, schema 65).
 * Everything is tolerant: unknown keys are ignored and almost every field is nullable, so a
 * server upgrade that adds or drops fields degrades gracefully instead of crashing.
 */
val maJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
data class ServerInfo(
    @SerialName("server_id") val serverId: String,
    @SerialName("server_version") val serverVersion: String = "?",
    @SerialName("schema_version") val schemaVersion: Int = 0,
    @SerialName("min_supported_schema_version") val minSupportedSchemaVersion: Int = 0,
    @SerialName("base_url") val baseUrl: String? = null,
    val name: String? = null,
    @SerialName("onboard_done") val onboardDone: Boolean? = null,
)

@Serializable
data class MediaItemImage(
    val type: String? = null,
    val path: String? = null,
    val provider: String? = null,
    @SerialName("remotely_accessible") val remotelyAccessible: Boolean = false,
    @SerialName("proxy_id") val proxyId: String? = null,
)

@Serializable
data class MediaItemMetadata(
    val images: List<MediaItemImage>? = null,
    val description: String? = null,
)

/** Lightweight reference used inside other items (album.artists, track.album, ...). */
@Serializable
data class ItemMapping(
    @SerialName("item_id") val itemId: String,
    val provider: String,
    val name: String = "",
    val uri: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    val image: MediaItemImage? = null,
    val year: Int? = null,
    val available: Boolean = true,
)

/**
 * One class covers artist, album, playlist and track: the fields differ, but all are optional
 * and the browse UI only needs a common subset. `mediaType` tells them apart.
 */
@Serializable
data class MediaItem(
    @SerialName("item_id") val itemId: String,
    val provider: String,
    val name: String = "",
    val version: String? = null,
    @SerialName("sort_name") val sortName: String? = null,
    val uri: String? = null,
    @SerialName("media_type") val mediaType: String = "unknown",
    @SerialName("is_playable") val isPlayable: Boolean = true,
    val metadata: MediaItemMetadata? = null,
    val favorite: Boolean = false,
    // album / track
    val year: Int? = null,
    val artists: List<ItemMapping>? = null,
    @SerialName("album_type") val albumType: String? = null,
    // track
    val duration: Double? = null,
    val album: ItemMapping? = null,
    @SerialName("track_number") val trackNumber: Int? = null,
    @SerialName("disc_number") val discNumber: Int? = null,
    // playlist
    val owner: String? = null,
    // artist
    @SerialName("artist_type") val artistType: String? = null,
    // browse folder (music/browse): `path` is what you pass back to browse into it
    val path: String? = null,
    val image: MediaItemImage? = null,
    @SerialName("provider_mappings") val providerMappings: List<ProviderMapping>? = null,
) {
    val artistLine: String get() = artists?.joinToString(", ") { it.name }.orEmpty()

    /**
     * Better than CD off the source file. Only meaningful on tracks: an album's own mapping carries
     * an unfilled placeholder (content_type "?", 16/44.1), so albums must be judged by their tracks.
     */
    val isHiResTrack: Boolean
        get() = providerMappings.orEmpty().any { pm ->
            val f = pm.audioFormat ?: return@any false
            isHiRes(f.bitDepth, f.sampleRate)
        }

    /** The format of this item's own file, when a provider reported one. */
    val audioFormat: AudioFormat? get() = providerMappings.orEmpty().firstNotNullOfOrNull { it.audioFormat }
    val isFolder: Boolean get() = mediaType == "folder"
    val thumb: MediaItemImage? get() = metadata?.images?.firstOrNull { it.type == "thumb" } ?: metadata?.images?.firstOrNull() ?: image
}

@Serializable
data class PlayerMedia(
    val uri: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val duration: Double? = null,
    @SerialName("queue_item_id") val queueItemId: String? = null,
)

@Serializable
data class DeviceInfo(
    val model: String? = null,
    val manufacturer: String? = null,
    @SerialName("ip_address") val ipAddress: String? = null,
)

@Serializable
data class Player(
    @SerialName("player_id") val playerId: String,
    val provider: String = "",
    val type: String = "player",
    val name: String = "",
    val available: Boolean = true,
    val enabled: Boolean = true,
    @SerialName("hide_in_ui") val hideInUi: Boolean = false,
    @SerialName("device_info") val deviceInfo: DeviceInfo? = null,
    @SerialName("playback_state") val playbackState: String = "idle",
    val powered: Boolean? = null,
    @SerialName("volume_level") val volumeLevel: Int? = null,
    @SerialName("volume_muted") val volumeMuted: Boolean? = null,
    @SerialName("group_members") val groupMembers: List<String>? = null,
    @SerialName("synced_to") val syncedTo: String? = null,
    @SerialName("active_source") val activeSource: String? = null,
    @SerialName("active_group") val activeGroup: String? = null,
    @SerialName("current_media") val currentMedia: PlayerMedia? = null,
    @SerialName("elapsed_time") val elapsedTime: Double? = null,
    @SerialName("elapsed_time_last_updated") val elapsedTimeLastUpdated: Double? = null,
    @SerialName("supported_features") val supportedFeatures: List<String>? = null,
) {
    val isPlaying: Boolean get() = playbackState == "playing"
    /** Players a user would choose on the sofa: real endpoints and groups, not helper/virtual types. */
    val isSelectable: Boolean get() = available && enabled && !hideInUi && type in setOf("player", "stereo_pair", "group")
}

@Serializable
data class QueueItem(
    @SerialName("queue_item_id") val queueItemId: String,
    val name: String = "",
    val duration: Double? = null,
    val index: Int? = null,
    val image: MediaItemImage? = null,
    @SerialName("media_item") val mediaItem: MediaItem? = null,
    val streamdetails: StreamDetails? = null,
)

/**
 * What the server is actually streaming. Only the queue's current item carries this — a library
 * album's own `audio_format` is a placeholder the file providers never fill in.
 */
@Serializable
data class StreamDetails(
    @SerialName("audio_format") val audioFormat: AudioFormat? = null,
    @SerialName("audio_processing") val audioProcessing: AudioProcessing? = null,
) {
    /** Music Assistant's own verdict: "hi_res", "lossless", "lossy". */
    val fidelity: String? get() = audioProcessing?.inputFidelity?.quality
}

/** "FLAC 24/44.1" — codec, bit depth and rate, skipping whatever the server did not tell us. */
fun audioFormatLabel(codec: String?, bitDepth: Int?, sampleRateKhz: String?): String {
    val numbers = when {
        bitDepth != null && sampleRateKhz != null -> "$bitDepth/$sampleRateKhz"
        sampleRateKhz != null -> "$sampleRateKhz kHz"
        bitDepth != null -> "$bitDepth-bit"
        else -> null
    }
    val name = codec?.uppercase()?.takeIf { it.isNotBlank() && it != "?" }
    return listOfNotNull(name, numbers).joinToString(" ")
}

/** Better than CD: more than 16 bits, or faster than 48 kHz. */
fun isHiRes(bitDepth: Int?, sampleRate: Int?): Boolean =
    (bitDepth ?: 16) > 16 || (sampleRate ?: 44100) > 48000

@Serializable
data class ProviderMapping(
    @SerialName("item_id") val itemId: String = "",
    @SerialName("provider_domain") val providerDomain: String = "",
    val available: Boolean = true,
    @SerialName("audio_format") val audioFormat: AudioFormat? = null,
)

@Serializable
data class AudioFormat(
    @SerialName("content_type") val contentType: String = "",
    @SerialName("sample_rate") val sampleRate: Int? = null,
    @SerialName("bit_depth") val bitDepth: Int? = null,
    val channels: Int? = null,
    @SerialName("bit_rate") val bitRate: Int? = null,
) {
    /** 44100 -> "44.1", 48000 -> "48", 192000 -> "192". */
    val sampleRateKhz: String? get() = sampleRate?.let {
        val khz = it / 1000.0
        if (khz == khz.toInt().toDouble()) khz.toInt().toString() else String.format("%.1f", khz)
    }
}

@Serializable
data class AudioProcessing(
    @SerialName("input_fidelity") val inputFidelity: InputFidelity? = null,
)

@Serializable
data class InputFidelity(
    val quality: String? = null,
    @SerialName("bit_perfect") val bitPerfect: Boolean? = null,
)

@Serializable
data class PlayerQueue(
    @SerialName("queue_id") val queueId: String,
    @SerialName("display_name") val displayName: String = "",
    val active: Boolean = false,
    val available: Boolean = true,
    val items: Int = 0,
    @SerialName("shuffle_enabled") val shuffleEnabled: Boolean = false,
    @SerialName("repeat_mode") val repeatMode: String = "off",
    @SerialName("current_index") val currentIndex: Int? = null,
    @SerialName("elapsed_time") val elapsedTime: Double = 0.0,
    @SerialName("elapsed_time_last_updated") val elapsedTimeLastUpdated: Double = 0.0,
    val state: String = "idle",
    @SerialName("current_item") val currentItem: QueueItem? = null,
    @SerialName("next_item") val nextItem: QueueItem? = null,
)

/** Helpers for the loosely typed bits of the protocol. */
internal fun JsonElement.stringField(name: String): String? =
    (this as? JsonObject)?.get(name)?.let { it as? JsonPrimitive }?.contentOrNull

internal fun JsonElement.doubleOrNullValue(): Double? = (this as? JsonPrimitive)?.doubleOrNull

/** Library list results are a plain array on current servers, or `{items:[...]}` on older ones. */
internal fun JsonElement.asItemArray(): List<JsonElement> = when (this) {
    is JsonObject -> this["items"]?.jsonArray?.toList() ?: emptyList()
    else -> jsonArray.toList()
}

internal fun JsonElement.findStringDeep(key: String): String? {
    if (this is JsonObject) {
        (this[key] as? JsonPrimitive)?.contentOrNull?.let { return it }
        for (v in values) v.findStringDeep(key)?.let { return it }
    }
    return null
}

internal fun JsonElement.objOrNull(): JsonObject? = this as? JsonObject
