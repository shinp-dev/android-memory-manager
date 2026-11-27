package com.shinpstudio.memorymanager

data class Snapshot(val rows: List<ProcessMemory>, val packages: Map<String, Int>)

/** Only fixed commands and validated package names may cross the ADB boundary. */
class ShellMemoryService(private val run: (String) -> String, private val user: Int, private val remoteStop: ((String, Int) -> String)? = null) {
    init { require(user >= 0) }
    private fun command(vararg args: String): String = run(args.filter { it.isNotEmpty() }.joinToString(" "))
    private fun processes() = ProcessIdentity.parse(command("/system/bin/ps", "-A", "-o", "UID:12,PID:12"))
    private fun packages(thirdParty: Boolean = false) = ProcessIdentity.packages(command("/system/bin/pm", "list", "packages", "-U", if (thirdParty) "-3" else "", "--user", user.toString()))
    fun snapshot(): Snapshot {
        val before = processes()
        val rows = MemoryParser.parse(command("/system/bin/dumpsys", "meminfo"))
        check(rows.isNotEmpty()) { "PSS一覧を取得できません。端末の出力形式を確認してください。" }
        val after = processes()
        return Snapshot(rows.map { it.copy(uid = ProcessIdentity.stableUid(it.pid, before, after)) }, packages())
    }
    fun stopPackage(packageName: String, expectedUid: Int): String = try {
        require(MemoryParser.validPackage(packageName) && packageName != "com.shinpstudio.memorymanager" && packageName != "moe.shizuku.privileged.api")
        check(command("/system/bin/am", "get-current-user").trim().toIntOrNull() == user) { "現在のユーザーが異なります" }
        val all = packages()
        check(ProcessIdentity.uniquePackage(expectedUid, all, user) == packageName && packages(true)[packageName] == expectedUid) { "UID変更・共有UID・システムアプリは終了できません" }
        val home = command("/system/bin/cmd", "package", "resolve-activity", "--brief", "--user", user.toString(), "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
        val ime = command("/system/bin/settings", "--user", user.toString(), "get", "secure", "default_input_method").trim()
        // Fail closed if the protected roles cannot be identified.
        val homePkg = home.lineSequence().map { it.trim() }.lastOrNull { it.contains('/') }?.substringBefore('/')
        val imePkg = ime.substringBefore('/')
        check(homePkg != null && MemoryParser.validPackage(homePkg) && ime.contains('/') && MemoryParser.validPackage(imePkg)) { "ホームまたは入力アプリの保護を確認できません" }
        check(packageName != homePkg && packageName != imePkg) { "ホームまたは入力アプリは保護対象です" }
        check(expectedUid in processes().values) { "対象はすでに停止しています" }
        if (remoteStop != null) remoteStop.invoke(packageName, expectedUid) else {
            command("/system/bin/am", "force-stop", "--user", user.toString(), packageName)
            "OK"
        }
    } catch (e: Exception) { "ERROR\n${e.message ?: e.javaClass.simpleName}" }
}
