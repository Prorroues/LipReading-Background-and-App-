package com.rokid.cxrmsamples.managers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import android.util.Log
import androidx.core.graphics.createBitmap
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.infos.IconInfo
import com.rokid.cxr.client.extend.listeners.BatteryLevelUpdateListener
import com.rokid.cxr.client.extend.listeners.CustomViewListener
import com.rokid.cxr.client.extend.listeners.SceneStatusUpdateListener
import com.rokid.cxrmsamples.R
import com.rokid.cxrmsamples.services.assistant.VoiceAssistantManager
import com.rokid.cxrmsamples.dataBeans.selfView.ImageViewProps
import com.rokid.cxrmsamples.dataBeans.selfView.LinearLayoutProps
import com.rokid.cxrmsamples.dataBeans.selfView.SelfViewJson
import com.rokid.cxrmsamples.dataBeans.selfView.TextViewProps
import com.rokid.cxrmsamples.dataBeans.selfView.UpdateViewJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局自定义View管理器
 * 用于在任何地方显示自定义View，特别是语音唤醒后的显示
 */
class GlobalCustomViewManager private constructor() {
    
    private val TAG = "GlobalCustomViewManager"
    
    companion object {
        @Volatile
        private var instance: GlobalCustomViewManager? = null
        
        fun getInstance(): GlobalCustomViewManager {
            return instance ?: synchronized(this) {
                instance ?: GlobalCustomViewManager().also { instance = it }
            }
        }
    }
    
    private val _isCustomViewOpen = MutableStateFlow(false)
    val isCustomViewOpen = _isCustomViewOpen.asStateFlow()
    
    private val _iconsSent = MutableStateFlow(false)
    val iconsSent = _iconsSent.asStateFlow()
    
    private val _aiAssistRunning = MutableStateFlow(false)
    val aiAssistRunning = _aiAssistRunning.asStateFlow()
    
    // 当前显示的文字内容
    private var currentDialogText = ""
    
    // 当前电量
    private var currentBattery = 100
    
    // 时间更新Job
    private var timeUpdateJob: Job? = null
    
    // 保存应用Context（用于访问SharedPreferences等）
    private var appContext: Context? = null
    
    // 追加的场景状态监听器（避免被其他页面覆盖）
    private val extraSceneStatusListeners = mutableSetOf<SceneStatusUpdateListener>()
    // 追加的自定义View监听器
    private val extraCustomViewListeners = mutableSetOf<CustomViewListener>()
    
    // 场景状态监听器 - 监听AI助手启动
    private val sceneStatusListener = SceneStatusUpdateListener { sceneStatus ->
        sceneStatus?.let {
            val wasRunning = _aiAssistRunning.value
            _aiAssistRunning.value = it.isAiAssistRunning
            
            // AI助手刚启动时
            if (!wasRunning && it.isAiAssistRunning) {
                Log.d(TAG, "检测到AI助手启动（乐奇唤醒），触发语音识别")
                
                // 尝试自动启用全局语音助手（如果已配置）
                try {
                    val context = appContext
                    if (context != null) {
                        val voiceAssistant = VoiceAssistantManager.getInstance(context)
                        if (!voiceAssistant.enabled.value) {
                            // 检查是否已配置
                            val nlsPrefs = context.getSharedPreferences("voice_service_config", Context.MODE_PRIVATE)
                            val llmPrefs = context.getSharedPreferences("llm_config", Context.MODE_PRIVATE)
                            
                            val nlsAppKey = nlsPrefs.getString("app_key", "") ?: ""
                            val apiKey = llmPrefs.getString("api_key", "") ?: ""
                            val provider = llmPrefs.getString("provider", "") ?: ""
                            
                            if (nlsAppKey.isNotEmpty() && apiKey.isNotEmpty() && provider.isNotEmpty()) {
                                Log.d(TAG, "检测到已配置，自动启用全局语音助手")
                                voiceAssistant.enable()
                            } else {
                                Log.d(TAG, "语音助手未配置，跳过自动启用")
                            }
                        } else {
                            Log.d(TAG, "语音助手已启用")
                        }
                    } else {
                        Log.w(TAG, "无法获取Context，跳过自动启用语音助手")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "自动启用语音助手失败", e)
                }
                
                // 显示对话界面
                showDialogView("正在聆听...")
                // 触发语音识别
                GlobalVoiceRecognitionManager.getInstance().startRecognition()
            }
            // AI助手关闭时，立即关闭自定义View
            else if (wasRunning && !it.isAiAssistRunning) {
                Log.d(TAG, "AI助手已关闭，立即关闭对话界面")
                closeView()
                // 同时停止语音识别
                GlobalVoiceRecognitionManager.getInstance().stopRecognition()
            }

            // 分发给其他监听器（不要影响主流程）
            extraSceneStatusListeners.forEach { listener ->
                try {
                    dispatchSceneStatus(listener, sceneStatus)
                } catch (e: Exception) {
                    Log.w(TAG, "额外SceneStatus监听器回调异常", e)
                }
            }
        }
    }
    
    // 电量监听器 - 实时更新电量显示
    private val batteryListener = BatteryLevelUpdateListener { level, isCharging ->
        Log.d(TAG, "电量更新: $level%, 充电中: $isCharging")
        currentBattery = level
        if (_isCustomViewOpen.value) {
            updateStatusBar()
        }
    }
    
    private val customViewListener = object : CustomViewListener {
        override fun onIconsSent() {
            Log.d(TAG, "图标上传成功")
            _iconsSent.value = true
            extraCustomViewListeners.forEach { listener ->
                try {
                    listener.onIconsSent()
                } catch (e: Exception) {
                    Log.w(TAG, "额外CustomView监听器回调异常", e)
                }
            }
        }
        
        override fun onOpened() {
            Log.d(TAG, "自定义View已打开")
            _isCustomViewOpen.value = true
            extraCustomViewListeners.forEach { listener ->
                try {
                    listener.onOpened()
                } catch (e: Exception) {
                    Log.w(TAG, "额外CustomView监听器回调异常", e)
                }
            }
        }
        
        override fun onOpenFailed(errorCode: Int) {
            Log.e(TAG, "自定义View打开失败: $errorCode")
            _isCustomViewOpen.value = false
            extraCustomViewListeners.forEach { listener ->
                try {
                    listener.onOpenFailed(errorCode)
                } catch (e: Exception) {
                    Log.w(TAG, "额外CustomView监听器回调异常", e)
                }
            }
        }
        
        override fun onUpdated() {
            Log.d(TAG, "自定义View已更新")
            extraCustomViewListeners.forEach { listener ->
                try {
                    listener.onUpdated()
                } catch (e: Exception) {
                    Log.w(TAG, "额外CustomView监听器回调异常", e)
                }
            }
        }
        
        override fun onClosed() {
            Log.d(TAG, "自定义View已关闭")
            _isCustomViewOpen.value = false
            extraCustomViewListeners.forEach { listener ->
                try {
                    listener.onClosed()
                } catch (e: Exception) {
                    Log.w(TAG, "额外CustomView监听器回调异常", e)
                }
            }
        }
    }

    fun registerSceneStatusListener(listener: SceneStatusUpdateListener) {
        extraSceneStatusListeners.add(listener)
    }

    fun unregisterSceneStatusListener(listener: SceneStatusUpdateListener) {
        extraSceneStatusListeners.remove(listener)
    }

    fun registerCustomViewListener(listener: CustomViewListener) {
        extraCustomViewListeners.add(listener)
    }

    fun unregisterCustomViewListener(listener: CustomViewListener) {
        extraCustomViewListeners.remove(listener)
    }

    private fun dispatchSceneStatus(listener: SceneStatusUpdateListener, sceneStatus: Any?) {
        // 通过反射调用，避免SDK接口方法名变化导致编译失败
        val method = listener.javaClass.methods.firstOrNull { it.parameterTypes.size == 1 }
        method?.invoke(listener, sceneStatus)
    }
    
    /**
     * 初始化管理器，上传必要的图标资源
     */
    fun initialize(context: Context) {
        // 保存应用Context
        appContext = context.applicationContext
        try {
            Log.d(TAG, "初始化GlobalCustomViewManager")
            
            // 设置自定义View监听器
            CxrApi.getInstance().setCustomViewListener(customViewListener)
            
            // 设置场景状态监听器，监听AI助手启动
            CxrApi.getInstance().setSceneStatusUpdateListener(sceneStatusListener)
            Log.d(TAG, "场景状态监听器已设置")
            
            // 设置电量监听器
            CxrApi.getInstance().setBatteryLevelUpdateListener(batteryListener)
            Log.d(TAG, "电量监听器已设置")
            
            // 如果图标还没上传，则上传
            if (!_iconsSent.value) {
                uploadIcons(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "初始化失败", e)
        }
    }
    
    /**
     * 上传图标资源
     */
    private fun uploadIcons(context: Context) {
        try {
            Log.d(TAG, "开始上传图标资源")
            
            // Convert icon1.png to Base64
            val icon1Drawable = context.resources.getDrawable(R.drawable.icon1, null)
            val icon1Bitmap = drawableToBitmap(icon1Drawable)
            val icon1OutputStream = ByteArrayOutputStream()
            icon1Bitmap.compress(Bitmap.CompressFormat.PNG, 100, icon1OutputStream)
            val icon1Base64 = Base64.encodeToString(icon1OutputStream.toByteArray(), Base64.NO_WRAP)
            icon1OutputStream.close()
            
            // Convert vector.png to Base64
            val vectorDrawable = context.resources.getDrawable(R.drawable.vector, null)
            val vectorBitmap = drawableToBitmap(vectorDrawable)
            val vectorOutputStream = ByteArrayOutputStream()
            vectorBitmap.compress(Bitmap.CompressFormat.PNG, 100, vectorOutputStream)
            val vectorBase64 = Base64.encodeToString(vectorOutputStream.toByteArray(), Base64.NO_WRAP)
            vectorOutputStream.close()
            
            // Create IconInfo objects
            val icon1Info = IconInfo("icon1", icon1Base64)
            val vectorInfo = IconInfo("vector", vectorBase64)
            
            // Send icons using CXR API
            CxrApi.getInstance().sendCustomViewIcons(listOf(icon1Info, vectorInfo))
            Log.d(TAG, "图标上传完成")
        } catch (e: Exception) {
            Log.e(TAG, "图标上传失败", e)
        }
    }
    
    /**
     * 显示带有指定文字的自定义View
     * @param text 要显示的文字
     * @param textColor 文字颜色，默认为青色 "#00FFFF"
     * @param backgroundColor 背景颜色，默认为黑色 "#FF000000"
     */
    fun showTextView(
        text: String, 
        textColor: String = "#00FFFF",  // 青色
        backgroundColor: String = "#FF000000"  // 黑色背景
    ) {
        try {
            Log.d(TAG, "显示文字: $text")
            
            // 创建一个显示在上方中间的布局，不遮挡AI助手界面下方的元素
            val selfView = SelfViewJson().apply {
                type = "LinearLayout"
                props = LinearLayoutProps().apply {
                    id = "root"
                    layout_width = "match_parent"
                    layout_height = "match_parent"
                    marginTop = "200dp"  // 上方留白，显示在视野上半部分
                    marginBottom = "300dp"  // 下方留白，避免遮挡"HI，我在听"等元素
                    this.backgroundColor = "#00000000"  // 完全透明背景
                    orientation = "vertical"
                    gravity = "center_horizontal"  // 水平居中
                }.toJson()
                children = listOf(
                    // 文字显示（带半透明青色背景框）
                    SelfViewJson().apply {
                        type = "LinearLayout"
                        props = LinearLayoutProps().apply {
                            id = "container"
                            layout_width = "wrap_content"
                            layout_height = "wrap_content"
                            this.backgroundColor = "#CC001010"  // 深色半透明背景
                            orientation = "vertical"
                            gravity = "center"
                            paddingStart = "48dp"
                            paddingEnd = "48dp"
                            paddingTop = "24dp"
                            paddingBottom = "24dp"
                        }.toJson()
                        children = listOf(
                            SelfViewJson().apply {
                                type = "TextView"
                                props = TextViewProps().apply {
                                    id = "textView"
                                    layout_width = "wrap_content"
                                    layout_height = "wrap_content"
                                    this.text = text
                                    this.textColor = textColor  // 青色文字
                                    textSize = "21sp"  // 缩小为原来的3/4
                                    gravity = "center"
                                    textStyle = "bold"
                                }.toJson()
                            }
                        )
                    }
                )
            }
            
            CxrApi.getInstance().openCustomView(selfView.toJson())
            Log.d(TAG, "自定义View已发送显示请求")
        } catch (e: Exception) {
            Log.e(TAG, "显示自定义View失败", e)
        }
    }
    
    /**
     * 显示完整的对话界面（覆盖AI助手界面）
     * 包含：对话区域、时间、电量、提示文字
     */
    fun showDialogView(initialText: String = "你好") {
        try {
            Log.d(TAG, "显示对话界面")
            currentDialogText = initialText
            
            // 获取当前时间和电量
            val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            
            // 创建完整的对话界面
            val selfView = SelfViewJson().apply {
                type = "LinearLayout"
                props = LinearLayoutProps().apply {
                    id = "root"
                    layout_width = "match_parent"
                    layout_height = "match_parent"
                    marginTop = "0dp"  // 全屏显示，完全覆盖
                    marginBottom = "0dp"
                    this.backgroundColor = "#FF000000"  // 黑色背景
                    orientation = "vertical"
                    gravity = "center_horizontal"
                }.toJson()
                children = listOf(
                    // 顶部留白
                    SelfViewJson().apply {
                        type = "TextView"
                        props = TextViewProps().apply {
                            id = "topSpacer"
                            layout_width = "match_parent"
                            layout_height = "wrap_content"
                            text = ""
                            marginTop = "180dp"
                        }.toJson()
                    },
                    // 中间对话区域（青色框）
                    SelfViewJson().apply {
                        type = "LinearLayout"
                        props = LinearLayoutProps().apply {
                            id = "dialogContainer"
                            layout_width = "wrap_content"
                            layout_height = "wrap_content"
                            this.backgroundColor = "#AA001A1A"  // 深青色半透明背景
                            orientation = "vertical"
                            gravity = "center"
                            paddingStart = "40dp"
                            paddingEnd = "40dp"
                            paddingTop = "30dp"
                            paddingBottom = "30dp"
                        }.toJson()
                        children = listOf(
                            // 对话文字
                            SelfViewJson().apply {
                                type = "TextView"
                                props = TextViewProps().apply {
                                    id = "dialogText"
                                    layout_width = "wrap_content"
                                    layout_height = "wrap_content"
                                    this.text = initialText
                                    this.textColor = "#00FFFF"  // 青色
                                    textSize = "18sp"
                                    gravity = "center"
                                    textStyle = "bold"
                                }.toJson()
                            }
                        )
                    },
                    // 底部信息区域
                    SelfViewJson().apply {
                        type = "LinearLayout"
                        props = LinearLayoutProps().apply {
                            id = "bottomContainer"
                            layout_width = "match_parent"
                            layout_height = "wrap_content"
                            orientation = "vertical"
                            marginTop = "100dp"
                            marginBottom = "40dp"
                            paddingStart = "20dp"
                            paddingEnd = "20dp"
                        }.toJson()
                        children = listOf(
                            // "HI，我在听"
                            SelfViewJson().apply {
                                type = "TextView"
                                props = TextViewProps().apply {
                                    id = "listeningHint"
                                    layout_width = "match_parent"
                                    layout_height = "wrap_content"
                                    this.text = "HI，我在听"
                                    this.textColor = "#00FF00"
                                    textSize = "12sp"
                                    gravity = "center"
                                    marginBottom = "20dp"
                                }.toJson()
                            },
                            // 提示信息
                            SelfViewJson().apply {
                                type = "TextView"
                                props = TextViewProps().apply {
                                    id = "hint"
                                    layout_width = "match_parent"
                                    layout_height = "wrap_content"
                                    this.text = "滑动翻页/双击退出"
                                    this.textColor = "#007777"
                                    textSize = "9sp"
                                    gravity = "center"
                                    marginBottom = "10dp"
                                }.toJson()
                            },
                            // 时间和电量（一行显示）
                            SelfViewJson().apply {
                                type = "TextView"
                                props = TextViewProps().apply {
                                    id = "statusBar"
                                    layout_width = "match_parent"
                                    layout_height = "wrap_content"
                                    this.text = "$currentTime                     电量: $currentBattery%"
                                    this.textColor = "#00FF00"
                                    textSize = "9sp"
                                    gravity = "center"
                                }.toJson()
                            }
                        )
                    }
                )
            }
            
            CxrApi.getInstance().openCustomView(selfView.toJson())
            Log.d(TAG, "对话界面已显示")
            
            // 启动时间更新
            startTimeUpdate()
        } catch (e: Exception) {
            Log.e(TAG, "显示对话界面失败", e)
        }
    }
    
    /**
     * 更新对话文字（流式输出）
     * @param text 新的完整文字内容
     */
    fun updateDialogText(text: String) {
        try {
            // 如果对话界面未打开，自动打开（用于语音助手处理过程中）
            if (!_isCustomViewOpen.value) {
                Log.d(TAG, "对话界面未打开，自动打开: $text")
                showDialogView(text)
                return
            }
            
            currentDialogText = text
            
            val updateViewJson = UpdateViewJson().apply {
                updateList.add(UpdateViewJson.UpdateJson(id = "dialogText").apply {
                    props["text"] = text
                })
            }
            
            CxrApi.getInstance().updateCustomView(updateViewJson.toJson())
            Log.d(TAG, "对话文字已更新: $text")
        } catch (e: Exception) {
            Log.e(TAG, "更新对话文字失败", e)
        }
    }
    
    /**
     * 更新电量显示
     */
    fun updateBattery(batteryLevel: Int) {
        try {
            currentBattery = batteryLevel
            updateStatusBar()
        } catch (e: Exception) {
            Log.e(TAG, "更新电量失败", e)
        }
    }
    
    /**
     * 启动时间自动更新（每分钟更新一次）
     */
    private fun startTimeUpdate() {
        timeUpdateJob?.cancel()
        timeUpdateJob = CoroutineScope(Dispatchers.Main).launch {
            while (true) {
                delay(60000) // 每分钟更新一次
                updateStatusBar()
            }
        }
    }
    
    /**
     * 更新状态栏（时间和电量）
     */
    private fun updateStatusBar() {
        try {
            if (!_isCustomViewOpen.value) return
            
            val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            
            val updateViewJson = UpdateViewJson().apply {
                updateList.add(UpdateViewJson.UpdateJson(id = "statusBar").apply {
                    props["text"] = "$currentTime                     电量: $currentBattery%"
                })
            }
            
            CxrApi.getInstance().updateCustomView(updateViewJson.toJson())
        } catch (e: Exception) {
            Log.e(TAG, "更新状态栏失败", e)
        }
    }
    
    /**
     * 更新已打开的自定义View的文字内容
     * @param text 新的文字内容
     */
    fun updateText(text: String) {
        try {
            if (!_isCustomViewOpen.value) {
                Log.w(TAG, "自定义View未打开，无法更新")
                return
            }
            
            val updateViewJson = UpdateViewJson().apply {
                updateList.add(UpdateViewJson.UpdateJson(id = "textView").apply {
                    props["text"] = text
                })
            }
            
            CxrApi.getInstance().updateCustomView(updateViewJson.toJson())
            Log.d(TAG, "文字已更新: $text")
        } catch (e: Exception) {
            Log.e(TAG, "更新文字失败", e)
        }
    }
    
    /**
     * 关闭自定义View
     */
    fun closeView() {
        try {
            if (_isCustomViewOpen.value) {
                // 停止时间更新
                timeUpdateJob?.cancel()
                timeUpdateJob = null
                
                CxrApi.getInstance().closeCustomView()
                Log.d(TAG, "自定义View已关闭")
            }
        } catch (e: Exception) {
            Log.e(TAG, "关闭自定义View失败", e)
        }
    }

    /**
     * 刷新眼镜屏幕显示（重新渲染自定义界面）
     */
    fun refreshScreen() {
        try {
            currentDialogText = ""
            closeView()
        } catch (e: Exception) {
            Log.e(TAG, "刷新眼镜屏幕失败", e)
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        try {
            closeView()
            CxrApi.getInstance().setCustomViewListener(null)
            CxrApi.getInstance().setSceneStatusUpdateListener(null)
            CxrApi.getInstance().setBatteryLevelUpdateListener(null)
            _iconsSent.value = false
        } catch (e: Exception) {
            Log.e(TAG, "清理资源失败", e)
        }
    }
    
    /**
     * 将Drawable对象转换为Bitmap对象
     */
    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }
        
        val bitmap = createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        
        return bitmap
    }
}
