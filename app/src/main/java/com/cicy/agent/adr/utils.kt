package com.cicy.agent.adr

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
import android.util.Log
import com.hjq.permissions.XXPermissions
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileReader

const val RES_FAILED = -100

// Activity requestCode
const val REQ_INVOKE_PERMISSION_ACTIVITY_MEDIA_PROJECTION = 101
const val REQ_REQUEST_MEDIA_PROJECTION = 201

// intent action, extra
const val ACT_REQUEST_MEDIA_PROJECTION = "REQUEST_MEDIA_PROJECTION"
const val ACT_INIT_MEDIA_PROJECTION_AND_SERVICE = "INIT_MEDIA_PROJECTION_AND_SERVICE"
const val EXT_MEDIA_PROJECTION_RES_INTENT = "MEDIA_PROJECTION_RES_INTENT"

const val DEFAULT_NOTIFY_TITLE = "CiCy"
const val RECORDING_NOTIFY_TEXT = "Recording is running"
const val LOCAL_SERVER_NOTIFY_TEXT = "Server is running"

const val RECORDING_NOTIFY_ID = 23
const val LOCAL_SERVER_NOTIFY_ID = 2
const val NOTIFY_ID_OFFSET = 600


fun getClientNotifyID(clientID: Int): Int {
    return clientID + NOTIFY_ID_OFFSET
}

fun getClientId(): String {
    val brand = Build.BRAND.replace(" ", "")
    val model = Build.MODEL.replace(" ", "")
    return "ADR-${brand}-${model}"
}

fun readServerUrlFromFile(): String? {
    return try {
        val file = File("/data/local/tmp/config_server.txt")
        if (!file.exists()) {
            return null
        }

        BufferedReader(FileReader(file)).use { reader ->
            reader.readLine()?.trim()?.takeIf { it.isNotEmpty() }
        }
    } catch (e: Exception) {
        null
    }
}

fun bitmapToByteArray(
    bitmap: Bitmap,
    format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
    quality: Int = 100
): ByteArray {
    val outputStream = ByteArrayOutputStream()
    bitmap.compress(format, quality, outputStream)
    return outputStream.toByteArray()
}


fun startAction(context: Context, action: String) {
    try {
        context.startActivity(Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (ACTION_ACCESSIBILITY_SETTINGS != action) {
                data = Uri.parse("package:" + context.packageName)
            }
        })
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun getAbi(): String? {
    return Build.SUPPORTED_ABIS[0]
}

fun isX86_64(): Boolean {
    return Build.SUPPORTED_ABIS[0].equals("x86_64")
}

fun requestPermission(context: Context, type: String) {
    XXPermissions.with(context)
        .permission(type)
        .request { _, all ->
            if (all) {
                Log.d("requestPermission", all.toString())
            }
        }
}


fun stringToHex(input: String): String {
    return input.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
}

fun padKeyTo8Bytes(key: String): String {
    return if (key.length >= 8) {
        key.substring(0, 8)  // Truncate if the key is longer than 8 characters
    } else {
        key.padEnd(8, '0')  // Pad with '0' if the key is shorter than 8 characters
    }
}
