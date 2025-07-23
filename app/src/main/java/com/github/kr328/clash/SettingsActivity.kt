package com.github.kr328.clash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.cicy.agent.adr.AGENT_JSONRPC_PORT
import com.cicy.agent.adr.HttpClient
import com.cicy.agent.adr.InputService
import com.cicy.agent.adr.MessageHandler
import com.cicy.agent.adr.MessageHandler.Companion.ACTION_REQUEST
import com.cicy.agent.adr.MessageHandler.Companion.EXTRA_MESSAGE_ASYNC
import com.cicy.agent.adr.NetworkUtils
import com.cicy.agent.adr.RecordingService
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.SettingsDesign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

import kotlinx.coroutines.withContext
import org.json.JSONObject

class SettingsActivity : BaseActivity<SettingsDesign>() {
    lateinit var localBroadcastManager: LocalBroadcastManager
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        localBroadcastManager.registerReceiver(
            serviceRequestReceiver,
            IntentFilter(MessageHandler.ACTION_REQUEST)
        )
    }

    override fun onDestroy() {
        coroutineScope.cancel()

        localBroadcastManager.unregisterReceiver(serviceRequestReceiver)
        super.onDestroy()
    }
    private val serviceRequestReceiver = object : BroadcastReceiver() {
        @RequiresApi(Build.VERSION_CODES.O)
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != MessageHandler.ACTION_REQUEST) return
            val messageAsync = intent.getStringExtra(MessageHandler.EXTRA_MESSAGE_ASYNC)
            if(messageAsync != null){
                launch {
                    design?.setIsRecordingEnabled(RecordingService.isReady)
                }
            }
        }
    }

    private fun sendMessage(msg:String){
        Intent(ACTION_REQUEST).apply {
            putExtra(EXTRA_MESSAGE_ASYNC, msg)
            LocalBroadcastManager.getInstance(this@SettingsActivity).sendBroadcast(this)
        }
    }
    private fun showConfirmationDialog(
        context: Context,
        title: String,
        confirmAction: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setPositiveButton("OK") { dialog, _ ->
                confirmAction()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }

    override fun onResume() {
        super.onResume()
        launch {
            design?.setIsInputEnabled(InputService.isReady)
        }

        coroutineScope.launch {
            design?.setIsInputEnabled(InputService.isReady)
            fetchDeviceInfo()
        }
    }
    private suspend fun fetchDeviceInfo() {
        try {
            val (status, response) = withContext(Dispatchers.IO) {
                HttpClient().get(
                    "http://127.0.0.1:${AGENT_JSONRPC_PORT}/deviceInfo",
                    mapOf("Accept" to "application/json")
                )
            }

            design?.apply {
                if (status != 200) {
                    setAgentVersion("Error:${status} ")
                } else {
                    val jsonResponse = JSONObject(response)
                    setAgentVersion(jsonResponse.getJSONObject("result").getString("agentVersion"))
                }
            }
        } catch (e: Exception) {
            design?.setAgentVersion("Error: ${e.message}")
        }
    }

    override suspend fun main() {
        val design = SettingsDesign(this)

        setContentDesign(design)

        val ip = withContext(Dispatchers.IO) {
            NetworkUtils.getCurrentIp(this@SettingsActivity)
        }


        launch {
            design.setIp(ip.toString())
            design.setIsInputEnabled(InputService.isReady)
            design.setIsRecordingEnabled(RecordingService.isReady)

        }
        while (isActive) {
            select<Unit> {
                events.onReceive {

                }
                design.requests.onReceive {
                    when (it) {
                        SettingsDesign.Request.StartApp ->
                            startActivity(AppSettingsActivity::class.intent)
                        SettingsDesign.Request.StartNetwork ->
                            startActivity(NetworkSettingsActivity::class.intent)
                        SettingsDesign.Request.StartOverride ->
                            startActivity(OverrideSettingsActivity::class.intent)
                        SettingsDesign.Request.StartMetaFeature ->
                            startActivity(MetaFeatureSettingsActivity::class.intent)
                        SettingsDesign.Request.ReloadAgentVersion ->{
                            design.setAgentVersion("Loading...")
                            fetchDeviceInfo()
                        }
                        SettingsDesign.Request.ToggleRecording ->
                        {
                            if(RecordingService.isReady){
                                showConfirmationDialog(
                                    context = this@SettingsActivity,  // Explicitly reference the activity
                                    title = "确定要停止录制么？",
                                    confirmAction = {
                                        sendMessage("onStopRecording")
                                    }
                                )
                            }else{
                                sendMessage("onStartRecording")
                            }
                        }
                        SettingsDesign.Request.ToggleInput ->
                        {
                            if(InputService.isReady){
                                showConfirmationDialog(
                                    context = this@SettingsActivity,  // Explicitly reference the activity
                                    title = "确定要停止辅助么？",
                                    confirmAction = {
                                        sendMessage("onStopInput")
                                    }
                                )
                            }else{
                                sendMessage("onStartInput")
                            }
                        }
                    }
                }
            }
        }
    }
}