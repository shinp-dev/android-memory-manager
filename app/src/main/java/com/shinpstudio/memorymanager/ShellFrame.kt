package com.shinpstudio.memorymanager

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal object ShellFrame {
    private fun start(marker:String)=Regex("(?m)^${Regex.escape(marker)}BEGIN\\r?\\n")
    private fun end(marker:String)=Regex("(?m)^${Regex.escape(marker)}([0-9]+)\\r?\\n")
    fun complete(raw:String,marker:String):Boolean {
        val first=start(marker).find(raw)?:return false
        return end(marker).find(raw,first.range.last+1)!=null
    }
    fun result(raw:String,marker:String):String {
        val first=start(marker).find(raw)?:error("ADB出力が途中で切断されました")
        val last=end(marker).find(raw,first.range.last+1)?:error("ADB終了状態を取得できません")
        val output=raw.substring(first.range.last+1,last.range.first).trim('\r','\n')
        check(last.groupValues[1]=="0"&&!output.contains("Permission Denial",true)&&!output.contains("SecurityException",true)&&!output.contains("DUMP TIMEOUT",true)&&!output.lineSequence().any { it.startsWith("Error:",true) || it.startsWith("Exception occurred",true) }){output.take(250).ifBlank{"コマンド失敗"}}
        return output
    }
    /** Scan complete lines incrementally; decode UTF-8 once, not on every packet. */
    fun read(input: InputStream, marker: String, limit: Int = 8*1024*1024): String {
        val out=ByteArrayOutputStream(); val line=ByteArrayOutputStream(); val buffer=ByteArray(8192)
        var started=false
        val endLine=Regex("^${Regex.escape(marker)}[0-9]+$")
        while (true) {
            val n=input.read(buffer)
            check(n>0) { "ADB出力が途中で切断されました" }
            for (i in 0 until n) {
                check(out.size()<limit) { "出力が大きすぎます" }
                val b=buffer[i].toInt() and 255; out.write(b)
                if (b==10) {
                    val text=line.toString("UTF-8").trimEnd('\r');line.reset()
                    if (text==marker+"BEGIN") started=true
                    else if (started && endLine.matches(text)) return result(out.toString("UTF-8"),marker)
                } else line.write(b)
            }
        }
    }
}
