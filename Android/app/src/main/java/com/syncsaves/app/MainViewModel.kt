package com.syncsaves.app

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.syncsaves.app.data.AppPreferences
import com.syncsaves.app.network.PcSyncClient
import com.syncsaves.app.scanner.MyBoyScanner
import com.syncsaves.app.scanner.SaveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class UiState(
    val deviceId: String = "",
    val pcHost: String = "",
    val customScanRoot: String = "",
    val saves: List<SaveFile> = emptyList(),
    val isScanning: Boolean = false,
    val isUploading: Boolean = false,
    val isDownloading: Boolean = false,
    val statusMessage: String = "Listo para barrer saves de My Boy!",
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = AppPreferences(application)
    private val syncClient = PcSyncClient()

    private val _state = MutableStateFlow(
        UiState(
            deviceId = prefs.deviceId,
            pcHost = prefs.pcHost,
            customScanRoot = prefs.customScanRoot.ifBlank {
                prefs.customTreeUri.takeIf { it.isNotBlank() }?.let {
                    MyBoyScanner.displayLabelForTreeUri(Uri.parse(it))
                }.orEmpty()
            },
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun onDeviceIdChange(value: String) {
        val normalized = value.trim().uppercase().take(8)
        prefs.deviceId = normalized
        _state.update { it.copy(deviceId = normalized) }
    }

    fun onPcHostChange(value: String) {
        prefs.pcHost = value
        _state.update { it.copy(pcHost = value) }
    }

    fun setCustomRootFromTreeUri(uri: Uri) {
        val label = MyBoyScanner.displayLabelForTreeUri(uri)
        val path = treeUriToPath(uri).orEmpty()
        prefs.customTreeUri = uri.toString()
        prefs.customScanRoot = path.ifBlank { label }
        _state.update {
            it.copy(
                customScanRoot = path.ifBlank { label },
                statusMessage = "Carpeta del emulador lista. Ahora pulsa Barrer saves My Boy!",
            )
        }
    }

    fun clearCustomRoot() {
        prefs.customScanRoot = ""
        prefs.customTreeUri = ""
        _state.update {
            it.copy(
                customScanRoot = "",
                statusMessage = "Carpeta personalizada limpiada. Se usarán rutas MyBoy por defecto.",
            )
        }
    }

    fun scanMyBoySaves() {
        viewModelScope.launch {
            _state.update { it.copy(isScanning = true, statusMessage = "Barriendo carpeta…") }
            val treeUri = prefs.customTreeUri.ifBlank { null }
            val path = prefs.customScanRoot.ifBlank { null }
            val found = withContext(Dispatchers.IO) {
                MyBoyScanner.scan(
                    context = getApplication(),
                    treeUriString = treeUri,
                    fallbackPath = path,
                )
            }
            _state.update {
                it.copy(
                    isScanning = false,
                    saves = found,
                    statusMessage = if (found.isEmpty()) {
                        "0 archivos. ¿Elegiste la carpeta que contiene los .st0/.sav? " +
                            "También concede acceso a todos los archivos si el sistema lo pide."
                    } else {
                        "Encontrados ${found.size} archivo(s) de guardado My Boy!"
                    },
                )
            }
        }
    }

    fun uploadFoundSaves() {
        val current = _state.value
        if (!validateConnection(current)) return
        if (current.saves.isEmpty()) {
            _state.update { it.copy(statusMessage = "No hay archivos para enviar. Barre primero.") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isUploading = true, statusMessage = "Enviando a la PC…") }
            val app = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    var ok = 0
                    for (save in current.saves) {
                        syncClient.uploadSave(app, current.pcHost, current.deviceId, save).getOrThrow()
                        ok += 1
                    }
                    ok
                }
            }
            result.fold(
                onSuccess = { count ->
                    _state.update {
                        it.copy(
                            isUploading = false,
                            statusMessage = "Enviados $count archivo(s) a saves_folder de la PC.",
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            isUploading = false,
                            statusMessage = "Error al enviar: ${error.message}",
                        )
                    }
                },
            )
        }
    }

    fun pullNewerFromPc() {
        val current = _state.value
        if (!validateConnection(current)) return

        viewModelScope.launch {
            _state.update {
                it.copy(isDownloading = true, statusMessage = "Preparando comparación con la PC…")
            }
            val app = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    var locals = current.saves
                    if (locals.isEmpty()) {
                        val treeUri = prefs.customTreeUri.ifBlank { null }
                        val path = prefs.customScanRoot.ifBlank { null }
                        locals = MyBoyScanner.scan(
                            context = app,
                            treeUriString = treeUri,
                            fallbackPath = path,
                        )
                    }
                    if (locals.isEmpty()) {
                        error("No hay archivos en el móvil. Elige la carpeta del emulador y barre primero.")
                    }

                    // Comprueba que la PC tenga API v2 (/list + /download)
                    val ping = syncClient.ping(current.pcHost).getOrThrow()
                    if (ping.optInt("api_version", 1) < 2) {
                        error(
                            "La PC tiene SyncSaves antiguo. Cierra la app en la PC y " +
                                "vuelve a abrirla desde PC_Win (necesita /list y /download).",
                        )
                    }

                    val remote = syncClient
                        .listRemoteSaves(current.pcHost, current.deviceId)
                        .getOrThrow()
                        .associateBy { it.name.lowercase() }

                    var updated = 0
                    var checked = 0
                    val updatedNames = mutableListOf<String>()
                    val refreshed = locals.map { local ->
                        val pc = remote[local.name.lowercase()] ?: return@map local
                        checked += 1
                        // PC más reciente → reemplazar en el móvil
                        if (pc.lastModified <= local.lastModified) return@map local

                        val downloaded = syncClient
                            .downloadSave(current.pcHost, current.deviceId, pc.name)
                            .getOrThrow()
                        val mtime = downloaded.lastModified.takeIf { it > 0 } ?: pc.lastModified
                        syncClient.writeLocalSave(
                            context = app,
                            save = local,
                            bytes = downloaded.bytes,
                            lastModified = mtime,
                        )
                        updated += 1
                        updatedNames += local.name
                        local.copy(
                            sizeBytes = downloaded.bytes.size.toLong(),
                            lastModified = mtime,
                        )
                    }
                    PullResult(updated, checked, refreshed, updatedNames)
                }
            }
            result.fold(
                onSuccess = { pull ->
                    _state.update {
                        it.copy(
                            isDownloading = false,
                            saves = pull.refreshed,
                            statusMessage = when {
                                pull.checked == 0 ->
                                    "Ningún archivo del móvil coincide por nombre con los de la PC."
                                pull.updated == 0 ->
                                    "Todo al día. ${pull.checked} archivo(s) en ambos lados; " +
                                        "ninguno de la PC es más nuevo."
                                else ->
                                    "Reemplazados en el móvil ${pull.updated} archivo(s) " +
                                        "con la versión más nueva de la PC: " +
                                        pull.updatedNames.joinToString(", ")
                            },
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            isDownloading = false,
                            statusMessage = "Error al verificar/actualizar: ${error.message}",
                        )
                    }
                },
            )
        }
    }

    private data class PullResult(
        val updated: Int,
        val checked: Int,
        val refreshed: List<SaveFile>,
        val updatedNames: List<String>,
    )

    private fun validateConnection(current: UiState): Boolean {
        if (current.deviceId.length != 8) {
            _state.update { it.copy(statusMessage = "Ingresa el device_id de 8 caracteres de la PC.") }
            return false
        }
        if (current.pcHost.isBlank()) {
            _state.update { it.copy(statusMessage = "Ingresa la IP de la PC en la red local.") }
            return false
        }
        return true
    }

    private fun treeUriToPath(uri: Uri): String? {
        if (!DocumentsContract.isTreeUri(uri)) return null
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val split = docId.split(":", limit = 2)
            if (split.size != 2) return null
            val volume = split[0]
            val relative = split[1]
            if (volume.equals("primary", ignoreCase = true)) {
                File(Environment.getExternalStorageDirectory(), relative).absolutePath
            } else {
                "/storage/$volume/$relative"
            }
        } catch (_: Exception) {
            null
        }
    }

    fun needsAllFilesAccess(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
    }
}
