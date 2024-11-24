package com.ileping.network_speed

import SpeedTestManager
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.NetworkInterface
import java.net.Inet4Address
import android.net.Network
import android.net.NetworkRequest
import android.webkit.JavascriptInterface
import androidx.core.view.WindowInsetsCompat
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback
    private lateinit var webAppInterface: WebAppInterface
    
    private var lastBackPressTime: Long = 0
    
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
            return
        }
        
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBackPressTime > 2000) {
            Toast.makeText(this, "再按一次退出应用", Toast.LENGTH_SHORT).show()
            lastBackPressTime = currentTime
        } else {
            super.onBackPressed()
            finish()
        }
    }

    inner class WebAppInterface(private val activity: Activity) {
        private val speedTestManager = SpeedTestManager()
        
        private fun showToast(message: String) {
            activity.runOnUiThread { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show() }
        }

        private fun checkAndRequestPermission(): Boolean {
            if (!Settings.System.canWrite(activity)) {
                showToast("需要授权才能调节亮度")
                requestBrightnessPermission()
                return false
            }
            return true
        }

        @android.webkit.JavascriptInterface
        fun checkBrightnessPermission(): Boolean {
            return Settings.System.canWrite(activity)
        }

        @android.webkit.JavascriptInterface
        fun requestBrightnessPermission() {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                intent.data = Uri.parse("package:" + activity.packageName)
                activity.startActivity(intent)
            } catch (e: Exception) {
                Log.e("MainActivity", "请求权限失败: ${e.message}")
                showToast("打开设置页面失败，请手动授权")
            }
        }

        @android.webkit.JavascriptInterface
        fun setScreenBrightness(brightness: Int): Boolean {
            if (!checkAndRequestPermission()) {
                return false
            }

            return try {
                val validBrightness = brightness.coerceIn(0, 255)
                Settings.System.putInt(
                        activity.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS,
                        validBrightness
                )
                activity.runOnUiThread {
                    activity.window.attributes =
                            activity.window.attributes.apply {
                                screenBrightness = validBrightness / 255f
                            }
                }
                true
            } catch (e: Exception) {
                Log.e("MainActivity", "设置亮度失败: ${e.message}")
                showToast("设置亮度失败")
                false
            }
        }

        @android.webkit.JavascriptInterface
        fun setAutoBrightness(enabled: Boolean): Boolean {
            if (!checkAndRequestPermission()) {
                return false
            }

            return try {
                Settings.System.putInt(
                        activity.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        if (enabled) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                        else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                true
            } catch (e: Exception) {
                Log.e("MainActivity", "设置自动亮度失败: ${e.message}")
                showToast("设置自动亮度失败")
                false
            }
        }

        @android.webkit.JavascriptInterface
        fun getScreenBrightness(): Int {
            try {
                return Settings.System.getInt(
                        activity.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS
                )
            } catch (e: Exception) {
                Log.e("MainActivity", "获取亮度失败: ${e.message}")
                e.printStackTrace()
            }
            return -1
        }

        @android.webkit.JavascriptInterface
        fun isAutoBrightnessEnabled(): Boolean {
            return try {
                Settings.System.getInt(
                        activity.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE
                ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            } catch (e: Exception) {
                Log.e("MainActivity", "获取自动亮度状态失败: ${e.message}")
                false
            }
        }

        @android.webkit.JavascriptInterface
        fun setKeepScreenOn(enabled: Boolean): Boolean {
            return try {
                activity.runOnUiThread {
                    if (enabled) {
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                true
            } catch (e: Exception) {
                Log.e("MainActivity", "设置屏幕常亮失败: ${e.message}")
                showToast("设置屏幕常亮失败")
                false
            }
        }

        @android.webkit.JavascriptInterface
        fun isKeepScreenOn(): Boolean {
            return (activity.window.attributes.flags and
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        }

        @android.webkit.JavascriptInterface
        fun startSpeedTest() {
            speedTestManager.startSpeedTest(object : SpeedTestManager.SpeedTestCallback {
                override fun onProgress(speed: SpeedTestManager.SpeedResult, progress: Int) {
                    activity.runOnUiThread {
                        webView.evaluateJavascript(
                            """
                            updateSpeedTest(
                                ${speed.bytesPerSecond}, 
                                $progress, 
                                '${speed.formattedSpeed}'
                            )
                            """.trimIndent(),
                            null
                        )
                    }
                }

                override fun onComplete(averageSpeed: SpeedTestManager.SpeedResult) {
                    activity.runOnUiThread {
                        webView.evaluateJavascript(
                            """
                            completeSpeedTest(
                                ${averageSpeed.bytesPerSecond}, 
                                '${averageSpeed.formattedSpeed}'
                            )
                            """.trimIndent(),
                            null
                        )
                    }
                }

                override fun onError(message: String) {
                    activity.runOnUiThread {
                        webView.evaluateJavascript(
                            "errorSpeedTest('$message')",
                            null
                        )
                    }
                }
            })
        }

        @android.webkit.JavascriptInterface
        fun stopSpeedTest() {
            speedTestManager.stopSpeedTest()
        }

        @JavascriptInterface
        fun getNetworkType(): String {
            val connectivityManager = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            
            return when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> {
                    when {
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                            when {
                                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> "5G"
                                else -> "4G"
                            }
                        }
                        else -> "移动网络"
                    }
                }
                else -> "未知"
            }
        }

        @JavascriptInterface
        fun getIpAddress(): String {
            try {
                val networkInterfaces = NetworkInterface.getNetworkInterfaces()
                while (networkInterfaces.hasMoreElements()) {
                    val networkInterface = networkInterfaces.nextElement()
                    val inetAddresses = networkInterface.inetAddresses
                    while (inetAddresses.hasMoreElements()) {
                        val inetAddress = inetAddresses.nextElement()
                        if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                            return inetAddress.hostAddress ?: "未知"
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return "未知"
        }

        // 网络状态变化时调用此方法更新 Web 页面
        fun updateNetworkStatus() {
            activity.runOnUiThread {
                webView.evaluateJavascript(
                    "window.NetworkManager.updateNetworkInfo('${getNetworkType()}', '${getIpAddress()}')",
                    null
                )
            }
        }

        @JavascriptInterface
        fun getSystemInsets(): String {
            val window = activity.window
            val decorView = window.decorView
            val density = activity.resources.displayMetrics.density
            
            val windowInsets = WindowInsetsCompat.toWindowInsetsCompat(decorView.rootWindowInsets)
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            
            // 将 px 转换为 dp
            val statusBarHeight = (insets.top / density).toInt()
            val navigationBarHeight = (insets.bottom / density).toInt()

            Log.d("SystemInsets", "Status bar: $statusBarHeight dp, Navigation bar: $navigationBarHeight dp")
            
            return "{" +
                "\"statusBarHeight\": $statusBarHeight," +
                "\"navigationBarHeight\": $navigationBarHeight" +
                "}"
        }

        // 检查是否有导航栏
        private fun Activity.hasNavigationBar(): Boolean {
            val id = resources.getIdentifier("config_showNavigationBar", "bool", "android")
            return id > 0 && resources.getBoolean(id)
        }

        @JavascriptInterface
        fun getNetworkInfo(): String {
            val connectivityManager = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            
            // 获取网络类型
            val networkType = when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "移动网络"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "以太网"
                else -> "未知"
            }
            
            // 获取连接时间
            val connectionTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date())
            
            // 获取IP地址
            val ip = getIpAddress()
            
            // 测试网络延迟
            val latency = measureLatency()
            
            return "{" +
                "\"type\": \"$networkType\"," +
                "\"ip\": \"$ip\"," +
                "\"latency\": $latency," +
                "\"connectionTime\": \"$connectionTime\"" +
                "}"
        }
        
        private fun measureLatency(): Int {
            var latency = -1
            try {
                CoroutineScope(Dispatchers.IO).launch {
                    val startTime = System.currentTimeMillis()
                    val socket = Socket()
                    try {
                        socket.connect(InetSocketAddress("8.8.8.8", 53), 1000)
                        latency = (System.currentTimeMillis() - startTime).toInt()
                        socket.close()
                        
                        // 更新UI
                        withContext(Dispatchers.Main) {
                            activity.runOnUiThread {
                                webView.evaluateJavascript(
                                    "NetworkManager.updateLatency($latency)",
                                    null
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("NetworkInfo", "Error measuring latency", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("NetworkInfo", "Error in latency measurement", e)
            }
            return latency
        }

        @JavascriptInterface
        fun measureNetworkQuality() {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // 进行多次 ping 测试来计算延迟和抖动
                    val pingResults = mutableListOf<Long>()
                    repeat(2) {
                        val startTime = System.currentTimeMillis()
                        val socket = Socket()
                        try {
                            socket.connect(InetSocketAddress("8.8.8.8", 53), 1000)
                            val pingTime = System.currentTimeMillis() - startTime
                            pingResults.add(pingTime)
                            socket.close()
                        } catch (e: Exception) {
                            Log.e("NetworkInfo", "Ping failed", e)
                        }
                        delay(200) // 每次 ping 间隔 200ms
                    }

                    // 计算平均延迟和抖动
                    val avgLatency = if (pingResults.isNotEmpty()) {
                        pingResults.average().toInt()
                    } else -1

                    val jitter = if (pingResults.size > 1) {
                        var sum = 0.0
                        for (i in 1 until pingResults.size) {
                            sum += Math.abs(pingResults[i] - pingResults[i-1])
                        }
                        (sum / (pingResults.size - 1)).toInt()
                    } else -1

                    // 获取网络类型和IP
                    val connectivityManager = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val network = connectivityManager.activeNetwork
                    val capabilities = connectivityManager.getNetworkCapabilities(network)
                    
                    val networkType = when {
                        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "移动网络"
                        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "以太网"
                        else -> "未知"
                    }

                    // 获取IP地址
                    val ip = try {
                        NetworkInterface.getNetworkInterfaces().toList()
                            .flatMap { it.inetAddresses.toList() }
                            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                            ?.hostAddress ?: "未知"
                    } catch (e: Exception) {
                        Log.e("NetworkInfo", "Error getting IP address", e)
                        "未知"
                    }

                    // 更新UI
                    withContext(Dispatchers.Main) {
                        activity.runOnUiThread {
                            webView.evaluateJavascript(
                                """
                                NetworkManager.updateNetworkInfo(
                                    "$networkType",
                                    "$ip",
                                    $avgLatency,
                                    $jitter
                                )
                                """.trimIndent(),
                                null
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("NetworkInfo", "Error measuring network quality", e)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化 WebAppInterface
        webAppInterface = WebAppInterface(this)

        webView = WebView(this).apply {
            settings.apply {
                domStorageEnabled = true
                javaScriptEnabled = true
                blockNetworkImage = false
            }

            // 添加 JavaScript 接口
            addJavascriptInterface(webAppInterface, "Android")

            webViewClient = object : WebViewClient() {}
            webChromeClient = WebChromeClient()
            loadUrl("file:///android_asset/main.html")
        }

        findViewById<LinearLayout>(R.id.main_container).addView(
            webView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // 初始化网络监听
        setupNetworkCallback()
    }

    private fun setupNetworkCallback() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                webAppInterface.updateNetworkStatus()
            }

            override fun onLost(network: Network) {
                webAppInterface.updateNetworkStatus()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                webAppInterface.updateNetworkStatus()
            }
        }

        val request = NetworkRequest.Builder().build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }
}
