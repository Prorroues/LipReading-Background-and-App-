package com.rokid.cxrmsamples.utils

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.rokid.cxrmsamples.managers.MediaCaptureManager
import java.io.File

/**
 * 把眼镜同步下来的视频立刻写入系统相册，避免只躺在 Download 目录里几天后才被扫描到。
 */
object GalleryPublisher {
    private const val TAG = "GalleryPublisher"
    private const val ALBUM_DIR = "Rokid"

    fun publishVideo(context: Context, file: File) {
        if (!file.exists() || file.length() < 1024L) return
        try {
            MediaCaptureManager.getInstance(context).addVideoIfAbsent(file.absolutePath, "眼镜同步")
        } catch (e: Exception) {
            Log.w(TAG, "写入 App 相册失败", e)
        }
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf(mimeTypeOf(file.name)),
                null
            )
        } catch (e: Exception) {
            Log.w(TAG, "MediaScanner 扫描失败", e)
        }
    }

    private fun insertIntoMediaStore(context: Context, file: File): Uri? {
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val displayName = file.name
        if (alreadyInStore(context, displayName)) {
            Log.d(TAG, "系统相册已有同名视频: $displayName")
            return null
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, mimeTypeOf(displayName))
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_TAKEN, file.lastModified())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/$ALBUM_DIR")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: run {
            resolver.delete(uri, null, null)
            return null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
        Log.d(TAG, "已写入系统相册: $uri")
        return uri
    }

    private fun alreadyInStore(context: Context, displayName: String): Boolean {
        val projection = arrayOf(MediaStore.Video.Media._ID)
        val selection = "${MediaStore.Video.Media.DISPLAY_NAME}=?"
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            arrayOf(displayName),
            null
        )?.use { cursor ->
            return cursor.moveToFirst()
        }
        return false
    }

    private fun mimeTypeOf(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".mov") -> "video/quicktime"
            else -> "video/mp4"
        }
    }
}
