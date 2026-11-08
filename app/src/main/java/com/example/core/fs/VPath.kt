package com.example.core.fs

data class VPath(val rawPath: String) {
    val normalized: String = normalize(rawPath)

    val name: String
        get() {
            if (normalized == "/" || normalized.isEmpty()) return "/"
            val clean = normalized.trimEnd('/')
            val idx = clean.lastIndexOf('/')
            return if (idx >= 0) clean.substring(idx + 1) else clean
        }

    val extension: String
        get() {
            val fileName = name
            val idx = fileName.lastIndexOf('.')
            return if (idx > 0 && idx < fileName.length - 1) {
                fileName.substring(idx + 1).lowercase()
            } else {
                ""
            }
        }

    val parent: VPath?
        get() {
            if (normalized == "/" || normalized.isEmpty()) return null
            val clean = normalized.trimEnd('/')
            val idx = clean.lastIndexOf('/')
            if (idx == -1) return null
            val parentStr = if (idx == 0) "/" else clean.substring(0, idx)
            return VPath(parentStr)
        }

    val isRoot: Boolean
        get() = normalized == "/" || normalized.isEmpty()

    fun resolve(childName: String): VPath {
        val cleanChild = childName.trim().trim('/')
        if (cleanChild.isEmpty()) return this
        val base = if (normalized == "/") "" else normalized.trimEnd('/')
        return VPath("$base/$cleanChild")
    }

    val segments: List<String>
        get() {
            return normalized.split('/').filter { it.isNotEmpty() }
        }

    companion object {
        val ROOT = VPath("/")

        fun normalize(path: String): String {
            val trimmed = path.trim()
            if (trimmed.isEmpty() || trimmed == "/") return "/"
            val parts = trimmed.split('/').filter { it.isNotEmpty() && it != "." }
            val stack = mutableListOf<String>()
            for (part in parts) {
                if (part == "..") {
                    if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                } else {
                    stack.add(part)
                }
            }
            val prefix = if (trimmed.startsWith("/")) "/" else ""
            return prefix + stack.joinToString("/")
        }
    }
}
