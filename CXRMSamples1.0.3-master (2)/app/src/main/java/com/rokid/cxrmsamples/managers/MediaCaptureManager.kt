package com.rokid.cxrmsamples.managers

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rokid.cxrmsamples.utils.MediaPathProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MediaType {
    PHOTO,
    VIDEO
}

data class CapturedMedia(
    val id: String,
    val type: MediaType,
    val timestamp: Long,
    val filePath: String? = null,
    val mimeType: String? = null,
    val note: String? = null
)

class MediaCaptureManager private constructor(private val context: Context) {
    private val TAG = "MediaCaptureManager"
    private val gson = Gson()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val syncVideoDir = MediaPathProvider.getRootPath(context)

    private val _mediaList = MutableStateFlow<List<CapturedMedia>>(emptyList())
    val mediaList: StateFlow<List<CapturedMedia>> = _mediaList.asStateFlow()

    init {
        loadFromPrefs()
    }

    fun addPhotoFromBase64(base64: String, note: String? = null): CapturedMedia? {
        return try {
            val clean = base64.substringAfter(",", base64)
            val bytes = Base64.decode(clean, Base64.DEFAULT)
            addPhotoFromBytes(bytes, "jpg", "image/jpeg", note)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode base64 photo", e)
            null
        }
    }

    fun addPhotoFromBytes(
        bytes: ByteArray,
        extension: String,
        mimeType: String,
        note: String? = null
    ): CapturedMedia? {
        return try {
            val file = saveToFile(bytes, "photo", extension)
            val media = CapturedMedia(
                id = UUID.randomUUID().toString(),
                type = MediaType.PHOTO,
                timestamp = System.currentTimeMillis(),
                filePath = file.absolutePath,
                mimeType = mimeType,
                note = note
            )
            addMedia(media)
            media
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save photo", e)
            null
        }
    }

    fun addVideoRecord(note: String? = null, filePath: String? = null): CapturedMedia {
        val finalPath = filePath ?: findLatestVideoFile()
        val media = CapturedMedia(
            id = UUID.randomUUID().toString(),
            type = MediaType.VIDEO,
            timestamp = System.currentTimeMillis(),
            filePath = finalPath,
            mimeType = finalPath?.let { guessVideoMimeType(it) },
            note = note
        )
        addMedia(media)
        return media
    }

    private fun addMedia(media: CapturedMedia) {
        val current = _mediaList.value.toMutableList()
        current.add(0, media)
        _mediaList.value = current
        persist()
    }

    private fun saveToFile(bytes: ByteArray, prefix: String, extension: String): File {
        val dir = File(MediaPathProvider.getRootPath(context))
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val file = File(dir, "${prefix}_${System.currentTimeMillis()}.$extension")
        file.writeBytes(bytes)
        return file
    }

    private fun persist() {
        try {
            prefs.edit().putString(KEY_MEDIA_LIST, gson.toJson(_mediaList.value)).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist media list", e)
        }
    }

    private fun loadFromPrefs() {
        try {
            val json = prefs.getString(KEY_MEDIA_LIST, null) ?: return
            val type = object : TypeToken<List<CapturedMedia>>() {}.type
            val list: List<CapturedMedia> = gson.fromJson(json, type) ?: emptyList()
            _mediaList.value = list
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load media list", e)
        }
    }

    private fun findLatestVideoFile(): String? {
        return try {
            val dir = File(syncVideoDir)
            if (!dir.exists() || !dir.isDirectory) return null
            val candidates = dir.listFiles { file ->
                file.isFile && (file.name.endsWith(".mp4", true) || file.name.endsWith(".webm", true))
            }?.toList().orEmpty()
            val latest = candidates.maxByOrNull { it.lastModified() }
            latest?.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find latest video file", e)
            null
        }
    }

    private fun guessVideoMimeType(path: String): String {
        return if (path.endsWith(".webm", true)) "video/webm" else "video/mp4"
    }

    companion object {
        private const val PREFS_NAME = "media_capture"
        private const val KEY_MEDIA_LIST = "media_list"

        @Volatile
        private var instance: MediaCaptureManager? = null

        fun initialize(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = MediaCaptureManager(context.applicationContext)
                    }
                }
            }
        }

        fun getInstance(context: Context): MediaCaptureManager {
            return instance ?: MediaCaptureManager(context.applicationContext).also { instance = it }
        }
    }
}
