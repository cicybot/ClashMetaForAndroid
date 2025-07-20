package com.cicy.agent.adr

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class PackagesList(private val context: AppCompatActivity) {

    fun getInstalledApps(isAll: Boolean): JSONArray {
        if (!hasQueryAllPackagesPermission()) {
            showPermissionExplanationDialog()
            return JSONArray() // Return empty array if permission not granted
        }

        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val result = JSONArray()

        for (app in apps) {
            try {
                if (!isAll && isSystemApp(app)) {
                    continue
                }
                val appInfo = JSONObject().apply {
                    put("name", pm.getApplicationLabel(app)) // App name
                    put("packageName", app.packageName)      // Package name
                    put("isSystem", isSystemApp(app))        // Is system app
                    // put("icon", getAppIconSafe(pm, app.packageName)) // Optional: uncomment if needed
                }
                result.put(appInfo)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return result
    }

    private fun showPermissionExplanationDialog() {
        AlertDialog.Builder(context).apply {
            setTitle("功能受限")
            setMessage("本应用需要查询已安装应用列表权限以提供完整功能。请前往系统设置授予此权限。")
            setPositiveButton("去设置") { _, _ -> openAppSettings() }
            setNegativeButton("取消", null)
            create()
            show()
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        context.startActivity(intent)
    }

    private fun hasQueryAllPackagesPermission(): Boolean {
        return context.checkSelfPermission(Manifest.permission.QUERY_ALL_PACKAGES) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun isSystemApp(appInfo: ApplicationInfo): Boolean {
        return (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }

    private fun getAppIconSafe(pm: PackageManager, packageName: String): String? {
        return try {
            val icon = pm.getApplicationIcon(packageName)
            val bitmap = (icon as BitmapDrawable).bitmap
            val resized = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
            bitmapToBase64(resized)
        } catch (e: Exception) {
            null
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        ByteArrayOutputStream().use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 70, outputStream)
            return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        }
    }
}