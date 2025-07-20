package com.cicy.agent.adr

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

interface WsOptions {
    fun onOpen(webSocket: WebSocket)
    fun onMessage(webSocket: WebSocket, text: String)
    fun onClose(webSocket: WebSocket, code: Int)
    fun onFailure(webSocket: WebSocket, t: Throwable)
}

class LocalWsServer(private val clientId: String, private val options: WsOptions? = null) {
    private val tag = "CCWebSocketClient"
    private var webSocket: WebSocket? = null
    private val isConnecting = AtomicBoolean(false)
    private val shouldReconnect = AtomicBoolean(true)
    private var token: String? = null
    private var isLogged = false

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS) // Keep connection alive
        .build()


    private fun processWebSocketUrl(originalUrl: String): Pair<String, String?> {
        return if (originalUrl.contains("token=")) {
            // Split into base URL and query parameters
            val (baseUrl, queryParams) = originalUrl.split('?', limit = 2)

            // Extract token from query parameters
            val token = queryParams.split('&')
                .firstOrNull { it.startsWith("token=") }
                ?.replace("token=", "")

            // Return clean URL and token
            Pair(baseUrl, token)
        } else {
            // No token in URL
            Pair(originalUrl, null)
        }
    }

    fun connect() {
        val serverUrl = readServerUrlFromFile()
        if (serverUrl == null) {
            Log.w(tag, "Server URL not set, retrying in 1 second")
            CoroutineScope(Dispatchers.IO).launch {
                delay(1000)
                connect()
            }
            return
        }

        if (isConnecting.get()) return
        isConnecting.set(true)

        try {
            val (cleanServerUrl, extractedToken) = processWebSocketUrl(serverUrl)
            token = extractedToken
            val url = "$cleanServerUrl?id=$clientId&t=${System.currentTimeMillis()}"
            Log.d(tag, "[+] Connecting to $url")

            val request = Request.Builder()
                .url(url)
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    isConnecting.set(false)
                    Log.d(tag, "[+] Connected to $url")
                    if (token?.isEmpty() == false) {
                        // Send login message
                        val loginMessage = JSONObject().apply {
                            put("action", "login")
                            put("payload", JSONObject().apply {
                                put("token", token)
                            })
                        }.toString()

                        webSocket.send(loginMessage)
                    }

                    options?.onOpen(webSocket)

                    // Start sending ping every 5 seconds after successful connection
                    startSendingPing()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(tag, "onMessage: $text")
                    try {
                        if (text.isJson()) {
                            val json = text.toJsonObject()
                            val action = json.optString("action")
                            val id = json.optString("id")

                            if (action == "callback" && id.isNotEmpty()) {
                                // Handle callback logic here
                                // MsgResult[id] = payload
                            } else if (action == "pong") {
                                Log.d(tag, "pp-pong!")
                            } else if (action == "logged") {
                                Log.d(tag, "logged")
                                isLogged = true
                            } else if (action == "logout") {
                                Log.d(tag, "logout")
                                isLogged = false
                            } else {
                                if (isLogged) {
                                    options?.onMessage(webSocket, text)
                                }

                            }
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Error processing message", e)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(tag, "onClosing: $code - $reason")
                    options?.onClose(webSocket, code)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    isConnecting.set(false)
                    Log.d(tag, "onClosed: $code - $reason")
                    if (code != WS_CLOSE_STOP_RECONNECT && shouldReconnect.get()) {
                        scheduleReconnect()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    isConnecting.set(false)
                    Log.e(tag, "Connection error", t)
                    options?.onFailure(webSocket, t)
                    if (shouldReconnect.get()) {
                        scheduleReconnect()
                    }
                }
            })
        } catch (e: Exception) {
            isConnecting.set(false)
            Log.e(tag, "connectCCServer error", e)
            if (shouldReconnect.get()) {
                scheduleReconnect()
            }
        }
    }

    // Start sending ping message every 5 seconds
    private fun startSendingPing() {
        CoroutineScope(Dispatchers.IO).launch {
            while (webSocket?.send("{\"action\":\"ping\"}") == true) {
                Log.d(tag, "pp-ping!")
                delay(10000) // Send ping every 5 seconds
            }
        }
    }

    private fun scheduleReconnect() {
        CoroutineScope(Dispatchers.IO).launch {
            delay(1000)
            connect()
        }
    }

    fun disconnect() {
        shouldReconnect.set(false)
        webSocket?.close(WS_CLOSE_STOP_RECONNECT, "User requested disconnect")
    }

    fun sendMessage(text: String): Boolean {
        return webSocket?.send(text) ?: false
    }

    companion object {
        const val WS_CLOSE_STOP_RECONNECT = 4000

        // Extension function to check if string is JSON
        private fun String.isJson(): Boolean {
            return try {
                toJsonObject()
                true
            } catch (e: Exception) {
                false
            }
        }

        // Extension function to parse JSON
        private fun String.toJsonObject(): JSONObject {
            return JSONObject(this)
        }
    }
}
