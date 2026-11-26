package com.shinpstudio.memorymanager

/** Numeric UID/PID pairs from toybox ps; reject ambiguous rows. */
object ProcessIdentity {
    fun parse(text: String): Map<Int, Int> {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.firstOrNull()?.trim()?.split(Regex("\\s+")) == listOf("UID", "PID")) { "ps出力形式が不明です" }
        val result = mutableMapOf<Int, Int>()
        for (line in lines.drop(1)) {
            val parts = line.trim().split(Regex("\\s+"))
            require(parts.size == 2) { "ps出力が不正です" }
            val uid = parts[0].toIntOrNull(); val pid = parts[1].toIntOrNull()
            require(uid != null && uid >= 0 && pid != null && pid > 0 && result.put(pid, uid) == null) { "ps出力が不正です" }
        }
        require(result.isNotEmpty()) { "プロセス情報が空です" }
        return result
    }
    fun stableUid(pid: Int, before: Map<Int, Int>, after: Map<Int, Int>): Int? = before[pid]?.takeIf { it == after[pid] }
    fun packages(text: String): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        for (line in text.lineSequence().filter { it.isNotBlank() }) {
            val m = Regex("^package:([^\\s]+)\\s+uid:(\\d+)$").matchEntire(line.trim()) ?: error("パッケージ一覧形式が不明です")
            val name = m.groupValues[1]; val uid = m.groupValues[2].toIntOrNull() ?: error("UID不正")
            require(result.put(name, uid) == null)
        }
        return result
    }
    fun uniquePackage(uid: Int?, installed: Map<String, Int>, user: Int): String? {
        if (uid == null || uid / 100000 != user || uid % 100000 !in 10000..19999) return null
        return installed.entries.filter { it.value == uid }.singleOrNull()?.key
    }
}
