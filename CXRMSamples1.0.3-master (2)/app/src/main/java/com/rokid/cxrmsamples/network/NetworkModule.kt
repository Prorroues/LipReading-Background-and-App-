package com.rokid.cxrmsamples.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkModule {
    private const val DEFAULT_BASE_URL = "http://88bill99.top:25000/"

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    /** 通用客户端（可带少量日志） */
    private val defaultClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 视频上传专用：关闭 BODY 日志，写超时放宽；读超时只需覆盖「传文件」阶段（识别走轮询）。
     */
    private val uploadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var currentBaseUrl: String = DEFAULT_BASE_URL
    private var currentRetrofit: Retrofit = createRetrofit(DEFAULT_BASE_URL, defaultClient)
    private var currentUploadRetrofit: Retrofit = createRetrofit(DEFAULT_BASE_URL, uploadClient)
    private var currentVideoUploadApi: VideoUploadApi = currentUploadRetrofit.create(VideoUploadApi::class.java)

    fun createVideoUploadApi(baseUrl: String): VideoUploadApi {
        var normalizedUrl = baseUrl.trim()
        normalizedUrl = if (normalizedUrl.endsWith("/")) normalizedUrl else "$normalizedUrl/"

        if (normalizedUrl != currentBaseUrl) {
            currentBaseUrl = normalizedUrl
            currentRetrofit = createRetrofit(normalizedUrl, defaultClient)
            currentUploadRetrofit = createRetrofit(normalizedUrl, uploadClient)
            currentVideoUploadApi = currentUploadRetrofit.create(VideoUploadApi::class.java)
        }

        return currentVideoUploadApi
    }

    val videoUploadApi: VideoUploadApi
        get() = currentVideoUploadApi

    private fun createRetrofit(baseUrl: String, client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
}
