package com.github.kr328.clash.remote

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat.startActivity
import com.github.kr328.clash.common.constants.Authorities
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.StatusProvider

class StatusClient(private val context: Context) {
    private val uri: Uri
        get() {
            return Uri.Builder()
                .scheme("content")
                .authority(Authorities.STATUS_PROVIDER)
                .build()
        }

    fun currentProfile(): String? {
        Log.w("=======>>> currentProfile $uri")

        return try {
            val result = context.contentResolver.call(
                uri,
                StatusProvider.METHOD_CURRENT_PROFILE,
                null,
                null
            )
            Log.w(" current profile: $result")

            result?.getString("name")
        } catch (e: Exception) {
            Log.w("Query current profile: $e", e)
            null
        }
    }
}