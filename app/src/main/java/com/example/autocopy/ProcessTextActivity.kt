package com.example.autocopy

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * 手动兜底入口（ACTION_PROCESS_TEXT）：
 * 长按选中文字后，系统选择菜单里会出现「复制保存」，
 * 点击直接拿到选中文本，复制 + 保存一步完成。
 * 不依赖无障碍树和手势模拟，只要能选中文字就一定能用。
 */
class ProcessTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagLog.init(this)
        val text = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        if (text.isEmpty()) {
            DiagLog.i("ProcessText", "opened with no text")
            Toast.makeText(this, "没有选中文本", Toast.LENGTH_SHORT).show()
        } else {
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("text", text))
            } catch (e: Throwable) {
                DiagLog.e("ProcessText", "setPrimaryClip failed", e)
            }
            val name = TextSaver.save(this, text)
            val msg = if (name != null) "已复制并保存：$name（${text.length} 字）"
                      else "已复制，但保存到下载失败"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            DiagLog.i("ProcessText", "saved len=${text.length} file=$name")
        }
        finish()
    }
}
