package com.example.autocopy

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Path
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CopyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoCopy"
        private const val MAX_DEPTH = 30
        private const val MAX_NODES = 3000
        private const val MAX_DUMP_NODES = 8000
        private const val DIR_NAME = "AutoCopy"
        private const val LOG_FILE = "last_dump.txt"
    }

    private val dumpNodeCount = java.util.concurrent.atomic.AtomicInteger(0)

    private var windowManager: WindowManager? = null
    private var floatView: View? = null
    // 记录用户在目标 App 里最后点击的位置，长按兜底时用
    private var lastClickBounds: Rect? = null
    private val handler = Handler(Looper.getMainLooper())
    private val bgHandler = Handler(
        android.os.HandlerThread("AutoCopyBg").apply { start() }.looper)

    override fun onServiceConnected() {
        super.onServiceConnected()
        DiagLog.init(this)
        logLine("onServiceConnected —— 服务已连接")
        logEnvSnapshot()
        try {
            showFloatingButton()
        } catch (e: Throwable) {
            logError("showFloatingButton", e)
            toast("悬浮窗创建失败：" + e.message)
        }
    }

    /** 连接时记录环境快照，方便对照"哪个机型/什么权限状态下出问题" */
    private fun logEnvSnapshot() {
        try {
            DiagLog.i(TAG, "device=${Build.MANUFACTURER} ${Build.MODEL} " +
                    "android=${Build.VERSION.RELEASE}(sdk ${Build.VERSION.SDK_INT})")
            val overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                    Settings.canDrawOverlays(this)
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            DiagLog.i(TAG, "overlayPermission=$overlayOk " +
                    "batteryOptIgnored=${pm.isIgnoringBatteryOptimizations(packageName)}")
            val si = serviceInfo
            DiagLog.i(TAG, "serviceInfo events=${si?.eventTypes} " +
                    "flags=${si?.flags} capabilities=${si?.capabilities}")
        } catch (e: Throwable) {
            logError("logEnvSnapshot", e)
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 20
        }

        // 单击 = 复制并保存
        floatView!!.setOnClickListener {
            logLine("float button CLICK")
            bgHandler.post {
                try {
                    doCopyAndSave()
                } catch (e: Throwable) {
                    logError("doCopyAndSave", e)
                    toast("出错：" + e.message)
                }
            }
        }

        // 长按 = dump 到固定文件
        floatView!!.setOnLongClickListener {
            logLine("float button LONG CLICK")
            toast("开始 dump，请稍等")
            bgHandler.post {
                try {
                    dumpAllWindowsToFile()
                    toast("Dump 完成：下载/$DIR_NAME/$LOG_FILE")
                } catch (e: Throwable) {
                    logError("dump", e)
                    toast("Dump 失败：" + e.message)
                }
            }
            true
        }

        windowManager!!.addView(floatView, params)
    }

    // ---------------- 复制逻辑 ----------------

    private fun doCopyAndSave() {
        val before = clipboardText()
        val focus = safeFocusNode()
        logLine("focus node = $focus")

        // 1) 焦点在输入框/文本上：有选中文字直接复制，没选中先全选再复制
        if (focus != null && tryCopyFromFocus(focus)) {
            Thread.sleep(400)
            saveClipboard(before)
            return
        }

        // 2) 屏幕上已有"复制"按钮（比如已长按弹出的选择菜单）
        if (clickByText("复制", "Copy", "拷贝", "复制文本")) {
            logLine("clicked 复制")
            Thread.sleep(400)
            saveClipboard(before)
            return
        }

        // 3) 兜底：在焦点/最后点击位置模拟长按，唤出系统选择菜单再点复制
        //    这样 WebView、自绘控件等无障碍树里没有"复制"按钮的场景也能用
        val bounds = lastClickBounds ?: nodeBounds(focus)
        if (bounds != null && longPressAt(bounds)) {
            logLine("long-press at $bounds, waiting for menu")
            Thread.sleep(800)
            if (clickByText("复制", "Copy", "拷贝", "复制文本")) {
                logLine("clicked 复制 after long-press")
                Thread.sleep(400)
                saveClipboard(before)
                return
            }
            if (clickByText("全选", "Select all", "全选文本")) {
                logLine("clicked 全选 after long-press")
                Thread.sleep(500)
                clickByText("复制", "Copy", "拷贝", "复制文本")
                Thread.sleep(400)
                saveClipboard(before)
                return
            }
        }

        // 4) 屏幕上有"全选"（普通输入框场景）
        if (clickByText("全选", "Select all", "全选文本")) {
            logLine("clicked 全选, waiting for copy button")
            Thread.sleep(500)
            if (clickByText("复制", "Copy", "拷贝", "复制文本")) {
                logLine("clicked 复制 after 全选")
            } else {
                logLine("copy button not found after 全选")
                toast("没找到复制按钮，长按悬浮按钮看 dump 文件")
            }
            Thread.sleep(400)
            saveClipboard(before)
            return
        }

        logLine("nothing found")
        toast("没找到可复制的内容，长按悬浮按钮看 dump 文件")
    }

    private fun tryCopyFromFocus(f: AccessibilityNodeInfo): Boolean {
        return try {
            val start = f.textSelectionStart
            val end = f.textSelectionEnd
            val hasSelection = start >= 0 && start != end
            if (!hasSelection && !f.isEditable) {
                logLine("focus has no selection and is not editable, skip")
                return false
            }
            if (!hasSelection) {
                if (!f.performAction(AccessibilityNodeInfo.ACTION_SELECT_ALL)) return false
                Thread.sleep(150)
            }
            val ok = f.performAction(AccessibilityNodeInfo.ACTION_COPY)
            logLine("focus copy hasSel=$hasSel editable=${f.isEditable} ok=$ok")
            ok
        } catch (e: Throwable) {
            logError("tryCopyFromFocus", e)
            false
        }
    }

    private fun longPressAt(bounds: Rect): Boolean {
        return try {
            val path = Path().apply {
                moveTo(bounds.exactCenterX(), bounds.exactCenterY())
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 500)
            dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (e: Throwable) {
            logError("longPressAt", e)
            false
        }
    }

    private fun nodeBounds(n: AccessibilityNodeInfo?): Rect? {
        if (n == null) return null
        return try {
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.isEmpty) null else r
        } catch (_: Throwable) {
            null
        }
    }

    private fun clipboardText(): String? = try {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
    } catch (_: Throwable) {
        null
    }

    private fun safeFocusNode(): AccessibilityNodeInfo? {
        return try {
            val root = rootInActiveWindow ?: return null
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (e: Throwable) {
            logError("safeFocusNode", e); null
        }
    }

    private fun clickByText(vararg labels: String): Boolean {
        try {
            if (clickInRoot(rootInActiveWindow, labels)) return true
        } catch (e: Throwable) {
            logError("clickByText rootInActiveWindow", e)
        }
        try {
            val ws = windows ?: return false
            for (w in ws) {
                try {
                    if (clickInRoot(w.root, labels)) return true
                } catch (e: Throwable) {
                    logError("clickByText window root", e)
                }
            }
        } catch (e: Throwable) {
            logError("clickByText windows", e)
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
                        try {
                            if (n.isClickable && n.isEnabled) {
                                logLine("click node label=$label node=$n")
                                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                                    return true
                                }
                            }
                        } catch (e: Throwable) {
                            logError("performClick", e)
                        }
                        n = try { n.parent } catch (_: Throwable) { null }
                    }
                }
            } catch (e: Throwable) {
                logError("clickInRoot label=$label", e)
            }
        }
        return false
    }

    private fun saveClipboard(before: String?) {
        try {
            val text = clipboardText()
            if (text.isNullOrEmpty()) {
                logLine("clipboard empty")
                toast("剪贴板为空，复制没生效")
                return
            }
            // 剪贴板内容和复制前一样，说明复制动作实际没生效，
            // 读到的是上一次的内容，不能保存误导用户
            if (before != null && text == before) {
                logLine("clipboard unchanged, copy likely failed")
                toast("剪贴板没有变化，复制可能没生效")
                return
            }
            val name = "copy_${System.currentTimeMillis()}.txt"
            val bytes = text.toByteArray()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/$DIR_NAME")
                }
                val uri = contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    toast("保存失败：uri 为 null")
                    logLine("saveClipboard uri null")
                    return
                }
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), DIR_NAME)
                dir.mkdirs()
                File(dir, name).writeBytes(bytes)
            }
            logLine("saved $name len=${text.length}")
            toast("已保存：$name（${text.length} 字）")
        } catch (e: Throwable) {
            logError("saveClipboard", e)
            toast("保存失败：" + e.message)
        }
    }

    // ---------------- Dump 到固定文件 ----------------

    private fun dumpAllWindowsToFile() {
        val sb = StringBuilder()
        dumpNodeCount.set(0)
        sb.append("===== DUMP START =====\n")
        sb.append("time=").append(nowStr()).append("\n\n")

        try {
            val ws = windows
            sb.append("windows count = ").append(ws?.size ?: 0).append("\n")
            ws?.forEachIndexed { i, w ->
                try {
                    sb.append("--- window[$i] type=").append(w.type)
                        .append(" root=").append(w.root).append("\n")
                    appendNode(sb, w.root, 0)
                } catch (e: Throwable) {
                    sb.append("window[$i] failed: ").append(e.message).append("\n")
                }
            }
        } catch (e: Throwable) {
            sb.append("windows() failed: ").append(e.message).append("\n")
        }

        sb.append("\n--- active window root ---\n")
        try {
            appendNode(sb, rootInActiveWindow, 0)
        } catch (e: Throwable) {
            sb.append("active root failed: ").append(e.message).append("\n")
        }

        sb.append("===== DUMP END =====\n")
        writeFixedFile(LOG_FILE, sb.toString())
    }

    private fun appendNode(sb: StringBuilder,
                           node: AccessibilityNodeInfo?,
                           depth: Int) {
        if (node == null || depth > MAX_DEPTH) return
        // 无上限遍历复杂页面（如大 WebView）会 OOM 把服务杀掉，导致悬浮按钮消失
        if (dumpNodeCount.incrementAndGet() > MAX_DUMP_NODES) {
            sb.append("  ...max dump nodes reached\n")
            return
        }

        val text = try { node.text?.toString()?.replace("\n", "\\n") ?: "" }
                   catch (_: Throwable) { "" }
        val desc = try { node.contentDescription?.toString()?.replace("\n", "\\n") ?: "" }
                   catch (_: Throwable) { "" }
        val cls = try { node.className?.toString() ?: "" } catch (_: Throwable) { "" }
        val viewId = try { node.viewIdResourceName ?: "" } catch (_: Throwable) { "" }
        val bounds = try {
            Rect().also { node.getBoundsInScreen(it) }.toString()
        } catch (_: Throwable) { "" }

        val clickable = try { node.isClickable } catch (_: Throwable) { false }
        val enabled = try { node.isEnabled } catch (_: Throwable) { false }
        val focused = try { node.isFocused } catch (_: Throwable) { false }
        val selected = try { node.isSelected } catch (_: Throwable) { false }

        val indent = "  ".repeat(depth)
        if (text.isNotEmpty() || desc.isNotEmpty() || clickable) {
            sb.append(indent)
                .append("cls=").append(cls)
                .append(" click=").append(clickable)
                .append(" enabled=").append(enabled)
                .append(" focus=").append(focused)
                .append(" sel=").append(selected)
                .append(" bounds=").append(bounds)
                .append(" id=").append(viewId)
                .append(" text=[").append(text).append("]")
                .append(" desc=[").append(desc).append("]")
                .append("\n")
        }

        val childCount = try { node.childCount } catch (_: Throwable) { 0 }
        for (i in 0 until childCount) {
            try {
                appendNode(sb, node.getChild(i), depth + 1)
            } catch (e: Throwable) {
                sb.append(indent).append(" child ").append(i)
                    .append(" failed: ").append(e.message).append("\n")
            }
        }
    }

    // ---------------- 文件写入 ----------------

    private fun writeFixedFile(name: String, content: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 用 MediaStore，但固定名字会失败，改为找到已有的，或者创建时用固定名字
                val relPath = Environment.DIRECTORY_DOWNLOADS + "/$DIR_NAME"
                // 查询是否已存在
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? " +
                        "AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
                val args = arrayOf(name, "$relPath/")
                var uri = contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID),
                    selection, args, null
                )?.use { c -> if (c.moveToFirst())
                    android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        c.getLong(0))
                else null }

                if (uri == null) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                    }
                    uri = contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                }
                uri?.let {
                    contentResolver.openOutputStream(it, "wt")?.use { os ->
                        os.write(content.toByteArray())
                    }
                }
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), DIR_NAME)
                dir.mkdirs()
                File(dir, name).writeText(content)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "writeFixedFile failed", e)
        }
    }

    private fun logLine(s: String) {
        DiagLog.i(TAG, s)
    }

    private fun logError(where: String, e: Throwable) {
        DiagLog.e(TAG, "$where failed", e)
    }

    private fun nowStr(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    // ---------------- 通用 ----------------

    private fun toast(msg: String) {
        try {
            handler.post {
                Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Throwable) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 记录用户在目标 App 里最后点击的位置（排除自己悬浮按钮的点击），
        // 供复制兜底时模拟长按使用
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            try {
                if (event.packageName == packageName) return
                val r = Rect()
                event.source?.getBoundsInScreen(r)
                if (!r.isEmpty) lastClickBounds = r
            } catch (_: Throwable) {}
        }
    }
    override fun onInterrupt() {}

    override fun onDestroy() {
        logLine("onDestroy —— 服务被销毁（如果是系统杀的，说明不是崩溃而是 ROM 杀后台/关开关）")
        try {
            floatView?.let { windowManager?.removeView(it) }
        } catch (_: Throwable) {}
        floatView = null
        super.onDestroy()
    }
}

/**
 * 统一诊断日志：
 * - 所有日志写入一个文件，方便排查悬浮按钮消失、复制失败等问题
 * - 文件位置：/sdcard/Android/data/com.example.autocopy/files/AutoCopy/diag.log
 * - 后台线程写入，超过 1MB 自动轮转为 diag.old.log
 */
object DiagLog {
    private const val DIR_NAME = "AutoCopy"
    private const val FILE_NAME = "diag.log"
    private const val MAX_BYTES = 1L shl 20 // 1MB 轮转

    private var ctx: Context? = null
    private val handler by lazy {
        Handler(android.os.HandlerThread("AutoCopyDiag").apply { start() }.looper)
    }

    fun init(context: Context) {
        ctx = context.applicationContext
    }

    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun e(tag: String, msg: String, err: Throwable? = null) = write("E", tag, msg, err)

    private fun write(level: String, tag: String, msg: String, err: Throwable?) {
        try {
            Log.println(if (level == "E") Log.ERROR else Log.DEBUG, tag, msg)
        } catch (_: Throwable) {}
        val c = ctx ?: return
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = buildString {
            append('[').append(time).append("] ").append(level).append('/')
                .append(tag).append(": ").append(msg).append('\n')
            if (err != null) {
                append(Log.getStackTraceString(err)).append('\n')
            }
        }
        handler.post {
            try {
                val dir = File(c.getExternalFilesDir(null), DIR_NAME)
                dir.mkdirs()
                val f = File(dir, FILE_NAME)
                if (f.length() > MAX_BYTES) {
                    val old = File(dir, "diag.old.log")
                    if (old.exists()) old.delete()
                    f.renameTo(old)
                }
                f.appendText(line)
            } catch (_: Throwable) {}
        }
    }
}
