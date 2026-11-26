package com.shinpstudio.memorymanager

data class ProcessMemory(val name: String, val pid: Int, val pssKiB: Long, val uid: Int? = null)
object MemoryParser {
    private val row = Regex("^\\s*([\\d,]+)\\s*(?:kB|KB|K)\\s*:\\s*(.+?)\\s+\\(pid\\s+(\\d+)(?:\\s*/[^)]*)?\\).*$", RegexOption.IGNORE_CASE)
    fun parse(text: String): List<ProcessMemory> {
        var inPss = false
        val rows = mutableListOf<ProcessMemory>()
        for (line in text.lineSequence()) {
            val s = line.trim()
            if (s == "Total PSS by process:") { inPss = true; continue }
            if (!inPss) continue
            if (s.startsWith("Total ") || s.startsWith("RAM:")) break
            val m = row.matchEntire(line) ?: continue
            val kib = m.groupValues[1].replace(",", "").toLongOrNull() ?: continue
            val pid = m.groupValues[3].toIntOrNull() ?: continue
            if (pid <= 0) continue
            rows.add(ProcessMemory(m.groupValues[2], pid, kib))
        }
        return rows.distinctBy { it.pid }
    }
    fun packageCandidate(name: String) = name.substringBefore(':')
    fun validPackage(name: String) = name.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
    fun encode(rows: List<ProcessMemory>): String = rows.joinToString("\n") { "${it.pssKiB}\t${it.pid}\t${it.name.replace('\t',' ')}" }
    fun decode(text: String): List<ProcessMemory> = text.lineSequence().mapNotNull { line ->
        val p = line.split('\t', limit = 3)
        if (p.size != 3) null else { val k = p[0].toLongOrNull(); val pid = p[1].toIntOrNull(); if (k == null || pid == null || k < 0 || pid <= 0) null else ProcessMemory(p[2], pid, k) }
    }.toList()
}
