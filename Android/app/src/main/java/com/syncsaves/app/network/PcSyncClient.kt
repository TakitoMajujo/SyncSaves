package com.syncsaves.app.network

import com.syncsaves.app.scanner.SaveFile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class PcSyncClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    fun uploadSave(
        host: String,
        deviceId: String,
        save: SaveFile,
        port: Int = 8765,
    ): Result<Unit> = runCatching {
        val file = File(save.absolutePath)
        require(file.exists()) { "No existe: ${save.absolutePath}" }

        val body = file.asRequestBody("application/octet-stream".toMediaType())
        val request = Request.Builder()
            .url("http://${host.trim()}:$port/upload")
            .addHeader("X-Device-Id", deviceId.trim().uppercase())
            .addHeader("X-Filename", file.name)
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = response.body?.string().orEmpty()
                error("HTTP ${response.code}: $detail")
            }
        }
    }
}
