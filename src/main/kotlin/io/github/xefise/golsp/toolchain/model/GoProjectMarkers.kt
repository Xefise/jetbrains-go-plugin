package io.github.xefise.golsp.toolchain.model

/** Shallow, index-free guess whether a project root is a Go project (root and its direct subdirectories). */
object GoProjectMarkers {
    const val MAX_SUBDIRECTORIES: Int = 200

    private val SKIPPED_DIRECTORIES = setOf("node_modules", "vendor", "testdata", "build", "dist", "out", "target")

    fun hasGoMarker(fileNames: Collection<String>): Boolean =
        fileNames.any { it == "go.mod" || it == "go.work" || it.endsWith(".go") }

    fun isScannedSubdirectory(name: String): Boolean = !name.startsWith(".") && name !in SKIPPED_DIRECTORIES
}
