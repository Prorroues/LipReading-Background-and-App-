package com.rokid.cxrmsamples.network

import android.util.Log
import com.rokid.cxrmsamples.utils.UploadUrlHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import org.json.JSONObject
import java.net.URI

/**
 * 分阶段上传：先快速传文件（wait=false），再轮询识别结果。
 * 默认 fast=true 时后端只跑唇语 VSR，跳过年龄/唇部合成视频以缩短总耗时。
 */
object VideoUploadCoordinator {
    private const val TAG = "VideoUploadCoordinator"
    private const val POLL_INTERVAL_MS = 500L
    private const val MAX_POLL_MS = 300_000L

    fun buildAsyncUploadUrl(baseUrl: String, fast: Boolean = true): String {
        val normalized = UploadUrlHelper.normalizeUploadUrl(baseUrl)
        val params = mutableListOf<String>()
        if (!normalized.contains("wait=")) params.add("wait=false")
        if (fast && !normalized.contains("fast=")) params.add("fast=true")
        if (params.isEmpty()) return normalized
        val separator = if (normalized.contains("?")) "&" else "?"
        return normalized + separator + params.joinToString("&")
    }

    fun buildStatusUrl(baseUrl: String, taskId: String): String {
        val normalized = UploadUrlHelper.normalizeUploadUrl(baseUrl)
        val uri = URI(normalized)
        val path = uri.path?.trimEnd('/') ?: UploadUrlHelper.DEFAULT_UPLOAD_PATH
        val statusPath = "$path/status/$taskId"
        return URI(uri.scheme, uri.userInfo, uri.host, uri.port, statusPath, null, null).toString()
    }

    /** 仅上传文件到服务器（落盘后立即返回 task_id） */
    suspend fun uploadFileOnly(
        api: VideoUploadApi,
        baseUrl: String,
        part: MultipartBody.Part,
        fast: Boolean = true,
        onPhase: (String) -> Unit = {}
    ): UploadFileResult = withContext(Dispatchers.IO) {
        val uploadUrl = buildAsyncUploadUrl(baseUrl, fast)
        onPhase("正在上传视频...")
        try {
            val response = api.uploadVideo(uploadUrl, part)
            if (!response.isSuccessful) {
                val err = response.errorBody()?.string() ?: response.message()
                return@withContext UploadFileResult(
                    success = false,
                    taskId = null,
                    immediateBody = null,
                    error = "HTTP ${response.code()}: $err"
                )
            }
            val body = response.body()?.string()
            if (body.isNullOrBlank()) {
                return@withContext UploadFileResult(false, null, null, "服务器返回为空")
            }
            val json = JSONObject(body)
            when (json.optString("status")) {
                "processing" -> {
                    val taskId = json.optString("task_id")
                    if (taskId.isBlank()) {
                        return@withContext UploadFileResult(true, null, body, null)
                    }
                    return@withContext UploadFileResult(true, taskId, body, null)
                }
                else -> UploadFileResult(true, null, body, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "上传异常", e)
            UploadFileResult(false, null, null, e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun pollRecognitionResult(
        api: VideoUploadApi,
        baseUrl: String,
        taskId: String,
        onPhase: (String) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        onPhase("上传完成，识别处理中...")
        pollTaskResult(api, baseUrl, taskId, onPhase)
    }

    suspend fun uploadAndResolve(
        api: VideoUploadApi,
        baseUrl: String,
        part: MultipartBody.Part,
        fast: Boolean = true,
        onPhase: (String) -> Unit = {}
    ): UploadResolveResult = withContext(Dispatchers.IO) {
        val upload = uploadFileOnly(api, baseUrl, part, fast, onPhase)
        if (!upload.success) {
            return@withContext UploadResolveResult(false, null, upload.error)
        }
        val taskId = upload.taskId
        if (taskId.isNullOrBlank()) {
            return@withContext UploadResolveResult(true, upload.immediateBody, null)
        }
        val finalBody = pollRecognitionResult(api, baseUrl, taskId, onPhase)
        UploadResolveResult(
            success = finalBody != null,
            responseBody = finalBody ?: upload.immediateBody,
            error = if (finalBody == null) "识别超时或失败" else null
        )
    }

    private suspend fun pollTaskResult(
        api: VideoUploadApi,
        baseUrl: String,
        taskId: String,
        onPhase: (String) -> Unit
    ): String? {
        val deadline = System.currentTimeMillis() + MAX_POLL_MS
        val statusUrl = buildStatusUrl(baseUrl, taskId)
        var polls = 0
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            polls++
            if (polls % 4 == 0) {
                onPhase("识别处理中... (${polls * POLL_INTERVAL_MS / 1000}s)")
            }
            try {
                val response = api.getUploadStatus(statusUrl)
                if (!response.isSuccessful) continue
                val body = response.body()?.string() ?: continue
                val json = JSONObject(body)
                when (json.optString("status")) {
                    "done" -> return body
                    "error" -> {
                        Log.e(TAG, "任务失败: ${json.optString("error")}")
                        return body
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "轮询状态失败: ${e.message}")
            }
        }
        return null
    }
}

data class UploadFileResult(
    val success: Boolean,
    val taskId: String?,
    val immediateBody: String?,
    val error: String?
)

data class UploadResolveResult(
    val success: Boolean,
    val responseBody: String?,
    val error: String?
)
