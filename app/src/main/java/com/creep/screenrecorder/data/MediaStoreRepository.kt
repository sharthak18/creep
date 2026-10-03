package com.creep.screenrecorder.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class CaptureKind(val folder: String, val isVideo: Boolean) {
    SCREENSHOT("Pictures/ScreenCapture", false),
    SCREEN_RECORDING("Movies/ScreenCapture", true),
    CAMERA_RECORDING("Movies/CameraCapture", true)
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
    fun createPending(context: Context, kind: CaptureKind, extension: String): Uri? {
        val displayName = newName(kind, extension)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, if (kind.isVideo) "video/mp4" else "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, kind.folder + "/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val base = if (kind.isVideo) {
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                } else {
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                }
                val directory = File(base, kind.folder.substringAfter('/'))
                if (!directory.exists() && !directory.mkdirs()) return null
                put(MediaStore.MediaColumns.DATA,
                    File(directory, displayName).absolutePath)
            }
        }
        return runCatching {
            context.contentResolver.insert(collection(kind), values)
        }.getOrNull()
    }

    /** Values for CameraX's MediaStoreOutputOptions. CameraX owns the insert/finalize lifecycle. */
    fun cameraOutputValues(context: Context, kind: CaptureKind): ContentValues {
        val displayName = newName(kind, ".mp4")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, kind.folder + "/")
            } else {
                val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val directory = File(base, kind.folder.substringAfter('/'))
                if (!directory.exists()) directory.mkdirs()
                put(MediaStore.MediaColumns.DATA,
                    File(directory, displayName).absolutePath)
            }
        }
        return values
    }

    fun publish(context: Context, uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return runCatching {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null) > 0
        }.getOrDefault(false)
    }

    fun delete(context: Context, uri: Uri?) {
        if (uri == null) return
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    fun queryRecent(context: Context, limit: Int = 8): List<MediaCapture> {
        val captures = mutableListOf<MediaCapture>()
        queryCollection(context, CaptureKind.SCREENSHOT, captures)
        queryCollection(context, CaptureKind.SCREEN_RECORDING, captures)
        queryCollection(context, CaptureKind.CAMERA_RECORDING, captures)
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
            val prefix = if (kind == CaptureKind.CAMERA_RECORDING) "CameraCapture_%"
                else if (kind == CaptureKind.SCREENSHOT) "ScreenCapture_%"
                else "ScreenCapture_%"
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

    private fun newName(kind: CaptureKind, extension: String): String {
        val prefix = when (kind) {
            CaptureKind.SCREENSHOT, CaptureKind.SCREEN_RECORDING -> "ScreenCapture"
            CaptureKind.CAMERA_RECORDING -> "CameraCapture"
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return "${prefix}_${timestamp}${extension}"
    }
}
