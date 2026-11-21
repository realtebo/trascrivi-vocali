# Trascrivi vocali in italiano

App Android per trascrivere localmente un vocale condiviso da WhatsApp. Application ID: `com.realtebo.trascrivi.vocali`.

## Primo avvio e uso

- Al primo avvio non è installato alcun modello e non c’è una scelta predefinita.
- La schermata iniziale è **Trascrivi**. Al primo avvio mostra **Benvenuto a bordo**, spiega che può ricevere vocali da altre app e trascriverli in testo senza inviare dati su Internet, quindi invita a scegliere il modello. Da lì puoi aprire anche la guida.
- Nella schermata **Scegli il modello**, scegli uno dei cinque profili. L’app mostra dimensione e qualità indicativa, scarica il modello scelto e ricorda la selezione. Il badge a destra mostra **SELECT**, l’animazione del download con una percentuale continua da 0 a 100 tra encoder e decoder, oppure **ACTIVE** per il profilo installato e in uso. Per avviare un cambio, tocca **SELECT** e conferma con un secondo tocco su **Confirm** entro 5 secondi; altrimenti torna a **SELECT**. A fine download il toast dice: «Download completato. Ora condividi un vocale con questa app».
- Poi, in WhatsApp, scegli **vocale → Condividi → Trascrivi vocali in italiano**. Le condivisioni successive vengono trascritte automaticamente con il modello ricordato, senza chiedere di sceglierlo ogni volta.
- Per cambiare modello, apri **Scegli il modello** da **Trascrivi**. Guida e selezione modello hanno un solo pulsante **Indietro**, color oro e collocato in fondo alla pagina. Il modello nuovo viene scaricato e quello precedente viene rimosso solo dopo che il nuovo download è verificato. Se torni più avanti al modello rimosso, dovrà essere scaricato di nuovo.
- Se l’app è aperta sul vocale ricevuto, cambiare modello lo trascrive nuovamente con la nuova scelta.
- Copia o condividi il testo ottenuto.
- Nella schermata del risultato trovi durata dell'audio, parole riconosciute e tempo di conversione.
- Se una conversione impiega più di 30 secondi, compare il solo pulsante **Interrompi** e il messaggio: «La conversione di questo vocale, per via della lunghezza e del modello di trascrizione, sta impiegando più tempo del previsto. Puoi interrompere, scegliere un profilo inferiore e riprovare.»

La prima installazione del modello e ogni cambio richiedono Internet. Il download resta nella schermata di selezione del modello e mostra lì lo stato; completato il download, la schermata resta aperta. Durante un cambio può esserci temporaneamente spazio per i due modelli; a regime l’app conserva un solo modello.

## Modelli

| Modello | Dimensione indicativa | Qualità indicativa nell’app |
|---|---:|---|
| Vosk `vosk-model-small-it-0.22` | 50 MB (47 MiB) | Scarsa; memoria di lavoro tipica dichiarata da Vosk circa 300 MB. Italiano. |
| Whisper `tiny` int8 | 104 MB | Molto bassa; multilingue, italiano incluso. Profilo Whisper più rapido. |
| Whisper `base` int8 | 160 MB (152 MiB) | Bassa; multilingue, italiano incluso. Più memoria e tempo di tiny. |
| Whisper `small` int8 | 375 MB | Media; multilingue, italiano incluso. Richiede più memoria e tempo. |
| Whisper `medium` int8 | circa 946 MB | Alta; multilingue, italiano incluso. Richiede molto più spazio, RAM e tempo. |

Le classi di qualità sono indicative, non un confronto diretto su vocali WhatsApp: l’accuratezza varia con voce, rumore e accento.

I file Whisper tiny (`65176e2`), base (`bb53ee204431c90d314c1cc08d28d23e5b7927cc`), small (`8f3c18b358db4d1f2fc1eae49d75cd20989e4309`) e medium (`5ce006117ee462612717129f32b0b23a88af0c96`) sono fissati alle rispettive revisioni dei repository Hugging Face; gli hash SHA-256 di encoder e decoder vengono verificati prima dell’installazione. L’archivio Vosk è verificato con SHA-256.

## Privacy e rete

- Il permesso Internet serve solo per scaricare il modello scelto via HTTPS.
- Il vocale viene letto dall’URI condiviso, decodificato con i codec Android e trascritto sul telefono. Audio e testo non vengono inviati in rete.
- Se il modello scelto è già presente, la trascrizione non richiede connessione.
- Non sono richiesti microfono, accesso generale ai file, account, VPN o servizio in background.

## Requisiti e compilazione

- Android SDK con piattaforma 36
- JDK 17
- Gradle 9.3.1 (wrapper incluso)

```sh
./gradlew assembleDebug
```

APK debug: `app/build/outputs/apk/debug/app-debug.apk`.

Il runtime sherpa-onnx Android 2.0.1 è incluso in `app/libs/`; i pesi Whisper non sono incorporati nell’APK. Il file `app/src/main/assets/vosk-model-small-it-0.22.zip` è un residuo del primo prototipo ed è escluso dal pacchetto Android: l’app scarica il modello selezionato on demand.

## Interfaccia

La schermata principale **Trascrivi** consente di aprire la guida e la selezione del modello; entrambe hanno un ritorno diretto a **Trascrivi**. Se il modello è installato e non è appena arrivato un vocale condiviso, mostra: «È tutto pronto. Ora condividi un messaggio vocale con questa app: verrà trascritto in testo.» Il risultato mostra durata audio, parole e tempo di conversione; i pulsanti **Copia testo** e **Condividi testo** sono affiancati sotto le metriche, con spazio tra loro e dal riquadro sopra. Il messaggio di completamento e quello di copia non vengono duplicati nella pagina. La schermata di scelta modello non ripete l’illustrazione spaziale e mostra lo stato di download solo durante il download. L’interfaccia usa un’estetica originale da plancia di esplorazione spaziale: tipografia Orbitron e Share Tech Mono, griglia stellare, cornici tecniche a taglio, marcatori di navigazione e pannelli blu notte con accenti oro. La grafica centrata della home mostra una luna crescente e onde sonore distese orizzontalmente che diventano il glifo pIqaD di *jatlh* (“parlare”), senza il cerchio decorativo a destra; l’icona dedicata resta invariata. Il font pIqaD è incluso nell’APK e la sua licenza è in app/src/main/res/raw/font_licenses.txt; le licenze complete sono raccolte anche in FONT-LICENSES.txt.

La decodifica e il riconoscimento girano in un processo Android separato dall’interfaccia. Questo permette al pulsante **Interrompi trascrizione** di arrestare anche una chiamata JNI sincrona di Whisper senza terminare l’app. La soglia per mostrare l’interruzione è 30 secondi dall’avvio del lavoro nel processo locale.
