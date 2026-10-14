package com.pathra.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.*
import androidx.webkit.WebViewAssetLoader

class MainActivity : Activity() {
    private fun svc(action: String? = null) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        try { startForegroundService(Intent(this, SosService::class.java).apply { this.action = action }) } catch (_: Exception) {}
    }

    inner class Bridge {
        @JavascriptInterface fun saveConfig(json: String) =
            getSharedPreferences("pathra", 0).edit().putString("cfg", json).apply()
        @JavascriptInterface fun startService() = svc()
        @JavascriptInterface fun triggerSos() = svc(SosService.ACT_SOS)
        @JavascriptInterface fun stopSos() = svc(SosService.ACT_STOP)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val perms = mutableListOf(Manifest.permission.SEND_SMS, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 1)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.addJavascriptInterface(Bridge(), "PathraNative")
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest) = loader.shouldInterceptRequest(r.url)
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(o: String, cb: GeolocationPermissions.Callback) = cb.invoke(o, true, false)
        }
        setContentView(wv)
        wv.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    // Android requires background location to be requested AFTER foreground location is granted
    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        if (c == 1) {
            svc()   // protection starts as soon as permissions are granted
            if (Build.VERSION.SDK_INT >= 29)
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
        }
    }
}

class BootReceiver : BroadcastReceiver() {   // restart protection after phone reboot
    override fun onReceive(c: Context, i: Intent) {
        try { c.startForegroundService(Intent(c, SosService::class.java)) } catch (_: Exception) {}  // Android may block this after reboot; app start restores it
    }
}
