package com.vauth.webviewapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val DEFAULT_URL = "https://example.com"
    }

    private var webView: WebView? = null
    private var progressBar: ProgressBar? = null
    private var container: FrameLayout? = null
    private var loadingOverlay: FrameLayout? = null
    private var fabBack: FloatingActionButton? = null
    private var fabForward: FloatingActionButton? = null

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var geolocationCallback: GeolocationPermissions.Callback? = null
    private var geolocationOrigin: String? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var isFirstPageLoad = true
    private var isDestroyed = false

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                val uris = data?.clipData?.let { clip ->
                    Array(clip.itemCount) { clip.getItemAt(it).uri }
                } ?: data?.data?.let { arrayOf(it) }
                fileUploadCallback?.onReceiveValue(uris)
            } else {
                fileUploadCallback?.onReceiveValue(null)
            }
            fileUploadCallback = null
        }

    private val mediaPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.values.all { it }) {
                pendingPermissionRequest?.grant(pendingPermissionRequest!!.resources)
            } else {
                pendingPermissionRequest?.deny()
            }
            pendingPermissionRequest = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        container = FrameLayout(this)
        webView = WebView(this)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 8, Gravity.TOP
            )
        }

        loadingOverlay = FrameLayout(this).apply {
            setBackgroundColor(0x88000000.toInt())
            addView(ProgressBar(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            })
        }

        container!!.addView(webView)
        container!!.addView(progressBar)
        container!!.addView(loadingOverlay)

        createFloatingButtons()
        setContentView(container)

        setupBackPressHandler()
        setupWebView()

        val url = BuildConfig.WEBSITE_URL
        webView!!.loadUrl(if (url.isBlank()) DEFAULT_URL else url)
    }

    private fun createFloatingButtons() {
        fabBack = FloatingActionButton(this).apply {
            setImageResource(android.R.drawable.ic_media_previous)
            setOnClickListener { if (webView!!.canGoBack()) webView!!.goBack() }
        }

        fabForward = FloatingActionButton(this).apply {
            setImageResource(android.R.drawable.ic_media_next)
            setOnClickListener { if (webView!!.canGoForward()) webView!!.goForward() }
        }

        val margin = 48
        fabBack!!.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START
        ).apply { setMargins(margin, margin, margin, margin) }

        fabForward!!.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START
        ).apply { setMargins(margin, margin, margin, margin + 150) }

        container!!.addView(fabBack)
        container!!.addView(fabForward)
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView!!.canGoBack()) webView!!.goBack()
                else finish()
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView!!.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        // ✅ DOWNLOAD SUPPORT
        webView!!.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimeType)

                CookieManager.getInstance().getCookie(url)?.let {
                    request.addRequestHeader("cookie", it)
                }
                request.addRequestHeader("User-Agent", userAgent)

                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                request.setTitle(fileName)
                request.setDescription("Downloading file...")
                request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, fileName
                )

                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager)
                    .enqueue(request)

                Toast.makeText(this, "Downloading $fileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Download error", e)
                Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show()
            }
        }

        webView!!.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar!!.visibility = View.GONE
                if (isFirstPageLoad) {
                    loadingOverlay!!.visibility = View.GONE
                    isFirstPageLoad = false
                }
            }
        }

        webView!!.webChromeClient = object : WebChromeClient() {

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback = filePathCallback
                fileChooserLauncher.launch(fileChooserParams!!.createIntent())
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                pendingPermissionRequest = request
                mediaPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO
                    )
                )
            }
        }
    }

    override fun onDestroy() {
        isDestroyed = true
        webView?.destroy()
        super.onDestroy()
    }
}
