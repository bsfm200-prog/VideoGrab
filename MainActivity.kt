package com.videograb.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
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

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val pool = Executors.newCachedThreadPool()
    private lateinit var initJob: Future<*>

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
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/www/index.html")
        initJob = pool.submit {
            YoutubeDL.getInstance().init(application)
            FFmpeg.getInstance().init(application)
        }
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
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

    inner class Bridge {
        @JavascriptInterface
        fun paste(): String {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            return cm.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""
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
                    fail(id, e)
                }
            }
        }

        @JavascriptInterface
        fun download(id: String, url: String, kind: String, height: String, title: String) {
            pool.execute {
                try {
                    initJob.get()
                    val dir = File(filesDir, "dl")
                    dir.deleteRecursively(); dir.mkdirs()
                    val req = YoutubeDLRequest(url)
                    req.addOption("--no-playlist")
                    req.addOption("--no-mtime")
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
                    YoutubeDL.getInstance().execute(req, id) { p, _, _ ->
                        push("__prog", id, p.toString())
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
                    push("__res", id, JSONObject().put("name", name).put("where", where).toString())
                } catch (e: Throwable) {
                    fail(id, e)
                }
            }
        }
    }
}
