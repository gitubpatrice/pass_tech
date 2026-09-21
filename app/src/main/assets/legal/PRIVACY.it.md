# Informativa sulla privacy — Pass Tech

**Versione del documento**: 20 settembre 2026 (Pass Tech v2.7.0)
**App**: Pass Tech
**Sito ufficiale**: https://www.files-tech.com
**Contatto**: contact@files-tech.com
**Codice sorgente**: https://github.com/gitubpatrice/pass_tech
**Licenza del codice**: Apache License 2.0

---

## 1. Scopo

Questa informativa spiega come l'applicazione **Pass Tech** — un gestore di password 100% locale — tratta i dati e i permessi.

## 2. In breve

- ✅ **Nessuna pubblicità** nell'applicazione.
- ✅ **Nessun tracker**, nessuna misurazione del pubblico, nessuna analisi comportamentale, nessuna profilazione.
- ✅ **Nessun account** specifico dell'applicazione.
- ✅ **Nessuna sincronizzazione cloud** — la tua cassaforte resta cifrata sul tuo dispositivo.
- ✅ **Nessuna telemetria** — nessun dato d'uso, nessuna segnalazione di errore inviata allo sviluppatore.

**Principio generale**: Pass Tech è una cassaforte di password 100% locale. Tutti i dati sensibili (password, segreti TOTP, carte di pagamento, note sicure) restano cifrati sul dispositivo. Lo sviluppatore non gestisce alcun server remoto.

## 3. Titolare / sviluppatore

- **Sviluppatore**: Files Tech / Patrice
- **Sito**: https://www.files-tech.com
- **Contatto privacy**: contact@files-tech.com
- **Repository del codice**: https://github.com/gitubpatrice/pass_tech
- **Licenza del codice sorgente**: Apache License 2.0

## 4. Dati consultati o memorizzati

| Tipo di dato                           | Uso                                                          | Luogo del trattamento                            |
| -------------------------------------- | ------------------------------------------------------------ | ------------------------------------------------ |
| Password, segreti TOTP, carte di pagamento, note sicure | Voci della cassaforte create da te           | Cifrati sul dispositivo (`pt_vault_a.enc`)       |
| Secondo spazio della cassaforte (`pt_vault_b.enc`) | Negabilità plausibile — **sempre presente**, che tu abbia configurato un'esca o no | Cifrato sul dispositivo con una propria chiave Keystore |
| Password principale                    | Deriva la chiave di cifratura (Argon2id, riferimento OWASP 2024) | Mai memorizzata; cancellata dalla RAM al blocco   |
| Chiave biometrica                      | Sblocco facoltativo con impronta o volto                     | Android Keystore (legata all'hardware), `setUserAuthenticationRequired(true)` |
| Backup cifrati (`.ptbak`)              | Esportazione facoltativa, avviata da te                       | Percorso scelto da te                             |
| Preferenze locali                      | Tema, durata del blocco automatico, tempo di pulizia degli appunti | Memoria locale del dispositivo                    |

## 5. Cifratura e derivazione delle chiavi

- **AES-256-GCM** (AEAD) con AAD vincolata, per resistere ai downgrade.
- **Argon2id** (m = 19 MiB, t = 2, p = 1, riferimento OWASP 2024) per derivare la chiave principale della cassaforte.
- **KEK legata all'hardware** nell'Android Keystore (StrongBox quando disponibile) che avvolge un segreto hardware proprio di ciascuna cassaforte.
- **Chiave biometrica legata all'hardware** tramite l'Android Keystore; non estraibile senza autenticazione biometrica.
- **Negabilità plausibile** — chi esamina il dispositivo non può stabilire se tieni una seconda cassaforte nascosta. I due spazi portano nomi neutri e indistinguibili (`pt_vault_a.enc` / `pt_vault_b.enc`) ed **esistono entrambi sempre**: se non hai configurato alcuna esca, l'app ne scrive comunque una fittizia — un elenco vuoto, cifrato con una password casuale che non viene conservata da nessuna parte e che quindi nessuno può aprire, né tu né noi. Alias del Keystore, salt e tempi di sblocco sono allineati fra i due percorsi.

## 6. Rete

- L'app usa la rete per **due funzioni dagli effetti strettamente locali**:
  1. **Controllo degli aggiornamenti**: interroga `api.github.com/repos/gitubpatrice/pass_tech/releases/latest` (HTTPS, senza autenticazione, senza cookie).
  2. **Controllo HIBP** (Have I Been Pwned, facoltativo): invia solo i **primi 5 caratteri dell'SHA-1** di una password (modello a k-anonimato). La password non lascia mai il dispositivo.
- La Network Security Config rifiuta l'HTTP in chiaro e le autorità installate dall'utente nella versione di rilascio.
- Nessuna telemetria, nessuna segnalazione di crash, nessuna analisi.

## 7. Condivisione e trasmissione dei dati

L'applicazione non trasmette alcun dato a un server gestito dallo sviluppatore. Una condivisione fuori dal dispositivo richiede:

- un'esportazione `.ptbak` che avvii esplicitamente tu (cifrata con una passphrase che scegli);
- l'uso volontario di una funzione Android di condivisione o di invio per email.

## 8. Conservazione ed eliminazione

- I dati della cassaforte restano in locale e sotto il tuo controllo.
- Disinstallare l'app elimina tutti i dati (il file della cassaforte si trova nella cartella privata dell'app ed è escluso dai backup cloud tramite `dataExtractionRules`).
- Puoi anche eliminare la cassaforte dall'app (`Impostazioni → Elimina tutti i dati`).
- **Nessuna copia residua di una cassaforte precedente** — aggiornando una vecchia cassaforte v3 restava una copia `.bak`, cifrata con lo schema più debole di prima e attaccabile offline. Dalla v2.5.1 viene eliminata non appena l'aggiornamento riesce.

## 9. Sicurezza

- Isolamento sandbox, `FLAG_SECURE` (blocca screenshot e anteprima nelle app recenti).
- `allowBackup=false` e `dataExtractionRules` escludono la cassaforte da qualsiasi backup cloud Android o trasferimento fra dispositivi.
- Blocco progressivo dopo 5 tentativi falliti (30 s → 30 min).
- Blocco automatico configurabile dopo l'inattività (5 minuti di default).
- La chiave derivata dalla password principale viene cancellata dalla RAM al blocco.
- Rilevamento RASP (root, emulatore, debugger) con avviso esplicito.
- Contrassegno degli appunti come sensibili (Android 13+) e pulizia immediata alla sospensione.

Vedi [SECURITY.md](./SECURITY.md).

## 10. Permessi Android

| Permesso / accesso                   | Motivo                                                                                              |
| ------------------------------------ | --------------------------------------------------------------------------------------------------- |
| `USE_BIOMETRIC` / `USE_FINGERPRINT`  | Sblocco biometrico facoltativo tramite Android BiometricPrompt.                                      |
| `INTERNET`                           | Controllo degli aggiornamenti (GitHub Releases) e controllo HIBP (k-anonimato, facoltativo).         |

`CAMERA` e `ACCESS_NETWORK_STATE` sono stati **rimossi il 2026-08-03** insieme
alla lettura dei codici QR, che si basava su Google ML Kit. Un segreto 2FA si
aggiunge ora incollando l'URI `otpauth://` che i servizi mostrano sotto il loro
codice QR.

## 11. Minori

L'applicazione non è rivolta specificamente ai minori e non contiene pubblicità comportamentale né meccanismi di profilazione.

## 12. Modifiche

Questa informativa può essere aggiornata con l'evolvere dell'applicazione.

## 13. Contatto

📧 **contact@files-tech.com**
