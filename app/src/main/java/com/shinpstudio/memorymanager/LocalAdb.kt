package com.shinpstudio.memorymanager

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Local ADB only; persistent identity in private, non-backed-up storage. */
class LocalAdb(private val context: Context) {
    private companion object { val IDENTITY_LOCK=Any() }
    private class Manager(context: Context) : AbsAdbConnectionManager() {
        private val key: PrivateKey
        private val cert: Certificate
        init { synchronized(IDENTITY_LOCK) {
            val identity=android.util.AtomicFile(File(context.noBackupFilesDir,"adb-identity.bin"))
            val oldKey=File(context.noBackupFilesDir,"adb-key.pk8")
            val oldCert=File(context.noBackupFilesDir,"adb-cert.der")
            fun decode(k: ByteArray, c: ByteArray): Pair<PrivateKey,Certificate> {
                val privateKey=KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(k))
                val certificate=CertificateFactory.getInstance("X.509").generateCertificate(c.inputStream()) as java.security.cert.X509Certificate
                certificate.checkValidity()
                val challenge=byteArrayOf(1,2,3,4)
                val sig=java.security.Signature.getInstance("SHA256withRSA")
                sig.initSign(privateKey);sig.update(challenge);val signed=sig.sign()
                sig.initVerify(certificate.publicKey);sig.update(challenge);check(sig.verify(signed)){"ADB認証鍵と証明書が一致しません"}
                return privateKey to certificate
            }
            val loaded=runCatching {
                java.io.DataInputStream(identity.openRead()).use { input ->
                    fun readPart(): ByteArray { val n=input.readInt();require(n in 1..16384);return ByteArray(n).also{input.readFully(it)} }
                    decode(readPart(),readPart())
                }
            }.getOrNull()
            val pair=loaded ?: runCatching { decode(oldKey.readBytes(),oldCert.readBytes()) }.getOrNull() ?: run {
                val keys=KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair()
                val name=X500Name("CN=Memory Manager")
                val now=System.currentTimeMillis()
                val holder=JcaX509v3CertificateBuilder(name,BigInteger(128,SecureRandom()).add(BigInteger.ONE),Date(now-86400000L),Date(now+3650L*86400000L),name,keys.public).build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private))
                keys.private to JcaX509CertificateConverter().getCertificate(holder)
            }
            key=pair.first;cert=pair.second
            if (loaded==null) {
                val output=identity.startWrite()
                try {
                    val data=java.io.DataOutputStream(output)
                    for (part in listOf(key.encoded,cert.encoded)) { data.writeInt(part.size);data.write(part) }
                    data.flush();identity.finishWrite(output)
                    oldKey.delete();oldCert.delete()
                } catch(e:Exception) { identity.failWrite(output);throw e }
            }
            setApi(Build.VERSION.SDK_INT);setTimeout(12,TimeUnit.SECONDS);setThrowOnUnauthorised(true)
        } }
        override fun getPrivateKey()=key
        override fun getCertificate()=cert
        override fun getDeviceName()="Memory Manager"
    }
    private var manager: Manager? = null
    private fun manager(): Manager = manager ?: Manager(context).also { manager = it }
    private fun retire(m: Manager) {
        if (manager !== m) return
        manager = null
        // Library disconnect may wait on its internal lock; never block UI worker on cleanup.
        thread(isDaemon=true, name="memory-adb-cleanup") { runCatching { m.disconnect() } }
    }
    private fun <T> bounded(m: Manager, seconds: Long, block: () -> T): T {
        val task = FutureTask<T> { block() }
        thread(isDaemon=true, name="memory-adb-operation") { task.run() }
        try { return task.get(seconds, TimeUnit.SECONDS) }
        catch (e: java.util.concurrent.TimeoutException) { task.cancel(true); retire(m); error("ADB操作がタイムアウトしました。再接続してください") }
        catch (e: InterruptedException) { task.cancel(true); retire(m); Thread.currentThread().interrupt(); throw e }
        catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? Exception ?: e) }
    }
    private fun discover(type:String):Int {
        val port=AtomicInteger(-1);val ready=CountDownLatch(1)
        // The library discovers only advertisements associated with this device's own addresses.
        val discovery=AdbMdns(context,type){ host,p ->
            if(host!=null && p in 1..65535 && port.compareAndSet(-1,p))ready.countDown()
        }
        try { discovery.start(); check(ready.await(8,TimeUnit.SECONDS)){"ポートを自動検出できません。設定画面のポートを入力してください"};return port.get() }
        finally { runCatching { discovery.stop() } }
    }
    fun pair(port:Int?,code:String){
        require(code.matches(Regex("[0-9]{6}")));disconnect()
        val p=port?:discover(AdbMdns.SERVICE_TYPE_TLS_PAIRING);require(p in 1..65535)
        val m = manager()
        try { check(bounded(m, 20) { m.pair("127.0.0.1",p,code) }) { "ペア設定失敗" } } catch (e: Exception) { retire(m); throw e }
    }
    fun connect(port:Int?){
        disconnect()
        val p=port?:discover(AdbMdns.SERVICE_TYPE_TLS_CONNECT);require(p in 1..65535)
        try {
            val m = manager()
            bounded(m, 18) { m.connect("127.0.0.1",p) }
            check(m.isConnected){"ADB接続できません。接続用ポートを指定してください"}
            check(command("id -u").trim()=="2000"){"shellユーザーではありません"}
        } catch(e:Exception){runCatching{disconnect()};throw e}
    }
    fun disconnect(){manager?.let { retire(it) }}
    internal fun command(command:String):String {
        val m = manager ?: error("ADB未接続。再接続してください")
        check(m.isConnected){"ADB未接続。再接続してください"}
        val marker="__MM_${UUID.randomUUID().toString().replace("-","")}_"
        try {
            return bounded(m, 22) {
                m.openStream("shell:sh").use { stream ->
                    // Keep the shell alive until the full exit marker has arrived. Closing
                    // immediately on the daemon side can lose unread data in libadb.
                    val script="stty -echo 2>/dev/null; printf '\\n${marker}BEGIN\\n'; $command 2>&1; printf '\\n${marker}%s\\n' \$?\n"
                    stream.openOutputStream().apply { write(script.toByteArray(Charsets.UTF_8)); flush() }
                    ShellFrame.read(stream.openInputStream(), marker)
                }
            }
        } catch (e: Exception) { if (manager === m) retire(m); throw e }
    }
}
