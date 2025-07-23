package com.cicy.agent.adr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.service.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class LocalServer : Service() {

    private var httpServer: LocalHttpServer? = null
    private var isRunning = false

    private var wsClient: LocalWsServer? = null

    private lateinit var messageHandler: MessageHandler


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(getClientNotifyID(LOCAL_SERVER_NOTIFY_ID), notification)
        startServer()
        return START_STICKY
    }

    private fun createNotification(): Notification {
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        } else {
            ""
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(DEFAULT_NOTIFY_TITLE)
            .setContentText(LOCAL_SERVER_NOTIFY_TEXT)
            .setSmallIcon(com.github.kr328.clash.design.R.mipmap.ic_stat_logo) // Make sure you have this icon
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    R.id.nf_clash_status,
                    Intent().setComponent(Components.MAIN_ACTIVITY)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
                )
            )
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(): String {
        val channelId = "local_server_channel"
        val channelName = "Local Server Background Service"
        val channel = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(channel)
        return channelId
    }


    override fun onCreate() {
        super.onCreate()
        messageHandler = MessageHandler(this)
    }

    override fun onDestroy() {
        stopServer()
        messageHandler.cleanup()
        super.onDestroy()
    }

    fun startServer() {
        if (isRunning) return
        try {
            initWsClient()
            httpServer = LocalHttpServer(this, messageHandler, PORT)
            httpServer?.start()
            messageHandler.sendAsyncMessageToActivity("onStartRecording")
            isRunning = true

            CoroutineScope(Dispatchers.IO).launch {
                while (true){
                    delay(200L)
                    if(httpServer?.wasStarted() == true){
                        messageHandler.process("updateClash", JSONArray())
                        break
                    }
                }
            }

        } catch (e: IOException) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun stopServer() {
        isRunning = false
        httpServer?.stop()

        wsClient?.disconnect()
        wsClient = null
    }

    companion object {
        const val PORT = 4477
        private fun String.isJson(): Boolean {
            return try {
                toJsonObject()
                true
            } catch (e: Exception) {
                false
            }
        }

        private fun String.toJsonObject(): JSONObject {
            return JSONObject(this)
        }
    }

    private fun initWsClient() {
        val clientId = getClientId()
        wsClient = LocalWsServer("$clientId-APP", object : WsOptions {
            override fun onOpen(webSocket: WebSocket) {
                Log.d("WebSocket", "Connected!")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("WebSocket", "Received: $text")
                if (text.isJson()) {
                    val json = text.toJsonObject()
                    val fromClientId = json.optString("from")
                    val action = json.getString("action")
                    val id = json.optString("id")
                    if (id.isNotEmpty() && fromClientId.isNotEmpty()) {
                        val payload =
                            json.optJSONObject("payload") ?: JSONObject() // Handle null payload
                        val method = payload.optString("method", "") // Safe method extraction
                        val params =
                            payload.optJSONArray("params") ?: JSONArray() // Handle null params
                        val result = when (action) {
                            "jsonrpc" -> messageHandler.process(method, params)
                            else -> JSONObject().apply {
                                put("err", "error action")
                            }
                        }

                        val res = result.opt("result")
                        var res1: Any = result
                        if(res != null){
                            res1  = res
                        }
                        val callbackResponse = JSONObject().apply {
                            put("to", fromClientId)
                            put("action", "callback")
                            put("id", id)
                            put("payload", res1) // Empty payload as in your example
                        }
                        webSocket.send(callbackResponse.toString())
                    }
                }
            }

            override fun onClose(webSocket: WebSocket, code: Int) {
                Log.d("WebSocket", "Closed with code: $code")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable) {
                Log.e("WebSocket", "Error", t)
            }
        })
        wsClient!!.connect()
    }
}

