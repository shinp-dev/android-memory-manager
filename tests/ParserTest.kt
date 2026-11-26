import com.shinpstudio.memorymanager.*
import java.io.ByteArrayInputStream
import java.io.InputStream
fun main(){
    val sample="""
Total RSS by process:
  900,000K: com.sample.app (pid 11)
Total PSS by process:
  200,000K: com.sample.app (pid 11)
  50,000K: com.sample.app:remote (pid 12 / activities)
  12 kB: surfaceflinger (pid 99)
  200,000K: com.sample.app (pid 11)
Total PSS by OOM adjustment:
  50,000K: com.sample.app:remote (pid 12)
""".trimIndent()
    val result=MemoryParser.parse(sample)
    check(result.size==3)
    check(result.sumOf{it.pssKiB}==250012L)
    check(result.groupBy{MemoryParser.packageCandidate(it.name)}["com.sample.app"]!!.sumOf{it.pssKiB}==250000L)
    check(MemoryParser.decode(MemoryParser.encode(result))==result)
    check(MemoryParser.parse("Total RSS by process:\n 500K: com.sample.app (pid 11)").isEmpty())
    check(MemoryParser.validPackage("com.sample.app"))
    check(!MemoryParser.validPackage("com.sample.app;id"))
    val m="__MM_TEST_"
    val frame="echoed command $m\r\n${m}BEGIN\r\n2000\r\n${m}0\r\n"
    check(ShellFrame.complete(frame,m))
    check(ShellFrame.result(frame,m)=="2000")
    check(!ShellFrame.complete(frame.dropLast(1),m))
    check(runCatching { ShellFrame.result("${m}BEGIN\nerror\n${m}1\n",m) }.isFailure)
    check(runCatching { ShellFrame.result("${m}BEGIN\npartial",m) }.isFailure)
    check(runCatching { ShellFrame.result("${m}BEGIN\nPermission Denial\n${m}0\n",m) }.isFailure)
    auditCases()
    println("PASS: ADB shell framing, CRLF, partial output, exit status and permission errors")
    println("PASS: PSS section, comma/unit parsing, process deduplication, aggregation, wire encoding, missing PSS, package validation")
}

private fun auditCases() {
    var count=0
    fun test(name:String, body:()->Unit) { body();count++;println("PASS: $name") }
    fun fail(block:()->Any?)=check(runCatching{block()}.isFailure)
    val marker="__MM_AUDIT_"
    fun framed(s:String, code:Int=0)="junk\r\n${marker}BEGIN\r\n$s\r\n$marker$code\r\n"
    test("shell every chunk size 1..64 / Japanese / CRLF") {
        val raw=framed("日本語\r\nline2").toByteArray()
        for (chunk in 1..64) {
            val input=object:ByteArrayInputStream(raw){ override fun read(b:ByteArray, off:Int, len:Int)=super.read(b,off,minOf(chunk,len)) }
            check(ShellFrame.read(input,marker)=="日本語\r\nline2")
        }
    }
    test("shell end marker before start is ignored") { check(ShellFrame.read(ByteArrayInputStream(("${marker}0\n"+framed("yes")).toByteArray()),marker)=="yes") }
    test("shell EOF, truncated marker, size cap") {
        fail { ShellFrame.read(ByteArrayInputStream(framed("abc").dropLast(1).toByteArray()),marker) }
        fail { ShellFrame.read(ByteArrayInputStream(framed("a".repeat(100)).toByteArray()),marker,50) }
        fail { ShellFrame.read(ByteArrayInputStream(byteArrayOf()),marker) }
    }
    test("shell nonzero status and known errors with exit 0") {
        fail { ShellFrame.result(framed("no",1),marker) }
        for (s in listOf("Error: bad", "Exception occurred while executing", "SecurityException", "DUMP TIMEOUT", "Permission Denial")) fail { ShellFrame.result(framed(s),marker) }
    }
    test("PSS rejects negative or zero PID, does not include RSS") {
        check(MemoryParser.parse("Total PSS by process:\n 1K: bad (pid 0)\n 5K: okay (pid 10)\nTotal RSS by process:\n 30K: foo (pid 99)").single().pid==10)
    }
    val ps="UID PID\n10123 12\n10123 13\n10124 14\n1010123 15\n1000 16"
    val pkgs="package:com.test.app uid:10123\npackage:com.test.other uid:10124\npackage:android uid:1000"
    test("numeric UID parser, other users, invalid and duplicate PID") {
        val p=ProcessIdentity.parse(ps);check(p[15]==1010123);check(p[12]==10123)
        fail{ProcessIdentity.parse("USER PID\nfoo 1")};fail{ProcessIdentity.parse("UID PID\n1000 2\n1001 2")};fail{ProcessIdentity.parse("UID PID\n1000 0")}
    }
    test("PID churn, changed UID, missing PID") {
        val a=ProcessIdentity.parse(ps);check(ProcessIdentity.stableUid(12,a,a)==10123)
        check(ProcessIdentity.stableUid(12,a,a-12)==null);check(ProcessIdentity.stableUid(12,a,a+(12 to 10124))==null)
    }
    test("package UID parsing and exclusive attribution") {
        val p=ProcessIdentity.packages(pkgs);check(ProcessIdentity.uniquePackage(10123,p,0)=="com.test.app")
        check(ProcessIdentity.uniquePackage(10123,p+("com.shared.app" to 10123),0)==null)
        check(ProcessIdentity.uniquePackage(1010123,p,0)==null);check(ProcessIdentity.uniquePackage(1000,p,0)==null);check(ProcessIdentity.uniquePackage(null,p,0)==null)
        fail{ProcessIdentity.packages("package:com.test.app uid:abc")}
    }
    class Fake {
        val commands=mutableListOf<String>();var all=pkgs;var third=pkgs.lines().take(2).joinToString("\n");var user="0";var home="com.home.app/.Home";var ime="com.ime.app/.Ime";var processes=ps;var failStop=false;var psCalls=0;var afterPs:String?=null
        fun run(cmd:String):String { commands.add(cmd); return when {
            cmd.contains("get-current-user")->user
            cmd.contains("pm list packages") -> if(cmd.contains(" -3 "))third else all
            cmd.contains("resolve-activity")->home
            cmd.contains("default_input_method")->ime
            cmd.contains("/system/bin/ps") -> if(++psCalls>1) afterPs?:processes else processes
            cmd.contains("dumpsys meminfo")->"Total PSS by process:\n 200K: custom.name (pid 12)\n 50K: com.test.app:remote (pid 13)\n 100K: com.test.app (pid 15)\nTotal PSS by OOM adjustment:"
            cmd.contains("am force-stop") -> if(failStop)error("simulated failure") else ""
            else->error("Unexpected: $cmd")
        } }
        fun service()=ShellMemoryService(::run,0)
        fun stops()=commands.filter{it.contains("am force-stop")}
    }
    test("snapshot uses real UID, not process name; missing PID fails closed") {
        val f=Fake();f.afterPs=ps.lines().filterNot{it.endsWith(" 13")}.joinToString("\n");val s=f.service().snapshot();check(s.rows.map{it.uid}==listOf(10123,null,1010123))
    }
    test("valid force stop uses explicit user") { val f=Fake();check(f.service().stopPackage("com.test.app",10123)=="OK");check(f.stops()==listOf("/system/bin/am force-stop --user 0 com.test.app")) }
    val denials:List<Pair<String,(Fake)->Unit>> = listOf(
        "wrong current user" to {f:Fake->f.user="10"},
        "changed UID" to {f:Fake->f.all=f.all.replace("10123","10125")},
        "shared UID" to {f:Fake->f.all+="\npackage:com.shared.app uid:10123"},
        "system/non-third-party" to {f:Fake->f.third=""},
        "default home" to {f:Fake->f.home="com.test.app/.Home"},
        "default IME" to {f:Fake->f.ime="com.test.app/.Ime"},
        "unknown home" to {f:Fake->f.home="No activity found"},
        "unknown IME" to {f:Fake->f.ime="null"},
        "already stopped" to {f:Fake->f.processes="UID PID\n1000 16"}
    )
    for ((name,setup) in denials) test("reject $name without sending force-stop") { val f=Fake();setup(f);check(f.service().stopPackage("com.test.app",10123).startsWith("ERROR"));check(f.stops().isEmpty()) }
    test("reject shell injection and own app") { val f=Fake();for(p in listOf("com.test.app;id","com.shinpstudio.memorymanager","moe.shizuku.privileged.api")) check(f.service().stopPackage(p,10123).startsWith("ERROR"));check(f.commands.isEmpty()) }
    test("force-stop error not reported as success") { val f=Fake();f.failStop=true;check(f.service().stopPackage("com.test.app",10123).startsWith("ERROR")) }
    println("AUDIT PASS: $count scenarios (including 64 chunk sizes in one scenario)")
}
