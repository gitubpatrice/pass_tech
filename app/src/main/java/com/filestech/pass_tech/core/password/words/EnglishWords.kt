package com.filestech.pass_tech.core.password.words

/**
 * The English words a passphrase is drawn from.
 *
 * **Everyday words, spelled the way they sound.** A passphrase is only worth its length if it can be
 * remembered and typed on a phone keyboard: nothing here is longer than nine letters, nothing needs
 * a mark above a letter, and nothing is a word one has to look up.
 *
 * Themes follow the French list one for one — animals, landscape, plants, weather, time, house,
 * tools, food, clothes, body, people, work, music, transport, city, play, places — which keeps the
 * lists comparable and keeps this one from drifting towards a single subject.
 *
 * **Every word appears once.** `DicewareTest` refuses a repeat, an accent, a capital and anything
 * outside three to nine letters — not for tidiness, but because the entropy a screen shows is
 * `log2(size)` per word, and a list counted wrong is an entropy announced wrong.
 */
internal object EnglishWords {

    val LIST: List<String> = listOf(
        // Animals
        "cat", "dog", "wolf", "fox", "bear", "tiger", "lion", "horse", "pony", "cow", "sheep", "lamb",
        "goat", "pig", "rabbit", "mouse", "rat", "monkey", "elephant", "giraffe", "zebra", "panda",
        "koala", "camel", "donkey", "deer", "moose", "otter", "badger", "beaver", "squirrel", "hedgehog",
        "bat", "seal", "walrus",
        // Birds
        "eagle", "raven", "pigeon", "sparrow", "swan", "duck", "hen", "rooster", "turkey", "owl",
        "robin", "finch", "heron", "stork", "falcon", "parrot", "penguin", "puffin", "magpie", "cuckoo",
        // Water life
        "dolphin", "whale", "shark", "octopus", "crab", "lobster", "oyster", "trout", "sardine", "tuna",
        "salmon", "carp", "pike", "eel", "shrimp", "mussel", "starfish", "jellyfish", "turtle", "coral",
        // Insects
        "bee", "hornet", "wasp", "butterfly", "ant", "spider", "beetle", "cricket", "snail", "slug",
        "moth", "dragonfly", "ladybird", "locust",
        // Landscape
        "tree", "forest", "valley", "hill", "mountain", "sea", "beach", "river", "stream", "lake",
        "pond", "spring", "waterfall", "cave", "volcano", "desert", "meadow", "garden", "orchard",
        "vineyard", "field", "marsh", "dune", "cliff", "rock", "sand", "stone", "pebble", "ore",
        "crystal", "canyon", "glacier", "island", "coast", "harbour",
        // Flowers and trees
        "flower", "rose", "lily", "tulip", "daisy", "violet", "iris", "poppy", "clover", "lavender",
        "oak", "beech", "ash", "fir", "pine", "cedar", "olive", "maple", "poplar", "willow", "birch",
        "elm", "hazel", "chestnut",
        // Fruits and plant parts
        "apple", "pear", "peach", "plum", "cherry", "berry", "grape", "fig", "lemon", "melon",
        "apricot", "banana", "leaf", "branch", "root", "bark", "sap", "bud", "seed", "fruit",
        "pollen", "nectar", "moss", "fern", "reed",
        // Weather and sky
        "sun", "moon", "star", "cloud", "rain", "snow", "wind", "storm", "thunder", "dew", "mist",
        "frost", "ice", "rainbow", "shadow", "sky", "planet", "comet", "galaxy", "rocket", "shuttle",
        // Time
        "dawn", "morning", "noon", "evening", "night", "autumn", "winter", "summer", "holiday",
        "january", "april", "july", "october", "monday", "friday", "sunday", "hour", "sunset",
        "week", "month", "year", "season", "today",
        // House
        "house", "roof", "wall", "door", "window", "shutter", "chimney", "attic", "cellar", "garage",
        "bedroom", "kitchen", "hall", "office", "balcony", "terrace", "stairs", "corridor", "gate",
        "fence", "porch", "bridge", "tunnel",
        // Furniture and objects
        "bed", "table", "chair", "sofa", "wardrobe", "shelf", "drawer", "carpet", "curtain", "cushion",
        "lamp", "candle", "clock", "mirror", "picture", "vase", "basket", "bucket", "broom", "pillow",
        "blanket", "towel", "bottle", "jar", "box", "bag", "rope", "ladder",
        // Tools
        "hammer", "nail", "screw", "bolt", "key", "lock", "handle", "lever", "switch", "socket",
        "battery", "bulb", "saw", "drill", "wrench", "chisel", "anvil", "pliers", "brush", "ruler",
        // Food
        "bread", "butter", "cheese", "ham", "egg", "milk", "honey", "flour", "sugar", "salt",
        "pepper", "rice", "pasta", "soup", "salad", "cake", "biscuit", "jam", "sauce", "oil",
        "onion", "garlic", "carrot", "potato", "tomato", "bean", "pea", "cabbage", "pumpkin",
        "mushroom", "almond", "walnut", "chocolate",
        // Drink and kitchen
        "water", "coffee", "tea", "juice", "cider", "cocoa", "cup", "glass", "plate", "bowl",
        "spoon", "fork", "knife", "kettle", "pan", "oven", "fridge", "napkin", "tray",
        // Clothes
        "shirt", "trousers", "dress", "skirt", "coat", "jacket", "sweater", "scarf", "glove", "hat",
        "cap", "shoe", "boot", "sock", "belt", "button", "pocket", "collar", "sleeve", "apron",
        // Body
        "hand", "finger", "thumb", "arm", "elbow", "shoulder", "head", "hair", "eye", "ear",
        "nose", "mouth", "tooth", "tongue", "neck", "back", "knee", "foot", "heel", "heart",
        // People
        "father", "mother", "sister", "brother", "uncle", "aunt", "cousin", "friend", "neighbour",
        "child", "baby", "guest", "teacher", "doctor", "nurse", "baker", "butcher", "farmer",
        "fisher", "sailor", "pilot", "driver", "painter", "singer", "dancer", "writer", "gardener",
        "builder", "miller", "tailor", "potter", "smith", "weaver", "shepherd",
        // Music and art
        "music", "song", "drum", "flute", "guitar", "violin", "piano", "trumpet", "harp", "bell",
        "choir", "dance", "story", "poem", "novel", "theatre", "cinema", "museum", "statue", "canvas",
        "paint", "pencil", "chalk", "ink", "paper",
        // School and work
        "school", "class", "lesson", "book", "page", "letter", "number", "answer", "question",
        "market", "shop", "grocer", "bank", "money", "coin", "ticket", "parcel", "receipt",
        // Transport
        "road", "path", "lane", "street", "square", "corner", "crossing", "car", "bus", "train",
        "tram", "bike", "boat", "ship", "ferry", "plane", "sail", "anchor", "wheel", "engine",
        "beacon", "quay", "pier", "wreck",
        // City and places
        "city", "town", "village", "hamlet", "district", "region", "province", "country", "border",
        "continent", "castle", "tower", "church", "palace", "avenue", "park", "fountain", "abbey",
        // Play
        "toy", "marble", "top", "doll", "ball", "kite", "hoop", "puzzle", "domino", "card",
        "chess", "riddle", "game", "prize",
    )
}
