package com.rokid.cxrmsamples.utils

import android.content.Context
import android.os.Environment
import java.io.File

object MediaPathProvider {
    private const val MEDIA_ROOT_DIR_NAME = "Rokid/Media"

    fun getRootDir(): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val mediaDir = File(downloadsDir, MEDIA_ROOT_DIR_NAME)
        if (!mediaDir.exists()) {
            mediaDir.mkdirs()
        }
        return mediaDir
    }

    fun getRootDir(context: Context): File {
        return getRootDir()
    }

    fun getRootPath(context: Context): String = getRootDir(context).absolutePath

    fun getRootPath(): String = getRootDir().absolutePath
}
