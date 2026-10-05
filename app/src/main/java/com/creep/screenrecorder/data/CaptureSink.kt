package com.creep.screenrecorder.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.OutputStream

/**
 * A single capture destination.
 *
 * MediaStore-backed sinks are written directly, so the system gallery indexes the file. Folder
 * sinks (a user-picked SAF folder, e.g. on an SD card) are written to a cache file first and
 * copied on [publish], because MediaRecorder/MediaMuxer need a seekable file to finish an MP4.
 */
internal class CaptureSink private constructor(
    val kind: CaptureKind,
    val displayName: String,
    private val mediaUri: Uri?,
    private val treeUri: Uri?,
    private val tempFile: File?,
) {
    var publishedUri: Uri? = null
        private set

    val isMediaStore: Boolean get() = mediaUri != null

    /** Cache file the caller writes to when this sink is folder-backed. */
    val tempTarget: File? get() = tempFile

    fun openDescriptorForWrite(context: Context): ParcelFileDescriptor? {
        val uri = mediaUri ?: return null
        return runCatching { context.contentResolver.openFileDescriptor(uri, "w") }.getOrNull()
    }

    fun openTempDescriptor(): ParcelFileDescriptor? {
        val file = tempFile ?: return null
        file.parentFile?.mkdirs()
        return runCatching {
            ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_READ_WRITE or
                    ParcelFileDescriptor.MODE_TRUNCATE,
            )
        }.getOrNull()
    }

    fun openStreamForWrite(context: Context): OutputStream? {
        val uri = mediaUri
        if (uri != null) {
            return runCatching { context.contentResolver.openOutputStream(uri, "w") }.getOrNull()
        }
        val file = tempFile ?: return null
        file.parentFile?.mkdirs()
        return runCatching { file.outputStream() }.getOrNull()
    }

    /** Finishes the capture and returns the URI other apps can open. */
    fun publish(context: Context): Uri? {
        publishedUri?.let { return it }
        val uri = mediaUri
        if (uri != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cleared = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                val updated = runCatching {
                    context.contentResolver.update(uri, cleared, null, null) > 0
                }.getOrDefault(false)
                if (!updated) return null
            }
            publishedUri = uri
            return uri
        }

        val source = tempFile ?: return null
        val tree = treeUri ?: return null
        if (!source.exists() || source.length() <= 0L) return null
        val mimeType = kind.mimeType()
        val document = runCatching {
            DocumentFile.fromTreeUri(context, tree)?.createFile(mimeType, displayName)
        }.getOrNull() ?: return null

        val copied = runCatching {
            val output = context.contentResolver.openOutputStream(document.uri, "w")
                ?: error("No output stream for the chosen folder.")
            output.use { stream ->
                source.inputStream().use { input -> input.copyTo(stream) }
                stream.flush()
            }
            true
        }.getOrDefault(false)

        if (!copied) {
            runCatching { document.delete() }
            return null
        }
        source.delete()
        publishedUri = document.uri
        return document.uri
    }

    /** Removes everything this sink created; safe to call at any point. */
    fun discard(context: Context) {
        val finished = publishedUri
        if (finished != null && treeUri != null) {
            runCatching { DocumentFile.fromSingleUri(context, finished)?.delete() }
        } else {
            val uri = finished ?: mediaUri
            if (uri != null) runCatching { context.contentResolver.delete(uri, null, null) }
        }
        runCatching { tempFile?.delete() }
        publishedUri = null
    }

    companion object {
        fun create(context: Context, kind: CaptureKind, extension: String): CaptureSink? {
            val displayName = newDisplayName(kind, extension)
            val mode = SettingsStore.saveMode(context)
            // A folder can disappear (card removed, grant revoked). Saving to the gallery is a
            // better outcome than losing the capture.
            val effectiveMode = if (mode == SaveLocationMode.CUSTOM_FOLDER &&
                SettingsStore.treeUri(context) == null
            ) {
                SaveLocationMode.MEDIA_DEFAULT
            } else {
                mode
            }
            return when (effectiveMode) {
                SaveLocationMode.MEDIA_DEFAULT -> {
                    val uri = MediaStoreRepository.insertPending(context, kind, displayName, null)
                        ?: return null
                    CaptureSink(kind, displayName, uri, null, null)
                }
                SaveLocationMode.MEDIA_VOLUME -> {
                    val volume = SettingsStore.mediaVolume(context)
                    val uri = MediaStoreRepository.insertPending(context, kind, displayName, volume)
                        ?: return null
                    CaptureSink(kind, displayName, uri, null, null)
                }
                SaveLocationMode.CUSTOM_FOLDER -> {
                    val tree = SettingsStore.treeUri(context) ?: return null
                    val file = File(cacheDirectory(context), displayName)
                    CaptureSink(kind, displayName, null, tree, file)
                }
            }
        }

        /** Wraps a file the camera recorder already wrote, so it can be copied into the folder. */
        fun forCompletedFile(context: Context, kind: CaptureKind, file: File): CaptureSink? {
            val tree = SettingsStore.treeUri(context) ?: return null
            if (!file.exists()) return null
            return CaptureSink(kind, file.name, null, tree, file)
        }

        fun cacheDirectory(context: Context): File =
            context.externalCacheDir ?: context.cacheDir

        private fun newDisplayName(kind: CaptureKind, extension: String): String {
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US)
                .format(java.util.Date())
            return "${kind.displayPrefix}_$timestamp$extension"
        }
    }
}

/** Legacy public folder roots, retained for Android 9 and older. */
internal object LegacyStorage {
    fun publicDirectory(kind: CaptureKind): File {
        val base = if (kind.isVideo) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        } else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        }
        return File(base, kind.folder.substringAfter('/'))
    }
}
