package com.shinpstudio.memorymanager

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import java.io.DataInputStream
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Binder is delivered only by the shell UID; no network listener. */
internal object ShellBridge {
    const val DESCRIPTOR = "com.shinpstudio.memorymanager.shell.v1"
    const val AUTHORITY = "com.shinpstudio.memorymanager.shellbridge"
    const val REQUEST = IBinder.FIRST_CALL_TRANSACTION
    const val MAX_OUTPUT = 8 * 1024 * 1024
    private val clientLifetime = Binder()
    @Volatile private var remote: IBinder? = null

    fun receive(binder: IBinder): IBinder {
        require(binder.interfaceDescriptor == DESCRIPTOR)
        remote = binder
        return clientLifetime
    }
    fun current(): IBinder? = remote?.takeIf { it.isBinderAlive }
    fun await(timeoutMs: Long): IBinder? {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        do {
            current()?.let { return it }
            SystemClock.sleep(150)
        } while (SystemClock.elapsedRealtime() < end)
        return null
    }
    fun start(context: Context, adb: LocalAdb): IBinder {
        val path = context.applicationInfo.sourceDir
        require(path.matches(Regex("/[A-Za-z0-9_./=+~-]+"))) { "APKパスを検証できません" }
        val uid = Process.myUid()
        adb.command("CLASSPATH='$path' /system/bin/nohup /system/bin/setsid /system/bin/app_process /system/bin --nice-name=memory-manager-shell com.shinpstudio.memorymanager.ShellDaemon $uid '$path' </dev/null >/dev/null 2>&1 &")
        return await(12000) ?: error("常駐サービスを起動できません。Wi-FiとADBを確認してください（logcat: MemoryShell）")
    }
    fun service(binder: IBinder, user: Int): ShellMemoryService {
        check(request(binder, "PING") == "2000") { "shell権限サービスではありません" }
        return ShellMemoryService({ request(binder, it) }, user) { pkg, uid ->
            request(binder, "STOP $user $uid $pkg")
        }
    }
    private fun request(binder: IBinder, command: String): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        val fd: ParcelFileDescriptor
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(command)
            check(binder.transact(REQUEST, data, reply, 0)) { "常駐サービスと通信できません" }
            reply.readException()
            fd = ParcelFileDescriptor.CREATOR.createFromParcel(reply)
        } finally {
            data.recycle()
            reply.recycle()
        }
        val stream = DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(fd))
        val task = FutureTask<String> {
            stream.use {
                val ok = it.readBoolean()
                val size = it.readInt()
                require(size in 0..MAX_OUTPUT) { "応答サイズが不正です" }
                val bytes = ByteArray(size)
                it.readFully(bytes)
                val message = bytes.toString(Charsets.UTF_8)
                check(ok) { message }
                message
            }
        }
        thread(isDaemon = true, name = "memory-binder-read") { task.run() }
        try {
            return task.get(if (command.startsWith("STOP ")) 150L else 25L, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? Exception ?: e)
        } catch (e: Exception) {
            task.cancel(true)
            runCatching { stream.close() }
            throw e
        }
    }
}

class ShellBridgeProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == 2000) { "shell UID required" }
        require(method == "attach")
        val binder = requireNotNull(extras?.getBinder("server"))
        return Bundle().apply { putBinder("client", ShellBridge.receive(binder)) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
