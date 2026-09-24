package com.filestech.pass_tech.core.json

import kotlinx.serialization.SerializationException
import java.nio.charset.CharacterCodingException

/**
 * One way for a file reader to fail: every check rejects through [reject], [ensure] or [orReject],
 * and [readOrNull] is the only place that turns a rejection into `null`. A reader built this way
 * has a single exit, so no path can forget to fail closed.
 *
 * Shared by every encrypted format of the app (vault slot, `.ptbak` backup).
 */
class FileRejected : Exception()

fun reject(): Nothing = throw FileRejected()

fun ensure(condition: Boolean) {
    if (!condition) reject()
}

fun <T : Any> T?.orReject(): T = this ?: reject()

/**
 * Runs [read] and maps every way a damaged, forged or foreign file can fail to `null`: an explicit
 * rejection, a Dart-incompatible JSON type, malformed JSON, malformed UTF-8, or malformed Base64.
 */
inline fun <T> readOrNull(read: () -> T): T? =
    try {
        read()
    } catch (_: FileRejected) {
        null
    } catch (_: DartCastException) {
        null
    } catch (_: SerializationException) {
        null
    } catch (_: CharacterCodingException) {
        null
    } catch (_: IllegalArgumentException) {
        // java.util.Base64 on a damaged field.
        null
    }
