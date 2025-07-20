package com.cicy.agent.app

import android.os.Build
import android.webkit.JavascriptInterface
import androidx.annotation.RequiresApi
import com.cicy.agent.adr.MessageActivityHandler
import com.github.kr328.clash.MainActivity
import org.json.JSONArray
import org.json.JSONObject

class WebAppInterface(private val context: MainActivity) {
    @RequiresApi(Build.VERSION_CODES.O)
    @JavascriptInterface
    fun jsonRpc(data: String): String {
        return try {
            val jsonRequest = JSONObject(data)
            val method = jsonRequest.getString("method")
            val params = jsonRequest.optJSONArray("params") ?: JSONArray()
            val result = MessageActivityHandler(context).process(JSONObject().apply {
                put("method", method)
                put("params", params)
            }.toString(), null)

            val responseJson = JSONObject()
            if (result.optString("err").isEmpty()) {
                responseJson.put("result", result)
            } else {
                responseJson.put("err", result["err"])
            }
            responseJson.put("jsonrpc", "2.0")
            responseJson.put("id", jsonRequest.opt("id"))
            responseJson.toString()
        } catch (e: Exception) {
            // 返回错误响应
            JSONObject().apply {
                put("jsonrpc", "2.0")
                put("err", JSONObject().apply {
                    put("code", -32603)
                    put("message", e.message ?: "Internal error")
                })
                put("id", null)
            }.toString()
        }
    }
}