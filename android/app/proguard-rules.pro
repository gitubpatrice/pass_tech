# Flutter / Dart
-keep class io.flutter.** { *; }
-keep class io.flutter.plugins.** { *; }
-keep class io.flutter.plugin.editing.** { *; }
-dontwarn io.flutter.embedding.**

# Plugins natifs utilisés
-keep class com.it_nomads.fluttersecurestorage.** { *; }
-keep class design.codeux.biometric_storage.** { *; }
-keep class dev.steenbakker.mobile_scanner.** { *; }
-keep class com.mr.flutter.plugin.filepicker.** { *; }

# QW9 v2.4.0 — cryptography_flutter FFI : sans ces keeps, R8 peut élider
# l'init Argon2id natif → fallback pur-Dart silencieux (~10× plus lent,
# régression UX invisible au CI). Reproduit côté upstream sur AGP 8.3+.
-keep class com.github.dint.cryptography_flutter.** { *; }
-dontwarn com.github.dint.cryptography_flutter.**

# Bouncy/PointyCastle (dépendance transitive d'encrypt)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# AndroidX biometric
-keep class androidx.biometric.** { *; }
-keep class androidx.fragment.** { *; }

# Conserver les annotations utilisées par certains plugins
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
