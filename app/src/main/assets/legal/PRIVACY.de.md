# Datenschutzerklärung — Pass Tech

- **Gilt für**: Pass Tech 3.0.0 (`com.filestech.pass_tech`)
- **Letzte Änderung**: 23. September 2026
- **Herausgeber**: Files Tech — Patrice Haltaya
- **Kontakt**: contact@files-tech.com
- **Quellcode**: https://github.com/gitubpatrice/pass_tech — Apache License 2.0

> Dieses Dokument beschreibt die **Kotlin**-Fassung von Pass Tech, die 3.0.0. Es ist nicht die
> Erklärung der früheren Flutter-Versionen (2.x, `com.passtech.pass_tech`), die anders arbeiteten.

---

## 1. Kurz gesagt

Pass Tech bewahrt Passwörter, Bankkarten und Notizen verschlüsselt auf Ihrem Telefon auf. Es gibt
kein Konto, keinen Server von uns und keine Kopie Ihrer Daten irgendwo sonst.

- **Wir erheben nichts.** Keine Werbung, kein Tracker, keine Statistik, keinen Absturzbericht, keine
  Kennung.
- **Wir erhalten nichts.** Keinen Namen, keine Adresse, kein Passwort, keine Zahl.
- **Bei uns gibt es nichts zu löschen**, weil bei uns nichts liegt.

Die App nutzt das Netz für **genau zwei Dinge**, beide in §5 beschrieben. Keines von beiden sendet
etwas, das Sie eingegeben haben.

## 2. Was auf Ihrem Telefon liegt, und wo

Alles liegt im privaten Verzeichnis der App, das keine andere App lesen kann.

| Datei | Was sie enthält |
| --- | --- |
| `pt_vault_a.enc`, `pt_vault_b.enc`, `pt_vault_c.enc` | Drei Tresor-Plätze. **Alle drei existieren immer und sind gleich groß**, ob Sie einen, zwei oder drei nutzen. |
| `pt_heir_a.enc`, `pt_heir_b.enc`, `pt_heir_c.enc` | Drei Erben-Abbilder, falls der Erbenzugang eingerichtet ist. **Alle drei existieren immer und sind gleich groß**, aus demselben Grund. |
| `pt_state.enc` | Die Zähler, die die App braucht, bevor ein Tresor offen ist: fehlgeschlagene Versuche, letzter Start, Datum der letzten Update-Prüfung. |
| Android-Einstellungen | Design, Sperrverzögerung, Verzögerung der Zwischenablage, ob Screenshots blockiert sind, ob die Domain-Prüfung an ist. Nichts Geheimes. |

**Warum drei von allem.** Ein zweites Passwort öffnet einen zweiten Tresor, und nichts auf dem
Telefon verrät, ob Sie ihn benutzen. Das hält nur, wenn ein benutzter und ein unbenutzter Platz
einander genau gleichen — gleicher Name, gleiche Größe, gleicher Inhalt für jeden, der die Bytes
liest. Die Plätze, die Sie nicht benutzen, enthalten Zufallsdaten, verschlüsselt mit einem Schlüssel,
den es nirgends gibt: weder Sie noch wir werden sie je öffnen können.

## 3. Wie verschlüsselt wird

- **AES-256-GCM** für den Tresor, die Erben-Abbilder und die Zustandsdatei.
- Der Schlüssel wird aus Ihrem Master-Passwort mit **Argon2id** abgeleitet (19 MiB Speicher, 2
  Durchläufe, 1 Thread — der OWASP-Richtwert 2024 für Mobilgeräte) **und** mit einem Schlüssel, der
  im Sicherheitschip des Telefons liegt und an jedem einzelnen Versuch beteiligt ist. Eine Kopie
  Ihres Tresors, auf einen anderen Rechner getragen, lässt sich dort nicht angreifen: dieser Chip ist
  nicht dabei.
- **Ihr Master-Passwort wird nie gespeichert**, in keiner Form, nirgends. Es kann nicht
  wiederhergestellt werden — nicht von uns, von niemandem. Es zu vergessen heißt, den Tresor zu
  verlieren.
- Das biometrische Entsperren hält seinen Schlüssel, wenn Sie es einschalten, im Android-Keystore:
  unlesbar ohne Ihren Fingerabdruck und zerstört, sobald sich die Fingerabdrücke auf dem Telefon
  ändern.
- Nach **5 falschen Versuchen** wartet die App: 30 Sekunden, dann 1, 5, 15 und 30 Minuten. Die
  Wartezeit steht in der Zustandsdatei und übersteht einen Neustart.

## 4. Was Sie ausgeben können

- **`.ptbak`-Sicherung**: Sie bestimmen wann, und eine eigene Passphrase verschlüsselt sie. Wir sehen
  sie nie. Wohin Sie sie danach legen, ist Ihre Entscheidung — eine Sicherung in einer Cloud liegt in
  dieser Cloud.
- **Export im Klartext**: angeboten für den Umzug zu einem anderen Verwalter. Er ist **nicht
  verschlüsselt**, und die App sagt das, bevor sie ihn schreibt.

Nichts geht von allein hinaus. Es gibt keine automatische Sicherung: Androids Cloud-Sicherung und die
Übertragung von Telefon zu Telefon sind für diese App beide abgeschaltet.

## 5. Die zwei Male, die die App das Netz nutzt

1. **Update-Prüfung.** Sobald Ihr Tresor offen ist, fragt die App bei GitHub nach, ob eine neuere
   Fassung erschienen ist — **höchstens zweimal am Tag**. Sie fragt `api.github.com` nach der
   neuesten Veröffentlichung dieses Projekts. Kein Konto, kein Cookie, nichts von Ihnen wird
   gesendet. Sie lädt nichts herunter und installiert nichts; sie zeigt, was sie gefunden hat, und
   einen Link.
2. **Leck-Prüfung**, auf dem Prüfungsbildschirm, **die Sie selbst starten**. Das Passwort wird nie
   gesendet. Die App berechnet seinen SHA-1-Fingerabdruck und sendet **nur die ersten fünf Zeichen**
   an Have I Been Pwned, das mit allen Fingerabdrücken antwortet, die so beginnen — Zehntausende
   davon. Der Vergleich findet auf Ihrem Telefon statt. Das ist das k-Anonymitätsmodell, das dieser
   Dienst veröffentlicht.

**Was diese beiden trotzdem verraten**, und es gehört offen gesagt: wer Ihre Verbindung sieht — Ihr
Anbieter, GitHub, Have I Been Pwned — erfährt, dass jemand unter Ihrer Adresse diese App benutzt. Er
erfährt nichts über Ihren Tresor. Falls Ihnen das wichtig ist: beide hören vollständig auf, sobald
die App als Taschenrechner getarnt ist (§7), und die Leck-Prüfung läuft ohnehin nie, solange Sie
nicht darauf drücken.

Die App verweigert unverschlüsseltes HTTP und verweigert Zertifizierungsstellen, die jemand anderes
dem Telefon hinzugefügt hat.

## 6. Die Domain-Prüfung und die Android-Berechtigung, die sie braucht

Wenn Sie **„Domain vor dem Kopieren prüfen“** einschalten, nutzt die App einen
Android-Bedienungshilfedienst. Das ist das Aufdringlichste, was sie je verlangt, also hier genau,
was sie damit tut.

- Er ist **bei der Installation aus** und erscheint nicht einmal in Androids Liste der
  Bedienungshilfen, solange Sie ihn nicht verlangen.
- Er erhält Ereignisse **nur von den Browsern, die er kennt** — Chrome, Firefox und seine
  Beta-Fassungen, Brave, Edge, Opera, Vivaldi, Samsung Internet, DuckDuckGo. Android sendet ihm
  nichts aus irgendeiner anderen App: nicht aus Ihrer Bank-App, nicht aus Ihren Nachrichten, nicht
  von Ihrer Tastatur.
- Aus diesen Browsern liest er **die Adresszeile und sonst nichts** — den Namen der Seite, nicht den
  Pfad, nicht die Parameter, kein Wort der Seite selbst.
- Dieser Name bleibt **nur im Speicher**, immer nur einer, bei jedem Lesen ersetzt und nach fünfzehn
  Sekunden vergessen. Er wird nie auf die Festplatte geschrieben und nie irgendwohin gesendet.
- Die Einstellung auszuschalten **nimmt die Berechtigung zurück**: der Dienst verlässt Androids
  Liste, und ihn wieder einzuschalten fragt Sie erneut danach.

## 7. Panikmodus

Wenn Sie ihn benutzen, sperrt sich die App, leert die Zwischenablage, entschärft den Fingerabdruck,
nimmt den Bedienungshilfedienst zurück und ersetzt ihren eigenen Namen und ihr Symbol auf Ihrem
Startbildschirm durch einen funktionierenden Taschenrechner. **Nichts wird gelöscht**: Ihr
Master-Passwort öffnet den Tresor weiterhin. Solange sie getarnt ist, macht die App überhaupt keinen
Netzaufruf.

## 8. Erbenzugang

Wenn Sie ihn einrichten, kann eine Person Ihrer Wahl ein **Abbild Ihres Tresors nur zum Lesen**
öffnen, nachdem es von Ihrer Seite lange genug still war — standardmäßig 90 Tage, dazu 7 Tage Gnade,
und jedes Entsperren setzt die Zählung zurück. Das Abbild ist mit einer eigenen Passphrase
verschlüsselt, die Sie selbst weitergeben. Es verlässt das Telefon nie, es gibt weder Cloud noch
Dritte, und wir haben damit nichts zu tun.

## 9. Android-Berechtigungen

Am veröffentlichten APK gemessen, nicht am Quelltext:

| Berechtigung | Wofür |
| --- | --- |
| `INTERNET` | Die zwei Aufrufe aus §5. |
| `USE_BIOMETRIC`, `USE_FINGERPRINT` | Das Entsperren per Fingerabdruck, falls Sie es einschalten. |
| `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Von einer Android-Bibliothek hinzugefügt; sie erlaubt der App, mit sich selbst zu sprechen, und sonst nichts. |

Der Bedienungshilfedienst aus §6 ist **keine** Berechtigung aus dieser Liste: Android erteilt ihn
gesondert, aus seinen eigenen Einstellungen, und dort können Sie ihn jederzeit zurücknehmen.

Es gibt keine Kamera-Berechtigung, keine für Kontakte, keine für den Standort, keine für den
Speicher: Pass Tech bittet das System um eine Datei, wenn Sie exportieren oder importieren, und das
System reicht ihr genau diese eine Datei.

## 10. Kinder

Die App richtet sich nicht an Kinder und enthält keine Werbung, kein Profiling und keinerlei
verhaltensbasierten Mechanismus.

## 11. Änderungen an diesem Dokument

Es wird mit der App und mit ihrem Quellcode veröffentlicht. Eine Änderung erscheint in einer
Version; das Datum oben sagt, in welcher.

## 12. Kontakt

contact@files-tech.com
