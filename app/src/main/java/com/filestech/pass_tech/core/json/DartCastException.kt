package com.filestech.pass_tech.core.json

/**
 * A JSON value that a Dart `value as T` cast would have refused. The readers catch it and refuse the
 * file (or the entry), exactly where the Flutter app does.
 */
class DartCastException(key: String) : Exception("JSON field '$key' has the wrong type")
