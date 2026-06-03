package com.rokid.cxrmsamples.utils

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 阿里云Token获取辅助类（Android兼容版本）
 * 不使用 javax.xml.bind.DatatypeConverter，改用 Android 原生 Base64
 * 参考: 阿里云 OpenAPI 签名机制
 */
class AliyunTokenHelper(
    private val accessKeyId: String,
    private val accessKeySecret: String
) {
    private val TAG = "AliyunTokenHelper"
    private var cachedToken: String? = null
    private var tokenExpireTime: Long = 0
    
    private val client = OkHttpClient()
    private val tokenUrl = "https://nls-meta.cn-shanghai.aliyuncs.com/"

    /**
     * 获取Token，如果Token未过期则返回缓存的Token，否则重新获取
     */
    fun getToken(): String {
        // 检查缓存的Token是否仍然有效（提前5分钟刷新）
        val currentTime = System.currentTimeMillis() / 1000
        if (cachedToken != null && tokenExpireTime > currentTime + 300) {
            Log.d(TAG, "使用缓存的Token, 过期时间: $tokenExpireTime")
            return cachedToken!!
        }

        return try {
            Log.d(TAG, "==================== 开始获取 Token ====================")
            val token = fetchTokenFromServer()
            Log.d(TAG, "==================== Token获取成功 ====================")
            Log.d(TAG, "Token: ${token.take(20)}***")
            Log.d(TAG, "过期时间: $tokenExpireTime")
            token
        } catch (e: Exception) {
            Log.e(TAG, "==================== Token获取失败 ====================", e)
            Log.e(TAG, "异常类型: ${e.javaClass.name}")
            Log.e(TAG, "异常消息: ${e.message}")
            e.printStackTrace()
            throw IOException("获取Token失败: ${e.message}", e)
        }
    }

    /**
     * 从阿里云服务器获取Token
     */
    private fun fetchTokenFromServer(): String {
        // 构造请求参数
        val timestamp = getISO8601Timestamp()
        val nonce = UUID.randomUUID().toString()
        
        val params = sortedMapOf(
            "AccessKeyId" to accessKeyId,
            "Action" to "CreateToken",
            "Format" to "JSON",
            "RegionId" to "cn-shanghai",
            "SignatureMethod" to "HMAC-SHA1",
            "SignatureNonce" to nonce,
            "SignatureVersion" to "1.0",
            "Timestamp" to timestamp,
            "Version" to "2019-02-28"
        )

        // 构造签名字符串
        val canonicalizedQueryString = params.entries.joinToString("&") { (key, value) ->
            "${percentEncode(key)}=${percentEncode(value)}"
        }
        
        val stringToSign = "GET&${percentEncode("/")}&${percentEncode(canonicalizedQueryString)}"
        
        // 计算签名
        val signature = hmacSha1Sign(stringToSign, "$accessKeySecret&")
        
        // 添加签名到参数
        val finalParams = params.toMutableMap()
        finalParams["Signature"] = signature
        
        // 构造完整URL
        val queryString = finalParams.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        val fullUrl = "$tokenUrl?$queryString"
        
        Log.d(TAG, "==================== Token 请求详情 ====================")
        Log.d(TAG, "AccessKeyId: ${accessKeyId.take(10)}***")
        Log.d(TAG, "Timestamp: $timestamp")
        Log.d(TAG, "Nonce: $nonce")
        Log.d(TAG, "StringToSign: $stringToSign")
        Log.d(TAG, "Signature: ${signature.take(10)}***")
        Log.d(TAG, "请求Token URL: $fullUrl")
        
        // 发送HTTP请求
        val request = Request.Builder()
            .url(fullUrl)
            .get()
            .build()
        
        Log.d(TAG, "开始发送 HTTP 请求...")
        val response = client.newCall(request).execute()
        val responseCode = response.code
        val responseMessage = response.message
        val responseBody = response.body?.string()
        
        Log.d(TAG, "HTTP 响应码: $responseCode")
        Log.d(TAG, "HTTP 响应消息: $responseMessage")
        Log.d(TAG, "HTTP 响应体: $responseBody")
        
        if (!response.isSuccessful || responseBody == null) {
            throw IOException("HTTP请求失败: $responseCode $responseMessage, Body: $responseBody")
        }
        
        // 解析响应
        val json = JSONObject(responseBody)
        
        if (!json.has("Token")) {
            val errorCode = json.optString("Code", "Unknown")
            val errorMsg = json.optString("Message", "未知错误")
            throw IOException("获取Token失败: $errorCode - $errorMsg")
        }
        
        val tokenObj = json.getJSONObject("Token")
        cachedToken = tokenObj.getString("Id")
        tokenExpireTime = tokenObj.getLong("ExpireTime")
        
        return cachedToken!!
    }

    /**
     * HMAC-SHA1 签名（Android兼容实现）
     */
    private fun hmacSha1Sign(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1")
        mac.init(secretKey)
        val rawHmac = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        // 使用 Android 的 Base64，而非 javax.xml.bind.DatatypeConverter
        return Base64.encodeToString(rawHmac, Base64.NO_WRAP)
    }

    /**
     * URL 编码（符合 RFC 3986）
     */
    private fun percentEncode(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
    }

    /**
     * 获取 ISO8601 格式的时间戳
     */
    private fun getISO8601Timestamp(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        return dateFormat.format(Date())
    }

    /**
     * 清除缓存的Token
     */
    fun clearToken() {
        cachedToken = null
        tokenExpireTime = 0
    }
}
