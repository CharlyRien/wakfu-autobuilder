package me.chosante.common

import java.io.File

/**
 * Locates the repository root by walking up from the current working directory until a
 * `settings.gradle.kts` is found.
 *
 * Shared by the standalone extractor tools (`equipments-extractor`, `spells-extractor`,
 * `bdata-extractor`), which all regenerate baked JSON under `<root>/autobuilder/src/main/resources`.
 * It is build-time tooling rather than domain, but lives here because common-lib is the one module all
 * three extractors already depend on, and the function needs no dependency beyond the JDK.
 */
fun findRepositoryRoot(): File =
    generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").exists() }
        ?: error("Unable to locate repository root from ${System.getProperty("user.dir")}")
