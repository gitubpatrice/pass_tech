# Condizioni d'uso — Pass Tech

**Versione del documento**: 20 settembre 2026 (Pass Tech v2.7.0)
**App**: Pass Tech
**Sito ufficiale**: https://www.files-tech.com
**Contatto**: contact@files-tech.com
**Codice sorgente**: https://github.com/gitubpatrice/pass_tech
**Licenza del codice**: Apache License 2.0

---

## 1. Oggetto

Queste condizioni d'uso definiscono le regole applicabili all'uso dell'applicazione **Pass Tech** — un gestore di password 100% locale.

## 2. Accettazione

L'uso dell'applicazione comporta l'accettazione di queste condizioni.

## 3. Funzionamento generale

Pass Tech è una cassaforte di password 100% locale. Conserva password, segreti 2FA TOTP, carte di pagamento e note sicure in un file cifrato sul dispositivo, protetto da una password principale e, facoltativamente, da una chiave biometrica dell'Android Keystore.

## 4. Nessuna pubblicità, nessun tracker, nessuna telemetria

Lo sviluppatore dichiara che l'applicazione non contiene pubblicità, tracker, analisi, analisi comportamentale, sistemi di profilazione, telemetria né segnalazione remota di crash. La cassaforte non viene mai trasmessa ad alcun server gestito dallo sviluppatore.

## 5. Licenza del codice sorgente

Codice sorgente pubblicato sotto **Apache License 2.0**.

- Repository: https://github.com/gitubpatrice/pass_tech
- Sito ufficiale: https://www.files-tech.com
- Contatto: contact@files-tech.com

## 6. Responsabilità dell'utente

- **Scegli una password principale robusta** (consigliati almeno 12 caratteri) e conservala al sicuro. **Lo sviluppatore non può recuperarla.**
- Esegui tu stesso backup cifrati (esportazione `.ptbak` con una passphrase robusta).
- Proteggi il dispositivo con un blocco adeguato, aggiornamenti di sicurezza e backup utili.
- Rispetta il diritto d'autore, la riservatezza, il segreto professionale e la legge applicabile.

## 7. Punti specifici dell'applicazione

- **La password principale non è recuperabile.** Se la dimentichi, la cassaforte diventa definitivamente inaccessibile.
- Lo sblocco biometrico è uno strato di comodità; la password principale resta la radice della fiducia.
- Contro la compromissione del dispositivo Pass Tech agisce secondo il principio del massimo impegno: su un dispositivo con root, in modalità debug o emulato, l'utente viene avvisato e usa l'app a proprio rischio.
- Il controllo HIBP è facoltativo e usa un protocollo a k-anonimato (vengono inviati solo i primi 5 caratteri dell'SHA-1 della password).

## 8. Esclusione di garanzia

L'applicazione è fornita così com'è. Lo sviluppatore si impegna al massimo per offrire uno strumento sicuro, ma non garantisce una sicurezza assoluta. L'utente resta responsabile della scelta della password principale e della protezione del proprio dispositivo.

## 9. Limitazione di responsabilità

Nei limiti consentiti dalla legge, lo sviluppatore non può essere ritenuto responsabile per perdite di dati (in particolare per una password principale dimenticata che renda la cassaforte irrecuperabile), per errori di utilizzo, per malfunzionamenti di servizi terzi o per le conseguenze di un uso non conforme.

## 10. Servizi di terze parti

- **API GitHub Releases** per il controllo degli aggiornamenti (HTTPS, senza autenticazione, senza cookie).
- **API Have I Been Pwned** per il controllo delle violazioni (HTTPS, k-anonimato, facoltativo).

## 11. Proprietà intellettuale

Il nome, i contenuti, i testi, le icone, gli elementi grafici e le risorse specifiche del progetto restano protetti. Il codice sorgente principale è rilasciato sotto Apache License 2.0.

## 12. Sicurezza

L'utente deve proteggere la propria password principale e il proprio dispositivo, ed evitare di usare Pass Tech su dispositivi con root o compromessi. Vedi [SECURITY.md](./SECURITY.md).

## 13. Modifica delle condizioni

Queste condizioni possono essere aggiornate. La data del documento indica la versione in vigore.

## 14. Legge applicabile

Fatte salve le disposizioni di legge inderogabili, queste condizioni sono redatte nel quadro del diritto francese ed europeo.
