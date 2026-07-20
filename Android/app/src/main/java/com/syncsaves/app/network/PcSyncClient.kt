package com.syncsaves.app.network

import android.content.Context
import android.net.Uri
import com.syncsaves.app.scanner.SaveFile
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class RemoteSaveInfo(
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

data class DownloadedSave(
    val bytes: ByteArray,
    val lastModified: Long,
)

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
            .addHeader("X-Last-Modified", save.lastModified.toString())
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = response.body?.string().orEmpty()
                error("HTTP ${response.code}: $detail")
            }
        }
    }

    fun ping(
        host: String,
        port: Int = 8765,
    ): Result<JSONObject> = runCatching {
        val request = Request.Builder()
            .url("http://${host.trim()}:$port/ping")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: $body")
            }
            JSONObject(body)
        }
    }

    fun listRemoteSaves(
        host: String,
        deviceId: String,
        port: Int = 8765,
    ): Result<List<RemoteSaveInfo>> = runCatching {
        val request = Request.Builder()
            .url("http://${host.trim()}:$port/list")
            .addHeader("X-Device-Id", deviceId.trim().uppercase())
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (response.code == 404 && body.contains("not_found")) {
                    error(
                        "La PC tiene SyncSaves antiguo (sin /list). " +
                            "Cierra SyncSaves en la PC y vuelve a abrirlo desde la carpeta PC_Win actualizada.",
                    )
                }
                error("HTTP ${response.code}: $body")
            }
            val json = JSONObject(body)
            if (!json.optBoolean("ok", false)) {
                error(json.optString("error", "list_failed"))
            }
            val files = json.getJSONArray("files")
            buildList {
                for (i in 0 until files.length()) {
                    val item = files.getJSONObject(i)
                    add(
                        RemoteSaveInfo(
                            name = item.getString("name"),
                            sizeBytes = item.getLong("size"),
                            lastModified = item.getLong("mtime_ms"),
                        ),
                    )
                }
            }
        }
    }

    fun downloadSave(
        host: String,
        deviceId: String,
        filename: String,
        port: Int = 8765,
    ): Result<DownloadedSave> = runCatching {
        val url = "http://${host.trim()}:$port/download".toHttpUrl().newBuilder()
            .addQueryParameter("name", filename)
            .build()
        val request = Request.Builder()
            .url(url)
            .addHeader("X-Device-Id", deviceId.trim().uppercase())
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = response.body?.string().orEmpty()
                error("HTTP ${response.code}: $detail")
            }
            val bytes = response.body?.bytes() ?: error("Respuesta vacía")
            val mtime = response.header("X-Last-Modified")?.toLongOrNull() ?: 0L
            DownloadedSave(bytes = bytes, lastModified = mtime)
        }
    }

    fun writeLocalSave(
        context: Context,
        save: SaveFile,
        bytes: ByteArray,
        lastModified: Long,
    ) {
        val contentUri = save.contentUri
        if (!contentUri.isNullOrBlank()) {
            val uri = Uri.parse(contentUri)
            val stream = context.contentResolver.openOutputStream(uri, "wt")
                ?: error("No se pudo escribir: ${save.name}")
            stream.use { it.write(bytes) }
            return
        }

        val file = File(save.absolutePath)
        require(file.exists() || file.parentFile?.exists() == true) {
            "No existe: ${save.absolutePath}"
        }
        file.writeBytes(bytes)
        if (lastModified > 0L) {
            file.setLastModified(lastModified)
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
