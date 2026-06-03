package com.rokid.cxrmsamples.activities.picture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.PhotoResultCallback
import com.rokid.cxr.client.utils.ValueUtil
import com.rokid.cxrmsamples.services.assistant.VoiceAssistantManager
import com.rokid.cxrmsamples.managers.MediaCaptureManager
import com.rokid.cxrmsamples.models.MessageMediaType
import com.rokid.cxrmsamples.managers.ErrorReporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class PictureViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "PictureViewModel"
    private val voiceAssistant = VoiceAssistantManager.getInstance(application)
    private val mediaCaptureManager = MediaCaptureManager.getInstance(application)

    // [4032x3024, 4000x3000, 4032x2268, 3264x2448, 3200x2400, 2268x3024, 2876x2156, 2688x2016, 2582x1936, 2400x1800, 1800x2400, 2560x1440, 2400x1350, 2048x1536, 2016x1512, 1920x1080, 1600x1200, 1440x1080, 1280x720, 720x1280, 1024x768, 800x600, 648x648, 854x480, 800x480, 640x480, 480x640, 352x288, 320x240, 320x180, 176x144]
    val pictureSize: Array<Size> = arrayOf(
        Size(1920, 1080),
        Size(4032, 3024),
        Size(4000, 3000),
        Size(4032, 2268),
        Size(3264, 2448),
        Size(3200, 2400),
        Size(2268, 3024),
        Size(2876, 2156),
        Size(2688, 2016),
        Size(2582, 1936),
        Size(2400, 1800),
        Size(1800, 2400),
        Size(2560, 1440),
        Size(2400, 1350),
        Size(2048, 1536),
        Size(2016, 1512),
        Size(1600, 1200),
        Size(1440, 1080),
        Size(1280, 720),
        Size(720, 1280),
        Size(1024, 768),
        Size(800, 600),
        Size(648, 648),
        Size(854, 480),
        Size(800, 480),
        Size(640, 480),
        Size(480, 640),
        Size(352, 288),
        Size(320, 240),
        Size(320, 180),
        Size(176, 144)

    )

    private val _takingPhoto = MutableStateFlow(false)
    val takingPhoto = _takingPhoto.asStateFlow()

    private val _selectedPictureSize = MutableStateFlow(pictureSize[0])
    val selectedPictureSize = _selectedPictureSize.asStateFlow()

    private val _showImageBitmap: MutableStateFlow<ImageBitmap?> = MutableStateFlow(null)
    val showImageBitmap = _showImageBitmap.asStateFlow()

    private val _errorMessage: MutableStateFlow<String?> = MutableStateFlow(null)
    val errorMessage = _errorMessage.asStateFlow()

    private fun handlePhotoResult(status: ValueUtil.CxrStatus?, imageData: ByteArray?) {
        _takingPhoto.value = false
        when (status) {
            ValueUtil.CxrStatus.RESPONSE_SUCCEED -> {
                try {
                    if (imageData != null && imageData.isNotEmpty()) {
                        // imageData is a webP format image
                        val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                        if (bitmap != null) {
                            _showImageBitmap.value = bitmap.asImageBitmap()
                            _errorMessage.value = null
                            Log.d(TAG, "Photo captured successfully, size: ${imageData.size} bytes")
                            val media = mediaCaptureManager.addPhotoFromBytes(
                                imageData,
                                "webp",
                                "image/webp",
                                "手动拍照"
                            )
                            voiceAssistant.addExternalMessage(
                                content = "已拍照（${bitmap.width}x${bitmap.height}）",
                                mediaType = media?.let { MessageMediaType.PHOTO },
                                mediaPath = media?.filePath
                            )
                        } else {
                            _errorMessage.value = "拍照失败: 无法解码图片数据"
                            Log.e(TAG, "Failed to decode image data")
                            ErrorReporter.report(TAG, "decode", "无法解码图片数据")
                        }
                    } else {
                        _errorMessage.value = "拍照失败: 图片数据为空"
                        Log.e(TAG, "Image data is null or empty")
                        ErrorReporter.report(TAG, "decode", "图片数据为空")
                    }
                } catch (e: Exception) {
                    _errorMessage.value = "拍照失败: ${e.message}"
                    Log.e(TAG, "Error processing photo", e)
                    ErrorReporter.report(TAG, "process", "处理拍照结果异常", e.message)
                }
            }
            else -> {
                _showImageBitmap.value = null
                _errorMessage.value = "拍照失败: 状态码 $status"
                Log.e(TAG, "Photo capture failed with status: $status")
                ErrorReporter.report(TAG, "capture", "拍照失败", "status=$status")
            }
        }
    }

    fun takePicture() {
        try {
            // 检查蓝牙连接
            if (!CxrApi.getInstance().isBluetoothConnected) {
                _errorMessage.value = "蓝牙未连接，无法拍照"
                Log.w(TAG, "Bluetooth not connected, cannot take picture")
                ErrorReporter.report(TAG, "takePicture", "蓝牙未连接，无法拍照")
                return
            }

            val size = _selectedPictureSize.value
            Log.d(TAG, "Taking picture with size: ${size.width}x${size.height}")
            _takingPhoto.value = true
            _errorMessage.value = null

            // 优先设置当前分辨率参数（避免用户忘记点“设置参数”）
            setPhotoParams()

            var retried = false
            lateinit var callback: PhotoResultCallback
            callback = PhotoResultCallback { status, imageData ->
                if (status == ValueUtil.CxrStatus.RESPONSE_SUCCEED) {
                    handlePhotoResult(status, imageData)
                    return@PhotoResultCallback
                }

                if (!retried) {
                    retried = true
                    Log.w(TAG, "拍照失败，尝试降级重试: status=$status")
                    ErrorReporter.report(TAG, "retry", "拍照失败，尝试重试", "status=$status")
                    try {
                        CxrApi.getInstance().setPhotoParams(1920, 1080)
                        CxrApi.getInstance().takeGlassPhotoGlobal(1920, 1080, 100, callback)
                        return@PhotoResultCallback
                    } catch (e: Exception) {
                        Log.e(TAG, "重试拍照异常", e)
                        ErrorReporter.report(TAG, "retry", "重试拍照异常", e.message)
                    }
                }

                handlePhotoResult(status, imageData)
            }

            CxrApi.getInstance().takeGlassPhotoGlobal(size.width, size.height, 100, callback)
        } catch (e: Exception) {
            _takingPhoto.value = false
            _errorMessage.value = "拍照异常: ${e.message}"
            Log.e(TAG, "Error taking picture", e)
            ErrorReporter.report(TAG, "takePicture", "拍照异常", e.message)
        }
    }

    fun sizeChoose(resolution: Size) {
        _selectedPictureSize.value = resolution
    }

    fun setPhotoParams() {
        try {
            // 检查蓝牙连接
            if (!CxrApi.getInstance().isBluetoothConnected) {
                _errorMessage.value = "蓝牙未连接，无法设置拍照参数"
                Log.w(TAG, "Bluetooth not connected, cannot set photo params")
                return
            }

            val width = _selectedPictureSize.value.width
            val height = _selectedPictureSize.value.height
            Log.d(TAG, "Setting photo params: ${width}x${height}")
            CxrApi.getInstance().setPhotoParams(width, height)
            _errorMessage.value = null
        } catch (e: Exception) {
            _errorMessage.value = "设置参数失败: ${e.message}"
            Log.e(TAG, "Error setting photo params", e)
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}