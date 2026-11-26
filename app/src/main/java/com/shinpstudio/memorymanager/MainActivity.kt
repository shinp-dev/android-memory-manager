package com.shinpstudio.memorymanager

import android.app.*
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var service: ShellMemoryService? = null
    private val adb by lazy { LocalAdb(applicationContext) }
    private var binding = false
    private var busy = false
    private var destroyed = false
    private var pendingOverlay = false
    private var pendingPairSettings = false
    private var apps = emptyList<AppRow>()
    private val selected = mutableSetOf<String>()
    private lateinit var summary: TextView
    private lateinit var bar: ProgressBar
    private lateinit var status: TextView
    private lateinit var refresh: Button
    private lateinit var connect: Button
    private lateinit var bulk: Button
    private lateinit var overlay: Switch
    private lateinit var list: LinearLayout
    private lateinit var showSystem: CheckBox
    private lateinit var search: EditText
    private val tick = object : Runnable { override fun run() { updateRam(); syncOverlay(); handler.postDelayed(this, 3000) } }
    private data class AppRow(val name: String, val pkg: String?, val pss: Long, val processes: List<ProcessMemory>, val protected: Boolean, val uid: Int?)
    private fun dp(n: Int) = (n*resources.displayMetrics.density).toInt()
    private fun label(s: String, size: Float = 14f) = TextView(this).apply { text=s; textSize=size; setTextColor(Color.rgb(31,47,46)) }
    private fun button(s: String, action: ()->Unit) = Button(this).apply { text=s; isAllCaps=false; setOnClickListener { action() } }
    private fun column() = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun horizontal() = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    private fun LinearLayout.weighted(v: View, weight: Float=1f) = addView(v, LinearLayout.LayoutParams(0,-2,weight))
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = column().apply { setBackgroundColor(0xFFF5F8F7.toInt()); setPadding(dp(14),dp(8),dp(14),dp(8)) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) { val i=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()); v.setPadding(dp(14)+i.left,dp(8)+i.top,dp(14)+i.right,dp(8)+i.bottom) }; insets
        }
        setContentView(root)
        root.addView(label("Memory Manager",25f).apply { setTypeface(null,android.graphics.Typeface.BOLD) })
        summary=label("RAMを取得中",19f); root.addView(summary)
        bar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100 }; root.addView(bar,LinearLayout.LayoutParams(-1,dp(12)))
        root.addView(label("使用量＝総RAM − 利用可能RAM（概算）",12f))
        overlay=Switch(this).apply { text="RAMオーバーレイ"; setOnClickListener { if (isChecked) enableOverlay() else stopService(Intent(this@MainActivity,OverlayService::class.java)) } }; root.addView(overlay)
        val actions=horizontal(); connect=button("ADB接続") { connectionDialog() }; refresh=button("更新") { load() }; actions.weighted(connect);actions.weighted(refresh);root.addView(actions)
        status=label("ADB未接続",13f);root.addView(status)
        search=EditText(this).apply { hint="アプリ名・パッケージで検索"; isSingleLine=true; textSize=14f }; root.addView(search)
        search.addTextChangedListener(object: android.text.TextWatcher { override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}; override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){ if(::list.isInitialized) render() }; override fun afterTextChanged(s:android.text.Editable?){} })
        val filters=horizontal(); showSystem=CheckBox(this).apply { text="システム等も表示"; isChecked=true; setOnCheckedChangeListener { _,_-> if(::list.isInitialized)render() } }; filters.weighted(showSystem);bulk=button("選択を終了") { confirmStop(selected.toList()) };filters.addView(bulk);root.addView(filters)
        root.addView(label("アプリ別 PSS（多い順）／タップでプロセス詳細",12f))
        list=column(); val scroll=ScrollView(this).apply { addView(list) };root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        buttons(); updateRam(); syncOverlay()
        if (getPreferences(MODE_PRIVATE).getBoolean("paired",false)) connectAdb(null,null,null)
    }
    override fun onResume() { super.onResume(); if(pendingOverlay){pendingOverlay=false;if(Settings.canDrawOverlays(this))startOverlay() else Toast.makeText(this,"オーバーレイ権限が必要です",Toast.LENGTH_SHORT).show()}; handler.removeCallbacks(tick); handler.post(tick); buttons(); if(pendingPairSettings){pendingPairSettings=false;connectionDialog()} }
    override fun onPause(){handler.removeCallbacks(tick);super.onPause()}
    private fun enableOverlay(){ if(!Settings.canDrawOverlays(this)){ pendingOverlay=true; overlay.isChecked=false; try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName"))) } catch(e:Exception){pendingOverlay=false;setStatus("設定から他のアプリの上に表示を許可してください")} } else startOverlay() }
    private fun startOverlay(){try{startForegroundService(Intent(this,OverlayService::class.java)); if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),72)}catch(e:Exception){setStatus("オーバーレイ起動失敗: ${e.message}");overlay.isChecked=false}}
    private fun syncOverlay(){overlay.isChecked=OverlayService.running}
    private fun updateRam(){val r=ram(this);summary.text="${r.gib(r.used)} / ${r.gib(r.total)} GiB  (${r.percent}%)\n利用可能 ${r.gib(r.available)} GiB${if(r.low) " ・メモリ逼迫" else ""}";bar.progress=r.percent}
    private fun setStatus(s:String){if(!destroyed && ::status.isInitialized)status.text=s}
    private fun buttons(){if(destroyed||!::bulk.isInitialized)return; refresh.isEnabled=service!=null&&!busy;connect.isEnabled=!binding&&!busy;bulk.isEnabled=service!=null&&!busy&&selected.isNotEmpty();bulk.text="${selected.size}件を終了"}
    private fun connectionDialog(){
        if(busy || binding)return
        val fields=column().apply { setPadding(dp(20),0,dp(20),0) }
        fields.addView(label("初回：設定のワイヤレスデバッグをON。分割画面でペア設定コードを確認してください。ポートは通常自動検出します。"))
        val port=EditText(this).apply{hint="ペア用ポート（自動検出できない場合）";inputType=2};fields.addView(port)
        val code=EditText(this).apply{hint="6桁のペア設定コード（初回のみ）";inputType=18};fields.addView(code)
        val target=EditText(this).apply{hint="接続用ポート（空欄で自動検出）";inputType=2};fields.addView(target)
        AlertDialog.Builder(this).setTitle("端末内ADB").setView(fields).setNeutralButton("開発者設定"){_,_->pendingPairSettings=true;startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))}.setNegativeButton("閉じる",null).setPositiveButton("接続",null).create().apply{
            setOnShowListener{getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                val p=port.text.toString();val c=code.text.toString();val t=target.text.toString()
                if((p.isNotEmpty()&&(p.toIntOrNull() ?: 0) !in 1..65535)||((p.isNotEmpty()||c.isNotEmpty())&&!c.matches(Regex("[0-9]{6}")))){port.error="6桁コード、または正しいポートを入力";return@setOnClickListener}
                if(t.isNotEmpty()&&(t.toIntOrNull() ?: 0) !in 1..65535){target.error="1〜65535";return@setOnClickListener}
                dismiss();connectAdb(p.toIntOrNull(),c.ifEmpty{null},t.toIntOrNull())
            }};show()
        }
    }
    private fun connectAdb(pairPort:Int?,code:String?,connectPort:Int?){
        if(destroyed||busy||binding)return
        binding=true;service=null;selected.clear();setStatus("端末内ADBに接続中…");buttons();render()
        worker.execute{
            val result=runCatching{
                if(code!=null){adb.pair(pairPort,code);getPreferences(MODE_PRIVATE).edit().putBoolean("paired",true).apply()}
                adb.connect(connectPort);getPreferences(MODE_PRIVATE).edit().putBoolean("paired",true).apply();ShellMemoryService(adb::command, android.os.Process.myUid() / 100000)
            }
            runOnUiThread{if(destroyed)return@runOnUiThread;binding=false;result.onSuccess{service=it;setStatus("ADB接続済み");load()}.onFailure{setStatus("接続失敗: ${it.message}。ワイヤレスデバッグとペア設定を確認")};buttons();render()}
        }
    }
    private fun group(snapshot: Snapshot):List<AppRow>{
        val pm=packageManager
        val user=android.os.Process.myUid()/100000
        val installed=pm.getInstalledApplications(0).associateBy{it.packageName}
        val home=pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        val ime=Settings.Secure.getString(contentResolver,Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
        return snapshot.rows.groupBy { row ->
            ProcessIdentity.uniquePackage(row.uid,snapshot.packages,user)?.let { "pkg:$it" } ?: "pid:${row.pid}"
        }.map{(key, processes)->
            val candidate=key.takeIf{it.startsWith("pkg:")}?.removePrefix("pkg:")
            val info=installed[candidate];val pkg=info?.packageName
            val uid=processes.first().uid
            val protected=info==null||info.uid!=uid||pkg==packageName||pkg==home||pkg==ime||pkg=="moe.shizuku.privileged.api"||(info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))!=0
            AppRow(info?.let{pm.getApplicationLabel(it).toString()}?:processes.first().name,pkg,processes.sumOf{it.pssKiB},processes,protected,uid)
        }.sortedByDescending{it.pss}
    }
    private fun snapshot(remote:ShellMemoryService):List<AppRow> = group(remote.snapshot())
    private fun load(){val remote=service?:return;if(busy)return;busy=true;setStatus("PSS取得中…");buttons();render();worker.execute{val result=runCatching{snapshot(remote)};runOnUiThread{if(destroyed)return@runOnUiThread;busy=false;result.onSuccess{apps=it;selected.retainAll(it.filterNot{a->a.protected}.mapNotNull{a->a.pkg}.toSet());setStatus("${it.size}件・${java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.JAPAN).format(java.util.Date())} 更新（UIDベース）");render()}.onFailure{service=null;selected.clear();setStatus("取得失敗: ${it.message}（旧一覧・再接続が必要）");render()};buttons();updateRam()}}}
    private fun render(){if(!::list.isInitialized)return;list.removeAllViews();val q=search.text.toString().trim();for(a in apps.filter{(showSystem.isChecked||!it.protected)&&(q.isEmpty()||it.name.contains(q,true)||it.pkg.orEmpty().contains(q,true))}){
        val row=horizontal().apply{setPadding(0,dp(5),0,dp(5))}
        val pkg=a.pkg
        if(!a.protected&&pkg!=null){val cb=CheckBox(this).apply{isEnabled=service!=null&&!busy;isChecked=pkg in selected;setOnCheckedChangeListener{_,checked->if(checked)selected.add(pkg)else selected.remove(pkg);buttons()}};row.addView(cb)}
        val info=column().apply{addView(label(a.name,16f));addView(label("${a.pkg?:"未帰属プロセス"} ・ ${a.processes.size} process",11f));setOnClickListener{AlertDialog.Builder(this@MainActivity).setTitle(a.name).setMessage(a.processes.joinToString("\n"){"${it.name}\nPID ${it.pid} / ${mb(it.pssKiB)} MiB"}).setPositiveButton("閉じる",null).show()}};row.weighted(info)
        row.addView(label("${mb(a.pss)} MiB",15f))
        if(a.protected||pkg==null)row.addView(label(" 保護",11f))else row.addView(button("終了"){confirmStop(listOf(pkg))}.apply{isEnabled=service!=null&&!busy})
        list.addView(row);list.addView(View(this).apply{setBackgroundColor(0xFFD7E2DF.toInt())},LinearLayout.LayoutParams(-1,dp(1)))
    };buttons()}
    private fun mb(k:Long)=String.format(java.util.Locale.JAPAN,"%.1f",k/1024.0)
    private fun confirmStop(packages:List<String>){if(busy||service==null)return;val targets=apps.filter{!it.protected&&it.pkg in packages}.mapNotNull{it.pkg}.distinct();if(targets.isEmpty())return;AlertDialog.Builder(this).setTitle("${targets.size}件を強制終了？").setMessage(apps.filter{it.pkg in targets}.joinToString("\n"){it.name}+"\n\n通知や同期、未保存の作業が止まることがあります。現在のユーザーのみ対象です。").setNegativeButton("キャンセル",null).setPositiveButton("終了"){_,_->stopApps(targets)}.show()}
    private fun stopApps(targets:List<String>){val remote=service?:return;if(busy)return;busy=true;buttons();render();setStatus("強制終了中…");val uids=apps.mapNotNull{a->a.pkg?.let{p->a.uid?.let{p to it}}}.toMap();worker.execute{val results=targets.map{p->p to runCatching{remote.stopPackage(p, uids.getValue(p))}.getOrElse{"ERROR\n${it.message}"}};val updated=runCatching{snapshot(remote)};runOnUiThread{if(destroyed)return@runOnUiThread;busy=false;val ok=results.filter{it.second=="OK"};selected.removeAll(ok.map{it.first}.toSet());updated.onFailure{service=null;selected.clear()};updated.onSuccess{apps=it;selected.retainAll(it.filterNot{a->a.protected}.mapNotNull{a->a.pkg}.toSet())};setStatus("終了指示成功 ${ok.size}/${targets.size} 件${if(updated.isFailure) "・再取得失敗（旧一覧）" else "・再取得済み"}");render();updateRam();val errors=results.filter{it.second!="OK"};if(errors.isNotEmpty())AlertDialog.Builder(this).setTitle("終了できなかったアプリ").setMessage(errors.joinToString("\n"){"${it.first}: ${it.second.substringAfter('\n')}"}).setPositiveButton("閉じる",null).show()}}}
    override fun onDestroy(){destroyed=true;handler.removeCallbacksAndMessages(null);worker.execute{runCatching{adb.disconnect()}};worker.shutdown();super.onDestroy()}
}
