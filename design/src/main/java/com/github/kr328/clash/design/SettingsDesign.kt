package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.databinding.DesignSettingsBinding
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SettingsDesign(context: Context) : Design<SettingsDesign.Request>(context) {
    enum class Request {
        StartApp, StartNetwork, StartOverride, StartMetaFeature,
        ReloadAgentVersion,
        ToggleInput,
        ToggleRecording,
    }

    private val binding = DesignSettingsBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    init {
        binding.self = this
        binding.agentVersion = "Loading..."
        binding.activityBarLayout.applyFrom(context)
        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)
    }

    suspend fun setIsRecordingEnabled(enabled: Boolean) {
        withContext(Dispatchers.Main) {
            binding.isRecordingEnabled = enabled
        }
    }
    suspend fun setIsInputEnabled(isInputEnabled: Boolean) {
        withContext(Dispatchers.Main) {
            binding.isInputEnabled = isInputEnabled
        }
    }


    suspend fun setAgentVersion(v: String) {
        withContext(Dispatchers.Main) {
            binding.agentVersion = v
        }
    }


    suspend fun setIp(ip: String) {
        withContext(Dispatchers.Main) {
            binding.ip = ip
        }
    }


    fun request(request: Request) {
        requests.trySend(request)
    }
}