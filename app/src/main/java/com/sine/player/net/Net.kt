package com.sine.player.net

import com.sine.player.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One HTTP client for every API Sine talks to (media servers, WebDAV, DLNA, OpenSubtitles). */
object Net {
    const val USER_AGENT = "Sine/${BuildConfig.VERSION_NAME} (Android TV)"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun execute(request: Request): Response {
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code
            val message = response.body?.string()?.take(300)?.trim().orEmpty()
            response.close()
            throw HttpException(code, message)
        }
        return response
    }

    fun text(request: Request): String = execute(request).use { it.body?.string().orEmpty() }

    fun json(request: Request): JSONObject = JSONObject(text(request).ifBlank { "{}" })

    fun jsonArray(request: Request): JSONArray = JSONArray(text(request).ifBlank { "[]" })
}

class HttpException(val code: Int, val body: String) : IOException(
    when (code) {
        401 -> "Sign-in was refused (401). Check the username and password."
        403 -> "Access denied (403)."
        404 -> "Not found (404)."
        else -> "Server replied $code${if (body.isNotBlank()) ": $body" else ""}"
    },
)
