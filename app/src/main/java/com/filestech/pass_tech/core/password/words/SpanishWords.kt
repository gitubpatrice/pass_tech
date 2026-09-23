package com.filestech.pass_tech.core.password.words

/**
 * The Spanish words a passphrase is drawn from.
 *
 * **No accent and no `ñ`**, as in the French list — but for two different reasons. An accent is
 * dropped the way `éléphant` is written `elephant` there: `limon`, `jamon`, `balcon` are still read
 * without a moment's thought. `ñ` is not an accent, it is another letter: drop it and `araña`,
 * `otoño`, `niño`, `viña` become `arana`, `otono`, `nino`, `vina`, which are nothing at all. Those
 * words were left out rather than written wrong, and a neighbour chosen instead.
 *
 * **`año` is the reason this list is read and not generated.** Without its tilde it is a word one
 * would not want drawn at random and shown on a screen. It is absent, and so is every word that
 * turns into another when its accent goes. Nothing is longer than nine letters.
 *
 * Themes follow the French list one for one, which keeps the lists comparable and keeps this one
 * from drifting towards a single subject.
 *
 * **Every word appears once.** `DicewareTest` refuses a repeat, an accent, a capital and anything
 * outside three to nine letters — not for tidiness, but because the entropy a screen shows is
 * `log2(size)` per word, and a list counted wrong is an entropy announced wrong.
 */
internal object SpanishWords {

    val LIST: List<String> = listOf(
        // Animales
        "gato", "perro", "lobo", "zorro", "oso", "tigre", "leon", "caballo", "poni", "vaca",
        "oveja", "cordero", "cabra", "cerdo", "conejo", "raton", "rata", "mono", "elefante",
        "jirafa", "cebra", "panda", "koala", "camello", "burro", "ciervo", "alce", "nutria",
        "tejon", "castor", "erizo", "lince", "foca", "morsa", "ardilla",
        // Aves
        "aguila", "cuervo", "paloma", "gorrion", "cisne", "pato", "gallina", "gallo", "pavo",
        "buho", "mirlo", "jilguero", "garza", "grulla", "halcon", "loro", "pinguino", "urraca",
        "cuco", "gaviota",
        // En el mar
        "delfin", "ballena", "tiburon", "pulpo", "cangrejo", "langosta", "ostra", "trucha",
        "sardina", "atun", "salmon", "carpa", "lucio", "anguila", "gamba", "mejillon", "medusa",
        "coral",
        // Insectos
        "abeja", "abejorro", "avispa", "mariposa", "hormiga", "grillo", "caracol", "polilla",
        "libelula", "oruga", "cigarra", "pulga", "mosca", "babosa",
        // Paisaje
        "arbol", "bosque", "valle", "colina", "monte", "mar", "playa", "rio", "arroyo", "lago",
        "charca", "fuente", "cascada", "cueva", "volcan", "desierto", "prado", "jardin", "huerto",
        "parra", "campo", "pantano", "duna", "risco", "roca", "arena", "piedra", "guijarro",
        "mineral", "cristal", "barranco", "glaciar", "isla", "costa", "puerto",
        // Flores y arboles
        "flor", "rosa", "lirio", "tulipan", "margarita", "violeta", "iris", "amapola", "trebol",
        "lavanda", "roble", "haya", "fresno", "abeto", "pino", "cedro", "olivo", "arce", "chopo",
        "sauce", "abedul", "olmo", "avellano", "nogal",
        // Frutas y plantas
        "manzana", "pera", "durazno", "ciruela", "cereza", "fresa", "mora", "arandano", "uva",
        "higo", "limon", "melon", "naranja", "platano", "hoja", "rama", "raiz", "corteza",
        "savia", "brote", "semilla", "fruto", "polen", "nectar", "musgo", "helecho", "junco",
        // Tiempo y cielo
        "sol", "luna", "estrella", "nube", "lluvia", "nieve", "viento", "tormenta", "trueno",
        "rocio", "niebla", "escarcha", "hielo", "arcoiris", "sombra", "cielo", "planeta", "cometa",
        "galaxia", "cohete", "sonda",
        // El tiempo que pasa
        "alba", "aurora", "mediodia", "tarde", "noche", "invierno", "verano", "primavera",
        "cosecha", "enero", "abril", "julio", "octubre", "lunes", "viernes", "domingo", "hora",
        "minuto", "semana", "mes", "siglo", "dia", "fiesta", "hoy", "ayer",
        // Casa
        "casa", "tejado", "muro", "puerta", "ventana", "persiana", "chimenea", "desvan", "sotano",
        "garaje", "cuarto", "cocina", "salon", "sala", "oficina", "balcon", "terraza", "escalera",
        "pasillo", "verja", "valla", "puente", "tunel",
        // Muebles y objetos
        "cama", "mesa", "silla", "sofa", "armario", "estante", "cajon", "alfombra", "cortina",
        "cojin", "lampara", "vela", "reloj", "espejo", "cuadro", "jarron", "cesta", "cubo",
        "escoba", "manta", "toalla", "botella", "jarra", "caja", "bolsa", "cuerda", "escala",
        // Herramientas
        "martillo", "clavo", "tornillo", "perno", "alicate", "cerradura", "manija", "palanca",
        "tecla", "enchufe", "pila", "bombilla", "sierra", "taladro", "cincel", "yunque", "pincel",
        "regla", "lima", "prensa",
        // Comida
        "pan", "manteca", "queso", "jamon", "huevo", "leche", "miel", "harina", "azucar", "sal",
        "pimienta", "arroz", "fideo", "sopa", "ensalada", "tarta", "galleta", "mermelada", "salsa",
        "aceite", "cebolla", "ajo", "zanahoria", "patata", "tomate", "judia", "guisante", "col",
        "calabaza", "seta", "almendra", "nuez", "turron",
        // Bebida y cocina
        "agua", "cafe", "sidra", "zumo", "cacao", "taza", "vaso", "plato", "cuenco", "cuchara",
        "tenedor", "cuchillo", "olla", "sarten", "horno", "nevera", "mantel", "bandeja",
        // Ropa
        "camisa", "pantalon", "vestido", "falda", "abrigo", "chaqueta", "jersey", "bufanda",
        "guante", "sombrero", "gorra", "zapato", "bota", "calcetin", "cinturon", "boton",
        "bolsillo", "solapa", "manga", "delantal",
        // Cuerpo
        "mano", "dedo", "pulgar", "brazo", "codo", "hombro", "cabeza", "pelo", "ojo", "oreja",
        "nariz", "boca", "diente", "lengua", "cuello", "espalda", "rodilla", "pie", "talon",
        "corazon",
        // Personas
        "padre", "madre", "hermana", "hermano", "tio", "tia", "primo", "amigo", "vecino", "chico",
        "invitado", "maestro", "medico", "panadero", "carnicero", "granjero", "pescador",
        "marinero", "piloto", "chofer", "pintor", "cantante", "bailarin", "poeta", "florista",
        "obrero", "molinero", "sastre", "alfarero", "herrero", "tejedor", "pastor",
        // Musica y arte
        "musica", "cancion", "tambor", "flauta", "guitarra", "violin", "piano", "trompeta", "arpa",
        "campana", "coro", "danza", "novela", "poema", "teatro", "cine", "museo", "estatua",
        "lienzo", "color", "lapiz", "tiza", "tinta", "papel",
        // Escuela y trabajo
        "escuela", "clase", "leccion", "libro", "pagina", "carta", "numero", "respuesta",
        "pregunta", "mercado", "tienda", "banco", "dinero", "moneda", "billete", "paquete",
        "recibo",
        // Calle
        "calle", "senda", "avenida", "plaza", "esquina", "cruce", "coche", "autobus", "tren",
        "tranvia", "bici", "barca", "barco", "ferri", "avion", "timon", "ancla", "rueda", "motor",
        "faro", "muelle", "dique", "pecio",
        // Ciudad y lugares
        "ciudad", "pueblo", "aldea", "barrio", "region", "provincia", "pais", "frontera", "nacion",
        "castillo", "torre", "iglesia", "palacio", "parque", "abadia",
        // Juego
        "juguete", "canica", "peonza", "titere", "pelota", "globo", "aro", "puzle", "domino",
        "dado", "ajedrez", "enigma", "juego", "premio",
    )
}
