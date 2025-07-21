package com.cicy.agent.adr

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity.MODE_PRIVATE
import androidx.core.content.edit
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.github.kr328.clash.R
import com.github.kr328.clash.RestartReceiver
import com.github.kr328.clash.common.util.componentName
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*


class MessageHandler(private val service: Service) {
    private val pendingCallbacks = mutableMapOf<String, (Result<String>) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun getDeviceInfo(): JSONObject {
        val httpClient = HttpClient()
        try {
            val (getStatus, getResponse) = httpClient.get(
                "http://127.0.0.1:4447/deviceInfo",
                mapOf("Accept" to "application/json")
            )
            if (getStatus != 200) {
                throw Error("response status Code is not 200")
            } else {
                val jsonResponse = JSONObject(getResponse)
                return jsonResponse.getJSONObject("result")
            }
        } catch (e: Exception) {
            var configContent = ""
            val configFile = File("/data/local/tmp/config_server.txt")
            if (configFile.exists()) {
                configContent = configFile.readText().trim()
            }
            val clientId = getClientId()
            return JSONObject().apply {
                put("serverUrl", configContent)
                put("clientId", clientId)
                put("errMsg", e)
            }

        }
    }

    private fun getClashDefaultConfig(): String {
        return try {
            service.resources?.openRawResource(R.raw.config)?.bufferedReader().use {
                it?.readText()
                    ?: ""
            }
        } catch (e: Exception) {
            Log.e("LeafVpnService", "Error reading default config", e)
            ""
        }
    }
    private fun getClashConfig(): JSONObject {
        val sharedPref =
            service.getSharedPreferences("clash_preferences", Context.MODE_PRIVATE)
        val proxyPoolHost = sharedPref?.getString("proxyPoolHost", "") ?: ""
        val proxyPoolPort = sharedPref?.getString("proxyPoolPort", "") ?: "4445"
        val username = sharedPref?.getString("username", "") ?: "Account_10000"
        val password = sharedPref?.getString("password", "") ?: "pwd"
        var configYaml = getClashDefaultConfig()
        val nodeName = "HTTP_NODE"
        if (proxyPoolHost.isNotEmpty() && !proxyPoolHost.equals("127.0.0.1")) {
            configYaml = configYaml.replace(
                "# - proxy",
                "- { name: ${nodeName}, type: http, server: ${proxyPoolHost}, port: ${proxyPoolPort}, username: ${username}, password: ${password}  }"
            )
        } else {
            configYaml = configYaml.replace(
                "# - proxy",
                "- { name:  ${nodeName}, type: http, server: 127.0.0.1, port: 4445 }"
            )
            configYaml = configYaml.replace("MATCH, HTTP", "MATCH, DIRECT")
        }

        val status = service.packageManager.getComponentEnabledSetting(
            RestartReceiver::class.componentName
        )
        val autoRestart  = status == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val store = ServiceStore(service)
        return JSONObject().apply {
            put("accessControlMode", store.accessControlMode)
            put("accessControlPackages", store.accessControlPackages)
            put("autoRestart", autoRestart)

            put("proxyPoolHost", proxyPoolHost)
            put("proxyPoolPort", proxyPoolPort)
            put("username", username)
            put("password", password)
            put("configYaml", configYaml)
        }
    }

    private suspend fun createUUID(name: String, url: String): UUID {
        val uuid = withProfile {
            create(Profile.Type.Url, name).also {
                patch(it, name, url, 0)
            }
        }
        withProfile {
            commit(uuid)
            queryByUUID(uuid)?.let { profile ->
                setActive(profile)
            } ?: throw IllegalStateException("Profile not found after creation")
        }
        service.getSharedPreferences("CLASH_CONFIG", MODE_PRIVATE).edit {
            putString("currentUUID", uuid.toString())
            apply()
        }
        return uuid
    }

    private suspend fun updateClash(){
        if(Remote.broadcasts.clashRunning) {
            service.stopClashService()
        }

        val url = "http://127.0.0.1:${LocalServer.PORT}/clashConfig.yaml"
        val name = "代理 ${SimpleDateFormat("dd HH:mm", Locale.getDefault()).format(Date())}"
        val prefs = service.getSharedPreferences("CLASH_CONFIG", MODE_PRIVATE)
        val currentUUID = prefs.getString("currentUUID", null)
        if(currentUUID == null){
            createUUID(name,url)
            startClashDelay()
        }else{
            val uuid = UUID.fromString(currentUUID)
            withProfile {
                val profile = queryByUUID(uuid)
                if(profile == null){
                    createUUID(name,url)
                    startClashDelay()
                }else{
                    patch(uuid, name, url, 0)
                    update(uuid)
                    setActive(profile)
                    startClashDelay()
                }
            }
        }
    }

    private fun editClashProxyConfig(params: JSONArray): String {
        val sharedPref = service.getSharedPreferences("clash_preferences", Context.MODE_PRIVATE)
        val editor = sharedPref.edit().apply {
            putString("proxyPoolHost", params.optString(0, ""))
            putString("proxyPoolPort", params.optString(1, "4455"))
            putString("username", params.optString(2, ""))
            putString("password", params.optString(3, "pwd"))
        }
        val success = editor.commit()
        if (success) {
            return "Save successful"
        } else {
            return "Save failed"
        }
    }

    private fun startClashDelay(){
        if(Remote.broadcasts.clashRunning){
            service.stopClashService()
            while (true){
                if(Remote.broadcasts.clashRunning){
                    service.startClashService()
                    break
                }
            }
        }else{
            service.startClashService()
        }
    }


    fun process(method: String, params: JSONArray): JSONObject {
        return when (method) {
            "deviceInfo" -> getDeviceInfo()
            "screenWithXml" -> {
                var imgData = ""
                var imgLen = 0
                if (RecordingService.isReady) {
                    imgLen = RecordingService.screenImgData.length
                    imgData = "data:image/jpeg;base64,${RecordingService.screenImgData}"
                }

                var xml = ""
                if (InputService.isReady) {
                    xml = InputService.ctx?.getDumpAsUiAutomatorXml().toString()
                }
                JSONObject().apply {
                    put("xml", xml)
                    put("imgData", imgData)
                    put("imgLen", imgLen)
                }
            }

            "getInstalledApps" -> {
                val isAll = params.optString(0).equals("all")
                val apps = PackagesList(service).getInstalledApps(isAll)
                JSONObject().put("apps", apps)
            }
            "isClashRunning" -> {
                JSONObject().put("isClashRunning", Remote.broadcasts.clashRunning)
            }

            "editClashProxyConfig" -> {
                val res = editClashProxyConfig(params)

                CoroutineScope(Dispatchers.IO).launch {
                    updateClash()
                }

                JSONObject().put("res", res)
            }
            "setClashAutoRestart"->{
                val status = if (params.getBoolean(0))
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED

                service.packageManager.setComponentEnabledSetting(
                    RestartReceiver::class.componentName,
                    status,
                    PackageManager.DONT_KILL_APP,
                )
                JSONObject().put("status", status)
            }

            "setAccessControlPackages"->{
                val store = ServiceStore(service)
                val accessControlPackages = params.getJSONArray(0)

                val packagesSet = mutableSetOf<String>()
                for (i in 0 until accessControlPackages.length()) {
                    packagesSet.add(accessControlPackages.getString(i))
                }
                store.accessControlPackages = packagesSet
                JSONObject().put("accessControlPackages", store.accessControlPackages)
            }
            "setAccessControlMode"->{
                val store = ServiceStore(service)
                val accessControlMode = params.getString(0)
                when(accessControlMode){
                    "AcceptAll"->store.accessControlMode = AccessControlMode.AcceptAll
                    "AcceptSelected"->store.accessControlMode = AccessControlMode.AcceptSelected
                    "DenySelected"->store.accessControlMode = AccessControlMode.DenySelected
                }
                JSONObject().put("accessControlMode", store.accessControlMode)
            }
            "startClash" -> {
                if(!Remote.broadcasts.clashRunning){
                    val vpnRequest = service.startClashService()
                    if (vpnRequest != null) {
                        JSONObject().put("res", true)
                    }else{
                        JSONObject().put("res", false)
                    }
                }else{
                    JSONObject().put("res", false)
                }
            }
            "stopClash" -> {
                if(Remote.broadcasts.clashRunning){
                    service.stopClashService()
                }
                JSONObject().put("ok", true)
            }

            "getClashConfig" -> {
                getClashConfig()
            }

            "updateClash" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    updateClash()
                }
                JSONObject().put("ok", true)

            }
            else -> {
                try {
                    val result = runBlocking(Dispatchers.IO) {
                        kotlin.runCatching {
                            sendMessageToActivity(JSONObject().apply {
                                put("method", method)
                                put("params", params)
                            }.toString())
                        }
                    }
                    if (result.isSuccess) {
                        result.getOrNull()?.let { responseStr ->
                            try {
                                JSONObject(responseStr)  // Parse the successful JSON string
                            } catch (e: Exception) {
                                JSONObject().apply {
                                    put("err", "Invalid JSON format: ${e.message}")
                                }
                            }
                        } ?: JSONObject().apply {
                            put("err", "Empty response")
                        }
                    } else {
                        JSONObject().put("err", result.exceptionOrNull()?.message)
                    }
                } catch (e: Exception) {
                    JSONObject().put("err", e.message)
                }
            }
        }
    }

    fun cleanup() {
        pendingCallbacks.clear()
        LocalBroadcastManager.getInstance(service).unregisterReceiver(responseReceiver)
    }

    private val responseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_RESPONSE) return

            val callbackId = intent.getStringExtra(EXTRA_CALLBACK_ID) ?: return
            val result = intent.getStringExtra(EXTRA_RESULT)
            val error = intent.getStringExtra(EXTRA_ERROR)

            pendingCallbacks.remove(callbackId)?.let { callback ->
                mainHandler.post {
                    if (error != null) {
                        callback(Result.failure(Exception(error)))
                    } else {
                        callback(Result.success(result ?: ""))
                    }
                }
            }
        }
    }

    init {
        LocalBroadcastManager.getInstance(service).registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != ACTION_RESPONSE) return

                    val callbackId = intent.getStringExtra(EXTRA_CALLBACK_ID) ?: return
                    val result = intent.getStringExtra(EXTRA_RESULT)
                    val error = intent.getStringExtra(EXTRA_ERROR)

                    pendingCallbacks.remove(callbackId)?.let { callback ->
                        mainHandler.post {
                            if (error != null) {
                                callback(Result.failure(Exception(error)))
                            } else {
                                callback(Result.success(result ?: ""))
                            }
                        }
                    }
                }
            },
            IntentFilter(ACTION_RESPONSE)
        )
    }

    fun sendAsyncMessageToActivity(message: String) {
        val intent = Intent(ACTION_REQUEST)
        intent.putExtra(EXTRA_MESSAGE_ASYNC, message)
        LocalBroadcastManager.getInstance(service).sendBroadcast(intent)
    }


    private suspend fun sendMessageToActivity(message: String): String =
        suspendCancellableCoroutine { continuation ->
            val callbackId = UUID.randomUUID().toString()

            // 存储回调（如果协程被取消，自动移除回调）
            pendingCallbacks[callbackId] = { result ->
                continuation.resumeWith(result)
            }

            continuation.invokeOnCancellation {
                pendingCallbacks.remove(callbackId)
            }

            // 发送广播
            val intent = Intent(ACTION_REQUEST).apply {
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_CALLBACK_ID, callbackId)
            }
            LocalBroadcastManager.getInstance(service).sendBroadcast(intent)
        }
    companion object {
        // Broadcast Actions
        const val ACTION_REQUEST = "mainMessage"
        const val ACTION_RESPONSE = "mainMessageResponse"

        // Intent Extras
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_MESSAGE_ASYNC = "messageAsync"
        const val EXTRA_CALLBACK_ID = "callbackId"
        const val EXTRA_RESULT = "result"
        const val EXTRA_ERROR = "error"
    }
}