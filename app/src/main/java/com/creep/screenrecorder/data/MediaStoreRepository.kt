package com.creep.screenrecorder.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.util.Locale

internal enum class CaptureKind(val folder: String, val isVideo: Boolean, val displayPrefix: String) {
    SCREENSHOT("Pictures/ScreenCapture", false, "ScreenCapture"),
    SCREEN_RECORDING("Movies/ScreenCapture", true, "ScreenCapture"),
    CAMERA_RECORDING("Movies/CameraCapture", true, "CameraCapture")
}

internal fun CaptureKind.mimeType(): String = if (isVideo) "video/mp4" else "image/png"

/** Maps a file name back to the capture that produced it. */
internal fun captureKindForFileName(name: String): CaptureKind? {
    val lower = name.lowercase(Locale.US)
    return when {
        !lower.contains('.') -> null
        lower.startsWith("screencapture") && lower.endsWith(".png") -> CaptureKind.SCREENSHOT
        lower.startsWith("screencapture") && lower.endsWith(".mp4") -> CaptureKind.SCREEN_RECORDING
        lower.startsWith("cameracapture") && lower.endsWith(".mp4") -> CaptureKind.CAMERA_RECORDING
        else -> null
    }
}

internal data class MediaCapture(
    val uri: Uri,
    val title: String,
    val kind: CaptureKind,
    val dateAddedSeconds: Long,
    val sizeBytes: Long,
    val durationMillis: Long = 0L,
)

/** MediaStore helpers for ScreenKit-owned captures only; no broad media-library access is needed. */
internal object MediaStoreRepository {
    /** Creates a pending MediaStore row, optionally on a specific storage volume (SD card). */
    fun insertPending(
        context: Context,
        kind: CaptureKind,
        displayName: String,
        volumeName: String?,
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, kind.mimeType())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, kind.folder + "/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                if (!volumeName.isNullOrBlank()) {
                    put(MediaStore.MediaColumns.VOLUME_NAME, volumeName)
                }
            } else {
                val directory = LegacyStorage.publicDirectory(kind)
                if (!directory.exists() && !directory.mkdirs()) return null
                put(MediaStore.MediaColumns.DATA, java.io.File(directory, displayName).absolutePath)
            }
        }
        return runCatching { context.contentResolver.insert(collection(kind), values) }.getOrNull()
    }

    /** Values for CameraX's MediaStoreOutputOptions; CameraX owns the insert/finalize lifecycle. */
    fun cameraOutputValues(context: Context, kind: CaptureKind, displayName: String, volumeName: String?): ContentValues {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, kind.folder + "/")
                if (!volumeName.isNullOrBlank()) {
                    put(MediaStore.MediaColumns.VOLUME_NAME, volumeName)
                }
            } else {
                val directory = LegacyStorage.publicDirectory(kind)
                if (!directory.exists()) directory.mkdirs()
                put(MediaStore.MediaColumns.DATA, java.io.File(directory, displayName).absolutePath)
            }
        }
        return values
    }

    fun displayName(kind: CaptureKind, extension: String): String {
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
            .format(java.util.Date())
        return "${kind.displayPrefix}_$timestamp$extension"
    }

    fun delete(context: Context, uri: Uri?) {
        if (uri == null) return
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    /** Library rows: ScreenKit's own MediaStore items plus files in a user-picked folder. */
    fun queryRecent(context: Context, limit: Int = 8): List<MediaCapture> {
        val captures = mutableListOf<MediaCapture>()
        queryCollection(context, CaptureKind.SCREENSHOT, captures)
        queryCollection(context, CaptureKind.SCREEN_RECORDING, captures)
        queryCollection(context, CaptureKind.CAMERA_RECORDING, captures)
        captures += queryCustomFolder(context)
        return captures.sortedByDescending { it.dateAddedSeconds }.take(limit)
    }

    fun collection(kind: CaptureKind): Uri = if (kind.isVideo) {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    private fun queryCollection(
        context: Context,
        kind: CaptureKind,
        output: MutableList<MediaCapture>,
    ) {
        val collection = collection(kind)
        val columns = if (kind.isVideo) {
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.SIZE,
                MediaStore.Video.VideoColumns.DURATION,
            )
        } else {
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.SIZE,
            )
        }

        val selection: String
        val selectionArgs: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                "${MediaStore.MediaColumns.IS_PENDING} = 0"
            selectionArgs = arrayOf(kind.folder + "/")
        } else {
            val prefix = "${kind.displayPrefix}_%"
            selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            selectionArgs = arrayOf(prefix)
        }

        try {
            context.contentResolver.query(
                collection,
                columns,
                selection,
                selectionArgs,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val durationColumn = if (kind.isVideo) {
                    cursor.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION)
                } else -1
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn).orEmpty()
                    val title = name.substringBeforeLast('.', name)
                    val uri = ContentUris.withAppendedId(collection, id)
                    output += MediaCapture(
                        uri = uri,
                        title = title,
                        kind = kind,
                        dateAddedSeconds = cursor.getLong(dateColumn),
                        sizeBytes = if (cursor.isNull(sizeColumn)) 0L else cursor.getLong(sizeColumn),
                        durationMillis = if (durationColumn >= 0 && !cursor.isNull(durationColumn)) {
                            cursor.getLong(durationColumn)
                        } else 0L,
                    )
                }
            }
        } catch (_: SecurityException) {
            // A permission denial should only hide the optional library view, never block capture.
        } catch (_: IllegalArgumentException) {
            // Some OEM MediaStore implementations reject unsupported query columns.
        }
    }

    /** Lists captures written into a user-picked folder (SAF), which MediaStore does not index. */
    private fun queryCustomFolder(context: Context): List<MediaCapture> {
        if (SettingsStore.saveMode(context) != SaveLocationMode.CUSTOM_FOLDER) return emptyList()
        val tree = SettingsStore.treeUri(context) ?: return emptyList()
        val root = runCatching { DocumentFile.fromTreeUri(context, tree) }.getOrNull() ?: return emptyList()
        val children = runCatching { root.listFiles() }.getOrNull() ?: return emptyList()
        return children.mapNotNull { document ->
            val name = document.name ?: return@mapNotNull null
            val kind = captureKindForFileName(name) ?: return@mapNotNull null
            if (!document.isFile) return@mapNotNull null
            val modified = document.lastModified()
            MediaCapture(
                uri = document.uri,
                title = name.substringBeforeLast('.', name),
                kind = kind,
                dateAddedSeconds = if (modified > 0L) modified / 1000L
                    else System.currentTimeMillis() / 1000L,
                sizeBytes = runCatching { document.length() }.getOrDefault(0L),
            )
        }
    }
}
