package com.example.autocopy

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** 保存文本到 下载/AutoCopy/，服务和兜底入口共用 */
object TextSaver {
    const val DIR_NAME = "AutoCopy"

    /** 返回文件名；失败返回 null（原因写诊断日志） */
    fun save(context: Context, text: String): String? {
        return try {
            val name = "copy_${System.currentTimeMillis()}.txt"
            val bytes = text.toByteArray()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/$DIR_NAME")
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    DiagLog.e("TextSaver", "insert returned null uri")
                    return null
                }
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), DIR_NAME)
                dir.mkdirs()
                File(dir, name).writeBytes(bytes)
            }
            name
        } catch (e: Throwable) {
            DiagLog.e("TextSaver", "save failed", e)
            null
        }
    }
}
