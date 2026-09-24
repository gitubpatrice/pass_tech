package com.filestech.pass_tech.core.password.words

/**
 * The German words a passphrase is drawn from.
 *
 * **No umlaut and no capital**, though German writes its nouns with one: a passphrase is typed in a
 * hurry, often on a keyboard whose layout is not the owner's, and `ä ö ü ß` are exactly what goes
 * wrong there. They are written `ae oe ue ss`, or the word was left out. Nothing is longer than nine
 * letters.
 *
 * Themes follow the French list one for one, which keeps the lists comparable and keeps this one
 * from drifting towards a single subject.
 *
 * **Every word appears once.** `DicewareTest` refuses a repeat, an accent, a capital and anything
 * outside three to nine letters — not for tidiness, but because the entropy a screen shows is
 * `log2(size)` per word, and a list counted wrong is an entropy announced wrong.
 */
internal object GermanWords {

    val LIST: List<String> = listOf(
        // Tiere
        "katze", "hund", "wolf", "fuchs", "baer", "tiger", "loewe", "pferd", "pony", "kuh", "schaf",
        "lamm", "ziege", "schwein", "hase", "maus", "ratte", "affe", "elefant", "giraffe", "zebra",
        "panda", "koala", "kamel", "esel", "reh", "elch", "otter", "dachs", "biber", "eichhorn",
        "igel", "luchs", "robbe", "walross",
        // Voegel
        "adler", "rabe", "taube", "spatz", "schwan", "ente", "henne", "hahn", "pute", "eule",
        "drossel", "fink", "reiher", "storch", "falke", "papagei", "pinguin", "elster", "kuckuck",
        "moewe",
        // Im Wasser
        "delfin", "wal", "hai", "krake", "krabbe", "hummer", "auster", "forelle", "sardine", "lachs",
        "karpfen", "hecht", "aal", "garnele", "muschel", "seestern", "qualle", "koralle",
        // Insekten
        "biene", "hornisse", "wespe", "falter", "ameise", "spinne", "kaefer", "grille", "schnecke",
        "motte", "libelle", "raupe", "zikade", "floh",
        // Landschaft
        "baum", "wald", "tal", "huegel", "berg", "meer", "strand", "fluss", "bach", "see", "teich",
        "quelle", "kaskade", "hoehle", "vulkan", "wueste", "wiese", "garten", "obsthain", "weinberg",
        "feld", "sumpf", "duene", "klippe", "fels", "sand", "stein", "kiesel", "erz", "kristall",
        "schlucht", "gletscher", "insel", "kueste", "hafen",
        // Blumen und Baeume
        "blume", "rose", "lilie", "tulpe", "nelke", "veilchen", "iris", "mohn", "klee", "lavendel",
        "eiche", "buche", "esche", "tanne", "kiefer", "zeder", "olive", "ahorn", "pappel", "weide",
        "birke", "ulme", "hasel", "kastanie",
        // Fruechte und Pflanzen
        "apfel", "birne", "pfirsich", "pflaume", "kirsche", "beere", "traube", "feige", "zitrone",
        "melone", "aprikose", "banane", "blatt", "zweig", "wurzel", "rinde", "saft", "knospe",
        "samen", "frucht", "pollen", "nektar", "moos", "farn", "schilf",
        // Wetter und Himmel
        "sonne", "mond", "stern", "wolke", "regen", "schnee", "wind", "sturm", "donner", "tau",
        "nebel", "frost", "eis", "bogen", "schatten", "himmel", "planet", "komet", "galaxie",
        "rakete", "sonde",
        // Zeit
        "morgen", "mittag", "abend", "nacht", "herbst", "winter", "sommer", "fruehling", "januar",
        "april", "juli", "oktober", "montag", "freitag", "sonntag", "stunde", "minute", "woche",
        "monat", "jahr", "feiertag", "heute", "gestern",
        // Haus
        "haus", "dach", "wand", "tuer", "fenster", "laden", "kamin", "dachboden", "keller", "garage",
        "zimmer", "kueche", "flur", "buero", "balkon", "terrasse", "treppe", "gang", "tor", "zaun",
        "veranda", "bruecke", "tunnel",
        // Moebel und Dinge
        "bett", "tisch", "stuhl", "sofa", "schrank", "regal", "schublade", "teppich", "vorhang",
        "kissen", "lampe", "kerze", "uhr", "spiegel", "bild", "vase", "korb", "eimer", "besen",
        "decke", "handtuch", "flasche", "krug", "dose", "beutel", "seil", "leiter",
        // Werkzeug
        "hammer", "nagel", "schraube", "bolzen", "zange", "schloss", "griff", "hebel", "schalter",
        "stecker", "batterie", "leuchte", "saege", "bohrer", "meissel", "amboss", "pinsel", "lineal",
        "feile", "klemme",
        // Essen
        "brot", "butter", "kaese", "schinken", "milch", "honig", "mehl", "zucker", "salz", "pfeffer",
        "reis", "nudel", "suppe", "salat", "kuchen", "keks", "marmelade", "sosse", "zwiebel",
        "knoblauch", "moehre", "kartoffel", "tomate", "bohne", "erbse", "kohl", "kuerbis", "pilz",
        "mandel", "walnuss", "praline",
        // Trinken und Kueche
        "wasser", "kaffee", "tee", "most", "kakao", "tasse", "glas", "teller", "schuessel", "loeffel",
        "gabel", "messer", "kessel", "pfanne", "ofen", "kuehler", "serviette", "tablett",
        // Kleidung
        "hemd", "hose", "kleid", "rock", "mantel", "jacke", "pullover", "schal", "hut", "muetze",
        "schuh", "stiefel", "socke", "guertel", "knopf", "tasche", "kragen", "aermel", "schuerze",
        // Koerper
        "hand", "finger", "daumen", "arm", "ellbogen", "schulter", "kopf", "haar", "auge", "ohr",
        "nase", "mund", "zahn", "zunge", "hals", "ruecken", "knie", "fuss", "ferse", "herz",
        // Menschen
        "vater", "mutter", "schwester", "bruder", "onkel", "tante", "cousin", "freund", "nachbar",
        "kind", "gast", "lehrer", "arzt", "pfleger", "baecker", "metzger", "bauer", "fischer",
        "matrose", "pilot", "fahrer", "maler", "saenger", "taenzer", "dichter", "gaertner", "maurer",
        "mueller", "schneider", "toepfer", "schmied", "weber", "hirte",
        // Musik und Kunst
        "musik", "lied", "trommel", "floete", "gitarre", "geige", "klavier", "trompete", "harfe",
        "glocke", "chor", "tanz", "roman", "gedicht", "buehne", "kino", "museum", "statue",
        "leinwand", "farbe", "stift", "kreide", "tinte", "papier",
        // Schule und Arbeit
        "schule", "klasse", "lektion", "buch", "seite", "brief", "zahl", "antwort", "frage", "markt",
        "kaufmann", "bank", "geld", "muenze", "karte", "paket", "quittung",
        // Verkehr
        "strasse", "pfad", "weg", "gasse", "platz", "ecke", "kreuzung", "auto", "bus", "zug", "tram",
        "rad", "boot", "schiff", "faehre", "flugzeug", "segel", "anker", "motor", "steg", "kai",
        "mole", "wrack",
        // Stadt und Orte
        "stadt", "dorf", "weiler", "viertel", "gegend", "land", "grenze", "erdteil", "burg", "turm",
        "kirche", "palast", "allee", "park", "brunnen", "abtei",
        // Spiel
        "murmel", "kreisel", "puppe", "ball", "drachen", "reifen", "puzzle", "domino", "wuerfel",
        "schach", "raetsel", "spiel", "preis",
    )
}
