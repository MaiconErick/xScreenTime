package com.maiconerick.xscreentime.data

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Dropbox v2 upload with the same app credentials as xPlayTimeTracker (refresh token -> short-lived
 * access token -> content upload). No SDK.
 */
object Dropbox {
    // Supply your own Dropbox app credentials. Real values must never be committed:
    // create an app at https://www.dropbox.com/developers/apps and generate a refresh token.
    private const val APP_KEY = "YOUR_DROPBOX_APP_KEY"
    private const val APP_SECRET = "YOUR_DROPBOX_APP_SECRET"
    private const val REFRESH_TOKEN = "YOUR_DROPBOX_REFRESH_TOKEN"

    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedUntil: Long = 0

    private fun accessToken(): String {
        val now = System.currentTimeMillis()
        cachedToken?.let { if (now < cachedUntil) return it }
        val body = "grant_type=refresh_token" +
            "&refresh_token=" + URLEncoder.encode(REFRESH_TOKEN, "UTF-8") +
            "&client_id=" + URLEncoder.encode(APP_KEY, "UTF-8") +
            "&client_secret=" + URLEncoder.encode(APP_SECRET, "UTF-8")
        val c = URL("https://api.dropbox.com/oauth2/token").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 12_000; c.readTimeout = 15_000
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toByteArray()) }
            check(c.responseCode in 200..299) { "token HTTP ${c.responseCode}" }
            val json = JSONObject(c.inputStream.bufferedReader().readText())
            val tok = json.getString("access_token")
            cachedToken = tok
            cachedUntil = now + (json.optLong("expires_in", 14_400) - 120) * 1000
            return tok
        } finally {
            c.disconnect()
        }
    }

    sealed interface Download {
        /** The file exists; it was saved to the destination and has this [rev]. */
        data class Found(val rev: String) : Download
        data object NotFound : Download
        data class Failed(val error: String) : Download
    }

    sealed interface Upload {
        data object Ok : Upload
        /** Someone (xPlayTime) changed the file after we downloaded it. */
        data object Conflict : Upload
        data class Failed(val error: String) : Upload
    }

    fun download(remotePath: String, dest: File): Download = runCatching {
        val token = accessToken()
        val c = URL("https://content.dropboxapi.com/2/files/download").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Dropbox-API-Arg", JSONObject().put("path", remotePath).toString())
            when (val code = c.responseCode) {
                in 200..299 -> {
                    c.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
                    Download.Found(JSONObject(c.getHeaderField("Dropbox-API-Result")).getString("rev"))
                }
                409 -> {
                    val body = c.errorStream?.bufferedReader()?.readText().orEmpty()
                    if ("not_found" in body) Download.NotFound else Download.Failed("Dropbox: download HTTP 409")
                }
                else -> Download.Failed("Dropbox: download HTTP $code")
            }
        } finally {
            c.disconnect()
        }
    }.getOrElse { Download.Failed(describe(it)) }

    /**
     * Uploads only if the remote file is still at [rev] (or still absent when [rev] is null), so a
     * change made in between is never overwritten.
     */
    fun upload(file: File, remotePath: String, rev: String?): Upload = runCatching {
        val token = accessToken()
        val mode: Any = if (rev == null) "add" else JSONObject().put(".tag", "update").put("update", rev)
        val arg = JSONObject().put("path", remotePath).put("mode", mode).put("autorename", false).put("mute", true).toString()
        val c = URL("https://content.dropboxapi.com/2/files/upload").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Dropbox-API-Arg", arg)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            file.inputStream().use { input -> c.outputStream.use { input.copyTo(it) } }
            when (val code = c.responseCode) {
                in 200..299 -> Upload.Ok
                409 -> Upload.Conflict
                else -> Upload.Failed("Dropbox: upload HTTP $code")
            }
        } finally {
            c.disconnect()
        }
    }.getOrElse { Upload.Failed(describe(it)) }

    private fun describe(e: Throwable): String = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException -> "No internet connection"
        is java.net.SocketTimeoutException -> "Dropbox didn't answer in time"
        else -> e.message?.let { "Dropbox: $it" } ?: "Dropbox: ${e.javaClass.simpleName}"
    }
}
