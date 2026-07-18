package com.syncsaves.app.scanner

import android.os.Environment
import java.io.File

data class SaveFile(
    val name: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

object MyBoyScanner {
    /** Extensiones típicas de My Boy! (battery save + estados). */
    val EXTENSIONS: Set<String> = setOf("sav", "dss", "state")

    private val preferredRelativeRoots = listOf(
        "MyBoy/save",
        "MyBoy/saves",
        "MyBoy",
    )

    fun scan(
        customRootPath: String? = null,
        maxDepth: Int = 8,
    ): List<SaveFile> {
        val roots = mutableListOf<File>()

        customRootPath
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.exists() && it.canRead() }
            ?.let { roots.add(it) }

        val external = Environment.getExternalStorageDirectory()
        preferredRelativeRoots
            .map { File(external, it) }
            .filter { it.exists() && it.canRead() }
            .forEach { roots.add(it) }

        // Si no hay carpeta MyBoy conocida, barre almacenamiento compartido con profundidad limitada.
        if (roots.isEmpty() && external.exists() && external.canRead()) {
            roots.add(external)
        }

        val found = linkedMapOf<String, SaveFile>()
        for (root in roots.distinctBy { it.absolutePath }) {
            walk(root, depth = 0, maxDepth = maxDepth) { file ->
                if (isMyBoySave(file)) {
                    found[file.absolutePath] = SaveFile(
                        name = file.name,
                        absolutePath = file.absolutePath,
                        sizeBytes = file.length(),
                        lastModified = file.lastModified(),
                    )
                }
            }
        }
        return found.values.sortedBy { it.name.lowercase() }
    }

    fun isMyBoySave(file: File): Boolean {
        if (!file.isFile || !file.canRead()) return false
        val ext = file.extension.lowercase()
        if (ext !in EXTENSIONS) return false

        // Prefer files clearly related to MyBoy, but accept .sav anywhere during custom/root scan.
        val path = file.absolutePath.lowercase()
        if (ext == "sav") return true
        return path.contains("myboy") || path.contains("/save") || path.contains("/saves")
    }

    private fun walk(dir: File, depth: Int, maxDepth: Int, onFile: (File) -> Unit) {
        if (depth > maxDepth) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isFile) {
                onFile(child)
            } else if (child.isDirectory && !child.name.startsWith(".")) {
                // Evita carpetas pesadas / irrelevantes en barrido amplio.
                val name = child.name.lowercase()
                if (name in SKIP_DIRS) continue
                walk(child, depth + 1, maxDepth, onFile)
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
