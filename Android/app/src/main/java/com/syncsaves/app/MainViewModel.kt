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

data class UiState(
    val deviceId: String = "",
    val pcHost: String = "",
    val customScanRoot: String = "",
    val saves: List<SaveFile> = emptyList(),
    val isScanning: Boolean = false,
    val isUploading: Boolean = false,
    val statusMessage: String = "Listo para barrer saves de My Boy!",
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = AppPreferences(application)
    private val syncClient = PcSyncClient()

    private val _state = MutableStateFlow(
        UiState(
            deviceId = prefs.deviceId,
            pcHost = prefs.pcHost,
            customScanRoot = prefs.customScanRoot,
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
        val path = treeUriToPath(uri)
        if (path.isNullOrBlank()) {
            _state.update {
                it.copy(statusMessage = "No se pudo resolver la carpeta elegida. Prueba otra ruta.")
            }
            return
        }
        prefs.customScanRoot = path
        _state.update {
            it.copy(
                customScanRoot = path,
                statusMessage = "Carpeta del emulador: $path",
            )
        }
    }

    fun clearCustomRoot() {
        prefs.customScanRoot = ""
        _state.update {
            it.copy(
                customScanRoot = "",
                statusMessage = "Carpeta personalizada limpiada. Se usarán rutas MyBoy por defecto.",
            )
        }
    }

    fun scanMyBoySaves() {
        viewModelScope.launch {
            _state.update { it.copy(isScanning = true, statusMessage = "Barriendo dispositivo…") }
            val root = _state.value.customScanRoot.ifBlank { null }
            val found = withContext(Dispatchers.IO) {
                MyBoyScanner.scan(customRootPath = root)
            }
            _state.update {
                it.copy(
                    isScanning = false,
                    saves = found,
                    statusMessage = "Encontrados ${found.size} archivo(s) de guardado My Boy!",
                )
            }
        }
    }

    fun uploadFoundSaves() {
        val current = _state.value
        if (current.deviceId.length != 8) {
            _state.update { it.copy(statusMessage = "Ingresa el device_id de 8 caracteres de la PC.") }
            return
        }
        if (current.pcHost.isBlank()) {
            _state.update { it.copy(statusMessage = "Ingresa la IP de la PC en la red local.") }
            return
        }
        if (current.saves.isEmpty()) {
            _state.update { it.copy(statusMessage = "No hay archivos para enviar. Barre primero.") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isUploading = true, statusMessage = "Enviando a la PC…") }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    var ok = 0
                    for (save in current.saves) {
                        syncClient.uploadSave(current.pcHost, current.deviceId, save).getOrThrow()
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

    private fun treeUriToPath(uri: Uri): String? {
        // content://com.android.externalstorage.documents/tree/primary%3AMyBoy%2Fsave
        if (DocumentsContract.isTreeUri(uri)) {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val split = docId.split(":", limit = 2)
            if (split.size == 2) {
                val volume = split[0]
                val relative = split[1]
                return if (volume.equals("primary", ignoreCase = true)) {
                    FilePath(Environment.getExternalStorageDirectory(), relative)
                } else {
                    "/storage/$volume/$relative"
                }
            }
        }
        return uri.path
    }

    private fun FilePath(base: java.io.File, relative: String): String {
        return java.io.File(base, relative).absolutePath
    }

    fun needsAllFilesAccess(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
    }
}
