package com.goodnotes.pointerlab

import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Bare-metal launcher for the pointer-sampling probe.
 *
 * It runs a loopback web server ([LocalServer]) hosting the measurement page, then opens that URL in a chosen
 * browser via a Custom Tab. A localhost URL can't pass TWA Digital-Asset-Links verification, so a real TWA
 * would degrade to a Custom Tab anyway — and a Custom Tab uses the identical Chromium engine + pointer pipeline
 * as a TWA, so it is a faithful proxy for how an installed web app / TWA behaves.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var server: LocalServer
    private var serverUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        server = LocalServer.startOn(applicationContext)
        serverUrl = "http://127.0.0.1:${server.listeningPort}/"

        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(48), px(24), px(24))
        }

        root.addView(TextView(this).apply {
            text = "PointerLab — touch-rate probe"
            textSize = 22f
        })

        root.addView(TextView(this).apply {
            text = "Serving: $serverUrl"
            textSize = 14f
            setPadding(0, px(12), 0, px(16))
        })

        root.addView(browserButton("Open in Samsung Internet", SAMSUNG_INTERNET))
        root.addView(browserButton("Open in Chrome", CHROME))

        root.addView(TextView(this).apply {
            text = "Draw with a stylus inside the page. Watch the live counters + verdict banner. " +
                "The page mirrors its console output to logcat (tag: chromium)."
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, px(24), 0, 0)
        })

        setContentView(root)
    }

    private fun browserButton(label: String, pkg: String): Button {
        val installed = isInstalled(pkg)
        return Button(this).apply {
            text = if (installed) label else "$label (not installed)"
            isEnabled = installed
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener { launchIn(pkg) }
        }
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun launchIn(pkg: String) {
        val customTabs = CustomTabsIntent.Builder().build()
        customTabs.intent.setPackage(pkg)
        customTabs.launchUrl(this, Uri.parse(serverUrl))
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::server.isInitialized) server.stop()
    }

    companion object {
        private const val SAMSUNG_INTERNET = "com.sec.android.app.sbrowser"
        private const val CHROME = "com.android.chrome"
    }
}
