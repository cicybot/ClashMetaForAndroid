package com.github.kr328.clash

import android.app.Activity
import android.content.Intent
import android.icu.text.SimpleDateFormat
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.edit
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.design.R
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.HttpClient
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*

class ExternalControlActivity : Activity(), CoroutineScope by MainScope() {


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
        getSharedPreferences("CLASH_CONFIG", MODE_PRIVATE).edit {
            putString("currentUUID", uuid.toString())
            apply()
        }
        return uuid
    }

    private fun startClashDelay(){
        launch(Dispatchers.Main) {
            delay(100L)
            if(!Remote.broadcasts.clashRunning){
                while (true){
                    delay(200L)
                    if(!Remote.broadcasts.clashRunning){
                        startClash()
                        break
                    }
                }
            }else{
                startClash()
            }
            finish()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when(intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return finish()
                val action = uri.getQueryParameter("action") ?: ""

                if(action.isNotEmpty()){
                    when (action){
                        "start"->{
                            if(!Remote.broadcasts.clashRunning) {
                                startClash()
                            }
                        }
                        "stop"->{
                            if(Remote.broadcasts.clashRunning) {
                                stopClash()
                            }
                        }

                        "clashRunning"->{
                            val port = uri.getQueryParameter("port") ?: "4477"
                            val url = "http://127.0.0.1:${port}/jsonrpc"
                            val clashRunning = Remote.broadcasts.clashRunning
                            val jsonBody = """{"jsonrpc": "2.0","method": "setIsClashRunning","params": [$clashRunning]}""".trimIndent()

                            HttpClient().post(
                                url,jsonBody,
                                mapOf("Content-Type" to "application/json")
                            )
                        }
                        "updateClash"->{
                            val port = uri.getQueryParameter("port") ?: "4477"
                            val url = "http://127.0.0.1:${port}/clashConfig.yaml"

                            val name = "代理 ${SimpleDateFormat("dd HH:mm", Locale.getDefault()).format(Date())}"
                            if(Remote.broadcasts.clashRunning) {
                                stopClashService()
                            }
                            launch {
                                val prefs = getSharedPreferences("CLASH_CONFIG", MODE_PRIVATE)
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
                        }
                    }

                }else{
                    val url = uri.getQueryParameter("url") ?: return finish()

                    launch {
                        val uuid = withProfile {
                            val type = when (uri.getQueryParameter("type")?.lowercase(Locale.getDefault())) {
                                "url" -> Profile.Type.Url
                                "file" -> Profile.Type.File
                                else -> Profile.Type.Url
                            }
                            val name = uri.getQueryParameter("name") ?: getString(R.string.new_profile)

                            create(type, name).also {
                                patch(it, name, url, 0)
                            }
                        }
                        startActivity(PropertiesActivity::class.intent.setUUID(uuid))
                        finish()
                    }
                }


            }

            Intents.ACTION_TOGGLE_CLASH -> if(Remote.broadcasts.clashRunning) {
                stopClash()
            }
            else {
                startClash()
            }

            Intents.ACTION_START_CLASH -> if(!Remote.broadcasts.clashRunning) {
                startClash()
            }
            else {
                Toast.makeText(this, R.string.external_control_started, Toast.LENGTH_LONG).show()
            }

            Intents.ACTION_STOP_CLASH -> if(Remote.broadcasts.clashRunning) {
                stopClash()
            }
            else {
                Toast.makeText(this, R.string.external_control_stopped, Toast.LENGTH_LONG).show()
            }
        }
        return finish()
    }

    private fun startClash() {
//        if (currentProfile == null) {
//            Toast.makeText(this, R.string.no_profile_selected, Toast.LENGTH_LONG).show()
//            return
//        }
        val vpnRequest = startClashService()
        if (vpnRequest != null) {
            Toast.makeText(this, R.string.unable_to_start_vpn, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, R.string.external_control_started, Toast.LENGTH_LONG).show()
    }

    private fun stopClash() {
        stopClashService()
        Toast.makeText(this, R.string.external_control_stopped, Toast.LENGTH_LONG).show()
    }
}