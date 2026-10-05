package com.screenkit.screenrecorder.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import com.screenkit.screenrecorder.CaptureContract

/**
 * What ends up in the audio track of a recording.
 *
 * [MICROPHONE] is handled by MediaRecorder; [DEVICE] and [MIXED] need AudioPlaybackCapture, which
 * means raw PCM mixed by ScreenKit and encoded with MediaCodec (see ScreenRecorderEngine).
 */
internal enum class CaptureAudioMode(
    val id: String,
    val label: String,
    val detail: String,
    val usesMicrophone: Boolean,
    val usesDeviceAudio: Boolean,
) {
    NONE("none", "Off", "Silent video", false, false),
    MICROPHONE("mic", "Microphone", "Your voice and room sound", true, false),
    DEVICE("device", "Device audio", "Sound this phone plays (Android 10+)", false, true),
    MIXED("mixed", "Mic + device", "Commentary over the phone's sound", true, true);

    /** AudioPlaybackCapture needs RECORD_AUDIO even when the mic itself is not recorded. */
    val requiresRecordPermission: Boolean get() = usesMicrophone || usesDeviceAudio

    fun next(): CaptureAudioMode = when (this) {
        NONE -> MICROPHONE
        MICROPHONE -> DEVICE
        DEVICE -> MIXED
        MIXED -> NONE
    }

    companion object {
        fun fromId(id: String?): CaptureAudioMode? = values().firstOrNull { it.id == id }
    }
}

/** Where ScreenKit writes finished files. */
internal enum class SaveLocationMode(val id: String, val label: String, val detail: String) {
    MEDIA_DEFAULT("media", "Device gallery", "Pictures/ScreenCapture and Movies/ScreenCapture"),
    MEDIA_VOLUME("volume", "SD card / volume", "Media library on a chosen storage volume"),
    CUSTOM_FOLDER("folder", "Folder", "Any folder you pick, used as-is");

    companion object {
        fun fromId(id: String?): SaveLocationMode = values().firstOrNull { it.id == id } ?: MEDIA_DEFAULT
    }
}

/** How much of the screen a projection session may capture. */
internal enum class ProjectionScope(val id: String) {
    /** Android 14+: the consent sheet only offers the whole display, so nothing freezes when you switch apps. */
    ENTIRE_SCREEN("entire"),

    /** Android 14+: the consent sheet also offers "a single app", which stops updating outside that app. */
    USER_CHOICE("choice");

    companion object {
        fun fromId(id: String?): ProjectionScope =
            values().firstOrNull { it.id == id } ?: ENTIRE_SCREEN
    }
}

/** Single source of truth for ScreenKit's persisted preferences. */
internal object SettingsStore {
    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(CaptureContract.PREFS_NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- audio

    fun audioMode(context: Context): CaptureAudioMode {
        val stored = prefs(context).getString(CaptureContract.PREF_AUDIO_MODE, null)
        CaptureAudioMode.fromId(stored)?.let { return it }
        // Migrate the original single microphone switch.
        return if (prefs(context).getBoolean(CaptureContract.PREF_MICROPHONE_ENABLED, false)) {
            CaptureAudioMode.MICROPHONE
        } else {
            CaptureAudioMode.NONE
        }
    }

    fun setAudioMode(context: Context, mode: CaptureAudioMode) {
        prefs(context).edit()
            .putString(CaptureContract.PREF_AUDIO_MODE, mode.id)
            .putBoolean(CaptureContract.PREF_MICROPHONE_ENABLED, mode.usesMicrophone)
            .apply()
    }

    fun microphoneEnabled(context: Context): Boolean = audioMode(context).usesMicrophone

    // ---------------------------------------------------------------- camera

    fun cameraFront(context: Context): Boolean =
        prefs(context).getBoolean(CaptureContract.PREF_CAMERA_FRONT, false)

    fun setCameraFront(context: Context, front: Boolean) {
        prefs(context).edit().putBoolean(CaptureContract.PREF_CAMERA_FRONT, front).apply()
    }

    fun cameraOverlayEnabled(context: Context): Boolean =
        prefs(context).getBoolean(CaptureContract.PREF_CAMERA_OVERLAY_ENABLED, false)

    fun setCameraOverlayEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(CaptureContract.PREF_CAMERA_OVERLAY_ENABLED, enabled).apply()
    }

    // ---------------------------------------------------------------- overlay

    fun overlayEnabled(context: Context): Boolean =
        prefs(context).getBoolean(CaptureContract.PREF_OVERLAY_ENABLED, false)

    fun setOverlayEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(CaptureContract.PREF_OVERLAY_ENABLED, enabled).apply()
    }

    fun overlayIntroSeen(context: Context): Boolean =
        prefs(context).getBoolean(CaptureContract.PREF_OVERLAY_INTRO_SEEN, false)

    fun markOverlayIntroSeen(context: Context) {
        prefs(context).edit().putBoolean(CaptureContract.PREF_OVERLAY_INTRO_SEEN, true).apply()
    }

    // ---------------------------------------------------------------- touches

    fun showTouches(context: Context): Boolean =
        prefs(context).getBoolean(CaptureContract.PREF_SHOW_TOUCHES, false)

    fun setShowTouches(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(CaptureContract.PREF_SHOW_TOUCHES, enabled).apply()
    }

    // ---------------------------------------------------------------- projection scope

    fun projectionScope(context: Context): ProjectionScope =
        ProjectionScope.fromId(prefs(context).getString(CaptureContract.PREF_PROJECTION_SCOPE, null))

    fun setProjectionScope(context: Context, scope: ProjectionScope) {
        prefs(context).edit().putString(CaptureContract.PREF_PROJECTION_SCOPE, scope.id).apply()
    }

    // ---------------------------------------------------------------- save location

    fun saveMode(context: Context): SaveLocationMode =
        SaveLocationMode.fromId(prefs(context).getString(CaptureContract.PREF_SAVE_MODE, null))

    fun setSaveMode(context: Context, mode: SaveLocationMode) {
        prefs(context).edit().putString(CaptureContract.PREF_SAVE_MODE, mode.id).apply()
    }

    fun mediaVolume(context: Context): String? =
        prefs(context).getString(CaptureContract.PREF_SAVE_VOLUME, null)?.takeIf { it.isNotBlank() }

    fun setMediaVolume(context: Context, volume: String?) {
        prefs(context).edit().putString(CaptureContract.PREF_SAVE_VOLUME, volume).apply()
    }

    fun treeUri(context: Context): Uri? =
        prefs(context).getString(CaptureContract.PREF_SAVE_TREE_URI, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun folderLabel(context: Context): String? =
        prefs(context).getString(CaptureContract.PREF_SAVE_FOLDER_LABEL, null)?.takeIf { it.isNotBlank() }

    /** Keeps the SAF grant and switches the save location to that folder. */
    fun commitTreeLocation(context: Context, uri: Uri, writeGranted: Boolean): Boolean {
        val flags = IntentFlags.readGrant(writeGranted)
        val persisted = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
            true
        }.getOrDefault(false)
        val label = describeTree(context, uri)
        prefs(context).edit()
            .putString(CaptureContract.PREF_SAVE_TREE_URI, uri.toString())
            .putString(CaptureContract.PREF_SAVE_FOLDER_LABEL, label)
            .putString(CaptureContract.PREF_SAVE_MODE, SaveLocationMode.CUSTOM_FOLDER.id)
            .apply()
        return persisted
    }

    fun describeTree(context: Context, uri: Uri): String {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return uri.toString()
        val volume = documentId.substringBefore(':', documentId)
        val path = documentId.substringAfter(':', "")
        val volumeLabel = if (volume == "primary" || volume == "home") {
            "Device storage"
        } else {
            StorageVolumes.list(context).firstOrNull { it.name == volume }?.label ?: volume
        }
        return if (path.isBlank()) volumeLabel else "$volumeLabel/$path"
    }

    /** Human-readable description of where the next capture will be written. */
    fun describeSaveLocation(context: Context): String = when (saveMode(context)) {
        SaveLocationMode.MEDIA_DEFAULT -> "Pictures/ScreenCapture and Movies/ScreenCapture"
        SaveLocationMode.MEDIA_VOLUME -> {
            val volume = mediaVolume(context)
            val label = StorageVolumes.list(context).firstOrNull { it.name == volume }?.label
            "Media library · ${label ?: volume ?: "secondary storage"}"
        }
        SaveLocationMode.CUSTOM_FOLDER -> folderLabel(context) ?: "your chosen folder"
    }
}

/** Small indirection so [SettingsStore] does not need to import Intent flags directly. */
private object IntentFlags {
    fun readGrant(writeGranted: Boolean): Int =
        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            (if (writeGranted) android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
}

/** Removable and internal volumes that MediaStore can index (Android 10+). */
internal object StorageVolumes {
    data class VolumeChoice(val name: String, val label: String, val removable: Boolean)

    fun list(context: Context): List<VolumeChoice> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            ?: return emptyList()
        val result = mutableListOf<VolumeChoice>()
        manager.storageVolumes.forEach { volume ->
            if (volume.state != Environment.MEDIA_MOUNTED) return@forEach
            val name = volume.mediaStoreVolumeName ?: return@forEach
            if (name.isBlank()) return@forEach
            val description = runCatching { volume.getDescription(context) }.getOrNull() ?: name
            result += VolumeChoice(name, description, volume.isRemovable)
        }
        return result
    }
}
