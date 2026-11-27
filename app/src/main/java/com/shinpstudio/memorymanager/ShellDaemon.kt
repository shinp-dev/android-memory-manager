package com.shinpstudio.memorymanager

import android.content.AttributionSource
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.Parcelable
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Started via app_process as UID 2000 in a detached session. No TCP listener. */
object ShellDaemon {
    private const val TAG = "MemoryShell"
    private const val PKG = "com.shinpstudio.memorymanager"

    @JvmStatic fun main(args: Array<String>) {
        try {
            check(Process.myUid() == 2000)
            val uid = args[0].toInt()
            require(uid % 100000 in 10000..19999)
            val user = uid / 100000
            val apk = File(args[1])
            RandomAccessFile("/data/local/tmp/.memory-manager-$uid.lock", "rw").use { lockFile ->
                val lock = lockFile.channel.tryLock() ?: return
                lock.use {
                    val server = Endpoint(uid, user)
                    var client: IBinder? = null
                    var first = true
                    while (apk.isFile) {
                        if (client?.isBinderAlive != true &&
                            (first || runCatching { appRunning() }.getOrDefault(false))) {
                            client = runCatching { deliver(server, user) }
                                .onFailure { Log.e(TAG, "Binder delivery failed", it) }.getOrNull()
                        }
                        first = false
                        Thread.sleep(2000)
                    }
                }
            }
        } catch (t: Throwable) { Log.e(TAG, "Shell service stopped", t) }
    }

    private fun appRunning(): Boolean {
        val p = ProcessBuilder("/system/bin/pidof", PKG).start()
        return try { p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0 }
        finally { p.destroyForcibly() }
    }

    // Hidden APIs are isolated in the shell process, not the ordinary app.
    private fun deliver(server: IBinder, user: Int): IBinder? {
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        val iface = Class.forName("android.app.IActivityManager")
        val get = iface.getMethod("getContentProviderExternal", String::class.java,
            Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
        val remove = iface.getMethod("removeContentProviderExternal", String::class.java, IBinder::class.java)
        val authority = ShellBridge.AUTHORITY
        val holder = get.invoke(am, authority, user, null, authority) ?: return null
        try {
            val provider = holder.javaClass.getField("provider").get(holder) ?: return null
            val extras = Bundle().apply { putBinder("server", server) }
            val call = Class.forName("android.content.IContentProvider")
            val result = if (Build.VERSION.SDK_INT >= 31) {
                val source = AttributionSource.Builder(2000).setPackageName("com.android.shell").build()
                call.getMethod("call", AttributionSource::class.java, String::class.java,
                    String::class.java, String::class.java, Bundle::class.java)
                    .invoke(provider, source, authority, "attach", null, extras)
            } else {
                call.getMethod("call", String::class.java, String::class.java, String::class.java,
                    String::class.java, String::class.java, Bundle::class.java)
                    .invoke(provider, "com.android.shell", null, authority, "attach", null, extras)
            } as? Bundle
            return result?.getBinder("client")
        } finally {
            runCatching { remove.invoke(am, authority, null) }
        }
    }

    private class Endpoint(private val clientUid: Int, private val user: Int) : Binder() {
        init { attachInterface(null, ShellBridge.DESCRIPTOR) }
        private val operationLock = Any()
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != ShellBridge.REQUEST) return super.onTransact(code, data, reply, flags)
            check(Binder.getCallingUid() == clientUid) { "Unauthorized caller" }
            data.enforceInterface(ShellBridge.DESCRIPTOR)
            val command = requireNotNull(data.readString())
            require(command.length <= 512)
            val response = requireNotNull(reply)
            val pipe = ParcelFileDescriptor.createPipe()
            try {
                response.writeNoException()
                pipe[0].writeToParcel(response, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
                thread(isDaemon = true, name = "memory-shell-command") {
                    try {
                        val result = runCatching { synchronized(operationLock) { execute(command) } }
                        val ok = result.isSuccess
                        val message = result.getOrElse { it.message ?: "Shell command failed" }
                        val bytes = message.toByteArray(Charsets.UTF_8)
                        DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])).use {
                            if (bytes.size > ShellBridge.MAX_OUTPUT) {
                                val error = "出力が大きすぎます".toByteArray(Charsets.UTF_8)
                                it.writeBoolean(false); it.writeInt(error.size); it.write(error)
                            } else {
                                it.writeBoolean(ok); it.writeInt(bytes.size); it.write(bytes)
                            }
                        }
                    } catch (t: Throwable) {
                        runCatching { pipe[1].close() }
                        Log.w(TAG, "Client response failed", t)
                    }
                }
            } catch (t: Throwable) {
                runCatching { pipe[0].close() }; runCatching { pipe[1].close() }
                throw t
            }
            return true
        }

        private fun execute(command: String): String {
            if (command == "PING") return Process.myUid().toString()
            val fields = command.split(' ')
            if (fields.firstOrNull() == "STOP") {
                require(fields.size == 4 && fields[1].toIntOrNull() == user)
                val uid = requireNotNull(fields[2].toIntOrNull())
                val pkg = fields[3]
                require(MemoryParser.validPackage(pkg))
                // The daemon repeats all UID, user, protected-role and third-party checks.
                return ShellMemoryService({ runCommand(it, allowStop = true) }, user).stopPackage(pkg, uid)
            }
            return runCommand(command, allowStop = false)
        }

        private fun runCommand(command: String, allowStop: Boolean): String {
            val allowed = setOf(
                "/system/bin/ps -A -o UID:12,PID:12",
                "/system/bin/dumpsys meminfo",
                "/system/bin/pm list packages -U --user $user",
                "/system/bin/pm list packages -U -3 --user $user",
                "/system/bin/am get-current-user",
                "/system/bin/cmd package resolve-activity --brief --user $user -a android.intent.action.MAIN -c android.intent.category.HOME",
                "/system/bin/settings --user $user get secure default_input_method"
            )
            val parts = command.split(' ')
            val validStop = allowStop && parts.size == 5 &&
                parts.take(4) == listOf("/system/bin/am", "force-stop", "--user", user.toString()) &&
                MemoryParser.validPackage(parts[4])
            require(command in allowed || validStop) { "未許可のコマンド" }
            // No shell evaluation or arbitrary commands.
            val process = ProcessBuilder(parts).redirectErrorStream(true).start()
            val reader = FutureTask<ByteArray> {
                process.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        check(out.size() + n <= ShellBridge.MAX_OUTPUT) { "出力が大きすぎます" }
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
            }
            thread(isDaemon = true, name = "memory-shell-output") { reader.run() }
            try {
                check(process.waitFor(15, TimeUnit.SECONDS)) { "コマンドがタイムアウトしました" }
                val output = reader.get(2, TimeUnit.SECONDS).toString(Charsets.UTF_8)
                val marker = "__MM_DAEMON_" + java.util.UUID.randomUUID() + "_"
                return ShellFrame.result(marker + "BEGIN\n" + output + "\n" + marker + process.exitValue() + "\n", marker)
            } finally {
                process.destroyForcibly()
                reader.cancel(true)
                runCatching { process.inputStream.close() }
            }
        }
    }
}
