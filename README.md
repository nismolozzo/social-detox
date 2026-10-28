# Social Detox: build dell'APK con GitHub Actions

## 1. Carica il progetto su GitHub (account personale)
1. Su github.com crea un repository nuovo (privato va bene), senza README.
2. Decomprimi lo zip e carica TUTTO il contenuto della cartella `socialdetox-project`
   (Add file -> Upload files, trascina cartelle e file).
3. Controlla che nel repository ci sia `.github/workflows/build.yml`.
   Se la cartella nascosta `.github` non e' stata caricata: Add file -> Create new file,
   scrivi come nome `.github/workflows/build.yml` e incolla il contenuto del file.
4. Il branch deve chiamarsi `main`.

## 2. Compila
- Ogni push su `main` parte da solo. Oppure: scheda Actions -> "Build APK" -> Run workflow.
- Attendi 3-5 minuti. Se il build e' verde, apri l'esecuzione e scarica
  `social-detox-apk` in fondo alla pagina (Artifacts): e' uno zip con `app-debug.apk`.
- Se il build e' rosso: apri il passo "Build debug APK" e copia l'errore.

## 3. Installa sul telefono
1. Porta `app-debug.apk` sul telefono (cavo, cloud, email).
2. Aprilo e consenti "Installa app sconosciute" per l'app da cui lo apri.
3. Apri Social Detox, scegli le ore stimate, consenti le notifiche.
4. Impostazioni -> Accessibilita' -> Social Detox -> attiva.
   Android 13+: se l'interruttore e' grigio, vai in Impostazioni -> App -> Social Detox ->
   menu a tre puntini -> "Consenti impostazioni con restrizioni", poi riprova.
5. Escludi l'app dall'ottimizzazione batteria (soprattutto Xiaomi e Samsung).

## 4. Test veloce
In `app/src/main/java/com/example/socialblocker/Store.kt` cambia:
- `TIMEOUT_MS = 60_000L`: la seconda barriera arriva dopo 1 minuto.
- `HOUR_MS = 60_000L`: "un'ora lontano dai social" dura 1 minuto.
Ricorda di rimettere 15 * 60 * 1000L e 3_600_000L alla fine.

## 5. Se il servizio "non funziona" o si spegne
- Dopo aver installato un APK nuovo sopra al vecchio, riattiva il servizio:
  Accessibilita' -> Social Detox -> spegni e riaccendi.
- Nella schermata principale dell'app c'e' la riga "Diagnostica": mostra l'ultimo evento
  ricevuto oppure l'ultimo ERRORE del servizio. Se vedi un errore, copialo.
- NON chiudere l'app trascinandola via dalle app recenti: su molti telefoni (Samsung, Xiaomi,
  Oppo, OnePlus...) e' come "Forza arresto" e Android spegne il servizio di accessibilita'.
  Blocca l'app nelle recenti (icona lucchetto / tieni premuto -> Blocca).
- Batteria: Impostazioni -> App -> Social Detox -> Batteria -> "Senza restrizioni".
  Xiaomi: attiva anche "Avvio automatico". Samsung: togli l'app da "App in stop".

## 6. Aggiornamenti senza disinstallare
Il progetto contiene `app/debug.keystore`, una chiave di debug fissa. Dalla build che la include
in poi, ogni nuovo APK si installa sopra il precedente. Solo la PRIMA volta devi disinstallare la
versione vecchia (era firmata con un'altra chiave). Dopo ogni aggiornamento riattiva il servizio in Accessibilita'.

## 7. Diagnostica nell'app
Nella schermata principale trovi: sessione in corso, numero di controlli, ULTIMO CRASH, ultimo errore e il
Registro delle ultime osservazioni (quale app il servizio ha visto in primo piano). Il pulsante
"Prova la schermata di conferma" mostra la schermata pesca senza aprire nessun social.

## 8. Social riaperto dalle recenti
Dopo "Rinuncio" l'app chiude il social in background (permesso KILL_BACKGROUND_PROCESSES), cosi'
riaprirlo dalle recenti e' un avvio da zero. In piu' il servizio controlla l'app in primo piano
ogni mezzo secondo, anche se il telefono non invia l'evento di cambio finestra.

## 9. Chiusura forzata del social dopo "Rinuncio"
Su "Rinuncio" (e "ESCO ORA") la schermata resta a schermo intero con "Chiudo il social...", mentre il
servizio apre per un attimo Impostazioni -> Info app del social e preme "Arresto forzato" + conferma,
poi torna alla home. Funziona cercando i testi dei pulsanti (italiano e inglese). Se non riesce entro
pochi secondi, torna alla home da solo e nel Registro compare "chiusura forzata NON riuscita".

## 10. "Blocco non attivo" anche se l'interruttore e' su ON
Succede quando l'interruttore e' acceso ma il telefono non ha avviato il servizio (tipico di Xiaomi/POCO e
simili dopo un aggiornamento). Spegni e riaccendi l'interruttore, poi nell'app premi "Batteria: nessuna
restrizione" e "Avvio automatico": abilita Social Detox. Blocca l'app nelle recenti. Se resta spento guarda
nell'app "ULTIMO CRASH" e il Registro (compare "processo avviato" ad ogni avvio).
