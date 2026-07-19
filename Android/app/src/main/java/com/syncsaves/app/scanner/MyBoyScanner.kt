package com.syncsaves.app.scanner

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

data class SaveFile(
    val name: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val lastModified: Long,
    /** content://… cuando viene del selector SAF; null si es ruta File. */
    val contentUri: String? = null,
)

object MyBoyScanner {
    private val FIXED_EXTENSIONS: Set<String> = setOf("sav", "dss", "state")
    private val SAVE_STATE_PATTERN = Regex("^st\\d+$")

    private val preferredRelativeRoots = listOf(
        "MyBoy/save",
        "MyBoy/saves",
        "MyBoy",
    )

    fun isSaveExtension(ext: String): Boolean {
        val normalized = ext.lowercase().removePrefix(".")
        return normalized in FIXED_EXTENSIONS || SAVE_STATE_PATTERN.matches(normalized)
    }

    fun extensionOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot in 0 until fileName.lastIndex) {
            fileName.substring(dot + 1)
        } else {
            ""
        }
    }

    fun scan(
        context: Context,
        treeUriString: String? = null,
        fallbackPath: String? = null,
        maxDepth: Int = 12,
    ): List<SaveFile> {
        val found = linkedMapOf<String, SaveFile>()

        // 1) Preferir la carpeta elegida con el selector (SAF / DocumentFile).
        val treeUri = treeUriString?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        if (treeUri != null) {
            val root = DocumentFile.fromTreeUri(context, treeUri)
            if (root != null && root.canRead()) {
                walkDocumentTree(root, depth = 0, maxDepth = maxDepth) { doc ->
                    val key = doc.uri.toString()
                    found[key] = SaveFile(
                        name = doc.name ?: "save.bin",
                        absolutePath = doc.uri.toString(),
                        sizeBytes = doc.length(),
                        lastModified = doc.lastModified(),
                        contentUri = doc.uri.toString(),
                    )
                }
            }
        }

        // 2) También intentar ruta File si se pudo resolver (útil con "Todos los archivos").
        fallbackPath
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.exists() && it.canRead() }
            ?.let { root ->
                walkFile(root, depth = 0, maxDepth = maxDepth) { file ->
                    found.putIfAbsent(
                        file.absolutePath,
                        SaveFile(
                            name = file.name,
                            absolutePath = file.absolutePath,
                            sizeBytes = file.length(),
                            lastModified = file.lastModified(),
                            contentUri = null,
                        ),
                    )
                }
            }

        // 3) Si no hay carpeta custom, rutas MyBoy por defecto.
        if (treeUri == null && fallbackPath.isNullOrBlank()) {
            val external = Environment.getExternalStorageDirectory()
            preferredRelativeRoots
                .map { File(external, it) }
                .filter { it.exists() && it.canRead() }
                .forEach { root ->
                    walkFile(root, depth = 0, maxDepth = maxDepth) { file ->
                        found[file.absolutePath] = SaveFile(
                            name = file.name,
                            absolutePath = file.absolutePath,
                            sizeBytes = file.length(),
                            lastModified = file.lastModified(),
                            contentUri = null,
                        )
                    }
                }

            if (found.isEmpty() && external.exists() && external.canRead()) {
                walkFile(external, depth = 0, maxDepth = 6) { file ->
                    found[file.absolutePath] = SaveFile(
                        name = file.name,
                        absolutePath = file.absolutePath,
                        sizeBytes = file.length(),
                        lastModified = file.lastModified(),
                        contentUri = null,
                    )
                }
            }
        }

        return found.values.sortedBy { it.name.lowercase() }
    }

    fun displayLabelForTreeUri(uri: Uri): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            docId.replace(':', '/')
        } catch (_: Exception) {
            uri.toString()
        }
    }

    private fun walkDocumentTree(
        dir: DocumentFile,
        depth: Int,
        maxDepth: Int,
        onFile: (DocumentFile) -> Unit,
    ) {
        if (depth > maxDepth) return
        val children = dir.listFiles()
        for (child in children) {
            if (child.isFile) {
                val name = child.name ?: continue
                if (isSaveExtension(extensionOf(name))) {
                    onFile(child)
                }
            } else if (child.isDirectory) {
                val name = (child.name ?: "").lowercase()
                if (name.startsWith(".") || name in SKIP_DIRS) continue
                walkDocumentTree(child, depth + 1, maxDepth, onFile)
            }
        }
    }

    private fun walkFile(dir: File, depth: Int, maxDepth: Int, onFile: (File) -> Unit) {
        if (depth > maxDepth) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isFile) {
                if (isSaveExtension(child.extension)) onFile(child)
            } else if (child.isDirectory && !child.name.startsWith(".")) {
                val name = child.name.lowercase()
                if (name in SKIP_DIRS) continue
                walkFile(child, depth + 1, maxDepth, onFile)
            }
        }
    }

    private val SKIP_DIRS = setOf(
        "android",
        "data",
        "obb",
        "cache",
        "dcim",
        "pictures",
        "movies",
        "music",
        "alarms",
        "notifications",
        "ringtones",
        "podcasts",
        ".thumbnails",
        ".trash",
    )
}
