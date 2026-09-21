# Datenschutzerklärung — Pass Tech

**Dokumentversion**: 20. September 2026 (Pass Tech v2.7.0)
**App**: Pass Tech
**Offizielle Website**: https://www.files-tech.com
**Kontakt**: contact@files-tech.com
**Quellcode**: https://github.com/gitubpatrice/pass_tech
**Lizenz des Codes**: Apache License 2.0

---

## 1. Zweck

Diese Datenschutzerklärung beschreibt, wie die Anwendung **Pass Tech** — ein zu 100 % lokaler Passwort-Manager — mit Nutzerdaten und Berechtigungen umgeht.

## 2. Zusammenfassung für Nutzerinnen und Nutzer

- ✅ **Keine Werbung** in der Anwendung.
- ✅ **Keine Tracker**, keine Reichweitenmessung, keine Verhaltensanalyse, kein Profiling.
- ✅ **Kein anwendungseigenes Konto**.
- ✅ **Keine Cloud-Synchronisierung** — Ihr Tresor bleibt verschlüsselt auf Ihrem Gerät.
- ✅ **Keine Telemetrie** — es werden weder Nutzungsdaten noch Fehlerberichte an den Entwickler gesendet.

**Grundsatz**: Pass Tech ist ein zu 100 % lokaler Passwort-Tresor. Alle sensiblen Daten (Passwörter, TOTP-Secrets, Bankkarten, sichere Notizen) bleiben verschlüsselt auf dem Gerät. Der Entwickler betreibt keinen entfernten Server.

## 3. Verantwortlicher / Entwickler

- **Entwickler**: Files Tech / Patrice
- **Website**: https://www.files-tech.com
- **Datenschutzkontakt**: contact@files-tech.com
- **Quellcode-Repository**: https://github.com/gitubpatrice/pass_tech
- **Lizenz des Quellcodes**: Apache License 2.0

## 4. Abgerufene oder gespeicherte Daten

| Datenart                               | Verwendung                                                   | Ort der Verarbeitung                             |
| -------------------------------------- | ------------------------------------------------------------ | ------------------------------------------------ |
| Passwörter, TOTP-Secrets, Bankkarten, sichere Notizen | Von der Nutzerin oder dem Nutzer angelegte Tresoreinträge | Verschlüsselt auf dem Gerät (`pt_vault_a.enc`) |
| Zweiter Tresorplatz (`pt_vault_b.enc`) | Glaubhafte Abstreitbarkeit — **immer vorhanden**, ob Sie einen Schein-Tresor eingerichtet haben oder nicht | Verschlüsselt auf dem Gerät mit eigenem Keystore-Schlüssel |
| Master-Passwort                        | Leitet den Verschlüsselungsschlüssel ab (Argon2id, OWASP-2024-Referenz) | Nie gespeichert; beim Sperren aus dem RAM gelöscht |
| Biometrischer Schlüssel                | Optionales Entsperren per Fingerabdruck oder Gesicht         | Android Keystore (hardwaregebunden), `setUserAuthenticationRequired(true)` |
| Verschlüsselte Backups (`.ptbak`)      | Optionaler Export, von Ihnen ausgelöst                        | Von Ihnen gewählter Speicherort                   |
| Lokale Einstellungen                   | Design, Dauer bis zur Auto-Sperre, Zeit bis zum Leeren der Zwischenablage | Lokaler Gerätespeicher                            |

## 5. Verschlüsselung und Schlüsselableitung

- **AES-256-GCM** (AEAD) mit gebundener AAD gegen Downgrades.
- **Argon2id** (m = 19 MiB, t = 2, p = 1, OWASP-2024-Referenz) für die Ableitung des Tresorschlüssels.
- **Hardwaregebundener KEK** im Android Keystore (StrongBox, wenn verfügbar) umschließt ein tresoreigenes Hardware-Secret.
- **An die Hardware gebundener biometrischer Schlüssel** über den Android Keystore; ohne biometrische Authentifizierung nicht auslesbar.
- **Glaubhafte Abstreitbarkeit** — wer das Gerät untersucht, kann nicht erkennen, ob Sie einen zweiten, verborgenen Tresor führen. Beide Tresorplätze tragen neutrale, nicht unterscheidbare Namen (`pt_vault_a.enc` / `pt_vault_b.enc`) und **beide existieren immer**: Ist kein Schein-Tresor eingerichtet, schreibt die App dennoch einen Platzhalter — eine leere Liste, verschlüsselt unter einem Zufallspasswort, das nirgends gespeichert wird und den daher niemand öffnen kann, weder Sie noch wir. Keystore-Aliase, Salts und das Zeitverhalten beim Entsperren sind zwischen beiden Pfaden angeglichen.

## 6. Netzwerk

- Die App nutzt das Netzwerk für **zwei Funktionen mit streng lokaler Wirkung**:
  1. **Update-Prüfung**: fragt `api.github.com/repos/gitubpatrice/pass_tech/releases/latest` ab (HTTPS, ohne Anmeldung, ohne Cookie).
  2. **HIBP-Prüfung** (Have I Been Pwned, opt-in): sendet nur die **ersten 5 Zeichen des SHA-1** eines Passworts (k-Anonymität). Das Passwort selbst verlässt das Gerät nie.
- Die Network Security Config weist in der Release-Fassung unverschlüsseltes HTTP und nutzerinstallierte Zertifizierungsstellen zurück.
- Keine Telemetrie, keine Absturzberichte, keine Analyse.

## 7. Weitergabe und Übermittlung von Daten

Die Anwendung übermittelt keine Daten an einen vom Entwickler betriebenen Server. Eine Weitergabe außerhalb des Geräts setzt voraus:

- einen `.ptbak`-Export, den Sie ausdrücklich auslösen (verschlüsselt mit einer von Ihnen gewählten Passphrase);
- die freiwillige Nutzung einer Android-Funktion zum Teilen oder Versenden.

## 8. Aufbewahrung und Löschung

- Die Tresordaten liegen lokal und bleiben unter Ihrer Kontrolle.
- Das Deinstallieren der App löscht alle Daten (die Tresordatei liegt im privaten Verzeichnis der App und ist über `dataExtractionRules` von Cloud-Backups ausgenommen).
- Sie können den Tresor auch in der App löschen (`Einstellungen → Alle Daten löschen`).
- **Keine Restkopie eines älteren Tresors** — beim Aktualisieren eines alten v3-Tresors blieb früher eine `.bak`-Kopie zurück, mit dem älteren, schwächeren Verfahren verschlüsselt und offline angreifbar. Seit v2.5.1 wird sie gelöscht, sobald die Aktualisierung gelingt.

## 9. Sicherheit

- Sandbox-Isolierung, `FLAG_SECURE` (verhindert Screenshots und die Vorschau in der App-Übersicht).
- `allowBackup=false` und `dataExtractionRules` nehmen den Tresor von jedem Android-Cloud- oder Geräteübertragungs-Backup aus.
- Zunehmende Sperre nach 5 Fehlversuchen (30 s → 30 min).
- Konfigurierbare Auto-Sperre nach Inaktivität (standardmäßig 5 Minuten).
- Der aus dem Master-Passwort abgeleitete Schlüssel wird beim Sperren aus dem RAM gelöscht.
- RASP-Erkennung (Root, Emulator, Debugger) mit ausdrücklichem Hinweis an Sie.
- Kennzeichnung der Zwischenablage als sensibel (Android 13+) und sofortiges Leeren beim Pausieren.

Siehe [SECURITY.md](./SECURITY.md).

## 10. Android-Berechtigungen

| Berechtigung / Zugriff               | Grund                                                                                               |
| ------------------------------------ | --------------------------------------------------------------------------------------------------- |
| `USE_BIOMETRIC` / `USE_FINGERPRINT`  | Optionales biometrisches Entsperren über Android BiometricPrompt.                                    |
| `INTERNET`                           | Update-Prüfung (GitHub Releases) und HIBP-Prüfung (k-Anonymität, opt-in).                            |

`CAMERA` und `ACCESS_NETWORK_STATE` wurden am **2026-08-03 entfernt**, zusammen
mit dem Scannen von QR-Codes, das auf Google ML Kit beruhte. Ein 2FA-Secret
fügen Sie nun hinzu, indem Sie die `otpauth://`-URI einfügen, die die Dienste
unter ihrem QR-Code anzeigen.

## 11. Kinder

Die Anwendung richtet sich nicht gezielt an Kinder und enthält weder verhaltensbasierte Werbung noch Profiling.

## 12. Änderungen

Diese Erklärung kann mit der Weiterentwicklung der Anwendung aktualisiert werden.

## 13. Kontakt

📧 **contact@files-tech.com**
