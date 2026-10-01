package com.rokid.cxrmsamples.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import com.rokid.cxrmsamples.managers.GlobalVideoSyncQueue
import com.rokid.cxrmsamples.managers.GlobalWifiManager
import kotlinx.coroutines.delay
import java.io.File

/**
 * 等待眼镜录像经 WiFi P2P 落到手机后再取文件。
 */
object SyncedVideoFinder {
    private const val TAG = "SyncedVideoFinder"
    private const val MIN_BYTES = 2 * 1024L
    private const val DEFAULT_TIMEOUT_MS = 90_000L
    private const val POLL_MS = 250L
    private const val RESYNC_INTERVAL_MS = 3_000L
    private const val STABLE_CHECK_MS = 250L

    fun isVideoFileName(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".mp4") ||
            lower.endsWith(".mov") ||
            lower.endsWith(".avi") ||
            lower.endsWith(".mkv") ||
            lower.endsWith(".webm") ||
            lower.endsWith(".m4v")
    }

    fun candidateDirs(context: Context, extraPath: String? = null): List<File> {
        val dirs = linkedSetOf<File>()
        extraPath?.trim()?.takeIf { it.isNotEmpty() }?.let { dirs.add(File(it)) }
        dirs.add(MediaPathProvider.getRootDir(context))
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dirs.add(File(downloads, "Rokid/Media"))
        dirs.add(File(downloads, "Rokid"))
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        dirs.add(File(dcim, "Rokid"))
        dirs.add(File(dcim, "Camera"))
        context.getExternalFilesDir(null)?.let { dirs.add(File(it, "Rokid/Media")) }
        return dirs.toList()
    }

    fun findLatestAfter(
        afterMs: Long,
        dirs: List<File>,
        exclude: Set<String> = emptySet()
    ): File? {
        val threshold = afterMs.coerceAtLeast(0L)
        var best: File? = null

        GlobalVideoSyncQueue.getLatestVideoAfter(threshold)?.let { path ->
            val queued = File(path)
            if (isUsableVideo(queued) &&
                queued.lastModified() >= threshold &&
                !isExcluded(queued, exclude)
            ) {
                best = queued
            }
        }

        dirs.forEach { dir ->
            collectRecentVideos(dir, threshold, depth = 0).forEach { file ->
                if (isExcluded(file, exclude)) return@forEach
                val current = best
                if (current == null || file.lastModified() > current.lastModified()) {
                    best = file
                }
            }
        }
        return best
    }

    suspend fun waitForLatestVideo(
        context: Context,
        recordedAfterMs: Long,
        extraDir: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        excludePaths: Collection<String> = emptyList(),
        onProgress: ((String) -> Unit)? = null
    ): File? {
        val dirs = candidateDirs(context, extraDir)
        val waitStartedAt = System.currentTimeMillis()
        val threshold = if (recordedAfterMs > 0L) recordedAfterMs - 2_000L else waitStartedAt - 2_000L
        val exclude = excludePaths.toSet()
        val knownPaths = snapshotVideoPaths(dirs)
        var lastSyncAt = 0L
        var lastProgressSec = -1L

        requestSync("wait-start")

        while (System.currentTimeMillis() - waitStartedAt < timeoutMs) {
            val elapsedSec = (System.currentTimeMillis() - waitStartedAt) / 1000
            if (elapsedSec != lastProgressSec) {
                lastProgressSec = elapsedSec
                onProgress?.invoke("正在从眼镜拉取本次录像（${elapsedSec}s）")
            }

            val candidate = (findLatestAfter(threshold, dirs, exclude)
                ?: findLatestFromMediaStore(context, threshold, exclude))
                ?.takeUnless { isExcluded(it, exclude) }
            if (candidate == null) {
                if (System.currentTimeMillis() - lastSyncAt >= RESYNC_INTERVAL_MS) {
                    lastSyncAt = System.currentTimeMillis()
                    requestSync("wait-poll")
                }
                delay(POLL_MS)
                continue
            }
            val isNewFile = candidate.absolutePath !in knownPaths ||
                candidate.lastModified() >= waitStartedAt - 1_000L
            if (isNewFile && waitUntilStable(candidate)) {
                Log.d(TAG, "找到已落盘视频: ${candidate.absolutePath}, size=${candidate.length()}")
                return candidate
            }

            if (System.currentTimeMillis() - lastSyncAt >= RESYNC_INTERVAL_MS) {
                lastSyncAt = System.currentTimeMillis()
                requestSync("wait-poll")
            }
            delay(POLL_MS)
        }

        val lastChance = (findLatestAfter(threshold, dirs, exclude)
            ?: findLatestFromMediaStore(context, threshold, exclude))
            ?.takeUnless { isExcluded(it, exclude) }
        if (lastChance != null && lastChance.exists() && lastChance.length() >= MIN_BYTES) {
            return lastChance
        }
        Log.w(TAG, "超时未找到录像，已检查: ${dirs.joinToString { it.absolutePath }}")
        return null
    }

    suspend fun waitUntilPathReady(path: String, timeoutMs: Long = 4_000L): String? {
        val start = System.currentTimeMillis()
        val file = File(path)
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (file.exists() && waitUntilStable(file)) {
                return file.absolutePath
            }
            delay(80)
        }
        return if (file.exists() && file.length() >= MIN_BYTES) file.absolutePath else null
    }

    private fun snapshotVideoPaths(dirs: List<File>): Set<String> {
        val paths = mutableSetOf<String>()
        dirs.forEach { dir ->
            collectRecentVideos(dir, threshold = 0L, depth = 0).forEach { paths.add(it.absolutePath) }
        }
        GlobalVideoSyncQueue.getAllVideos().forEach { paths.add(it) }
        return paths
    }

    private fun isExcluded(file: File, exclude: Set<String>): Boolean {
        if (exclude.isEmpty()) return false
        return file.absolutePath in exclude || file.name in exclude
    }

    private fun findLatestFromMediaStore(
        context: Context,
        afterMs: Long,
        exclude: Set<String> = emptySet()
    ): File? {
        return try {
            val projection = arrayOf(
                android.provider.MediaStore.Video.Media.DATA,
                android.provider.MediaStore.Video.Media.DATE_ADDED,
                android.provider.MediaStore.Video.Media.DATE_TAKEN,
                android.provider.MediaStore.Video.Media.DISPLAY_NAME
            )
            val sort = "${android.provider.MediaStore.Video.Media.DATE_ADDED} DESC"
            context.contentResolver.query(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sort
            )?.use { cursor ->
                val dataIdx = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.DATA)
                val addedIdx = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.DATE_ADDED)
                val takenIdx = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.DATE_TAKEN)
                val nameIdx = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val path = if (dataIdx >= 0) cursor.getString(dataIdx) else null
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                    if (path.isNullOrBlank() || !isVideoFileName(name.ifBlank { path })) continue
                    val file = File(path)
                    if (isExcluded(file, exclude)) continue
                    val addedMs = if (addedIdx >= 0) cursor.getLong(addedIdx) * 1000L else 0L
                    val takenMs = if (takenIdx >= 0) cursor.getLong(takenIdx) else 0L
                    val whenMs = maxOf(file.lastModified(), addedMs, takenMs)
                    if (whenMs >= afterMs && isUsableVideo(file)) {
                        return file
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "查询系统相册失败", e)
            null
        }
    }

    private fun isUsableVideo(file: File): Boolean {
        return file.exists() && file.isFile && isVideoFileName(file.name) && file.length() >= MIN_BYTES
    }

    private fun collectRecentVideos(dir: File, threshold: Long, depth: Int): List<File> {
        if (depth > 2 || !dir.exists() || !dir.isDirectory) return emptyList()
        val found = mutableListOf<File>()
        val children = dir.listFiles() ?: return emptyList()
        for (child in children) {
            if (child.isFile && isUsableVideo(child) && child.lastModified() >= threshold) {
                found.add(child)
            } else if (child.isDirectory && depth < 2) {
                found.addAll(collectRecentVideos(child, threshold, depth + 1))
            }
        }
        return found
    }

    private suspend fun waitUntilStable(file: File): Boolean {
        if (!file.exists()) return false
        val first = file.length()
        if (first < MIN_BYTES) return false
        delay(STABLE_CHECK_MS)
        return file.exists() && file.length() >= MIN_BYTES && file.length() == first
    }

    private fun requestSync(reason: String) {
        try {
            GlobalWifiManager.getInstance().requestImmediateVideoSync(reason)
        } catch (e: Exception) {
            Log.w(TAG, "请求视频同步失败 ($reason)", e)
        }
    }
}
