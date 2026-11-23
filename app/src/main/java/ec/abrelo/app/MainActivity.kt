package ec.abrelo.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private val incoming = ConcurrentHashMap<String, Uri>()
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingSave: File? = null
    private var pageReady = false
    private var pendingIntent: Intent? = null

    private val host = "appassets.androidplatform.net"

    /* Elegir archivos desde la app (botón "Abrir archivo") */
    private val pickLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val cb = fileCallback
            fileCallback = null
            if (cb == null) return@registerForActivityResult
            val data = res.data
            if (res.resultCode != RESULT_OK || data == null) {
                cb.onReceiveValue(null); return@registerForActivityResult
            }
            val list = mutableListOf<Uri>()
            val clip = data.clipData
            if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { list += it }
            else data.data?.let { list += it }
            cb.onReceiveValue(if (list.isEmpty()) null else list.toTypedArray())
        }

    /* Guardar: el teléfono pregunta dónde */
    private val saveLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val src = pendingSave
            pendingSave = null
            val uri = res.data?.data
            if (res.resultCode == RESULT_OK && uri != null && src != null) {
                try {
                    contentResolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
                    jsToast("Guardado. Lo encuentras en la carpeta que elegiste.")
                } catch (e: Exception) {
                    jsToast("No se pudo guardar el archivo.")
                }
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            builtInZoomControls = false
        }

        val loader = WebViewAssetLoader.Builder()
            .setDomain(host)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/incoming/", WebViewAssetLoader.PathHandler { path ->
                val uri = incoming[path] ?: return@PathHandler null
                try {
                    WebResourceResponse(
                        contentResolver.getType(uri) ?: "application/octet-stream",
                        null,
                        contentResolver.openInputStream(uri)
                    )
                } catch (e: Exception) {
                    null
                }
            })
            .build()

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == host) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (_: Exception) {}
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (pageReady) return
                pageReady = true
                pendingIntent?.let { handleIntent(it) }
                pendingIntent = null
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val types = params.acceptTypes
                    .flatMap { it.split(",") }
                    .map { it.trim() }
                    .filter { it.contains("/") }
                    .distinct()
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                i.type = if (types.size == 1) types[0] else "*/*"
                if (types.size > 1) i.putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
                if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                return try {
                    pickLauncher.launch(i); true
                } catch (e: Exception) {
                    fileCallback = null; false
                }
            }
        }

        web.addJavascriptInterface(Bridge(), "Android")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.__back?window.__back():false)") { r ->
                    if (r != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        web.loadUrl("https://$host/assets/index.html")
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { uris += it }
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris += it }
        }
        val uri = uris.firstOrNull() ?: return
        if (!pageReady) { pendingIntent = intent; return }
        val id = UUID.randomUUID().toString()
        incoming[id] = uri
        val name = displayName(uri)
        val mime = contentResolver.getType(uri) ?: intent.type ?: ""
        web.evaluateJavascript(
            "window.openIncoming&&window.openIncoming(" +
                JSONObject.quote("https://$host/incoming/$id") + "," +
                JSONObject.quote(name) + "," + JSONObject.quote(mime) + ")",
            null
        )
        intent.action = Intent.ACTION_MAIN // no volver a abrirlo al regresar
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment?.substringAfterLast('/') ?: "archivo"
    }

    private fun jsToast(msg: String) {
        runOnUiThread { web.evaluateJavascript("window.__toast&&window.__toast(" + JSONObject.quote(msg) + ")", null) }
    }

    private fun writeTemp(name: String, b64: String): File {
        val dir = File(cacheDir, "out").apply { mkdirs() }
        val old = System.currentTimeMillis() - 3_600_000
        dir.listFiles()?.forEach { if (it.lastModified() < old) it.delete() }
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "archivo" }
        return File(dir, safe).apply { writeBytes(Base64.decode(b64, Base64.DEFAULT)) }
    }

    inner class Bridge {
        @JavascriptInterface
        fun save(name: String, mime: String, b64: String) {
            val f = try { writeTemp(name, b64) } catch (e: Exception) { jsToast("No se pudo preparar el archivo."); return }
            runOnUiThread {
                pendingSave = f
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mime.ifBlank { "application/octet-stream" })
                    .putExtra(Intent.EXTRA_TITLE, f.name)
                try { saveLauncher.launch(i) } catch (e: Exception) { jsToast("No se pudo abrir la ventana para guardar.") }
            }
        }

        @JavascriptInterface
        fun share(name: String, mime: String, b64: String) {
            val f = try { writeTemp(name, b64) } catch (e: Exception) { jsToast("No se pudo preparar el archivo."); return }
            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", f)
            runOnUiThread {
                val send = Intent(Intent.ACTION_SEND)
                    .setType(mime.ifBlank { "application/octet-stream" })
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try { startActivity(Intent.createChooser(send, "Compartir")) } catch (e: Exception) { jsToast("No hay apps para compartir.") }
            }
        }
    }
}
