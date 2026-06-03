package com.rokid.cxrmsamples.managers

import android.util.Log
import com.rokid.cxrmsamples.utils.MediaPathProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ErrorReport(
    val timestamp: Long,
    val source: String,
    val stage: String,
    val message: String,
    val detail: String? = null
)

/**
 * 全局错误报告器
 * 收集最近的错误信息，便于排查唇语识别与同步问题
 */
object ErrorReporter {
    private const val TAG = "ErrorReporter"
    private const val MAX_REPORTS = 50
    private const val LOG_FILE_NAME = "error_reports.log"
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _reports = MutableStateFlow<List<ErrorReport>>(emptyList())
    val reports: StateFlow<List<ErrorReport>> = _reports.asStateFlow()

    fun report(source: String, stage: String, message: String, detail: String? = null) {
        val report = ErrorReport(
            timestamp = System.currentTimeMillis(),
            source = source,
            stage = stage,
            message = message,
            detail = detail
        )
        val newList = (_reports.value + report).takeLast(MAX_REPORTS)
        _reports.value = newList

        val time = timeFormat.format(Date(report.timestamp))
        val detailPart = detail?.let { " | detail=$it" } ?: ""
        Log.e(TAG, "[$time][$source][$stage] $message$detailPart")
        appendToFile(time, source, stage, message, detail)
    }

    private fun appendToFile(
        time: String,
        source: String,
        stage: String,
        message: String,
        detail: String?
    ) {
        try {
            val dir = MediaPathProvider.getRootDir()
            val logFile = File(dir, LOG_FILE_NAME)
            val detailPart = detail?.let { " | detail=$it" } ?: ""
            val line = "[$time][$source][$stage] $message$detailPart\n"
            logFile.appendText(line)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write error report", e)
        }
    }
}
