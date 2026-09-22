package com.filestech.pass_tech.core.password

import java.security.SecureRandom
import kotlin.math.ln

/**
 * Passphrases of common French words (2.7.1, `lib/services/diceware_fr.dart`): the same list, in the
 * same order, duplicates dropped as 2.7.1 drops them, so the entropy shown is the same.
 *
 * 471 words once deduplicated, ASCII lower case without accents: about 8.88 bits a word. 2.7.1's own
 * comment announced 512 words and 9 bits; the count is what the code actually draws from. French only,
 * as in 2.7.1: other languages are an open question (conversion plan, section 4).
 */
object Diceware {

    /** 2.7.1 appends a number from 10 to 99: 90 values. */
    private const val NUMBER_FROM = 10
    private const val NUMBER_VALUES = 90

    val WORDS: List<String> = listOf(
        "chat", "chien", "loup", "renard", "ours", "tigre", "lion", "aigle", "corbeau", "hibou", "pigeon", "moineau",
        "cygne", "canard", "poule", "coq", "dinde", "vache", "cheval", "poney", "mouton", "agneau", "chevre", "porc",
        "lapin", "souris", "rat", "singe", "elephant", "girafe", "zebre", "panda", "koala", "dauphin", "baleine",
        "requin", "poulpe", "crabe", "homard", "huitre", "abeille", "frelon", "guepe", "papillon", "fourmi",
        "araignee", "scarabee", "grillon", "escargot", "limace", "truite", "sardine", "thon", "saumon", "carpe",
        "brochet", "anguille", "sole", "crevette", "moule", "arbre", "foret", "vallee", "colline", "montagne", "mer",
        "plage", "riviere", "fleuve", "lac", "etang", "source", "cascade", "grotte", "volcan", "desert", "prairie",
        "jardin", "verger", "vigne", "champ", "marais", "dune", "falaise", "rocher", "sable", "pierre", "galet",
        "minerai", "cristal", "fleur", "rose", "lys", "tulipe", "marguerite", "violette", "iris", "jonquille",
        "muguet", "pavot", "chene", "hetre", "frene", "sapin", "pin", "cedre", "olivier", "platane", "peuplier",
        "saule", "pomme", "poire", "peche", "prune", "cerise", "fraise", "framboise", "myrtille", "raisin", "figue",
        "feuille", "branche", "racine", "ecorce", "seve", "bourgeon", "graine", "fruit", "pollen", "nectar",
        "soleil", "lune", "etoile", "nuage", "pluie", "neige", "vent", "orage", "foudre", "rosee", "brume", "givre",
        "glace", "arc", "aube", "soir", "nuit", "matin", "midi", "automne", "hiver", "ete", "printemps", "janvier",
        "avril", "juillet", "octobre", "samedi", "dimanche", "vendredi", "maison", "toit", "mur", "porte", "fenetre",
        "volet", "cheminee", "grenier", "cave", "garage", "chambre", "salon", "cuisine", "salle", "bureau", "balcon",
        "terrasse", "escalier", "couloir", "lit", "table", "chaise", "canape", "armoire", "etagere", "tiroir",
        "tapis", "rideau", "coussin", "lampe", "bougie", "horloge", "miroir", "tableau", "vase", "panier", "seau",
        "balai", "marteau", "clou", "tournevis", "clef", "serrure", "poignee", "levier", "interrupteur", "prise",
        "pile", "ampoule", "pain", "beurre", "fromage", "jambon", "oeuf", "lait", "miel", "farine", "sucre", "sel",
        "poivre", "huile", "vinaigre", "moutarde", "sauce", "soupe", "salade", "riz", "pates", "semoule", "gateau",
        "tarte", "crepe", "biscuit", "bonbon", "chocolat", "vanille", "citron", "orange", "banane", "melon", "radis",
        "tomate", "carotte", "poireau", "oignon", "ail", "persil", "basilic", "thym", "romarin", "laurier", "menthe",
        "sauge", "fenouil", "aneth", "cumin", "pantalon", "chemise", "veste", "manteau", "pull", "robe", "jupe",
        "foulard", "echarpe", "bonnet", "gant", "chaussure", "botte", "sandale", "chausson", "bijou", "bague",
        "collier", "montre", "ceinture", "main", "pied", "tete", "bras", "jambe", "dos", "epaule", "genou", "coude",
        "hanche", "visage", "front", "joue", "levre", "dent", "langue", "oreille", "nez", "cou", "menton", "livre",
        "cahier", "crayon", "stylo", "gomme", "regle", "encre", "papier", "carton", "enveloppe", "lettre", "timbre",
        "image", "photo", "carte", "plan", "globe", "boussole", "jumelle", "loupe", "sac", "valise", "coffre",
        "boite", "bocal", "bouteille", "verre", "assiette", "bol", "tasse", "cuillere", "fourchette", "couteau",
        "poele", "casserole", "marmite", "plat", "planche", "rape", "velo", "moto", "voiture", "bus", "train",
        "tram", "metro", "bateau", "barque", "voile", "avion", "navire", "ferry", "tracteur", "camion", "remorque",
        "char", "traineau", "luge", "patin", "boulanger", "jardinier", "couturier", "peintre", "musicien", "poete",
        "docteur", "infirmier", "plombier", "serveur", "libraire", "potier", "horloger", "meunier", "berger",
        "marin", "pilote", "soldat", "pompier", "facteur", "amour", "amitie", "joie", "espoir", "paix", "liberte",
        "bonheur", "douceur", "sagesse", "courage", "force", "vigueur", "patience", "calme", "silence", "musique",
        "melodie", "chanson", "danse", "poesie", "rouge", "jaune", "vert", "bleu", "indigo", "violet", "blanc",
        "noir", "gris", "marron", "or", "argent", "bronze", "cuivre", "fer", "acier", "bois", "soie", "laine",
        "coton", "lin", "tissu", "feutre", "cuir", "perle", "jade", "rubis", "vague", "ecume", "maree", "recif",
        "phare", "quai", "port", "jetee", "digue", "epave", "ciel", "nuee", "planete", "comete", "galaxie",
        "satellite", "fusee", "navette", "astre", "route", "sentier", "chemin", "pont", "tunnel", "rond", "virage",
        "carrefour", "place", "ruelle", "jouet", "bille", "toupie", "poupee", "ballon", "quille", "cerceau",
        "marionnette", "puzzle", "domino", "ville", "village", "hameau", "quartier", "region", "province", "pays",
        "frontiere", "continent", "ile",
    )

    fun entropyBits(words: Int, appendNumber: Boolean): Double =
        words * log2(WORDS.size) + if (appendNumber) log2(NUMBER_VALUES) else 0.0

    fun generate(words: Int, separator: String, appendNumber: Boolean, random: SecureRandom = SecureRandom()): String {
        val picked = List(words) { WORDS[random.nextInt(WORDS.size)] }
        val phrase = picked.joinToString(separator)
        return if (appendNumber) phrase + separator + (NUMBER_FROM + random.nextInt(NUMBER_VALUES)) else phrase
    }

    private fun log2(n: Int): Double = ln(n.toDouble()) / ln(2.0)
}
