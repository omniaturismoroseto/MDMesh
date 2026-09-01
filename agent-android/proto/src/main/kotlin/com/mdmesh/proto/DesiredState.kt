package com.mdmesh.proto

import kotlinx.serialization.Serializable

/**
 * Lo **stato desiderato** del dispositivo, consegnato ad ogni check-in.
 *
 * È il pezzo che mancava. Prima la risposta al check-in portava solo comandi:
 * qualunque cosa non fosse un comando esplicito — cioè tutta la configurazione
 * salvata in console — non lasciava mai il server. Un'impostazione cambiata e
 * salvata semplicemente non arrivava, e nessuno se ne accorgeva: la console
 * mostrava il valore salvato, il dispositivo ne aveva un altro.
 *
 * Un comando è un ordine dato una volta: se il dispositivo è spento in quel
 * momento, l'ordine si perde. Questo invece è una **descrizione di come deve
 * essere**, ripetuta ad ogni check-in: un dispositivo spento per tre giorni si
 * allinea da solo appena si riaccende, e uno a cui è stata cambiata
 * un'impostazione a mano viene rimesso a posto al giro successivo. Su una flotta
 * vera è la differenza fra un sistema che regge e uno che va controllato a mano
 * apparecchio per apparecchio.
 *
 * Tutto è facoltativo per scelta: un agente vecchio che non conosce questo
 * blocco lo ignora e continua a funzionare, un server vecchio non lo manda e
 * l'agente nuovo semplicemente non riconcilia. Nessuna delle due parti si rompe
 * se l'altra è indietro (vedi `proto/VERSIONING.md`).
 *
 * @property kiosk com'è voluto il kiosk; `null` significa **nessun kiosk**, e se
 *   il dispositivo ne ha uno attivo deve uscirne.
 * @property policies interruttori voluti, con le stesse chiavi che usa già
 *   `policy.apply` (`wifi`, `bluetooth`, `camera`, …). Una chiave **assente**
 *   vuol dire "non gestito", che è diverso da `false`: è la differenza fra
 *   "lascia com'è" e "spegnilo".
 * @property revision cresce ad ogni salvataggio della configurazione. Se
 *   coincide con quella già applicata, non c'è niente da fare. Non è
 *   un'ottimizzazione: senza, il kiosk verrebbe riapplicato ad ogni check-in e
 *   sullo schermo si vedrebbe.
 */
@Serializable
data class DesiredState(
    val kiosk: KioskApplyPayload? = null,
    val policies: Map<String, Boolean> = emptyMap(),
    val revision: Long = 0,
)
