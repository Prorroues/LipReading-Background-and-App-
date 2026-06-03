package com.rokid.cxrmsamples.services.camera

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.PhotoResultCallback
import com.rokid.cxr.client.utils.ValueUtil
import com.rokid.cxrmsamples.utils.ImageUtils
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 相机管理器
 * 基于Rokid CXR API实现拍照功能
 */
class CameraManager(private val context: Context) {
    private val TAG = "CameraManager"
    
    // 相机配置
    private val imageWidth = 1920
    private val imageHeight = 1080
    private val imageQuality = 100
    private var photoParamsSet = false
    
    /**
     * 设置拍照参数
     */
    fun setPhotoParams() {
        try {
            if (!CxrApi.getInstance().isBluetoothConnected) {
                Log.w(TAG, "蓝牙未连接，无法设置拍照参数")
                return
            }
            if (photoParamsSet) {
                return
            }
            
            Log.d(TAG, "设置拍照参数: ${imageWidth}x${imageHeight}")
            CxrApi.getInstance().setPhotoParams(imageWidth, imageHeight)
            photoParamsSet = true
        } catch (e: Exception) {
            Log.e(TAG, "设置拍照参数异常", e)
        }
    }
    
    /**
     * 拍照并返回Base64编码的图片
     * 使用Rokid CXR API
     */
    suspend fun takePictureBytes(): ByteArray = suspendCancellableCoroutine { continuation ->
        try {
            // 检查蓝牙连接
            if (!CxrApi.getInstance().isBluetoothConnected) {
                Log.e(TAG, "蓝牙未连接，无法拍照")
                continuation.resumeWithException(Exception("蓝牙未连接"))
                return@suspendCancellableCoroutine
            }
            
            Log.d(TAG, "开始拍照: ${imageWidth}x${imageHeight}")
            
            // 设置拍照参数
            setPhotoParams()
            
            // 创建拍照回调
            val callback = PhotoResultCallback { status, imageData ->
                try {
                    when (status) {
                        ValueUtil.CxrStatus.RESPONSE_SUCCEED -> {
                            if (imageData != null && imageData.isNotEmpty()) {
                                val originalSize = imageData.size / 1024
                                Log.d(TAG, "拍照成功，原始图片大小: ${originalSize}KB")
                                    
                                    if (continuation.isActive) {
                                    continuation.resume(imageData)
                                }
                            } else {
                                Log.e(TAG, "图片数据为空")
                                if (continuation.isActive) {
                                    continuation.resumeWithException(Exception("图片数据为空"))
                                }
                            }
                        }
                        else -> {
                            Log.e(TAG, "拍照失败，状态: $status")
                            if (continuation.isActive) {
                                continuation.resumeWithException(Exception("拍照失败: $status"))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "处理拍照结果异常", e)
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }
            }
            
            // 调用Rokid API拍照
            CxrApi.getInstance().takeGlassPhotoGlobal(
                imageWidth,
                imageHeight,
                imageQuality,
                callback
            )
            
            continuation.invokeOnCancellation {
                Log.d(TAG, "拍照被取消")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "拍照异常", e)
            if (continuation.isActive) {
                continuation.resumeWithException(e)
            }
        }
    }

    /**
     * 拍照并返回Base64编码的图片（用于视觉分析）
     */
    suspend fun takePicture(): String {
        val imageData = takePictureBytes()
        val originalSize = imageData.size / 1024
        Log.d(TAG, "拍照成功，原始图片大小: ${originalSize}KB")
        
        // imageData是WebP格式，需要解码
        val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
            ?: throw Exception("图片解码失败")
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        Log.d(TAG, "原始图片尺寸: ${originalWidth}x${originalHeight}")
        
        // 处理图片并转换为Base64（优化：512px，quality=80）
        val resized = ImageUtils.resizeBitmap(bitmap, maxSize = 512)
        val base64 = ImageUtils.bitmapToBase64(resized, quality = 80)
        
        val compressedSize = base64.length / 1024
        val compressionRatio = (compressedSize * 100.0 / originalSize).toInt()
        Log.d(TAG, "图片压缩完成，压缩后大小: ${compressedSize}KB (压缩率: ${compressionRatio}%)")
        
        // 释放bitmap
        if (bitmap != resized) {
            bitmap.recycle()
        }
        return base64
    }
}
