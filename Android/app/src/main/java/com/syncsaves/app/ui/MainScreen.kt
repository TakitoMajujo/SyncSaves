package com.syncsaves.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syncsaves.app.UiState
import com.syncsaves.app.scanner.SaveFile

private val Bg = Color(0xFF1A1D23)
private val Accent = Color(0xFF7DD3FC)
private val OnBg = Color(0xFFE8EAED)
private val Muted = Color(0xFF9AA0A6)
private val CardBg = Color(0xFF242830)

@Composable
fun MainScreen(
    state: UiState,
    onDeviceIdChange: (String) -> Unit,
    onPcHostChange: (String) -> Unit,
    onPickFolder: () -> Unit,
    onClearFolder: () -> Unit,
    onRequestStoragePermission: () -> Unit,
    onScan: () -> Unit,
    onUpload: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(20.dp),
    ) {
        Text(
            text = "SyncSaves",
            color = OnBg,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "My Boy! → PC (red local)",
            color = Muted,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
        )

        OutlinedTextField(
            value = state.deviceId,
            onValueChange = onDeviceIdChange,
            label = { Text("Código device_id de la PC") },
            placeholder = { Text("Ej. Z5EZLXAR") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = state.pcHost,
            onValueChange = onPcHostChange,
            label = { Text("IP de la PC") },
            placeholder = { Text("Ej. 192.168.1.20") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors(),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Archivos encontrados", color = Muted, fontSize = 13.sp)
                Text(
                    text = "${state.saves.size}",
                    color = Accent,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = if (state.customScanRoot.isBlank()) {
                        "Rutas MyBoy por defecto (+ barrido si hace falta)"
                    } else {
                        "Carpeta: ${state.customScanRoot}"
                    },
                    color = Muted,
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onPickFolder,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
            ) {
                Text("Carpeta emulador")
            }
            OutlinedButton(
                onClick = onClearFolder,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Muted),
            ) {
                Text("Limpiar carpeta")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                onRequestStoragePermission()
                onScan()
            },
            enabled = !state.isScanning && !state.isUploading,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg),
        ) {
            if (state.isScanning) {
                CircularProgressIndicator(
                    color = Bg,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Barrer saves My Boy!")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onUpload,
            enabled = !state.isScanning && !state.isUploading && state.saves.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155), contentColor = OnBg),
        ) {
            if (state.isUploading) {
                CircularProgressIndicator(
                    color = OnBg,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Enviar encontrados a la PC")
        }

        Text(
            text = state.statusMessage,
            color = Muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.saves, key = { it.absolutePath }) { save ->
                SaveRow(save)
            }
        }
    }
}

@Composable
private fun SaveRow(save: SaveFile) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = save.name, color = OnBg, fontWeight = FontWeight.SemiBold)
            Text(text = save.absolutePath, color = Muted, fontSize = 11.sp)
            Text(text = formatSize(save.sizeBytes), color = Accent, fontSize = 12.sp)
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = OnBg,
    unfocusedTextColor = OnBg,
    focusedBorderColor = Accent,
    unfocusedBorderColor = Muted,
    focusedLabelColor = Accent,
    unfocusedLabelColor = Muted,
    cursorColor = Accent,
    focusedContainerColor = CardBg,
    unfocusedContainerColor = CardBg,
)

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    return String.format("%.2f MB", kb / 1024.0)
}
