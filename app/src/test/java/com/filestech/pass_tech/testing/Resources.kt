package com.filestech.pass_tech.testing

import java.io.File

object Resources {
    fun text(path: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) { "test resource not found: $path" }
            .use { it.readBytes().decodeToString() }

    /**
     * Where the tests write files meant for the Flutter side of the compatibility check
     * (`tools/compat/compat_vectors_test.dart`, group "verify"). Under the build directory: never
     * committed, recreated by every run.
     */
    fun outputDir(name: String): File = File("build/compat/$name").apply { mkdirs() }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

fun unhex(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "odd hex length" }
    return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
