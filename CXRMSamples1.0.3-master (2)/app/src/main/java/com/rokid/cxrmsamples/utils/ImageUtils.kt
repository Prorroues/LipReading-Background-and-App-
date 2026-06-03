package com.rokid.cxrmsamples.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.Image
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * 图片处理工具类
 */
object ImageUtils {
    private const val TAG = "ImageUtils"
    
    /**
     * 将Camera2的Image对象转换为Bitmap
     */
    fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width
        
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        
        // 裁剪掉padding部分
        return if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        } else {
            bitmap
        }
    }
    
    /**
     * 压缩Bitmap到指定最大尺寸
     * @param bitmap 原始Bitmap
     * @param maxSize 长边最大像素值
     */
    fun resizeBitmap(bitmap: Bitmap, maxSize: Int = 1024): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        // 如果已经小于目标尺寸，直接返回
        if (width <= maxSize && height <= maxSize) {
            Log.d(TAG, "图片尺寸已满足要求: ${width}x${height}")
            return bitmap
        }
        
        // 计算缩放比例
        val scale = if (width > height) {
            maxSize.toFloat() / width
        } else {
            maxSize.toFloat() / height
        }
        
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        
        Log.d(TAG, "压缩图片: ${width}x${height} -> ${newWidth}x${newHeight}, 缩放比例: $scale")
        
        val matrix = Matrix()
        matrix.postScale(scale, scale)
        
        return Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
    }
    
    /**
     * 将Bitmap转换为Base64编码的JPEG
     * @param bitmap 原始Bitmap
     * @param quality JPEG质量 0-100
     * @return data:image/jpeg;base64,... 格式的字符串
     */
    fun bitmapToBase64(bitmap: Bitmap, quality: Int = 80): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val bytes = outputStream.toByteArray()
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        
        Log.d(TAG, "图片转Base64完成，原始大小: ${bytes.size / 1024}KB, Base64大小: ${base64.length / 1024}KB")
        
        return "data:image/jpeg;base64,$base64"
    }
    
    /**
     * 从文件路径加载并处理图片
     * @param path 图片文件路径
     * @param maxSize 最大尺寸
     * @param quality JPEG质量
     */
    fun loadAndProcessImage(path: String, maxSize: Int = 1024, quality: Int = 80): String {
        val bitmap = BitmapFactory.decodeFile(path)
        val resized = resizeBitmap(bitmap, maxSize)
        val base64 = bitmapToBase64(resized, quality)
        
        // 释放资源
        if (bitmap != resized) {
            bitmap.recycle()
        }
        
        return base64
    }
    
    /**
     * 从字节数组加载并处理图片
     */
    fun loadAndProcessImageFromBytes(
        bytes: ByteArray,
        maxSize: Int = 1024,
        quality: Int = 80
    ): String {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val resized = resizeBitmap(bitmap, maxSize)
        val base64 = bitmapToBase64(resized, quality)
        
        // 释放资源
        if (bitmap != resized) {
            bitmap.recycle()
        }
        
        return base64
    }
}
