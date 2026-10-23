package com.example.autocopy

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_overlay).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !Settings.canDrawOverlays(this)) {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            } else {
                Toast.makeText(this, "已有悬浮窗权限", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        val overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(this)
        val accOk = isAccessibilityServiceOn()
        findViewById<TextView>(R.id.tv_status).text = when {
            accOk && overlayOk -> "● 服务运行中"
            !overlayOk -> "○ 缺少悬浮窗权限"
            else -> "○ 无障碍服务未开启（可能被系统杀后台关掉了，重新打开即可）"
        }
    }

    private fun isAccessibilityServiceOn(): Boolean {
        val cn = ComponentName(this, CopyAccessibilityService::class.java)
        val wanted = setOf(cn.flattenToString(), cn.flattenToShortString())
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { it.trim() in wanted }
    }
}
