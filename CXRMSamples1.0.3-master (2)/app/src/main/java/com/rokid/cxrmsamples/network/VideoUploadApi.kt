package com.rokid.cxrmsamples.network

import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Url

interface VideoUploadApi {
    @Multipart
    @POST
    suspend fun uploadVideo(
        @Url url: String,
        @Part file: MultipartBody.Part
    ): Response<okhttp3.ResponseBody>

    @GET
    suspend fun getUploadStatus(
        @Url url: String
    ): Response<okhttp3.ResponseBody>
}
