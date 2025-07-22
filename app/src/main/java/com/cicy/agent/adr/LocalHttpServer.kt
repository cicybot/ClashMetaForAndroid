package com.cicy.agent.adr

import android.app.Service
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.Yaml
import java.io.InputStream
import java.net.URLEncoder

class LocalHttpServer(
    private val service: Service,
    private var messageHandler: MessageHandler,
    port: Int
) : NanoHTTPD("0.0.0.0", port) {
    override fun serve(session: IHTTPSession?): Response {
        if (session == null) return newFixedLengthResponse("Session is null")
        val uri = session.uri ?: "/index.html"
        val response = when (val path = uri.removePrefix("/")) {
            "jsonrpc" -> handleJSONRpcRequest(session)
            "clashConfig.yaml" -> handleClashConfigRequest()
            "agentAppInfo" -> {
                handleApiRequest("agentAppInfo")
            }
            "file" -> {
                handleFileFromAgentRequest(session)
            }
            "api" -> {
                val allParams = session.parameters.mapValues {
                    it.value.firstOrNull() ?: ""
                }
                val method = allParams["method"]
                handleApiRequest(method.toString())
            }
            "openapi.json" -> handleOpenapiJsonRequest()
            else -> handleFileRequest(path)
        }
        return response
    }

    private fun handleFileFromAgentRequest(session: IHTTPSession): Response {
        val allParams = session.parameters.mapValues {
            it.value.firstOrNull() ?: ""
        }
        val path = allParams["path"]
        if(path?.isEmpty() == true){
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "500 ERROR")
        }
        val filename = path!!.substringAfterLast('/').takeIf { it.isNotBlank() } ?: "file"

        val agentUrl = "http://127.0.0.1:4447/file?path=${URLEncoder.encode(path, "UTF-8")}"
        val httpClient = HttpClient()
        try {
            val (_, getResponse) = httpClient.get(
                agentUrl,
            )
            return newFixedLengthResponse(Response.Status.OK, "application/octet-stream", getResponse).apply {
                addHeader("Content-Disposition", "inline; filename=\"${filename}\"")
                val contentType = when (filename.substringAfterLast('.').lowercase()) {
                    "pdf" -> "application/pdf"
                    "jpg", "jpeg" -> "image/jpeg"
                    "png" -> "image/png"
                    "txt" -> "text/plain"
                    else -> "application/octet-stream"
                }
                addHeader("Content-type",contentType)
            }
        } catch (_: Exception) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "500 ERROR")
        }
    }

    private fun convertYamlToJson(yamlContent: String): String {
        val yaml = Yaml()
        val obj = yaml.load<Any>(yamlContent)
        return Gson().toJson(obj)
    }

    private fun handleClashConfigRequest(): Response {
        val config = messageHandler.process("getClashConfig", JSONArray())

        return newFixedLengthResponse(
            Response.Status.OK,
            "text/plain",
            config.getString("configYaml")
        )
    }

    private fun handleApiRequest(method: String): Response {
        val result = messageHandler.process(method, JSONArray())
        val responseJson = JSONObject()
        if (result.optString("err").isEmpty()) {
            responseJson.put("result", result)
        } else {
            responseJson.put("err", result["err"])
        }
        responseJson.put("jsonrpc", "2.0")
        responseJson.put("id", "1")
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            responseJson.toString()
        )
    }

    private fun handleOpenapiJsonRequest(): Response {
        return try {
            val yamlContent = service.assets.open("openapi.yaml").use { inputStream ->
                inputStream.bufferedReader().use { it.readText() }
            }

            val jsonContent = convertYamlToJson(yamlContent)

            newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                jsonContent
            )
        } catch (_: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
        }
    }

    private fun handleFileRequest(path: String): Response {
        return try {
            val actualPath = path.ifEmpty { "index.html" }
            val fullAssetPath = "public/$actualPath"  // Path to files in assets/public/

            val inputStream: InputStream = service.assets.open(fullAssetPath)
            val bytes = inputStream.readBytes()
            inputStream.close()

            val mimeType = when {
                actualPath.endsWith(".html") -> "text/html"
                actualPath.endsWith(".js") -> "application/javascript"
                actualPath.endsWith(".css") -> "text/css"
                actualPath.endsWith(".png") -> "image/png"
                actualPath.endsWith(".jpg") -> "image/jpeg"
                actualPath.endsWith(".gif") -> "image/gif"
                else -> "application/octet-stream"
            }
            newFixedLengthResponse(
                Response.Status.OK,
                mimeType,
                bytes.inputStream(),
                bytes.size.toLong()
            )
        } catch (_: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
        }
    }

    private fun handleJSONRpcRequest(session: IHTTPSession): Response {
        return try {
            val body = HashMap<String, String>()
            session.parseBody(body)
            val jsonStr = body["postData"] ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                """{"err": "Missing body"}"""
            )

            val requestJson = JSONObject(jsonStr)
            val method = requestJson.optString("method", "")
            val id = requestJson.opt("id") ?: "1"
            val params = requestJson.optJSONArray("params") ?: JSONArray()

            val result = messageHandler.process(method, params)

            val responseJson = JSONObject()

            if (result.optString("err").isEmpty()) {
                val res = result.opt("result")
                var res1: Any = result
                if(res != null){
                    res1  = res
                }
                responseJson.put("result", res1)
            } else {
                responseJson.put("err", result["err"])
            }
            responseJson.put("jsonrpc", "2.0")
            responseJson.put("id", id.toString())

            newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                responseJson.toString()
            )
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"err": "${e.message}"}"""
            )
        }
    }
}