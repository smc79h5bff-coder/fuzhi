package com.example.autocopy

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.io.File

class CopyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoCopy"
        private const val MAX_DEPTH = 40
        private const val MAX_NODES = 5000
    }

    private var windowManager: WindowManager? = null
    private var floatView: View? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "onServiceConnected")
        try {
            showFloatingButton()
        } catch (e: Exception) {
            Log.e(TAG, "showFloatingButton failed", e)
            toast("悬浮窗创建失败：" + e.message)
        }
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

        // 单击 = 复制并保存
        floatView!!.setOnClickListener {
            Log.d(TAG, "float button clicked")
            try {
                doCopyAndSave()
            } catch (e: Exception) {
                Log.e(TAG, "doCopyAndSave failed", e)
                toast("出错：" + e.message)
            }
        }

        // 长按 = 把当前节点树 dump 到文件
        floatView!!.setOnLongClickListener {
            toast("开始 dump，请稍等")
            handler.postDelayed({
                try {
                    val path = dumpAllWindowsToFile()
                    toast("Dump 完成：$path")
                } catch (e: Exception) {
                    Log.e(TAG, "dump failed", e)
                    toast("Dump 失败：" + e.message)
                }
            }, 600)
            true
        }

        windowManager!!.addView(floatView, params)
    }

    // ---------------- 复制逻辑 ----------------

    private fun doCopyAndSave() {
        // 路径 1：对焦点节点直接 ACTION_COPY
        val focus = findFocusNode()
        Log.d(TAG, "focus node = $focus")
        if (focus != null) {
            val ok = focus.performAction(AccessibilityNodeInfo.ACTION_COPY)
            Log.d(TAG, "ACTION_COPY on focus = $ok")
            if (ok) {
                handler.postDelayed({ saveClipboard() }, 400)
                return
            }
        }

        // 路径 2：找“复制”按钮点
        if (clickByText("复制", "Copy", "拷贝", "复制文本")) {
            Log.d(TAG, "clicked 复制")
            handler.postDelayed({ saveClipboard() }, 400)
            return
        }

        // 路径 3：找“全选”点，再找“复制”
        if (clickByText("全选", "Select all", "全选文本")) {
            Log.d(TAG, "clicked 全选, waiting for copy button")
            handler.postDelayed({
                if (clickByText("复制", "Copy", "拷贝", "复制文本")) {
                    Log.d(TAG, "clicked 复制 after 全选")
                } else {
                    Log.d(TAG, "copy button not found after 全选")
                    toast("没找到复制按钮，可长按悬浮按钮 dump 节点")
                }
                handler.postDelayed({ saveClipboard() }, 400)
            }, 500)
            return
        }

        Log.d(TAG, "nothing found")
        toast("没找到可复制的内容，可长按悬浮按钮 dump 节点")
    }

    private fun findFocusNode(): AccessibilityNodeInfo? {
        try {
            val root = rootInActiveWindow ?: return null
            return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (e: Exception) {
            Log.e(TAG, "findFocusNode", e)
            return null
        }
    }

    private fun clickByText(vararg labels: String): Boolean {
        try {
            if (clickInRoot(rootInActiveWindow, labels)) return true
        } catch (e: Exception) {
            Log.e(TAG, "rootInActiveWindow", e)
        }
        try {
            val ws = windows ?: return false
            for (w in ws) {
                try {
                    if (clickInRoot(w.root, labels)) return true
                } catch (e: Exception) {
                    Log.e(TAG, "window root", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "windows", e)
        }
        return false
    }

    private fun clickInRoot(root: AccessibilityNodeInfo?,
                            labels: Array<out String>): Boolean {
        root ?: return false
        val counter = intArrayOf(0)
        for (label in labels) {
            try {
                val nodes = root.findAccessibilityNodeInfosByText(label) ?: continue
                for (node in nodes) {
                    if (counter[0]++ > MAX_NODES) return false
                    var n: AccessibilityNodeInfo? = node
                    var depth = 0
                    while (n != null && depth++ < MAX_DEPTH) {
                        if (n.isClickable && n.isEnabled) {
                            Log.d(TAG, "click node: $n  label=$label")
                            if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                                return true
                            }
                        }
                        n = n.parent
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "clickInRoot label=$label", e)
            }
        }
        return false
    }

    private fun saveClipboard() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (text.isNullOrEmpty()) {
                Log.d(TAG, "clipboard empty")
                toast("剪贴板为空，复制没生效")
                return
            }
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
            Log.d(TAG, "saved $name len=${text.length}")
            toast("已保存：$name（${text.length} 字）")
        } catch (e: Exception) {
            Log.e(TAG, "saveClipboard", e)
            toast("保存失败：" + e.message)
        }
    }

    // ---------------- Dump 节点到文件 ----------------

    private fun dumpAllWindowsToFile(): String {
        val sb = StringBuilder()
        sb.append("===== DUMP START =====\n")
        sb.append("time=").append(System.currentTimeMillis()).append("\n\n")

        try {
            val ws = windows
            sb.append("windows count = ").append(ws?.size ?: 0).append("\n")
            ws?.forEachIndexed { i, w ->
                sb.append("--- window[$i] type=").append(w.type)
                    .append(" root=").append(w.root).append("\n")
                appendNode(sb, w.root, 0)
            }
        } catch (e: Exception) {
            sb.append("windows() failed: ").append(e.message).append("\n")
        }

        sb.append("\n--- active window root ---\n")
        appendNode(sb, rootInActiveWindow, 0)

        sb.append("===== DUMP END =====\n")

        val fileName = "dump_${System.currentTimeMillis()}.txt"
        val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/AutoCopy")
            }
            val uri = contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            contentResolver.openOutputStream(uri!!)?.use {
                it.write(sb.toString().toByteArray())
            }
            "下载/AutoCopy/$fileName"
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "AutoCopy")
            dir.mkdirs()
            val f = File(dir, fileName)
            f.writeText(sb.toString())
            f.absolutePath
        }

        Log.d(TAG, "dumped to $path")
        return path
    }

    private fun appendNode(sb: StringBuilder,
                           node: AccessibilityNodeInfo?,
                           depth: Int) {
        if (node == null || depth > MAX_DEPTH) return

        val text = node.text?.toString()?.replace("\n", "\\n") ?: ""
        val desc = node.contentDescription?.toString()?.replace("\n", "\\n") ?: ""
        val cls = node.className?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        val bounds = Rect().also { node.getBoundsInScreen(it) }

        val indent = "  ".repeat(depth)
        if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable) {
            sb.append(indent)
                .append("cls=").append(cls)
                .append(" click=").append(node.isClickable)
                .append(" enabled=").append(node.isEnabled)
                .append(" focus=").append(node.isFocused)
                .append(" sel=").append(node.isSelected)
                .append(" bounds=").append(bounds)
                .append(" id=").append(viewId)
                .append(" text=[").append(text).append("]")
                .append(" desc=[").append(desc).append("]")
                .append("\n")
        }

        for (i in 0 until node.childCount) {
            try {
                appendNode(sb, node.getChild(i), depth + 1)
            } catch (e: Exception) {
                sb.append(indent).append(" child ").append(i)
                    .append(" failed: ").append(e.message).append("\n")
            }
        }
    }

    // ---------------- 通用 ----------------

    private fun toast(msg: String) {
        try {
            handler.post {
                Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        try {
            floatView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        floatView = null
        super.onDestroy()
    }
}
