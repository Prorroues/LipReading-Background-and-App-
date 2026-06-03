package com.rokid.cxrmsamples.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit

/** 上传地址规范化与连通性探测（探测不会上传假视频）。 */
object UploadUrlHelper {
    const val DEFAULT_UPLOAD_PATH = "/upload/videos"

    fun normalizeUploadUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return trimmed
        return try {
            val uri = URI(trimmed)
            val path = uri.path?.trim().orEmpty()
            if (path.isEmpty() || path == "/") {
                trimmed.trimEnd('/') + DEFAULT_UPLOAD_PATH
            } else {
                trimmed
            }
        } catch (_: Exception) {
            trimmed
        }
    }

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun probeUploadEndpoint(uploadUrl: String): ServerProbeResult = withContext(Dispatchers.IO) {
        val url = uploadUrl.trim()
        if (url.isEmpty()) {
            return@withContext ServerProbeResult(false, "服务器地址为空")
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext ServerProbeResult(false, "地址需以 http:// 或 https:// 开头")
        }
        try {
            val uri = URI(url)
            val host = uri.host ?: return@withContext ServerProbeResult(false, "地址无效")
            val port = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80
            Socket().use { it.connect(InetSocketAddress(host, port), 3000) }
            val request = Request.Builder().url(url).head().build()
            probeClient.newCall(request).execute().use { response ->
                val msg = buildString {
                    append("服务器可达：$host:$port\n")
                    append("地址：$url\n")
                    append("HTTP：${response.code}\n")
                    append("说明：测试连接不会上传视频，不会触发唇语/唇部处理。")
                }
                when (response.code) {
                    in 200..299, 405, 422 -> ServerProbeResult(true, msg)
                    404 -> ServerProbeResult(false, "HTTP 404，请确认路径含 /upload/videos")
                    else -> ServerProbeResult(false, "HTTP ${response.code}\n$msg")
                }
            }
        } catch (e: java.net.UnknownHostException) {
            ServerProbeResult(false, "无法解析主机：$url")
        } catch (e: java.net.ConnectException) {
            ServerProbeResult(false, "连接被拒绝，请确认服务已启动：$url")
        } catch (e: Exception) {
            ServerProbeResult(false, e.message ?: e.javaClass.simpleName)
        }
    }
}

data class ServerProbeResult(val success: Boolean, val message: String)
