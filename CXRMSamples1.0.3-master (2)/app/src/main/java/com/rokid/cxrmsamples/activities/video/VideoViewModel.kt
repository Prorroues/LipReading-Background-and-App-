package com.rokid.cxrmsamples.activities.video

import android.util.Size
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.listeners.SceneStatusUpdateListener
import com.rokid.cxr.client.utils.ValueUtil
import com.rokid.cxrmsamples.services.assistant.VoiceAssistantManager
import com.rokid.cxrmsamples.managers.GlobalCustomViewManager
import com.rokid.cxrmsamples.managers.MediaCaptureManager
import com.rokid.cxrmsamples.models.MessageMediaType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class VideoViewModel(application: Application) : AndroidViewModel(application) {
    private val voiceAssistant = VoiceAssistantManager.getInstance(application)
    private val mediaCaptureManager = MediaCaptureManager.getInstance(application)
    private var recordStartAt: Long? = null
    // 视频分辨率选项
    val videoSize: Array<Size> = arrayOf(
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

    private val _selectedVideoSize = MutableStateFlow(videoSize[0])
    val selectedVideoSize = _selectedVideoSize.asStateFlow()

    private val _duration = MutableStateFlow(10)
    val duration = _duration.asStateFlow()

    private val _durationUnit = MutableStateFlow(DurationUnit.SECONDS)
    val durationUnit = _durationUnit.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val sceneStatusUpdateListener = SceneStatusUpdateListener { p0 ->
        p0?.isVideoRecordRunning?.let {
            if (!it) {
                _isRecording.value = false
                val startAt = recordStartAt
                if (startAt != null) {
                    val durationSec = ((System.currentTimeMillis() - startAt) / 1000).coerceAtLeast(1)
                    val media = mediaCaptureManager.addVideoRecord("手动录像完成，时长${durationSec}秒")
                    voiceAssistant.addExternalMessage(
                        content = "录像已完成，时长${durationSec}秒",
                        mediaType = media.filePath?.let { MessageMediaType.VIDEO },
                        mediaPath = media.filePath
                    )
                    recordStartAt = null
                }
            }
        }
    }

    fun setSceneStatusListener(toSet: Boolean) {
        if (toSet) {
            GlobalCustomViewManager.getInstance().registerSceneStatusListener(sceneStatusUpdateListener)
        } else {
            GlobalCustomViewManager.getInstance().unregisterSceneStatusListener(sceneStatusUpdateListener)
        }
    }

    fun sizeChoose(resolution: Size) {
        _selectedVideoSize.value = resolution
    }

    fun setDuration(duration: Int) {
        _duration.value = duration
    }

    fun setDurationUnit(unit: DurationUnit) {
        _durationUnit.value = unit
    }

    fun setVideoParams() {
        val size = _selectedVideoSize.value
        CxrApi.getInstance().setVideoParams(
            _duration.value,
            30,
            size.width,
            size.height,
            if (_durationUnit.value == DurationUnit.MINUTES) 0 else 1
        )
    }

    fun toggleRecording() {
        if (_isRecording.value) {
            // 停止录制
            openOrCloseVideoRecord(false)
            _isRecording.value = false
            val startAt = recordStartAt
            if (startAt != null) {
                val durationSec = ((System.currentTimeMillis() - startAt) / 1000).coerceAtLeast(1)
                val media = mediaCaptureManager.addVideoRecord("手动录像完成，时长${durationSec}秒")
                voiceAssistant.addExternalMessage(
                    content = "已停止录像，时长${durationSec}秒",
                    mediaType = media.filePath?.let { MessageMediaType.VIDEO },
                    mediaPath = media.filePath
                )
                recordStartAt = null
            } else {
                val media = mediaCaptureManager.addVideoRecord("手动录像完成")
                voiceAssistant.addExternalMessage(
                    content = "已停止录像",
                    mediaType = media.filePath?.let { MessageMediaType.VIDEO },
                    mediaPath = media.filePath
                )
            }
        } else {
            // 开始录制
            openOrCloseVideoRecord(true)
            _isRecording.value = true
            recordStartAt = System.currentTimeMillis()
            val media = mediaCaptureManager.addVideoRecord("手动录像开始")
            voiceAssistant.addExternalMessage(
                content = "已开始录像",
                mediaType = media.filePath?.let { MessageMediaType.VIDEO },
                mediaPath = media.filePath
            )
        }
    }

    private fun openOrCloseVideoRecord(toRecord: Boolean) {
        if (toRecord) {
            CxrApi.getInstance().controlScene(ValueUtil.CxrSceneType.VIDEO_RECORD, true, null)
        } else {
            CxrApi.getInstance().controlScene(ValueUtil.CxrSceneType.VIDEO_RECORD, false, null)
        }
    }

    enum class DurationUnit {
        SECONDS, MINUTES
    }
}