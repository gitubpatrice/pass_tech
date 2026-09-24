package com.filestech.pass_tech.core.password.words

/**
 * The Italian words a passphrase is drawn from.
 *
 * **No accent**, as in the French list: `caffè` is written `caffe`, `città` is written `citta`. A
 * passphrase is typed in a hurry, often on a keyboard whose layout is not the owner's, and an accent
 * is exactly what goes wrong there. Nothing is longer than nine letters.
 *
 * Themes follow the French list one for one, which keeps the lists comparable and keeps this one
 * from drifting towards a single subject.
 *
 * **Every word appears once.** `DicewareTest` refuses a repeat, an accent, a capital and anything
 * outside three to nine letters — not for tidiness, but because the entropy a screen shows is
 * `log2(size)` per word, and a list counted wrong is an entropy announced wrong.
 */
internal object ItalianWords {

    val LIST: List<String> = listOf(
        // Animali
        "gatto", "cane", "lupo", "volpe", "orso", "tigre", "leone", "cavallo", "pony", "mucca",
        "pecora", "agnello", "capra", "maiale", "coniglio", "topo", "ratto", "scimmia", "elefante",
        "giraffa", "zebra", "panda", "koala", "cammello", "asino", "cervo", "alce", "lontra",
        "tasso", "castoro", "riccio", "lince", "foca", "tricheco", "ghiro",
        // Uccelli
        "aquila", "corvo", "piccione", "passero", "cigno", "anatra", "gallina", "gallo", "tacchino",
        "gufo", "tordo", "merlo", "airone", "cicogna", "falco", "pavone", "pinguino",
        "gazza", "cuculo", "gabbiano",
        // Nel mare
        "delfino", "balena", "squalo", "polpo", "granchio", "astice", "ostrica", "trota", "sardina",
        "tonno", "salmone", "carpa", "luccio", "anguilla", "gambero", "cozza", "medusa", "corallo",
        // Insetti
        "ape", "calabrone", "vespa", "farfalla", "formica", "ragno", "scarabeo", "grillo",
        "lumaca", "tarma", "libellula", "bruco", "cicala", "pulce",
        // Paesaggio
        "albero", "bosco", "valle", "collina", "monte", "mare", "spiaggia", "fiume", "ruscello",
        "lago", "stagno", "sorgente", "cascata", "grotta", "vulcano", "deserto", "prato", "giardino",
        "frutteto", "vigna", "campo", "palude", "duna", "scogliera", "roccia", "sabbia", "pietra",
        "ciottolo", "minerale", "cristallo", "gola", "ghiaccio", "isola", "costa", "porto",
        // Fiori e alberi
        "fiore", "rosa", "giglio", "tulipano", "mimosa", "viola", "iride", "papavero",
        "trifoglio", "lavanda", "quercia", "faggio", "frassino", "abete", "pino", "cedro", "ulivo",
        "acero", "pioppo", "salice", "betulla", "olmo", "nocciolo", "castagno",
        // Frutti e piante
        "mela", "pera", "pesca", "prugna", "ciliegia", "fragola", "lampone", "mirtillo", "uva",
        "fico", "limone", "melone", "albicocca", "banana", "foglia", "ramo", "radice", "corteccia",
        "linfa", "gemma", "seme", "frutto", "polline", "nettare", "muschio", "felce", "canna",
        // Tempo e cielo
        "sole", "luna", "stella", "nuvola", "pioggia", "neve", "vento", "tempesta", "tuono",
        "rugiada", "nebbia", "brina", "gelo", "raggio", "ombra", "cielo", "pianeta",
        "cometa", "galassia", "razzo", "sonda",
        // Tempo che passa
        "alba", "mattino", "mezzodi", "sera", "notte", "autunno", "inverno", "estate", "primavera",
        "gennaio", "aprile", "luglio", "ottobre", "lunedi", "venerdi", "domenica", "ora", "minuto",
        "settimana", "mese", "anno", "festa", "oggi", "ieri",
        // Casa
        "casa", "tetto", "muro", "porta", "finestra", "persiana", "camino", "soffitta", "cantina",
        "garage", "camera", "salotto", "cucina", "sala", "ufficio", "balcone", "terrazza", "scala",
        "corridoio", "cancello", "recinto", "ponte", "tunnel",
        // Mobili e oggetti
        "letto", "tavolo", "sedia", "divano", "armadio", "scaffale", "cassetto", "tappeto", "tenda",
        "cuscino", "lampada", "candela", "orologio", "specchio", "quadro", "vaso", "cesto",
        "secchio", "scopa", "coperta", "telo", "bottiglia", "brocca", "scatola", "borsa",
        "corda", "scaletta",
        // Attrezzi
        "martello", "chiodo", "vite", "bullone", "pinza", "serratura", "maniglia", "leva",
        "tasto", "presa", "pila", "lampadina", "sega", "trapano", "scalpello", "incudine",
        "pennello", "righello", "lima", "morsa",
        // Cibo
        "pane", "burro", "formaggio", "salame", "uovo", "latte", "miele", "farina", "zucchero",
        "sale", "pepe", "riso", "pasta", "zuppa", "insalata", "torta", "biscotto", "sciroppo",
        "salsa", "olio", "cipolla", "aglio", "carota", "patata", "pomodoro", "fagiolo", "pisello",
        "cavolo", "zucca", "fungo", "mandorla", "noce", "dolce",
        // Bere e cucina
        "acqua", "caffe", "succo", "sidro", "cacao", "tazza", "bicchiere", "piatto", "ciotola",
        "cucchiaio", "forchetta", "coltello", "pentola", "padella", "forno", "frigo", "tovaglia",
        "vassoio",
        // Vestiti
        "camicia", "pantalone", "abito", "gonna", "cappotto", "giacca", "maglione", "sciarpa",
        "guanto", "cappello", "berretto", "scarpa", "stivale", "calza", "cintura", "bottone",
        "tasca", "colletto", "manica", "grembiule",
        // Corpo
        "mano", "dito", "pollice", "braccio", "gomito", "spalla", "testa", "capello", "occhio",
        "orecchio", "naso", "bocca", "dente", "lingua", "collo", "schiena", "ginocchio", "piede",
        "tallone", "cuore",
        // Persone
        "padre", "madre", "sorella", "fratello", "zio", "zia", "cugino", "amico", "vicino",
        "bambino", "ospite", "maestro", "medico", "fornaio", "macellaio", "contadino",
        "pescatore", "marinaio", "pilota", "autista", "pittore", "cantante", "ballerino", "poeta",
        "fioraio", "muratore", "mugnaio", "sarto", "vasaio", "fabbro", "tessitore", "pastore",
        // Musica e arte
        "musica", "canzone", "tamburo", "flauto", "chitarra", "violino", "piano", "tromba", "arpa",
        "campana", "coro", "danza", "romanzo", "poesia", "teatro", "cinema", "museo", "statua",
        "tela", "colore", "matita", "gesso", "pennino", "carta",
        // Scuola e lavoro
        "scuola", "classe", "lezione", "libro", "pagina", "lettera", "numero", "risposta",
        "domanda", "mercato", "negozio", "banca", "denaro", "moneta", "biglietto", "pacco",
        "ricevuta",
        // Strada
        "strada", "sentiero", "viale", "piazza", "angolo", "incrocio", "auto", "autobus", "treno",
        "tram", "bici", "barca", "nave", "traghetto", "aereo", "vela", "ancora", "ruota", "motore",
        "faro", "molo", "banchina", "relitto",
        // Citta e luoghi
        "citta", "paese", "borgo", "frazione", "quartiere", "regione", "provincia", "confine",
        "nazione", "castello", "torre", "chiesa", "palazzo", "parco", "fontana", "abbazia",
        // Gioco
        "biglia", "trottola", "bambola", "palla", "aquilone", "cerchio", "puzzle", "domino", "dado",
        "scacchi", "enigma", "gioco", "premio",
    )
}
