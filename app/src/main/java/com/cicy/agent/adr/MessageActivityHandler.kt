package com.cicy.agent.adr

import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import com.github.kr328.clash.BaseMainActivity
import com.github.kr328.clash.remote.Remote
import com.hjq.permissions.XXPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

class MessageActivityHandler(
    private val context: BaseMainActivity<*>,
) : CoroutineScope by MainScope() {
    private val logTag = "MessageActivityHandler"
    private fun getContext(): BaseMainActivity<*> {
        return context
    }

    private fun readFileFromAssets(filename: String): String {
        return try {
            val assetManager: AssetManager = context.assets
            val inputStream = assetManager.open(filename)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val content = reader.readText()
            reader.close()
            content
        } catch (e: IOException) {
            e.printStackTrace()
            ""
        }
    }


    private fun getScreenSize(windowManager: WindowManager): Pair<Int, Int> {
        var w = 0
        var h = 0
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val m = windowManager.maximumWindowMetrics
            w = m.bounds.width()
            h = m.bounds.height()
        } else {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(dm)
            w = dm.widthPixels
            h = dm.heightPixels
        }
        return Pair(w, h)
    }

    private fun getAgentAppInfo(): JSONObject {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (screenWidth, screenHeight) = getScreenSize(windowManager)
        val ipAddress = NetworkUtils.getCurrentIp(context)
        val brand = Build.BRAND  // 手机品牌
        val model = Build.MODEL  // 手机型号
        val device = Build.DEVICE // 设备名称
        val product = Build.PRODUCT // 产品名称
        val version = Build.VERSION.RELEASE  // 安卓系统版本
        val id = Build.ID  // 编译版本ID
        val serverUrl = readServerUrlFromFile()
        val abi = getAbi()

        val payload = JSONObject().apply {
            put("abi", abi)
            put("clientId", getClientId())
            put("serverUrl", serverUrl)
            put("model", model)
            put("inputIsReady", InputService.isReady)
            put("recordingIsReady", RecordingService.isReady)
            put("width", screenWidth)
            put("height", screenHeight)
            put("dpi", context.resources.displayMetrics.density)
            put("ipAddress", ipAddress)
            put("brand", brand)
            put("model", model)
            put("BuildDevice", device)
            put("BuildProduct", product)
            put("buildVersion", version)
            put("buildId", id)
            put("version", "1.0.1")
            put("isClashRunning", Remote.broadcasts.clashRunning)
        }
        return payload
    }

    private fun onStartInput() {
        if (!InputService.isReady) {
            startAction(context, Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }
    }

    private fun onStopInput() {
        if (InputService.isReady) {
            InputService.ctx?.disableSelf()
        }
    }

    fun processAsync(messageAsync: String) {
        when (messageAsync) {
            "onStartRecording" -> getContext().startRecording()
            "onStopRecording" -> getContext().stopRecording()
            "onStartInput" -> onStartInput()
            "onStopInput" -> onStopInput()
            else -> {}
        }
    }

    fun process(message: String, callbackId: String?): JSONObject {
        var response = JSONObject().apply {
            put("err", "")
        }
        try {
            val json = JSONObject(message)
            val method = json.getString("method")
            val params = json.getJSONArray("params")

            when (method) {
                "agentAppInfo" -> {
                    response = getAgentAppInfo()
                }

                "onStartRecording" -> getContext().startRecording()
                "onStopRecording" -> getContext().stopRecording()
                "onStartInput" -> {
                    onStartInput()
                }

                "onStopInput" -> {
                    onStopInput()
                }

                "click" -> {
                    if (!InputService.isReady) {
                        response.put("err", "InputService is not open")
                    } else {
                        getContext().recordingService?.handlePostEvent(JSONObject().apply {
                            put("eventType", "click")
                            put("x", params[0])
                            put("y", params[1])
                        })
                    }

                }
                "inputText" -> {
                    if (!InputService.isReady) {
                        response.put("err", "InputService is not open")
                    } else {
                        val text = params[0].toString()
                        InputService.ctx?.inputText(text)
                    }
                }

                "pressKey" -> {
                    if (!InputService.isReady) {
                        response.put("err", "InputService is not open")
                    } else {
                        // Extract the key name from the params array
                        val keyName = params[0].toString()
                        // Map the key name to the corresponding action code
                        val code = when (keyName) {
                            "back" -> 1
                            "home" -> 2
                            "recent" -> 3
                            else -> 0
                        }
                        // Send the action event if the mainService is available
                        getContext().recordingService?.handlePostEvent(JSONObject().apply {
                            put("eventType", "action")
                            put("value", code)
                        })
                    }
                }

                "takeScreenshot" -> {
                    var imgData = ""
                    var imgLen = 0
                    if (RecordingService.isReady) {
                        imgLen = RecordingService.screenImgData.length
                        imgData = "data:image/jpeg;base64,${RecordingService.screenImgData}"
                    }

                    response = JSONObject().apply {
                        put("imgData", imgData)
                        put("imgLen", imgLen)
                    }
                }

                "dumpWindowHierarchy" -> {
                    var xml = ""
                    if (InputService.isReady) {
                        xml = InputService.ctx?.getDumpAsUiAutomatorXml().toString()
                    }
                    response = JSONObject().apply {
                        put("xml", xml)
                    }
                }

                "showToast" -> {
                    Toast.makeText(context, params.optString(0), Toast.LENGTH_SHORT).show()
                }

                "getOpencvJs" -> {
                    val text = readFileFromAssets("opencv.js")
                    response = JSONObject().apply {
                        put("text", text)
                    }
                }

                "checkPermission" -> {
                    val isGranted = XXPermissions.isGranted(context, params.get(0) as String)
                    response = JSONObject().apply {
                        put("isGranted", isGranted)
                    }
                }

                "requestPermission" -> {
                    requestPermission(context, params.get(0) as String)
                    response = JSONObject().apply {
                        put("ok", true)
                    }
                }

                "startAction" -> {
                    startAction(context, params.get(0) as String)
                }
                else -> {
                    response.put("err", "Unknown method: $method")
                }
            }

        } catch (e: Exception) {
            response.put("err", "Invalid message format: ${e.message}")
        }
        if (callbackId !== null) {
            val responseIntent = Intent(MessageHandler.ACTION_RESPONSE).apply {
                putExtra(MessageHandler.EXTRA_CALLBACK_ID, callbackId)
                putExtra(MessageHandler.EXTRA_RESULT, response.toString())
            }
            getContext().localBroadcastManager.sendBroadcast(responseIntent)
        }
        return response
    }
}