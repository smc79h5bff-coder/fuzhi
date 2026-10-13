package com.example.autocopy

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.io.File

class CopyAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var floatView: View? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        showFloatingButton()
    }

    private fun showFloatingButton() {
        if (floatView != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatView = LayoutInflater.from(this).inflate(R.layout.floating_button, null)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 20
        }

        floatView!!.setOnClickListener { doCopyAndSave() }
        windowManager!!.addView(floatView, params)
    }

    private fun doCopyAndSave() {
        // 先尝试直接点“复制”
        if (clickByText("复制", "Copy", "拷贝", "复制文本")) {
            handler.postDelayed({ saveClipboard() }, 400)
            return
        }
        // 没找到就点“全选”，再点“复制”
        clickByText("全选", "Select all", "全选文本")
        handler.postDelayed({
            clickByText("复制", "Copy", "拷贝", "复制文本")
            handler.postDelayed({ saveClipboard() }, 400)
        }, 500)
    }

    /** 在所有窗口里找指定文字的节点，向上找可点击父节点并点击 */
    private fun clickByText(vararg labels: String): Boolean {
        if (clickInRoot(rootInActiveWindow, labels)) return true
        for (w in windows) {
            if (clickInRoot(w.root, labels)) return true
        }
        return false
    }

    private fun clickInRoot(root: AccessibilityNodeInfo?,
                            labels: Array<out String>): Boolean {
        root ?: return false
        for (label in labels) {
            val nodes = root.findAccessibilityNodeInfosByText(label) ?: continue
            for (node in nodes) {
                var n: AccessibilityNodeInfo? = node
                while (n != null) {
                    if (n.isClickable && n.isEnabled) {
                        if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            return true
                        }
                    }
                    n = n.parent
                }
            }
        }
        return false
    }

    private fun saveClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrEmpty()) {
            toast("剪贴板为空，可能复制没生效")
            return
        }
        try {
            val name = "copy_${System.currentTimeMillis()}.txt"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/AutoCopy")
                }
                val uri = contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                contentResolver.openOutputStream(uri!!)?.use {
                    it.write(text.toByteArray())
                }
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "AutoCopy")
                dir.mkdirs()
                File(dir, name).writeText(text)
            }
            toast("已保存：$name（${text.length} 字）")
        } catch (e: Exception) {
            toast("保存失败：${e.message}")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        floatView?.let { windowManager?.removeView(it) }
        floatView = null
        super.onDestroy()
    }
}
