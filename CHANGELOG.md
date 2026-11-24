# Changelog

## 0.4.1 - 2026-11-22

### English

- **What OpenWatch reads and writes.** A screen lists every Health Connect data type the app writes, under the device it comes from, every type it reads and why, and what the watch app asks for. It opens from Settings, the Health Connect setup and Health Connect's own permission page.
- **Notifications on the watch.** "Only apps I choose" sends only the apps you switch on. "Hide sensitive content", on by default, sends only the title where Android hides an app's content on the lock screen; a secret notification never reaches the watch.
- **Connected apps names the app.** Each card shows the package name and signing key, and warns when an app takes another app's name.
- **A running workout on the watch face**, with its sport and a stopwatch, or Paused.
- **Two permissions fewer.** No more reading steps or writing wheelchair pushes, which nothing used.
- **A sync that fails says why**, in your language, and a missing permission or a paused sync waits for you instead of being retried. Several devices sync at once, and a scheduled sync continues in follow-up runs instead of being stopped partway.
- **Secrets stay on this phone.** The scale's key and the watch link tokens are kept encrypted under an Android Keystore key, and stay behind when the app moves to a new phone, as does the list of apps you allowed.
- **A second watch of the same kind** writes records of its own instead of overwriting the first's. Garmin wellness records now say a Garmin watch recorded them, and the scale reaches connected apps under a random id instead of its Bluetooth address.
- **Breathing rate on more nights**, from one clear reading when the second method finds none.
- **Languages.** The new screens and messages are translated into all thirteen languages.
- **Fixes:** a Garmin watch no longer stops syncing for one missing permission; a large first sync no longer fails in Health Connect; a Garmin workout waits on the phone while sync is paused instead of being written or lost; one damaged device entry no longer forgets every device; Garmin replies over 375 bytes go in parts, so a vivoactive 5 takes them; a deleted weigh-in no longer comes back; pairing failures and a failed find are shown in your language.

### Espanol

- **Que lee y escribe OpenWatch.** Una pantalla enumera cada tipo de dato de Health Connect que la app escribe, bajo el dispositivo del que viene, cada tipo que lee y para que, y lo que pide la app del reloj. Se abre desde Ajustes, la configuracion de Health Connect y la propia pagina de permisos de Health Connect.
- **Notificaciones en el reloj.** "Solo las apps que elija" envia solo las apps que actives. "Ocultar contenido sensible", activado por defecto, envia solo el titulo cuando Android oculta el contenido de una app en la pantalla de bloqueo; una notificacion secreta nunca llega al reloj.
- **Apps conectadas identifica la app.** Cada tarjeta muestra el nombre del paquete y la clave de firma, y avisa cuando una app usa el nombre de otra.
- **Un entrenamiento en curso en la esfera del reloj**, con su deporte y un cronometro, o En pausa.
- **Dos permisos menos.** Ya no se leen pasos ni se escriben impulsos de silla de ruedas, que nada usaba.
- **Una sincronizacion fallida dice por que**, en tu idioma, y un permiso que falta o una sincronizacion en pausa esperan por ti en lugar de reintentarse. Varios dispositivos se sincronizan a la vez, y una sincronizacion programada continua en ejecuciones siguientes en lugar de quedarse a medias.
- **Los secretos se quedan en este telefono.** La clave de la bascula y los tokens de enlace de los relojes se guardan cifrados con una clave del Android Keystore, y no pasan a un telefono nuevo, igual que la lista de apps que permitiste.
- **Un segundo reloj del mismo tipo** escribe sus propios registros en lugar de sobrescribir los del primero. Los registros de bienestar de Garmin dicen ahora que los registro un reloj Garmin, y la bascula llega a las apps conectadas con un id aleatorio en lugar de su direccion Bluetooth.
- **Frecuencia respiratoria en mas noches**, con una lectura clara cuando el segundo metodo no encuentra ninguna.
- **Idiomas.** Las nuevas pantallas y mensajes estan traducidos a los trece idiomas.
- **Correcciones:** un reloj Garmin ya no deja de sincronizar por un permiso que falta; una primera sincronizacion grande ya no falla en Health Connect; un entrenamiento de Garmin espera en el telefono mientras la sincronizacion esta en pausa en lugar de escribirse o perderse; una entrada de dispositivo danada ya no hace olvidar todos los dispositivos; las respuestas a Garmin de mas de 375 bytes van por partes, asi que un vivoactive 5 las acepta; una pesada borrada ya no vuelve; los fallos de emparejamiento y una busqueda fallida se muestran en tu idioma.

### Deutsch

- **Was OpenWatch liest und schreibt.** Ein Bildschirm listet jeden Health-Connect-Datentyp, den die App schreibt, unter dem Geraet, von dem er kommt, jeden Typ, den sie liest, und wofuer, und was die Uhr-App anfragt. Er oeffnet sich aus den Einstellungen, der Health-Connect-Einrichtung und der Berechtigungsseite von Health Connect selbst.
- **Benachrichtigungen auf der Uhr.** "Nur ausgewaehlte Apps" sendet nur die Apps, die du einschaltest. "Sensible Inhalte ausblenden", standardmaessig an, sendet nur den Titel, wo Android die Inhalte einer App auf dem Sperrbildschirm ausblendet; eine geheime Benachrichtigung erreicht die Uhr nie.
- **Verbundene Apps nennt die App.** Jede Karte zeigt den Paketnamen und den Signaturschluessel und warnt, wenn eine App den Namen einer anderen App annimmt.
- **Ein laufendes Training auf dem Zifferblatt**, mit Sportart und Stoppuhr oder Pausiert.
- **Zwei Berechtigungen weniger.** Kein Lesen von Schritten und kein Schreiben von Rollstuhlschueben mehr, die nichts nutzte.
- **Ein fehlgeschlagener Sync sagt, warum**, in deiner Sprache, und eine fehlende Berechtigung oder ein pausierter Sync wartet auf dich, statt wiederholt zu werden. Mehrere Geraete synchronisieren gleichzeitig, und ein geplanter Sync laeuft in Folgelaeufen weiter, statt mittendrin gestoppt zu werden.
- **Geheimnisse bleiben auf diesem Telefon.** Der Schluessel der Waage und die Verbindungstoken der Uhren werden verschluesselt unter einem Schluessel des Android Keystore gespeichert und bleiben zurueck, wenn die App auf ein neues Telefon umzieht, ebenso die Liste der erlaubten Apps.
- **Eine zweite Uhr derselben Art** schreibt eigene Eintraege, statt die der ersten zu ueberschreiben. Garmin-Wellness-Eintraege sagen jetzt, dass eine Garmin-Uhr sie aufgezeichnet hat, und die Waage erscheint in verbundenen Apps unter einer zufaelligen ID statt ihrer Bluetooth-Adresse.
- **Atemfrequenz in mehr Naechten**, aus einer klaren Messung, wenn die zweite Methode keine findet.
- **Sprachen.** Die neuen Bildschirme und Meldungen sind in alle dreizehn Sprachen uebersetzt.
- **Korrekturen:** Eine Garmin-Uhr hoert wegen einer fehlenden Berechtigung nicht mehr auf zu synchronisieren; ein grosser erster Sync scheitert nicht mehr in Health Connect; ein Garmin-Training wartet bei pausiertem Sync auf dem Telefon, statt geschrieben zu werden oder verloren zu gehen; ein beschaedigter Geraeteeintrag laesst nicht mehr alle Geraete vergessen; Antworten an Garmin ueber 375 Bytes gehen in Teilen, sodass eine vivoactive 5 sie annimmt; eine geloeschte Wiegung kommt nicht mehr zurueck; Kopplungsfehler und eine fehlgeschlagene Suche werden in deiner Sprache angezeigt.

### Italiano

- **Cosa legge e scrive OpenWatch.** Una schermata elenca ogni tipo di dato di Health Connect che l'app scrive, sotto il dispositivo da cui proviene, ogni tipo che legge e a cosa serve, e cosa chiede l'app dell'orologio. Si apre dalle Impostazioni, dalla configurazione di Health Connect e dalla pagina dei permessi di Health Connect stessa.
- **Notifiche sull'orologio.** "Solo le app che scelgo" invia solo le app che attivi. "Nascondi contenuti sensibili", attivo per impostazione predefinita, invia solo il titolo dove Android nasconde il contenuto di un'app sulla schermata di blocco; una notifica segreta non arriva mai all'orologio.
- **App collegate identifica l'app.** Ogni scheda mostra il nome del pacchetto e la chiave di firma, e avvisa quando un'app usa il nome di un'altra.
- **Un allenamento in corso sul quadrante**, con lo sport e un cronometro, o In pausa.
- **Due permessi in meno.** Non si leggono piu i passi e non si scrivono piu le spinte in sedia a rotelle, che nulla usava.
- **Una sincronizzazione fallita dice perche**, nella tua lingua, e un permesso mancante o una sincronizzazione in pausa aspettano te invece di essere ritentati. Piu dispositivi si sincronizzano insieme, e una sincronizzazione pianificata continua in esecuzioni successive invece di fermarsi a meta.
- **I segreti restano su questo telefono.** La chiave della bilancia e i token di collegamento degli orologi sono conservati cifrati con una chiave dell'Android Keystore e restano indietro quando l'app passa a un nuovo telefono, come l'elenco delle app consentite.
- **Un secondo orologio dello stesso tipo** scrive record propri invece di sovrascrivere quelli del primo. I record di benessere Garmin ora dicono che li ha registrati un orologio Garmin, e la bilancia arriva alle app collegate con un id casuale invece del suo indirizzo Bluetooth.
- **Frequenza respiratoria in piu notti**, da una lettura chiara quando il secondo metodo non ne trova.
- **Lingue.** Le nuove schermate e i nuovi messaggi sono tradotti in tutte le tredici lingue.
- **Correzioni:** un orologio Garmin non smette piu di sincronizzarsi per un permesso mancante; una prima sincronizzazione grande non fallisce piu in Health Connect; un allenamento Garmin aspetta sul telefono mentre la sincronizzazione e in pausa invece di essere scritto o perso; una voce di dispositivo danneggiata non fa piu dimenticare tutti i dispositivi; le risposte a Garmin oltre i 375 byte vanno in parti, cosi un vivoactive 5 le accetta; una pesata eliminata non torna piu; gli errori di abbinamento e una ricerca fallita vengono mostrati nella tua lingua.

### Eesti

- **Mida OpenWatch loeb ja kirjutab.** Ekraan loetleb iga Health Connecti andmetuubi, mille rakendus kirjutab, selle seadme all, kust see tuleb, iga tuubi, mida see loeb, ja milleks, ning mida kellarakendus kusib. See avaneb Seadetest, Health Connecti seadistusest ja Health Connecti enda loade lehelt.
- **Teavitused kellas.** "Ainult minu valitud rakendused" saadab ainult sisse lulitatud rakendused. "Peida tundlik sisu", vaikimisi sees, saadab ainult pealkirja, kui Android peidab rakenduse sisu lukustuskuval; salajane teavitus ei joua kunagi kella.
- **Uhendatud rakendused nimetab rakenduse.** Iga kaart naitab paketi nime ja allkirjavotit ning hoiatab, kui rakendus votab teise rakenduse nime.
- **Kaimasolev treening kella sihverplaadil**, spordiala ja stopperiga voi Peatatud.
- **Kaks luba vahem.** Enam ei loeta samme ega kirjutata ratastooli tougeid, mida miski ei kasutanud.
- **Ebaonnestunud sunkroonimine utleb, miks**, sinu keeles, ning puuduv luba voi peatatud sunkroonimine ootab sind, selle asemel et uuesti proovida. Mitu seadet sunkroonivad korraga ja ajastatud sunkroonimine jatkub jargmistes kaivitustes, selle asemel et poole peal peatuda.
- **Saladused jaavad sellesse telefoni.** Kaalu votit ja kellade uhendusmarke hoitakse krupteeritult Android Keystore'i votmega ning need jaavad maha, kui rakendus kolib uude telefoni, nagu ka lubatud rakenduste loend.
- **Teine sama tuupi kell** kirjutab oma kirjed, selle asemel et esimese omad ule kirjutada. Garmini heaolukirjed utlevad nuud, et need salvestas Garmini kell, ja kaal jouab uhendatud rakendusteni juhusliku ID-ga, mitte oma Bluetoothi aadressiga.
- **Hingamissagedus rohkematel oodel**, uhest selgest lugemist, kui teine meetod ei leia uhtegi.
- **Keeled.** Uued ekraanid ja teated on tolgitud koigisse kolmeteistkumnesse keelde.
- **Parandused:** Garmini kell ei lopeta enam sunkroonimist uhe puuduva loa parast; suur esimene sunkroonimine ei ebaonnestu enam Health Connectis; Garmini treening ootab telefonis, kui sunkroonimine on peatatud, selle asemel et kirjutada voi kaduma minna; uks kahjustatud seadmekirje ei lase enam koiki seadmeid unustada; Garmini vastused ule 375 baidi lahevad osadena, nii et vivoactive 5 votab need vastu; kustutatud kaalumine ei tule enam tagasi; sidumisvead ja ebaonnestunud otsing naidatakse sinu keeles.

### Portugues

- **O que o OpenWatch le e escreve.** Um ecra lista cada tipo de dado do Health Connect que a app escreve, sob o dispositivo de onde vem, cada tipo que le e para que, e o que a app do relogio pede. Abre-se a partir das Definicoes, da configuracao do Health Connect e da propria pagina de permissoes do Health Connect.
- **Notificacoes no relogio.** "Apenas as apps que escolho" envia apenas as apps que ativar. "Ocultar conteudo sensivel", ativo por predefinicao, envia apenas o titulo quando o Android oculta o conteudo de uma app no ecra de bloqueio; uma notificacao secreta nunca chega ao relogio.
- **Apps ligadas identifica a app.** Cada cartao mostra o nome do pacote e a chave de assinatura, e avisa quando uma app usa o nome de outra.
- **Um treino em curso no mostrador do relogio**, com o desporto e um cronometro, ou Em pausa.
- **Duas permissoes a menos.** Ja nao se leem passos nem se escrevem impulsos de cadeira de rodas, que nada usava.
- **Uma sincronizacao falhada diz porque**, no seu idioma, e uma permissao em falta ou uma sincronizacao em pausa esperam por si em vez de serem repetidas. Varios dispositivos sincronizam ao mesmo tempo, e uma sincronizacao agendada continua em execucoes seguintes em vez de parar a meio.
- **Os segredos ficam neste telefone.** A chave da balanca e os tokens de ligacao dos relogios sao guardados cifrados com uma chave do Android Keystore e ficam para tras quando a app passa para um telefone novo, tal como a lista de apps que permitiu.
- **Um segundo relogio do mesmo tipo** escreve registos proprios em vez de substituir os do primeiro. Os registos de bem-estar da Garmin dizem agora que foram registados por um relogio Garmin, e a balanca chega as apps ligadas com um id aleatorio em vez do seu endereco Bluetooth.
- **Frequencia respiratoria em mais noites**, a partir de uma leitura clara quando o segundo metodo nao encontra nenhuma.
- **Idiomas.** Os novos ecras e mensagens estao traduzidos nos treze idiomas.
- **Correcoes:** um relogio Garmin ja nao deixa de sincronizar por uma permissao em falta; uma primeira sincronizacao grande ja nao falha no Health Connect; um treino Garmin espera no telefone enquanto a sincronizacao esta em pausa em vez de ser escrito ou perdido; uma entrada de dispositivo danificada ja nao faz esquecer todos os dispositivos; as respostas a Garmin acima de 375 bytes vao por partes, pelo que um vivoactive 5 as aceita; uma pesagem eliminada ja nao volta; as falhas de emparelhamento e uma procura falhada sao mostradas no seu idioma.

## 0.4.0 - 2026-11-15

### English

- **Today's dashboard on the watch.** The Wear OS app opens on today's steps and heart rate as the watch recorded them, with Start activity and Settings, where the status screen now lives.
- **Workouts recorded on the watch.** 18 sports with time, heart rate, distance, steps and climb, and pause, resume and stop. The phone writes each workout to Health Connect with its route, pauses and distance, and an active calories estimate from the heart rate, the distance or the sport, which needs a weight in the body profile.
- **Night measurements.** Heart rate variability from short windows of the raw PPG while you sleep, and a breathing rate from the same windows, written only when its switch is on. On a Samsung watch, SpO2 at most once an hour and the skin temperature through the night, each behind its own switch, off by default; the heart rate and HRV can also come from the Samsung Health Platform.
- **Naps, and nights without bedtime mode.** Sleep outside the main night is written as its own sleep session, and after ten still minutes with a falling heart rate the watch records every minute as in bedtime mode, until you wake or unlock the phone.
- **Power-save heart rate, and the watch's settings from the phone.** The heart rate every 10 seconds or, in power save, about every 10 minutes outside bedtime mode, and a Watch settings card on the phone that reads and changes the watch's settings.
- **One set of calculations with OpenVitals.** Both apps give the same maximum heart rate and calorie estimate from the same data. An imported workout without calories gets OpenVitals' estimate from the body profile, for every sport the Compendium covers, with no 70 kg assumed.
- **Adding a Wear OS watch needs Android's companion association.** A watch added before shows "Link watch" on its status card.
- **Connected apps is harder to trick.** A warning for an app that uses OpenVitals' name without its key, no touches through other apps' overlays, and no home screen card for an app that asks to connect.
- **Languages.** The phone app's screens are translated into all thirteen languages again. The watch app is in English and German.
- **Fixes:** Health Connect setup no longer asks for two permissions Health Connect does not define, which kept it from ever finishing.

### Espanol

- **El panel de hoy en el reloj.** La app de Wear OS se abre con los pasos y la frecuencia cardiaca de hoy tal como los registro el reloj, con Start activity y Settings, donde ahora esta la pantalla de estado.
- **Entrenamientos grabados en el reloj.** 18 deportes con tiempo, frecuencia cardiaca, distancia, pasos y desnivel, y pausa, reanudar y detener. El telefono escribe cada entrenamiento en Health Connect con su ruta, sus pausas y su distancia, y una estimacion de calorias activas a partir de la frecuencia cardiaca, la distancia o el deporte, que necesita un peso en el Perfil corporal.
- **Mediciones nocturnas.** Variabilidad de la frecuencia cardiaca a partir de ventanas cortas del PPG en bruto mientras duermes, y una frecuencia respiratoria de esas mismas ventanas, que solo se escribe con su interruptor activado. En un reloj Samsung, SpO2 como mucho una vez por hora y la temperatura de la piel durante la noche, cada una con su propio interruptor, desactivado por defecto; la frecuencia cardiaca y la VFC tambien pueden venir de la Samsung Health Platform.
- **Siestas, y noches sin el modo hora de dormir.** El sueno fuera de la noche principal se escribe como una sesion de sueno propia, y tras diez minutos quieto con la frecuencia cardiaca bajando, el reloj registra cada minuto como en el modo hora de dormir, hasta que te despiertas o desbloqueas el telefono.
- **Frecuencia cardiaca en ahorro, y los ajustes del reloj desde el telefono.** La frecuencia cardiaca cada 10 segundos o, en ahorro, aproximadamente cada 10 minutos fuera del modo hora de dormir, y una tarjeta Ajustes del reloj en el telefono que lee y cambia los ajustes del reloj.
- **Los mismos calculos que OpenVitals.** Las dos apps dan la misma frecuencia cardiaca maxima y la misma estimacion de calorias a partir de los mismos datos. Un entrenamiento importado sin calorias recibe la estimacion de OpenVitals a partir del Perfil corporal, para cada deporte que cubre el Compendium, sin suponer 70 kg.
- **Anadir un reloj Wear OS necesita la asociacion de Android.** Un reloj anadido antes muestra "Vincular reloj" en su tarjeta de estado.
- **Apps conectadas es mas dificil de enganar.** Un aviso para una app que usa el nombre de OpenVitals sin su clave, ningun toque a traves de las superposiciones de otras apps y ninguna tarjeta en la pantalla de inicio para una app que pide conectarse.
- **Idiomas.** Las pantallas de la app del telefono vuelven a estar traducidas a los trece idiomas. La app del reloj esta en ingles y aleman.
- **Correcciones:** la configuracion de Health Connect ya no pide dos permisos que Health Connect no define, lo que le impedia terminar.

### Deutsch

- **Das heutige Dashboard auf der Uhr.** Die Wear-OS-App oeffnet sich mit den heutigen Schritten und der Herzfrequenz, wie die Uhr sie aufgezeichnet hat, mit Training starten und Einstellungen, wo jetzt der Statusbildschirm liegt.
- **Auf der Uhr aufgezeichnete Trainings.** 18 Sportarten mit Zeit, Herzfrequenz, Distanz, Schritten und Anstieg sowie Pause, Fortsetzen und Stopp. Das Telefon schreibt jedes Training mit Route, Pausen und Distanz in Health Connect, dazu eine Schaetzung der aktiven Kalorien aus der Herzfrequenz, der Distanz oder der Sportart, die ein Gewicht im Koerperprofil braucht.
- **Messungen in der Nacht.** Herzfrequenzvariabilitaet aus kurzen Fenstern des rohen PPG, waehrend du schlaefst, und eine Atemfrequenz aus denselben Fenstern, die nur geschrieben wird, wenn ihr Schalter an ist. Auf einer Samsung-Uhr SpO2 hoechstens einmal pro Stunde und die Hauttemperatur die Nacht ueber, jeweils mit eigenem Schalter, standardmaessig aus; Herzfrequenz und HRV koennen auch von der Samsung Health Platform kommen.
- **Nickerchen, und Naechte ohne Schlafenszeitmodus.** Schlaf ausserhalb der Hauptnacht wird als eigene Schlafsitzung geschrieben, und nach zehn ruhigen Minuten mit sinkender Herzfrequenz zeichnet die Uhr jede Minute auf wie im Schlafenszeitmodus, bis du aufwachst oder das Telefon entsperrst.
- **Puls im Sparmodus, und die Einstellungen der Uhr vom Telefon aus.** Die Herzfrequenz alle 10 Sekunden oder im Sparmodus etwa alle 10 Minuten ausserhalb des Schlafenszeitmodus, und eine Karte Uhr-Einstellungen auf dem Telefon, die die Einstellungen der Uhr liest und aendert.
- **Dieselben Berechnungen wie OpenVitals.** Beide Apps liefern aus denselben Daten dieselbe maximale Herzfrequenz und dieselbe Kalorienschaetzung. Ein importiertes Training ohne Kalorien erhaelt die Schaetzung von OpenVitals aus dem Koerperprofil, fuer jede Sportart, die das Compendium abdeckt, ohne angenommene 70 kg.
- **Eine Wear-OS-Uhr hinzuzufuegen braucht die Android-Verknuepfung.** Eine frueher hinzugefuegte Uhr zeigt "Uhr verknuepfen" auf ihrer Statuskarte.
- **Verbundene Apps ist schwerer zu taeuschen.** Eine Warnung fuer eine App, die den Namen von OpenVitals ohne dessen Schluessel nutzt, keine Beruehrungen durch Overlays anderer Apps und keine Karte auf dem Startbildschirm fuer eine App, die sich verbinden moechte.
- **Sprachen.** Die Bildschirme der Telefon-App sind wieder in alle dreizehn Sprachen uebersetzt. Die Uhr-App gibt es auf Englisch und Deutsch.
- **Korrekturen:** Die Health-Connect-Einrichtung fragt nicht mehr nach zwei Berechtigungen, die Health Connect nicht definiert und durch die sie nie fertig wurde.

### Italiano

- **La dashboard di oggi sull'orologio.** L'app Wear OS si apre sui passi e sulla frequenza cardiaca di oggi come li ha registrati l'orologio, con Start activity e Settings, dove ora si trova la schermata di stato.
- **Allenamenti registrati sull'orologio.** 18 sport con tempo, frequenza cardiaca, distanza, passi e dislivello, e pausa, riprendi e stop. Il telefono scrive ogni allenamento in Health Connect con il suo percorso, le pause e la distanza, e una stima delle calorie attive dalla frequenza cardiaca, dalla distanza o dallo sport, che richiede un peso nel Profilo corporeo.
- **Misurazioni notturne.** Variabilita della frequenza cardiaca da brevi finestre del PPG grezzo mentre dormi, e una frequenza respiratoria dalle stesse finestre, scritta solo quando il suo interruttore e attivo. Su un orologio Samsung, SpO2 al massimo una volta all'ora e la temperatura cutanea durante la notte, ciascuna con il proprio interruttore, disattivato per impostazione predefinita; la frequenza cardiaca e l'HRV possono venire anche dalla Samsung Health Platform.
- **Pisolini, e notti senza modalita notte.** Il sonno fuori dalla notte principale viene scritto come sessione di sonno a se, e dopo dieci minuti fermi con la frequenza cardiaca in calo l'orologio registra ogni minuto come in modalita notte, finche ti svegli o sblocchi il telefono.
- **Frequenza cardiaca a risparmio, e le impostazioni dell'orologio dal telefono.** La frequenza cardiaca ogni 10 secondi o, a risparmio, circa ogni 10 minuti fuori dalla modalita notte, e una scheda Impostazioni dell'orologio sul telefono che legge e cambia le impostazioni dell'orologio.
- **Gli stessi calcoli di OpenVitals.** Le due app danno la stessa frequenza cardiaca massima e la stessa stima delle calorie dagli stessi dati. Un allenamento importato senza calorie riceve la stima di OpenVitals dal Profilo corporeo, per ogni sport coperto dal Compendium, senza supporre 70 kg.
- **Aggiungere un orologio Wear OS richiede l'associazione di Android.** Un orologio aggiunto prima mostra "Collega orologio" nella sua scheda di stato.
- **App collegate e piu difficile da ingannare.** Un avviso per un'app che usa il nome di OpenVitals senza la sua chiave, nessun tocco attraverso le sovrapposizioni di altre app e nessuna scheda nella schermata iniziale per un'app che chiede di collegarsi.
- **Lingue.** Le schermate dell'app del telefono sono di nuovo tradotte in tutte le tredici lingue. L'app dell'orologio e in inglese e tedesco.
- **Correzioni:** la configurazione di Health Connect non chiede piu due permessi che Health Connect non definisce, il che le impediva di terminare.

### Eesti

- **Tanane toolaud kellas.** Wear OS rakendus avaneb tanaste sammude ja pulsiga, nagu kell need salvestas, nuppudega Start activity ja Settings, kus on nuud olekuekraan.
- **Kellas salvestatud treeningud.** 18 spordiala aja, pulsi, vahemaa, sammude ja tousuga ning pausi, jatkamise ja peatamisega. Telefon kirjutab iga treeningu Health Connecti koos marsruudi, pauside ja vahemaaga ning aktiivsete kalorite hinnanguga pulsi, vahemaa voi spordiala pohjal, mis vajab Kehaprofiilis kaalu.
- **Oised mootmised.** Sudame loogisageduse varieeruvus toore PPG luhikestest akendest une ajal ja hingamissagedus samadest akendest, mis kirjutatakse ainult siis, kui selle luliti on sees. Samsungi kellal SpO2 kuni kord tunnis ja naha temperatuur kogu oo jooksul, kumbki oma lulitiga, vaikimisi valjas; pulss ja HRV voivad tulla ka Samsung Health Platformilt.
- **Uinakud ja ood ilma unereziimita.** Uni valjaspool pohiood kirjutatakse eraldi uneseansina, ja parast kumme liikumatut minutit langeva pulsiga salvestab kell iga minuti nagu unereziimis, kuni arkad voi avad telefoni.
- **Saastlik pulss ja kella seaded telefonist.** Pulss iga 10 sekundi jarel voi saastureziimis umbes iga 10 minuti jarel valjaspool unereziimi, ning telefonis kaart Kella seaded, mis loeb ja muudab kella seadeid.
- **Samad arvutused mis OpenVitalsis.** Mollemad rakendused annavad samadest andmetest sama maksimaalse pulsi ja sama kalorihinnangu. Imporditud treening ilma kaloriteta saab OpenVitalsi hinnangu Kehaprofiili pohjal iga spordiala jaoks, mida Compendium katab, ilma et eeldataks 70 kg.
- **Wear OS kella lisamine vajab Androidi seost.** Varem lisatud kell naitab oma olekukaardil "Seo kell".
- **Uhendatud rakendusi on raskem petta.** Hoiatus rakenduse kohta, mis kasutab OpenVitalsi nime ilma selle votmeta, puudutusi teiste rakenduste ulekatete kaudu ei arvestata ning avakuval pole kaarti rakenduse jaoks, mis soovib uhenduda.
- **Keeled.** Telefonirakenduse ekraanid on taas tolgitud koigisse kolmeteistkumnesse keelde. Kellarakendus on inglise ja saksa keeles.
- **Parandused:** Health Connecti seadistus ei kusi enam kahte luba, mida Health Connect ei maaratle ja mis ei lasknud sel kunagi loppeda.

### Portugues

- **O painel de hoje no relogio.** A app Wear OS abre com os passos e a frequencia cardiaca de hoje tal como o relogio os registou, com Start activity e Settings, onde agora esta o ecra de estado.
- **Treinos gravados no relogio.** 18 desportos com tempo, frequencia cardiaca, distancia, passos e subida, e pausa, retomar e parar. O telefone grava cada treino no Health Connect com a sua rota, as pausas e a distancia, e uma estimativa de calorias ativas a partir da frequencia cardiaca, da distancia ou do desporto, que precisa de um peso no Perfil corporal.
- **Medicoes noturnas.** Variabilidade da frequencia cardiaca a partir de janelas curtas do PPG em bruto enquanto dorme, e uma frequencia respiratoria das mesmas janelas, gravada apenas com o seu interruptor ligado. Num relogio Samsung, SpO2 no maximo uma vez por hora e a temperatura da pele durante a noite, cada uma com o seu interruptor, desligado por predefinicao; a frequencia cardiaca e a VFC tambem podem vir da Samsung Health Platform.
- **Sestas, e noites sem o modo hora de dormir.** O sono fora da noite principal e gravado como uma sessao de sono propria, e apos dez minutos parado com a frequencia cardiaca a descer, o relogio regista cada minuto como no modo hora de dormir, ate acordar ou desbloquear o telefone.
- **Frequencia cardiaca em economia, e as configuracoes do relogio a partir do telefone.** A frequencia cardiaca a cada 10 segundos ou, em economia, cerca de a cada 10 minutos fora do modo hora de dormir, e um cartao Configuracoes do relogio no telefone que le e altera as configuracoes do relogio.
- **Os mesmos calculos que o OpenVitals.** As duas apps dao a mesma frequencia cardiaca maxima e a mesma estimativa de calorias a partir dos mesmos dados. Um treino importado sem calorias recebe a estimativa do OpenVitals a partir do Perfil corporal, para cada desporto que o Compendium cobre, sem supor 70 kg.
- **Adicionar um relogio Wear OS precisa da associacao do Android.** Um relogio adicionado antes mostra "Associar relogio" no seu cartao de estado.
- **Apps ligadas e mais dificil de enganar.** Um aviso para uma app que usa o nome do OpenVitals sem a sua chave, nenhum toque atraves de sobreposicoes de outras apps e nenhum cartao no ecra inicial para uma app que pede para se ligar.
- **Idiomas.** Os ecras da app do telefone voltam a estar traduzidos nas treze linguas. A app do relogio esta em ingles e alemao.
- **Correcoes:** a configuracao do Health Connect ja nao pede duas permissoes que o Health Connect nao define, o que a impedia de terminar.

## 0.3.0 - 2026-11-10

### English

- **OpenVitals connects to OpenWatch.** Over OpenLink, a connection that never leaves the phone, the OpenVitals version after 2.16.0 shares the body profile with OpenWatch, shows your watches and scale with battery and last sync, starts a sync, and receives a watch's live heart rate while it records an activity. A silent notification is up while a watch streams for another app.
- **Connected apps.** Settings, Connected apps lists each app that asked to use OpenWatch, with Allow, Block and Forget and a switch for each thing it may do. An unknown app gets nothing until you answer; OpenVitals connects without a prompt. Each card says whether the app is in use now, when it last connected and what its recording is doing.
- **One body profile for both apps.** Birth year, sex, height and weight are kept the same in OpenWatch and OpenVitals, the newest change winning.
- **Health Connect setup lists what is missing.** The permissions not granted yet are named, with where background and history access are granted.
- **Privacy policy.** A new section on connected apps, a list of what each device writes that matches the records, and the publisher, MTU Hale, with hello@hale.ngo as contact.

### Espanol

- **OpenVitals se conecta con OpenWatch.** Mediante OpenLink, una conexion que nunca sale del telefono, la version de OpenVitals posterior a la 2.16.0 comparte el perfil corporal con OpenWatch, muestra tus relojes y la bascula con bateria y ultima sincronizacion, inicia una sincronizacion y recibe la frecuencia cardiaca en directo del reloj mientras graba una actividad. Una notificacion silenciosa permanece mientras un reloj transmite para otra app.
- **Connected apps.** Ajustes, Connected apps muestra cada app que pidio usar OpenWatch, con Allow, Block y Forget y un interruptor por cada cosa que puede hacer. Una app desconocida no recibe nada hasta que respondas; OpenVitals se conecta sin preguntar. Cada tarjeta dice si la app esta en uso ahora, cuando se conecto por ultima vez y que hace su grabacion.
- **Un perfil corporal para las dos apps.** Ano de nacimiento, sexo, altura y peso se mantienen iguales en OpenWatch y OpenVitals; gana el cambio mas reciente.
- **La configuracion de Health Connect indica lo que falta.** Los permisos aun sin conceder aparecen por su nombre, con el lugar donde se concede el acceso en segundo plano y al historial.
- **Politica de privacidad.** Una seccion nueva sobre las apps conectadas, una lista de lo que escribe cada dispositivo que coincide con los registros, y el editor, MTU Hale, con hello@hale.ngo como contacto.

### Deutsch

- **OpenVitals verbindet sich mit OpenWatch.** Ueber OpenLink, eine Verbindung, die das Telefon nie verlaesst, teilt die OpenVitals-Version nach 2.16.0 das Koerperprofil mit OpenWatch, zeigt Uhren und Waage mit Akku und letzter Synchronisierung, startet eine Synchronisierung und erhaelt die Live-Herzfrequenz der Uhr, waehrend sie eine Aktivitaet aufzeichnet. Eine stille Benachrichtigung bleibt sichtbar, solange eine Uhr fuer eine andere App uebertraegt.
- **Connected apps.** Einstellungen, Connected apps listet jede App, die OpenWatch nutzen moechte, mit Allow, Block und Forget und einem Schalter fuer alles, was sie darf. Eine unbekannte App erhaelt nichts, bis du antwortest; OpenVitals verbindet sich ohne Nachfrage. Jede Karte sagt, ob die App gerade in Verwendung ist, wann sie zuletzt verbunden war und was ihre Aufzeichnung tut.
- **Ein Koerperprofil fuer beide Apps.** Geburtsjahr, Geschlecht, Groesse und Gewicht bleiben in OpenWatch und OpenVitals gleich; die neueste Aenderung gewinnt.
- **Die Health-Connect-Einrichtung nennt, was fehlt.** Die noch nicht erteilten Berechtigungen werden benannt, mit dem Ort, an dem Hintergrund- und Verlaufszugriff erteilt werden.
- **Datenschutzerklaerung.** Ein neuer Abschnitt ueber verbundene Apps, eine Liste dessen, was jedes Geraet schreibt, passend zu den Eintraegen, und der Herausgeber, MTU Hale, mit hello@hale.ngo als Kontakt.

### Italiano

- **OpenVitals si collega a OpenWatch.** Tramite OpenLink, una connessione che non lascia mai il telefono, la versione di OpenVitals successiva alla 2.16.0 condivide il profilo corporeo con OpenWatch, mostra orologi e bilancia con batteria e ultima sincronizzazione, avvia una sincronizzazione e riceve la frequenza cardiaca in tempo reale dell'orologio mentre registra un'attivita. Una notifica silenziosa resta visibile mentre un orologio trasmette per un'altra app.
- **Connected apps.** Impostazioni, Connected apps elenca ogni app che ha chiesto di usare OpenWatch, con Allow, Block e Forget e un interruttore per ogni cosa che puo fare. Un'app sconosciuta non riceve nulla finche non rispondi; OpenVitals si collega senza chiedere. Ogni scheda dice se l'app e in uso ora, quando si e collegata l'ultima volta e cosa fa la sua registrazione.
- **Un profilo corporeo per le due app.** Anno di nascita, sesso, altezza e peso restano uguali in OpenWatch e OpenVitals; vince la modifica piu recente.
- **La configurazione di Health Connect elenca cio che manca.** I permessi non ancora concessi sono indicati per nome, con il punto in cui si concedono l'accesso in background e alla cronologia.
- **Informativa sulla privacy.** Una nuova sezione sulle app collegate, un elenco di cio che scrive ogni dispositivo che corrisponde ai record, e l'editore, MTU Hale, con hello@hale.ngo come contatto.

### Eesti

- **OpenVitals uhendub OpenWatchiga.** OpenLinki kaudu, mis ei lahku kunagi telefonist, jagab OpenVitalsi versioon parast 2.16.0 OpenWatchiga kehaprofiili, naitab kelli ja kaalu koos aku ja viimase sunkroonimisega, kaivitab sunkroonimise ning saab tegevuse salvestamise ajal kella pulsi reaalajas. Vaikne teavitus pusib seni, kuni kell teise rakenduse jaoks andmeid saadab.
- **Connected apps.** Seaded, Connected apps loetleb iga rakenduse, mis kusis OpenWatchi kasutamist, nuppudega Allow, Block ja Forget ning lulitiga iga lubatava asja jaoks. Tundmatu rakendus ei saa midagi enne, kui vastad; OpenVitals uhendub kusimata. Iga kaart utleb, kas rakendus on praegu kasutusel, millal see viimati uhendus ja mida selle salvestus teeb.
- **Uks kehaprofiil molemale rakendusele.** Sunniaasta, sugu, pikkus ja kaal pusivad OpenWatchis ja OpenVitalsis samad; voidab uusim muudatus.
- **Health Connecti seadistus loetleb puuduva.** Veel andmata load on nimetatud koos kohaga, kus antakse taustal ja ajaloole juurdepaas.
- **Privaatsuspoliitika.** Uus jaotis uhendatud rakenduste kohta, kirjetega kokku langev loetelu sellest, mida iga seade kirjutab, ning valjaandja MTU Hale kontaktiga hello@hale.ngo.

### Portugues

- **O OpenVitals liga-se ao OpenWatch.** Atraves do OpenLink, uma ligacao que nunca sai do telemovel, a versao do OpenVitals posterior a 2.16.0 partilha o perfil corporal com o OpenWatch, mostra os relogios e a balanca com bateria e ultima sincronizacao, inicia uma sincronizacao e recebe a frequencia cardiaca em direto do relogio enquanto grava uma atividade. Uma notificacao silenciosa fica visivel enquanto um relogio transmite para outra app.
- **Connected apps.** Definicoes, Connected apps lista cada app que pediu para usar o OpenWatch, com Allow, Block e Forget e um interruptor para cada coisa que pode fazer. Uma app desconhecida nao recebe nada ate responderes; o OpenVitals liga-se sem perguntar. Cada cartao diz se a app esta em uso agora, quando se ligou pela ultima vez e o que a sua gravacao esta a fazer.
- **Um perfil corporal para as duas apps.** Ano de nascimento, sexo, altura e peso mantem-se iguais no OpenWatch e no OpenVitals; ganha a alteracao mais recente.
- **A configuracao do Health Connect lista o que falta.** As permissoes ainda nao concedidas sao indicadas pelo nome, com o local onde se concede o acesso em segundo plano e ao historico.
- **Politica de privacidade.** Uma nova seccao sobre as apps ligadas, uma lista do que cada dispositivo escreve que corresponde aos registos, e o editor, MTU Hale, com hello@hale.ngo como contacto.

## 0.1.1 - 2026-11-07

### English

- **First release.** The devices half of OpenVitals as its own app: Garmin watches over Garmin's own Bluetooth protocol, Wear OS watches through the OpenWatch watch app, and the Xiaomi S400 and S400 Pro scales, all written to Health Connect. No account, no cloud, no internet permission.
