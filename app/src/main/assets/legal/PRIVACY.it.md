# Informativa sulla privacy — Pass Tech

- **Si applica a**: Pass Tech 3.0.0 (`com.filestech.pass_tech`)
- **Ultima modifica**: 23 settembre 2026
- **Editore**: Files Tech — Patrice Haltaya
- **Contatto**: contact@files-tech.com
- **Codice sorgente**: https://github.com/gitubpatrice/pass_tech — Apache License 2.0

> Questo documento descrive la versione **Kotlin** di Pass Tech, la 3.0.0. Non è l'informativa delle
> precedenti versioni Flutter (2.x, `com.passtech.pass_tech`), che funzionavano in modo diverso.

---

## 1. In breve

Pass Tech conserva password, carte bancarie e note sul tuo telefono, cifrate. Non c'è nessun account,
nessun server nostro e nessuna copia dei tuoi dati altrove.

- **Non raccogliamo nulla.** Nessuna pubblicità, nessun tracciatore, nessuna statistica, nessuna
  segnalazione di arresto anomalo, nessun identificativo.
- **Non riceviamo nulla.** Né un nome, né un indirizzo, né una password, né un numero.
- **Da noi non c'è nulla da cancellare**, perché da noi non c'è nulla.

L'app usa la rete per **due sole cose**, entrambe descritte al §5. Nessuna delle due invia ciò che hai
digitato.

## 2. Cosa c'è sul tuo telefono, e dove

Tutto vive nella cartella privata dell'app, che nessun'altra app può leggere.

| File | Cosa contiene |
| --- | --- |
| `pt_vault_a.enc`, `pt_vault_b.enc`, `pt_vault_c.enc` | Tre posti per la cassaforte. **Tutti e tre esistono sempre e hanno la stessa dimensione**, che tu ne usi uno, due o tre. |
| `pt_heir_a.enc`, `pt_heir_b.enc`, `pt_heir_c.enc` | Tre istantanee per l'erede, se l'accesso erede è configurato. **Tutte e tre esistono sempre e hanno la stessa dimensione**, per lo stesso motivo. |
| `pt_state.enc` | I contatori che servono all'app prima che una cassaforte sia aperta: tentativi falliti, ultimo avvio, data dell'ultimo controllo degli aggiornamenti. |
| Preferenze Android | Tema, ritardo di blocco, ritardo degli appunti, se gli screenshot sono bloccati, se il controllo del dominio è attivo. Nessun segreto. |

**Perché tre di tutto.** Una seconda password apre una seconda cassaforte, e nulla sul telefono dice
se la usi. Questo regge solo se un posto usato e uno inutilizzato si assomigliano esattamente —
stesso nome, stessa dimensione, stesso contenuto per chi legge i byte. I posti che non usi
contengono dati casuali, cifrati con una chiave che non esiste da nessuna parte: né tu né noi
potremo mai aprirli.

## 3. Come è cifrato

- **AES-256-GCM** per la cassaforte, le istantanee dell'erede e il file di stato.
- La chiave è derivata dalla tua password principale con **Argon2id** (19 MiB di memoria, 2
  passaggi, 1 thread — il riferimento OWASP 2024 per il mobile) **e** con una chiave custodita nel
  chip sicuro del telefono, che partecipa a ogni singolo tentativo. Una copia della tua cassaforte
  portata su un'altra macchina non può esservi attaccata: quel chip non c'è.
- **La tua password principale non viene mai salvata**, in nessuna forma, da nessuna parte. Non può
  essere recuperata — né da noi, né da nessuno. Dimenticarla significa perdere la cassaforte.
- Lo sblocco biometrico, se lo attivi, tiene la sua chiave nel Keystore di Android: illeggibile senza
  la tua impronta e distrutta se le impronte del telefono cambiano.
- Dopo **5 tentativi sbagliati** l'app aspetta: 30 secondi, poi 1, 5, 15 e 30 minuti. L'attesa è
  contata nel file di stato e sopravvive a un riavvio.

## 4. Cosa puoi esportare

- **Copia `.ptbak`**: scegli tu quando, e una frase di accesso tua la cifra. Noi non la vediamo mai.
  Dove la metti poi è una tua decisione — una copia su un cloud sta su quel cloud.
- **Esportazione in chiaro**: offerta per passare a un altro gestore. **Non è cifrata**, e l'app lo
  dice prima di scriverla.

Nulla esce da solo. Non esiste alcuna copia automatica: il backup nel cloud di Android e il
trasferimento da telefono a telefono sono disattivati per questa app.

## 5. Le due volte in cui l'app usa la rete

1. **Controllo degli aggiornamenti.** Una volta aperta la cassaforte, l'app chiede a GitHub se è
   uscita una versione più recente — **al massimo due volte al giorno**. Interroga
   `api.github.com` sull'ultima pubblicazione di questo progetto. Nessun account, nessun cookie,
   nulla di tuo viene inviato. Non scarica e non installa nulla: mostra quello che ha trovato e un
   collegamento.
2. **Controllo delle violazioni**, nella schermata di controllo, **che avvii tu**. La password non
   viene mai inviata. L'app calcola la sua impronta SHA-1 e ne invia **solo i primi cinque
   caratteri** a Have I Been Pwned, che risponde con tutte le impronte che cominciano così — decine
   di migliaia. Il confronto avviene sul tuo telefono. È il modello di k-anonimato che quel servizio
   pubblica.

**Cosa rivelano comunque queste due chiamate**, ed è meglio dirlo: chi vede la tua connessione — il
tuo operatore, GitHub, Have I Been Pwned — viene a sapere che qualcuno al tuo indirizzo usa questa
app. Non viene a sapere nulla della tua cassaforte. Se la cosa ti importa: entrambe si fermano del
tutto quando l'app è camuffata da calcolatrice (§7), e il controllo delle violazioni non parte mai
finché non lo premi.

L'app rifiuta l'HTTP in chiaro e rifiuta le autorità di certificazione aggiunte al telefono da
qualcun altro.

## 6. Il controllo del dominio e il permesso Android che richiede

Se attivi **«Verificare il dominio prima di copiare»**, l'app usa un servizio di accessibilità di
Android. È la cosa più invadente che chieda, quindi ecco esattamente che cosa ne fa.

- È **spento all'installazione** e non compare nemmeno nell'elenco di accessibilità di Android
  finché non lo chiedi.
- Riceve eventi **solo dai browser che conosce** — Chrome, Firefox e le sue versioni beta, Brave,
  Edge, Opera, Vivaldi, Samsung Internet, DuckDuckGo. Android non gli manda nulla da nessun'altra
  app: né dalla tua app bancaria, né dai tuoi messaggi, né dalla tastiera.
- Da quei browser legge **la barra degli indirizzi e nient'altro** — il nome del sito, non il
  percorso, non i parametri, non una parola della pagina.
- Quel nome resta **solo in memoria**, uno alla volta, sostituito a ogni lettura e dimenticato dopo
  quindici secondi. Non viene mai scritto su disco e mai inviato da nessuna parte.
- Spegnere l'impostazione **ritira il permesso**: il servizio esce dall'elenco di Android, e
  riaccenderlo te lo richiederà.

## 7. Modalità panico

Se la usi, l'app si blocca, svuota gli appunti, disarma l'impronta, ritira il servizio di
accessibilità e sostituisce il proprio nome e la propria icona sulla schermata iniziale con una
calcolatrice funzionante. **Nulla viene cancellato**: la tua password principale apre ancora la
cassaforte. Finché è camuffata, l'app non fa alcuna chiamata di rete.

## 8. Accesso erede

Se lo configuri, una persona che scegli tu potrà aprire un'**istantanea in sola lettura** della tua
cassaforte dopo un silenzio abbastanza lungo dalla tua parte — 90 giorni per impostazione
predefinita, più 7 giorni di tolleranza, e ogni apertura fa ripartire il conteggio. L'istantanea è
cifrata con una frase di accesso tutta sua, che consegni tu. Non lascia mai il telefono, non c'è né
cloud né terzi, e noi non c'entriamo.

## 9. Permessi Android

Misurati sull'APK pubblicato, non sul codice sorgente:

| Permesso | Perché |
| --- | --- |
| `INTERNET` | Le due chiamate del §5. |
| `USE_BIOMETRIC`, `USE_FINGERPRINT` | Lo sblocco con l'impronta, se lo attivi. |
| `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Aggiunto da una libreria Android; permette all'app di parlare a se stessa, e nient'altro. |

Il servizio di accessibilità del §6 **non** è un permesso di questo elenco: Android lo concede a
parte, dalle proprie impostazioni, e puoi ritirarlo lì quando vuoi.

Non c'è alcun permesso per la fotocamera, né per i contatti, né per la posizione, né per
l'archiviazione: Pass Tech chiede un file al sistema quando esporti o importi, e il sistema le
consegna quel file.

## 10. Bambini

L'app non si rivolge ai bambini e non contiene pubblicità, profilazione né alcun meccanismo
comportamentale di alcun tipo.

## 11. Modifiche a questo documento

È pubblicato con l'app e con il suo codice sorgente. Una modifica esce in una versione; la data in
alto dice quale.

## 12. Contatto

contact@files-tech.com
