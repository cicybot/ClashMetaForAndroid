package com.cicy.agent.adr

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

class MessageHandler(private val service: Service) {
    private val pendingCallbacks = mutableMapOf<String, (Result<String>) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

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
        // 注册接收 Activity 响应的 BroadcastReceiver
        LocalBroadcastManager.getInstance(service).registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != ACTION_RESPONSE) return

                    val callbackId = intent.getStringExtra(EXTRA_CALLBACK_ID) ?: return
                    val result = intent.getStringExtra(EXTRA_RESULT)
                    val error = intent.getStringExtra(EXTRA_ERROR)

                    // 从 Map 中移除回调并执行
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

    /**
     * 发送消息到 Activity，并等待响应（协程挂起）
     */
    suspend fun sendMessageToActivity(message: String): String =
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

    fun process(method: String, params: JSONArray): JSONObject {
        return when (method) {
            "version" -> {
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

    /**
     * 清理资源（防止内存泄漏）
     */
    fun cleanup() {
        pendingCallbacks.clear()
        LocalBroadcastManager.getInstance(service).unregisterReceiver(responseReceiver)
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