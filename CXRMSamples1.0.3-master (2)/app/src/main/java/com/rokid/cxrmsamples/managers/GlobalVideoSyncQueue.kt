package com.rokid.cxrmsamples.managers

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.LinkedList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 全局视频同步队列管理器（单例）
 * 维护最近5个同步的视频完整路径，供唇语识别等场景使用
 */
object GlobalVideoSyncQueue {
    private const val TAG = "GlobalVideoSyncQueue"
    private const val MAX_QUEUE_SIZE = 5
    
    private val videoQueue = LinkedList<String>()
    private val queueLock = ReentrantLock()
    
    // 用于通知队列更新的 StateFlow
    private val _latestVideo = MutableStateFlow<String?>(null)
    val latestVideo: StateFlow<String?> = _latestVideo.asStateFlow()
    
    /**
     * 添加视频到队列（线程安全）
     * 如果队列已满，移除最旧的视频
     */
    fun addVideo(path: String) {
        if (path.isBlank()) {
            Log.w(TAG, "尝试添加空路径到队列")
            return
        }
        
        queueLock.withLock {
            // 如果队列中已存在该路径，先移除（避免重复）
            videoQueue.remove(path)
            
            // 添加到队列头部（最新的在前面）
            videoQueue.addFirst(path)
            
            // 如果超过最大容量，移除最旧的
            while (videoQueue.size > MAX_QUEUE_SIZE) {
                val removed = videoQueue.removeLast()
                Log.d(TAG, "队列已满，移除最旧视频: $removed")
            }
            
            Log.d(TAG, "添加视频到队列: $path, 队列长度: ${videoQueue.size}")
            
            // 更新 StateFlow，通知监听者
            _latestVideo.value = path
        }
    }
    
    /**
     * 获取最新添加的视频（线程安全）
     */
    fun getLatestVideo(): String? {
        return queueLock.withLock {
            videoQueue.firstOrNull()
        }
    }
    
    /**
     * 获取队列中的所有视频（线程安全）
     * 返回列表的副本，按添加顺序（最新的在前）
     */
    fun getAllVideos(): List<String> {
        return queueLock.withLock {
            videoQueue.toList()
        }
    }
    
    /**
     * 获取在指定时间戳之后添加的最新视频
     * 通过比较文件修改时间来判断
     */
    fun getLatestVideoAfter(timestamp: Long): String? {
        return queueLock.withLock {
            videoQueue.firstOrNull { path ->
                try {
                    val file = java.io.File(path)
                    if (file.exists()) {
                        file.lastModified() >= timestamp
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "检查文件时间失败: $path", e)
                    false
                }
            }
        }
    }
    
    /**
     * 清空队列（线程安全）
     */
    fun clear() {
        queueLock.withLock {
            videoQueue.clear()
            _latestVideo.value = null
            Log.d(TAG, "队列已清空")
        }
    }
    
    /**
     * 获取队列当前大小（线程安全）
     */
    fun size(): Int {
        return queueLock.withLock {
            videoQueue.size
        }
    }
}
