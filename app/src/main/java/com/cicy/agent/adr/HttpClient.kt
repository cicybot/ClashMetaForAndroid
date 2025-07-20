package com.cicy.agent.adr

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * fun main() {
 *     val httpClient = HttpClient()
 *
 *     // GET request example
 *     val (getStatus, getResponse) = httpClient.get(
 *         "https://jsonplaceholder.typicode.com/posts/1",
 *         mapOf("Accept" to "application/json")
 *     )
 *     println("GET Status: $getStatus")
 *     println("GET Response: $getResponse")
 *
 *     // POST request example
 *     val postData = """
 *         {
 *             "title": "foo",
 *             "body": "bar",
 *             "userId": 1
 *         }
 *     """.trimIndent()
 *
 *     val (postStatus, postResponse) = httpClient.post(
 *         "https://jsonplaceholder.typicode.com/posts",
 *         postData,
 *         mapOf("Accept" to "application/json")
 *     )
 *     println("POST Status: $postStatus")
 *     println("POST Response: $postResponse")
 * }
 */
class HttpClient {

    /**
     * Perform a GET request
     * @param urlString The URL to request
     * @param headers Optional map of headers to include
     * @return Pair of status code and response body
     */
    fun get(urlString: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"

        // Set headers
        headers.forEach { (key, value) ->
            connection.setRequestProperty(key, value)
        }

        return handleResponse(connection)
    }

    /**
     * Perform a POST request
     * @param urlString The URL to request
     * @param body The request body to send
     * @param headers Optional map of headers to include
     * @return Pair of status code and response body
     */
    fun post(
        urlString: String,
        body: String,
        headers: Map<String, String> = emptyMap()
    ): Pair<Int, String> {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true

        // Set headers
        val combinedHeaders = headers.toMutableMap()
        if (!combinedHeaders.containsKey("Content-Type")) {
            combinedHeaders["Content-Type"] = "application/json"
        }
        combinedHeaders.forEach { (key, value) ->
            connection.setRequestProperty(key, value)
        }

        // Write body
        connection.outputStream.use { os ->
            val input: ByteArray = body.toByteArray(Charsets.UTF_8)
            os.write(input, 0, input.size)
        }

        return handleResponse(connection)
    }

    /**
     * Handle the response from the server
     * @param connection The HttpURLConnection
     * @return Pair of status code and response body
     */
    private fun handleResponse(connection: HttpURLConnection): Pair<Int, String> {
        val statusCode = connection.responseCode

        val reader = if (statusCode in 200..299) {
            BufferedReader(InputStreamReader(connection.inputStream))
        } else {
            BufferedReader(InputStreamReader(connection.errorStream))
        }

        val response = reader.use { it.readText() }
        connection.disconnect()

        return Pair(statusCode, response)
    }
}