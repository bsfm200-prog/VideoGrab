package com.videograb.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.TreeSet
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

object Notif {
    const val CH = "dl"

    fun channel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Downloads", NotificationManager.IMPORTANCE_LOW))
    }

    fun progress(c: Context, title: String, p: Int): Notification {
        val b = Notification.Builder(c, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (title.isEmpty()) "VideoGrab" else title)
            .setContentText(if (p >= 0) "$p%" else "...")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        b.setProgress(100, if (p < 0) 0 else p, p < 0)
        return b.build()
    }

    fun done(c: Context, text: String) {
        val n = Notification.Builder(c, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("VideoGrab")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(2, n)
    }
}

class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notif.channel(this)
        val n = Notif.progress(this, intent?.getStringExtra("t") ?: "", -1)
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }
}

class LoginActivity : Activity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.BLACK)
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.userAgentString = w.settings.userAgentString.replace("; wv", "")
        w.webViewClient = WebViewClient()
        root.addView(w, LinearLayout.LayoutParams(-1, 0, 1f))
        val b = Button(this)
        b.text = "تم تسجيل الدخول - حفظ"
        b.setOnClickListener { saveCookies() }
        root.addView(b, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        w.loadUrl("https://www.instagram.com/accounts/login/")
    }

    private fun saveCookies() {
        val raw = CookieManager.getInstance().getCookie("https://www.instagram.com") ?: ""
        if (!raw.contains("sessionid=")) {
            Toast.makeText(this, "سجّل الدخول أولاً ثم اضغط حفظ", Toast.LENGTH_LONG).show()
            return
        }
        CookieManager.getInstance().flush()
        val exp = System.currentTimeMillis() / 1000 + 31536000
        val sb = StringBuilder("# Netscape HTTP Cookie File\n")
        for (part in raw.split(";")) {
            val kv = part.trim()
            val i = kv.indexOf('=')
            if (i <= 0) continue
            sb.append(".instagram.com\tTRUE\t/\tTRUE\t$exp\t${kv.substring(0, i)}\t${kv.substring(i + 1)}\n")
        }
        File(filesDir, "cookies.txt").writeText(sb.toString())
        Toast.makeText(this, "تم الحفظ. ارجع وأعد تحليل الرابط", Toast.LENGTH_LONG).show()
        finish()
    }
}

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val pool = Executors.newCachedThreadPool()
    private lateinit var initJob: Future<*>
    @Volatile private var aria2Ok = false
    private var pageReady = false
    private var pendingShare: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        web = WebView(this)
        setContentView(web)
        web.setBackgroundColor(0xFF000000.toInt())
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                pendingShare?.let { sendShare(it); pendingShare = null }
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/www/index.html")
        initJob = pool.submit {
            YoutubeDL.getInstance().init(application)
            FFmpeg.getInstance().init(application)
            try {
                Aria2c.getInstance().init(application)
                aria2Ok = true
            } catch (e: Throwable) {
                aria2Ok = false
            }
        }
        extractShare(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractShare(intent)
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    private fun extractShare(i: Intent?) {
        val it = i ?: return
        if (it.action != Intent.ACTION_SEND) return
        val t = it.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val m = Regex("https?://\\S+").find(t) ?: return
        if (pageReady) sendShare(m.value) else pendingShare = m.value
    }

    private fun sendShare(u: String) {
        runOnUiThread {
            web.evaluateJavascript("window.__share&&window.__share(${JSONObject.quote(u)})", null)
        }
    }

    private fun push(fn: String, id: String, payload: String) {
        runOnUiThread {
            web.evaluateJavascript(
                "window.$fn(${JSONObject.quote(id)},${JSONObject.quote(payload)})", null
            )
        }
    }

    private fun fail(id: String, e: Throwable) {
        val root = if (e is ExecutionException && e.cause != null) e.cause!! else e
        var m = root.message ?: root.toString()
        if (m.length > 300) m = m.substring(0, 300)
        push("__res", id, JSONObject().put("error", m).toString())
    }

    private fun JSONObject.s(k: String): String = if (isNull(k)) "" else optString(k, "")

    private fun withCookies(req: YoutubeDLRequest, url: String) {
        val f = File(filesDir, "cookies.txt")
        if (f.exists() && url.contains("instagram.com")) {
            req.addOption("--cookies", f.absolutePath)
        }
    }

    private fun askIgLogin() {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("انستغرام")
                .setMessage("هذا المحتوى يحتاج تسجيل دخول. هل تريد تسجيل الدخول الآن؟\n\nنصيحة: استخدم حساباً ثانوياً.")
                .setPositiveButton("تسجيل الدخول") { _, _ ->
                    startActivity(Intent(this, LoginActivity::class.java))
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun paste(): String {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            return cm.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""
        }

        @JavascriptInterface
        fun open(uri: String, mime: String): Boolean {
            return try {
                val i = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(uri), mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                true
            } catch (e: Throwable) {
                false
            }
        }

        @JavascriptInterface
        fun info(id: String, url: String) {
            pool.execute {
                try {
                    initJob.get()
                    val req = YoutubeDLRequest(url)
                    req.addOption("--dump-single-json")
                    req.addOption("--no-playlist")
                    req.addOption("--no-warnings")
                    req.addOption("--skip-download")
                    withCookies(req, url)
                    val out = YoutubeDL.getInstance().execute(req).out
                    val d = JSONObject(out.substring(out.indexOf('{')))
                    val fs = d.optJSONArray("formats") ?: JSONArray()
                    val hs = TreeSet<Int>(Comparator.reverseOrder())
                    var preview = ""
                    var prevH = -1
                    for (i in 0 until fs.length()) {
                        val f = fs.getJSONObject(i)
                        val h = if (f.isNull("height")) 0 else f.optInt("height", 0)
                        val vc = f.s("vcodec")
                        val ac = f.s("acodec")
                        val hasV = vc.isNotEmpty() && vc != "none"
                        val hasA = ac.isNotEmpty() && ac != "none"
                        if (h > 0 && hasV) hs.add(h)
                        val u = f.s("url")
                        val p = f.s("protocol")
                        if (hasV && hasA && u.startsWith("http") && (p == "https" || p == "http") && h <= 720 && h >= prevH) {
                            preview = u; prevH = h
                        }
                    }
                    if (preview.isEmpty() && d.s("url").startsWith("http") && d.s("protocol").startsWith("http")) {
                        preview = d.s("url")
                    }
                    val r = JSONObject()
                        .put("title", d.s("title").ifEmpty { "فيديو" })
                        .put("duration", if (d.isNull("duration")) 0 else d.optInt("duration", 0))
                        .put("uploader", d.s("uploader"))
                        .put("site", d.s("extractor_key"))
                        .put("heights", JSONArray(hs.toList()))
                        .put("thumb", d.s("thumbnail"))
                        .put("preview", preview)
                    push("__res", id, r.toString())
                } catch (e: Throwable) {
                    val msg = e.message ?: ""
                    if (url.contains("instagram.com") &&
                        (msg.contains("login", true) || msg.contains("cookies", true))
                    ) {
                        askIgLogin()
                    }
                    fail(id, e)
                }
            }
        }

        @JavascriptInterface
        fun download(id: String, url: String, kind: String, height: String, title: String) {
            try {
                startForegroundService(
                    Intent(this@MainActivity, DownloadService::class.java).putExtra("t", title)
                )
            } catch (e: Throwable) {
            }
            pool.execute {
                try {
                    initJob.get()
                    val dir = File(filesDir, "dl")
                    dir.deleteRecursively(); dir.mkdirs()
                    val req = YoutubeDLRequest(url)
                    req.addOption("--no-playlist")
                    req.addOption("--no-mtime")
                    req.addOption("-N", "8")
                    withCookies(req, url)
                    if (aria2Ok) {
                        req.addOption("--downloader", "libaria2c.so")
                        req.addOption("--external-downloader-args", "aria2c:-x 16 -s 16 -k 1M")
                    }
                    req.addOption("-o", dir.absolutePath + "/vg.%(ext)s")
                    if (kind == "audio") {
                        req.addOption("-f", "bestaudio/best")
                        req.addOption("-x")
                        req.addOption("--audio-format", "mp3")
                    } else {
                        val h = height.toIntOrNull()
                        val fmt = if (h != null)
                            "bestvideo[height<=$h][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=$h]+bestaudio/best[height<=$h]/best"
                        else
                            "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best"
                        req.addOption("-f", fmt)
                        req.addOption("--merge-output-format", "mp4")
                    }
                    val nm = getSystemService(NotificationManager::class.java)
                    var last = -2
                    YoutubeDL.getInstance().execute(req, id) { p, _, _ ->
                        push("__prog", id, p.toString())
                        val pi = p.toInt()
                        if (pi != last) {
                            last = pi
                            nm.notify(1, Notif.progress(this@MainActivity, title, pi))
                        }
                    }
                    val f = dir.listFiles()
                        ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                        ?.maxByOrNull { it.length() }
                        ?: throw RuntimeException("لم يتم إنتاج ملف")
                    val ext = f.extension.lowercase()
                    val base = title.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), " ").trim().take(80).ifEmpty { "video" }
                    val name = "$base.$ext"
                    val mime = when (ext) {
                        "mp3" -> "audio/mpeg"
                        "m4a" -> "audio/mp4"
                        "mp4" -> "video/mp4"
                        "webm" -> "video/webm"
                        "mkv" -> "video/x-matroska"
                        else -> "application/octet-stream"
                    }
                    val cv = ContentValues()
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    val where: String
                    val table: Uri
                    when {
                        mime.startsWith("video") -> {
                            where = "Movies/VideoGrab"; table = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        }
                        mime.startsWith("audio") -> {
                            where = "Music/VideoGrab"; table = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        }
                        else -> {
                            where = "Download/VideoGrab"; table = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                        }
                    }
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, where)
                    val uri = contentResolver.insert(table, cv) ?: throw RuntimeException("تعذر حفظ الملف")
                    contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
                    dir.deleteRecursively()
                    Notif.done(this@MainActivity, name)
                    push(
                        "__res", id,
                        JSONObject().put("name", name).put("where", where)
                            .put("uri", uri.toString()).put("mime", mime).toString()
                    )
                } catch (e: Throwable) {
                    fail(id, e)
                } finally {
                    stopService(Intent(this@MainActivity, DownloadService::class.java))
                }
            }
        }
    }
}
