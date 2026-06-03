package com.rokid.cxrmsamples.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 权限管理工具类
 */
object PermissionUtils {
    
    // 权限请求码
    const val REQUEST_CAMERA_PERMISSION = 1001
    const val REQUEST_WRITE_SETTINGS_PERMISSION = 1002
    const val REQUEST_ALL_PERMISSIONS = 1003
    
    /**
     * 检查相机权限
     */
    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * 检查修改系统设置权限（用于调整亮度）
     */
    fun hasWriteSettingsPermission(context: Context): Boolean {
        return Settings.System.canWrite(context)
    }
    
    /**
     * 请求相机权限
     */
    fun requestCameraPermission(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.CAMERA),
            REQUEST_CAMERA_PERMISSION
        )
    }
    
    /**
     * 请求修改系统设置权限
     */
    fun requestWriteSettingsPermission(activity: Activity) {
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
        intent.data = Uri.parse("package:${activity.packageName}")
        activity.startActivityForResult(intent, REQUEST_WRITE_SETTINGS_PERMISSION)
    }
    
    /**
     * 检查所有必要权限
     */
    fun hasAllPermissions(context: Context): Boolean {
        return hasCameraPermission(context) && hasWriteSettingsPermission(context)
    }
    
    /**
     * 请求所有必要权限
     */
    fun requestAllPermissions(activity: Activity) {
        val permissions = mutableListOf<String>()
        
        if (!hasCameraPermission(activity)) {
            permissions.add(Manifest.permission.CAMERA)
        }
        
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                permissions.toTypedArray(),
                REQUEST_ALL_PERMISSIONS
            )
        }
        
        // 修改系统设置权限需要单独申请
        if (!hasWriteSettingsPermission(activity)) {
            requestWriteSettingsPermission(activity)
        }
    }
    
    /**
     * 处理权限请求结果
     */
    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
        onGranted: () -> Unit,
        onDenied: () -> Unit
    ) {
        when (requestCode) {
            REQUEST_CAMERA_PERMISSION,
            REQUEST_ALL_PERMISSIONS -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    onGranted()
                } else {
                    onDenied()
                }
            }
        }
    }
}
