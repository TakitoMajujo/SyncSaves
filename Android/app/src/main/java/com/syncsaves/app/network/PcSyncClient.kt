package com.syncsaves.app.network

import android.content.Context
import android.net.Uri
import com.syncsaves.app.scanner.SaveFile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
        context: Context,
        host: String,
        deviceId: String,
        save: SaveFile,
        port: Int = 8765,
    ): Result<Unit> = runCatching {
        val body = buildBody(context, save)
        val request = Request.Builder()
            .url("http://${host.trim()}:$port/upload")
            .addHeader("X-Device-Id", deviceId.trim().uppercase())
            .addHeader("X-Filename", save.name)
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = response.body?.string().orEmpty()
                error("HTTP ${response.code}: $detail")
            }
        }
    }

    private fun buildBody(context: Context, save: SaveFile): RequestBody {
        val contentUri = save.contentUri
        if (!contentUri.isNullOrBlank()) {
            val uri = Uri.parse(contentUri)
            val stream = context.contentResolver.openInputStream(uri)
                ?: error("No se pudo abrir: ${save.name}")
            val bytes = stream.use { it.readBytes() }
            return bytes.toRequestBody("application/octet-stream".toMediaType())
        }

        val file = File(save.absolutePath)
        require(file.exists()) { "No existe: ${save.absolutePath}" }
        return file.asRequestBody("application/octet-stream".toMediaType())
    }
}
