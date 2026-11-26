package com.shinpstudio.memorymanager
import android.app.ActivityManager
import android.content.Context
import java.util.Locale

data class Ram(val total: Long, val available: Long, val low: Boolean) {
    val used get() = (total - available).coerceAtLeast(0)
    val percent get() = if (total > 0) (used * 100 / total).toInt().coerceIn(0,100) else 0
    fun gib(n: Long) = String.format(Locale.JAPAN, "%.2f", n / 1073741824.0)
}
fun ram(context: Context): Ram {
    val info = ActivityManager.MemoryInfo()
    context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
    return Ram(info.totalMem, info.availMem, info.lowMemory)
}
