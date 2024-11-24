import android.util.Log
import android.webkit.JavascriptInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

class SpeedTestManager {
    companion object {
        private const val TAG = "SpeedTestManager"

        // 单位转换常量
        private const val BYTES_TO_KB = 1024.0
        private const val BYTES_TO_MB = BYTES_TO_KB * 1024.0
        private const val BYTES_TO_GB = BYTES_TO_MB * 1024.0

        // 格式化速度
        fun formatSpeed(bytesPerSecond: Double): String {
            return when {
                bytesPerSecond >= BYTES_TO_GB -> "%.2f <br />GB/s".format(bytesPerSecond / BYTES_TO_GB)
                bytesPerSecond >= BYTES_TO_MB -> "%.2f <br />MB/s".format(bytesPerSecond / BYTES_TO_MB)
                bytesPerSecond >= BYTES_TO_KB -> "%.2f <br />KB/s".format(bytesPerSecond / BYTES_TO_KB)
                else -> "%.2f <br />B/s".format(bytesPerSecond)
            }
        }

        // 使用更可靠的测速服务器
        private val TEST_SERVERS = listOf(

            "https://dldir1.qq.com/weixin/Windows/WeChatSetup.exe",
        )
    }

    private var isRunning = false
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null

    data class SpeedResult(
        val bytesPerSecond: Double,
        val formattedSpeed: String
    )

    fun startSpeedTest(callback: SpeedTestCallback) {
        if (isRunning) return
        isRunning = true

        job = coroutineScope.launch {
            try {
                Log.d(TAG, "开始测速")
                val bestServer = findBestServer()
                Log.d(TAG, "选择的服务器: $bestServer")

                var totalBytes = 0L
                var lastBytes = 0L
                val startTime = System.currentTimeMillis()
                var lastTime = startTime

                val connection = URL(bestServer).openConnection() as HttpURLConnection
                connection.apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    requestMethod = "GET"
                    setRequestProperty("Range", "bytes=0-")
                }

                val inputStream = connection.inputStream
                val buffer = ByteArray(8192)

                repeat(10) { second ->
                    if (!isRunning) {
                        Log.d(TAG, "测速被中断")
                        return@repeat
                    }

                    var bytesRead = 0
                    val secondStart = System.currentTimeMillis()

                    while (System.currentTimeMillis() - secondStart < 1000 && isRunning) {
                        val read = inputStream.read(buffer)
                        if (read == -1) break
                        bytesRead += read
                        totalBytes += read
                    }

                    val now = System.currentTimeMillis()
                    val duration = (now - lastTime) / 1000.0
                    val currentSpeed = bytesRead / duration // bytes per second
                    val formattedSpeed = formatSpeed(currentSpeed)
                    
                    Log.d(TAG, "当前速度: $formattedSpeed, 已下载: ${formatSpeed(totalBytes.toDouble())}")

                    lastTime = now
                    lastBytes = totalBytes

                    withContext(Dispatchers.Main) {
                        callback.onProgress(SpeedResult(currentSpeed, formattedSpeed), (second + 1) * 10)
                    }
                }

                val totalDuration = (System.currentTimeMillis() - startTime) / 1000.0
                val averageSpeed = totalBytes / totalDuration
                val formattedAverageSpeed = formatSpeed(averageSpeed)
                
                Log.d(TAG, "测速完成，平均速度: $formattedAverageSpeed")

                withContext(Dispatchers.Main) {
                    callback.onComplete(SpeedResult(averageSpeed, formattedAverageSpeed))
                }

                inputStream.close()
                connection.disconnect()

            } catch (e: Exception) {
                Log.e(TAG, "测速出错", e)
                withContext(Dispatchers.Main) {
                    callback.onError("测速失败: ${e.message}")
                }
            } finally {
                isRunning = false
            }
        }
    }

    private suspend fun findBestServer(): String {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "开始查找最佳服务器")

            val serverLatencies = TEST_SERVERS.map { server ->
                try {
                    val start = System.currentTimeMillis()
                    val connection = URL(server).openConnection() as HttpURLConnection
                    connection.apply {
                        connectTimeout = 3000
                        readTimeout = 3000
                        requestMethod = "HEAD" // 只获取头信息
                    }

                    val latency = System.currentTimeMillis() - start
                    connection.disconnect()

                    Log.d(TAG, "服务器 $server 延迟: $latency ms")
                    server to latency
                } catch (e: Exception) {
                    Log.e(TAG, "服务器 $server 测试失败", e)
                    server to Long.MAX_VALUE
                }
            }

            val bestServer =
                serverLatencies.minByOrNull { it.second }?.first ?: TEST_SERVERS.first()
            Log.d(TAG, "选择的最佳服务器: $bestServer")
            bestServer
        }
    }

    fun stopSpeedTest() {
        Log.d(TAG, "停止测速")
        isRunning = false
        job?.cancel()
        job = null
    }

    interface SpeedTestCallback {
        fun onProgress(speed: SpeedResult, progress: Int)
        fun onComplete(averageSpeed: SpeedResult)
        fun onError(message: String)
    }
} 
