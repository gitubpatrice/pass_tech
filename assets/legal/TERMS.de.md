# Nutzungsbedingungen — Pass Tech

**Dokumentversion**: 20. September 2026 (Pass Tech v2.7.0)
**App**: Pass Tech
**Offizielle Website**: https://www.files-tech.com
**Kontakt**: contact@files-tech.com
**Quellcode**: https://github.com/gitubpatrice/pass_tech
**Lizenz des Codes**: Apache License 2.0

---

## 1. Zweck

Diese Nutzungsbedingungen legen die Regeln für die Verwendung der Anwendung **Pass Tech** fest — eines zu 100 % lokalen Passwort-Managers.

## 2. Annahme

Die Nutzung der Anwendung gilt als Annahme dieser Bedingungen.

## 3. Allgemeine Funktionsweise

Pass Tech ist ein zu 100 % lokaler Passwort-Tresor. Er speichert Passwörter, TOTP-2FA-Secrets, Bankkarten und sichere Notizen in einer verschlüsselten Datei auf dem Gerät, geschützt durch ein Master-Passwort und wahlweise durch einen biometrischen Schlüssel im Android Keystore.

## 4. Keine Werbung, keine Tracker, keine Telemetrie

Der Entwickler erklärt, dass die Anwendung keine Werbung, keinen Tracker, keine Analyse, keine Verhaltensauswertung, kein Profiling, keine Telemetrie und keine entfernte Absturzberichterstattung enthält. Der Tresor wird niemals an einen vom Entwickler betriebenen Server übertragen.

## 5. Lizenz des Quellcodes

Der Quellcode ist unter der **Apache License 2.0** veröffentlicht.

- Repository: https://github.com/gitubpatrice/pass_tech
- Offizielle Website: https://www.files-tech.com
- Kontakt: contact@files-tech.com

## 6. Pflichten der Nutzerin oder des Nutzers

- **Wählen Sie ein starkes Master-Passwort** (mindestens 12 Zeichen empfohlen) und bewahren Sie es sicher auf. **Es kann vom Entwickler nicht wiederhergestellt werden.**
- Erstellen Sie eigene verschlüsselte Backups (`.ptbak`-Export mit einer starken Passphrase).
- Schützen Sie Ihr Gerät mit einer geeigneten Sperre, mit Sicherheitsupdates und mit sinnvollen Sicherungen.
- Beachten Sie Urheberrecht, Privatsphäre, Berufsgeheimnisse und geltendes Recht.

## 7. Besonderheiten dieser Anwendung

- **Das Master-Passwort kann nicht wiederhergestellt werden.** Vergessen Sie es, ist der Tresor dauerhaft unzugänglich.
- Das biometrische Entsperren ist eine Bequemlichkeitsschicht; das Master-Passwort bleibt die Vertrauenswurzel.
- Gegen ein kompromittiertes Gerät wirkt Pass Tech nach bestem Bemühen: auf einem gerooteten, debugfähigen oder emulierten Gerät werden Sie gewarnt und nutzen die App auf eigenes Risiko.
- Die HIBP-Prüfung ist opt-in und verwendet ein k-Anonymitätsverfahren (es werden nur die ersten 5 Zeichen des SHA-1 des Passworts gesendet).

## 8. Gewährleistungsausschluss

Die Anwendung wird wie besehen bereitgestellt. Der Entwickler bemüht sich nach Kräften um ein sicheres Werkzeug, garantiert jedoch keine absolute Sicherheit. Für die Wahl des Master-Passworts und den Schutz des Geräts bleiben Sie verantwortlich.

## 9. Haftungsbeschränkung

Soweit gesetzlich zulässig, haftet der Entwickler nicht für Datenverluste (insbesondere für ein vergessenes Master-Passwort, das den Tresor unwiederbringlich macht), für Bedienfehler, für Störungen von Drittdiensten oder für die Folgen einer nicht bestimmungsgemäßen Nutzung.

## 10. Dienste Dritter

- **GitHub-Releases-API** für die Update-Prüfung (HTTPS, ohne Anmeldung, ohne Cookie).
- **Have-I-Been-Pwned-API** für die Prüfung auf Datenlecks (HTTPS, k-Anonymität, opt-in).

## 11. Geistiges Eigentum

Name, Inhalte, Texte, Symbole, Grafiken und projekteigene Ressourcen bleiben geschützt. Der Hauptquellcode steht unter der Apache License 2.0.

## 12. Sicherheit

Sie müssen Ihr Master-Passwort und Ihr Gerät schützen und Pass Tech nicht auf gerooteten oder kompromittierten Geräten verwenden. Siehe [SECURITY.md](./SECURITY.md).

## 13. Änderung der Bedingungen

Diese Bedingungen können aktualisiert werden. Das Datum des Dokuments gibt die jeweils geltende Fassung an.

## 14. Anwendbares Recht

Vorbehaltlich zwingender gesetzlicher Bestimmungen sind diese Bedingungen im Rahmen des französischen und europäischen Rechts abgefasst.
