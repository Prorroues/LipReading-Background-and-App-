package com.rokid.cxrmsamples.services.weather

import android.content.Context
import android.location.Location
import android.util.Log
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.amap.api.services.weather.WeatherSearch
import com.amap.api.services.weather.WeatherSearchQuery
import com.amap.api.services.weather.LocalWeatherLive
import com.amap.api.services.weather.WeatherSearch.OnWeatherSearchListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 天气服务
 * 使用高德SDK提供定位和天气查询功能
 */
class WeatherService(private val context: Context) {
    private val TAG = "WeatherService"
    
    private var locationClient: AMapLocationClient? = null
    private var weatherSearch: WeatherSearch? = null
    private var cachedLocation: AMapLocation? = null
    
    /**
     * 天气信息数据类
     */
    data class WeatherInfo(
        val city: String,
        val weather: String,
        val temperature: String,
        val windDirection: String,
        val windPower: String,
        val humidity: String,
        val reportTime: String
    ) {
        fun toFormattedString(): String {
            return "${city}：${weather}，温度${temperature}℃，${windDirection}${windPower}级，湿度${humidity}%"
        }
    }

    /**
     * 位置 + 天气的组合结果
     */
    data class LocationWeatherResult(
        val address: String,
        val city: String,
        val weatherInfo: WeatherInfo
    )
    
    /**
     * 初始化定位服务
     */
    fun initialize() {
        try {
            // 高德定位隐私合规（初始化前必须调用）
            AMapLocationClient.updatePrivacyShow(context.applicationContext, true, true)
            AMapLocationClient.updatePrivacyAgree(context.applicationContext, true)
            // 初始化定位客户端
            locationClient = AMapLocationClient(context.applicationContext)
            
            // 设置定位参数
            val locationOption = AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                isOnceLocation = true
                isOnceLocationLatest = true
                isNeedAddress = true
                isMockEnable = false
                httpTimeOut = 30000
            }
            locationClient?.setLocationOption(locationOption)
            
            // 初始化天气搜索
            weatherSearch = WeatherSearch(context.applicationContext)
            
            Log.d(TAG, "天气服务初始化完成")
        } catch (e: Exception) {
            Log.e(TAG, "天气服务初始化失败", e)
        }
    }

    /**
     * 启动时缓存一次定位信息
     */
    suspend fun preloadLocation(): AMapLocation? = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            try {
                if (locationClient == null) {
                    continuation.resumeWithException(Exception("定位服务未初始化"))
                    return@suspendCancellableCoroutine
                }

                val listener = object : AMapLocationListener {
                    override fun onLocationChanged(location: AMapLocation) {
                        locationClient?.stopLocation()
                        locationClient?.onDestroy()

                        if (location.errorCode == AMapLocation.LOCATION_SUCCESS) {
                            cachedLocation = location
                            Log.d(TAG, "启动定位缓存成功: ${formatAddress(location)}")
                            continuation.resume(location)
                        } else {
                            val errorMsg = "定位失败: ${location.errorCode} - ${location.errorInfo}"
                            Log.e(TAG, errorMsg)
                            continuation.resumeWithException(Exception(errorMsg))
                        }
                    }
                }

                locationClient?.setLocationListener(listener)
                locationClient?.startLocation()

                continuation.invokeOnCancellation {
                    locationClient?.stopLocation()
                }
            } catch (e: Exception) {
                Log.e(TAG, "启动定位缓存失败", e)
                continuation.resumeWithException(e)
            }
        }
    }
    
    /**
     * 获取当前位置
     */
    suspend fun getCurrentLocation(): Location? = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            try {
                if (locationClient == null) {
                    continuation.resumeWithException(Exception("定位服务未初始化"))
                    return@suspendCancellableCoroutine
                }
                
                val listener = object : AMapLocationListener {
                    override fun onLocationChanged(location: AMapLocation) {
                        locationClient?.stopLocation()
                        locationClient?.onDestroy()
                        
                        if (location.errorCode == AMapLocation.LOCATION_SUCCESS) {
                            val androidLocation = Location("AMap")
                            androidLocation.latitude = location.latitude
                            androidLocation.longitude = location.longitude
                            androidLocation.accuracy = location.accuracy
                            androidLocation.time = location.time
                            
                            Log.d(TAG, "定位成功: ${location.city}, ${location.latitude}, ${location.longitude}")
                            continuation.resume(androidLocation)
                        } else {
                            val errorMsg = "定位失败: ${location.errorCode} - ${location.errorInfo}"
                            Log.e(TAG, errorMsg)
                            continuation.resumeWithException(Exception(errorMsg))
                        }
                    }
                }
                
                locationClient?.setLocationListener(listener)
                locationClient?.startLocation()
                
                continuation.invokeOnCancellation {
                    locationClient?.stopLocation()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "获取位置失败", e)
                continuation.resumeWithException(e)
            }
        }
    }
    
    /**
     * 查询天气
     * @param city 城市名称，如果为null则使用当前位置
     */
    suspend fun queryWeather(city: String? = null): WeatherInfo = withContext(Dispatchers.IO) {
        // 如果city为null，优先使用AdCode，否则使用城市名称
        val queryKey = if (city != null) {
            city
        } else {
            try {
                val adCode = cachedLocation?.adCode ?: getCurrentAdCode()
                if (!adCode.isNullOrBlank()) {
                    adCode
                } else {
                    val currentCity = cachedLocation?.city ?: getCurrentCity()
                    if (!currentCity.isNullOrBlank()) {
                        currentCity
                    } else {
                        throw Exception("无法获取位置，请手动指定城市名称")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "获取位置失败，降级策略：提示用户指定城市", e)
                throw Exception("定位失败，请手动指定城市名称")
            }
        }
        
        suspendCancellableCoroutine { continuation ->
            try {
                if (weatherSearch == null) {
                    continuation.resumeWithException(Exception("天气服务未初始化"))
                    return@suspendCancellableCoroutine
                }
                
                val query = WeatherSearchQuery(queryKey, WeatherSearchQuery.WEATHER_TYPE_LIVE)
                
                val listener = object : OnWeatherSearchListener {
                    override fun onWeatherLiveSearched(weatherLiveResult: com.amap.api.services.weather.LocalWeatherLiveResult?, rCode: Int) {
                        if (rCode == 1000) {
                            val liveWeather = weatherLiveResult?.liveResult
                            if (liveWeather != null) {
                                val weatherInfo = WeatherInfo(
                                    city = liveWeather.city ?: (cachedLocation?.city ?: queryKey),
                                    weather = liveWeather.weather ?: "未知",
                                    temperature = liveWeather.temperature ?: "未知",
                                    windDirection = liveWeather.windDirection ?: "未知",
                                    windPower = liveWeather.windPower ?: "未知",
                                    humidity = liveWeather.humidity ?: "未知",
                                    reportTime = liveWeather.reportTime ?: "未知"
                                )
                                Log.d(TAG, "天气查询成功: ${weatherInfo.toFormattedString()}")
                                continuation.resume(weatherInfo)
                            } else {
                                continuation.resumeWithException(Exception("天气数据为空"))
                            }
                        } else {
                            val errorMsg = if (rCode == 1008) {
                                "天气查询失败: $rCode (Key/SHA1鉴权失败, query=$queryKey)"
                            } else {
                                "天气查询失败: $rCode (query=$queryKey)"
                            }
                            Log.e(TAG, errorMsg)
                            continuation.resumeWithException(Exception(errorMsg))
                        }
                    }
                    
                    override fun onWeatherForecastSearched(weatherForecastResult: com.amap.api.services.weather.LocalWeatherForecastResult?, rCode: Int) {
                        // 不需要实现
                    }
                }
                
                weatherSearch?.setOnWeatherSearchListener(listener)
                weatherSearch?.query = query
                weatherSearch?.searchWeatherAsyn()
                
            } catch (e: Exception) {
                Log.e(TAG, "查询天气失败", e)
                continuation.resumeWithException(e)
            }
        }
    }

    /**
     * 查询当前位置（城市+经纬度）
     */
    suspend fun queryLocationInfo(): String = withContext(Dispatchers.IO) {
        val cached = cachedLocation
        if (cached != null) {
            return@withContext formatAddress(cached, includeLatLng = false)
        }
        suspendCancellableCoroutine { continuation ->
            try {
                if (locationClient == null) {
                    continuation.resumeWithException(Exception("定位服务未初始化"))
                    return@suspendCancellableCoroutine
                }

                val listener = object : AMapLocationListener {
                    override fun onLocationChanged(location: AMapLocation) {
                        locationClient?.stopLocation()
                        locationClient?.onDestroy()

                        if (location.errorCode == AMapLocation.LOCATION_SUCCESS) {
                            cachedLocation = location
                            val result = formatAddress(location, includeLatLng = false)
                            Log.d(TAG, "定位结果: $result")
                            continuation.resume(result)
                        } else {
                            val errorMsg = "定位失败: ${location.errorCode} - ${location.errorInfo}"
                            Log.e(TAG, errorMsg)
                            continuation.resumeWithException(Exception(errorMsg))
                        }
                    }
                }

                locationClient?.setLocationListener(listener)
                locationClient?.startLocation()

                continuation.invokeOnCancellation {
                    locationClient?.stopLocation()
                }
            } catch (e: Exception) {
                Log.e(TAG, "查询位置失败", e)
                continuation.resumeWithException(e)
            }
        }
    }

    /**
     * 获取当前位置（包含地址），成功后自动查询天气
     */
    suspend fun queryLocationAndWeather(): LocationWeatherResult = withContext(Dispatchers.IO) {
        val location = getLocationWithAddress()
        val adCode = location.adCode
        val city = location.city
        val queryKey = adCode?.takeIf { it.isNotBlank() } ?: city
        if (queryKey.isNullOrBlank()) {
            throw Exception("获取到的城市为空，无法查询天气")
        }
        val weatherInfo = queryWeather(queryKey)
        val address = formatAddress(location, includeLatLng = false)
        val displayCity = city ?: weatherInfo.city
        LocationWeatherResult(address = address, city = displayCity, weatherInfo = weatherInfo)
    }

    /**
     * 获取当前城市名称
     */
    private suspend fun getCurrentCity(): String? = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            try {
                if (locationClient == null) {
                    continuation.resumeWithException(Exception("定位服务未初始化"))
                    return@suspendCancellableCoroutine
                }

                val listener = object : AMapLocationListener {
                    override fun onLocationChanged(location: AMapLocation) {
                        locationClient?.stopLocation()
                        locationClient?.onDestroy()

                        if (location.errorCode == AMapLocation.LOCATION_SUCCESS) {
                            cachedLocation = location
                            val city = location.city
                            Log.d(TAG, "定位成功，城市: $city")
                            continuation.resume(city)
                        } else {
                            val errorMsg = "定位失败: ${location.errorCode} - ${location.errorInfo}"
                            Log.e(TAG, errorMsg)
                            continuation.resumeWithException(Exception(errorMsg))
                        }
                    }
                }

                locationClient?.setLocationListener(listener)
                locationClient?.startLocation()

                continuation.invokeOnCancellation {
                    locationClient?.stopLocation()
                }
            } catch (e: Exception) {
                Log.e(TAG, "获取城市失败", e)
                continuation.resumeWithException(e)
            }
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        try {
            locationClient?.stopLocation()
            locationClient?.onDestroy()
            locationClient = null
            weatherSearch = null
            cachedLocation = null
            Log.d(TAG, "天气服务资源已释放")
        } catch (e: Exception) {
            Log.e(TAG, "释放资源失败", e)
        }
    }

    private fun formatAddress(location: AMapLocation, includeLatLng: Boolean = true): String {
        val province = location.province ?: ""
        val city = location.city ?: ""
        val district = location.district ?: ""
        val aoiName = location.aoiName ?: ""
        val poiName = location.poiName ?: ""
        val street = location.street ?: ""
        val streetNum = location.streetNum ?: ""
        val address = location.address ?: ""
        val lat = location.latitude
        val lng = location.longitude

        val detail = joinUniqueNonBlank(
            province,
            city,
            district,
            aoiName,
            poiName,
            street,
            streetNum
        )
        val main = if (detail.isNotBlank()) detail else address
        return if (main.isNotBlank()) {
            if (includeLatLng) "$main（$lat,$lng）" else main
        } else {
            if (includeLatLng) "未知位置（$lat,$lng）" else "未知位置"
        }
    }

    private fun joinUniqueNonBlank(vararg parts: String): String {
        val unique = LinkedHashSet<String>()
        parts.forEach { part ->
            val trimmed = part.trim()
            if (trimmed.isNotBlank()) {
                unique.add(trimmed)
            }
        }
        return unique.joinToString("")
    }

    private suspend fun getCurrentAdCode(): String? = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            try {
                if (locationClient == null) {
                    continuation.resumeWithException(Exception("定位服务未初始化"))
                    return@suspendCancellableCoroutine
                }

                val listener = object : AMapLocationListener {
                    override fun onLocationChanged(location: AMapLocation) {
                        locationClient?.stopLocation()
                        locationClient?.onDestroy()

                        if (location.errorCode == AMapLocation.LOCATION_SUCCESS) {
                            cachedLocation = location
                            val adCode = location.adCode
                            Log.d(TAG, "定位成功，AdCode: $adCode")
                            continuation.resume(adCode)
                        } else {
                            val errorMsg = "定位失败: ${location.errorCode} - ${location.errorInfo}"
                            Log.e(TAG, errorMsg)
                            continuation.resumeWithException(Exception(errorMsg))
                        }
                    }
                }

                locationClient?.setLocationListener(listener)
                locationClient?.startLocation()

                continuation.invokeOnCancellation {
                    locationClient?.stopLocation()
                }
            } catch (e: Exception) {
                Log.e(TAG, "获取AdCode失败", e)
                continuation.resumeWithException(e)
            }
        }
    }

    private suspend fun getLocationWithAddress(): AMapLocation = withContext(Dispatchers.IO) {
        val cached = cachedLocation
        if (cached != null && !cached.address.isNullOrBlank()) {
            return@withContext cached
        }
        suspendCancellableCoroutine { continuation ->
            val client = AMapLocationClient(context.applicationContext)
            val option = AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                isOnceLocation = true
                isOnceLocationLatest = true
                isNeedAddress = true
                isMockEnable = false
                httpTimeOut = 30000
            }
            client.setLocationOption(option)
            client.setLocationListener(object : AMapLocationListener {
                override fun onLocationChanged(location: AMapLocation?) {
                    if (location != null && location.errorCode == AMapLocation.LOCATION_SUCCESS) {
                        cachedLocation = location
                        Log.d(TAG, "定位成功: ${formatAddress(location, includeLatLng = false)}")
                        continuation.resume(location)
                    } else if (location != null) {
                        val errorMsg = "定位失败: ${location.errorCode} - ${location.errorInfo}"
                        Log.e(TAG, errorMsg)
                        continuation.resumeWithException(Exception(errorMsg))
                    } else {
                        continuation.resumeWithException(Exception("定位返回空结果"))
                    }
                    client.stopLocation()
                    client.onDestroy()
                }
            })
            client.startLocation()

            continuation.invokeOnCancellation {
                client.stopLocation()
                client.onDestroy()
            }
        }
    }
}
