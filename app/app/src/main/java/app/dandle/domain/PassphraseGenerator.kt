package app.dandle.domain

import kotlin.random.Random

/**
 * Generates the encryption passphrase offered on Dandle Cloud create-account (brief 06 §4):
 * 6 common lowercase words joined by "-" plus a 2-digit suffix, e.g.
 * `otter-lantern-meadow-sparrow-harbor-copper-41`.
 *
 * Entropy math (why this shape): each word is an independent uniform pick, so the passphrase
 * carries `log2(|WORDS|) * WORD_COUNT + log2(100)` bits =
 * `log2(512) * 6 + log2(100)` = `9 * 6 + 6.64` = **60.64 bits >= 60** (brief 06 floor).
 * `PassphraseGeneratorTest` recomputes this from the live list so a future wordlist edit
 * cannot silently drop below the floor.
 *
 * Deviation note (brief 06 strategy): the instruction sketch said "5 words from a ~128-256
 * word list", but that shape tops out at 5*log2(256)+log2(100) = 46.6 bits — under the same
 * instruction's own >= 60-bit mandate. The compact curated list was kept and the word count
 * raised to 6 (the brief allows 5-6 words); 512 entries is still a one-file, transcribable
 * wordlist of common short words. Word-style output is preferred for transcribability — the
 * paste-to-verify step (brief 06 §4) catches transcription mistakes before the account exists.
 */
object PassphraseGenerator {

    /** Words per passphrase ([WORDS] picks are independent, so duplicates are allowed). */
    const val WORD_COUNT = 6

    /**
     * The curated wordlist: common short lowercase words (3-9 letters), family-friendly,
     * no offensive tokens, no duplicates (pinned by [PassphraseGeneratorTest]). 512 entries.
     */
    val WORDS: List<String> = listOf(
        // Animals
        "badger", "beaver", "bison", "bunny", "camel", "dolphin", "donkey", "dragon",
        "ferret", "goat", "goose", "hedgehog", "horse", "kitten", "lamb", "lion",
        "mole", "mouse", "otter", "owl", "panda", "pony", "puma", "rabbit",
        "seal", "shark", "sheep", "snail", "sparrow", "squirrel", "tiger", "turtle",
        "wolf", "zebra", "eagle", "falcon", "heron", "lark", "robin", "raven",
        "wren", "kiwi", "moth", "beetle", "cricket", "firefly", "spider", "eel",
        "clam", "ant", "bee", "bug", "fin", "wing", "paw", "claw",
        "nest", "den", "perch", "burrow", "hive",
        // Plants
        "acorn", "aspen", "bamboo", "basil", "cedar", "clover", "daisy", "fern",
        "grove", "hazel", "ivy", "lotus", "maple", "moss", "nectar", "olive",
        "pine", "poppy", "reed", "rose", "thorn", "tulip", "violet", "willow",
        "branch", "petal", "pollen", "sprout", "root", "stem", "leaf",
        // Weather
        "breeze", "cloud", "comet", "dawn", "dew", "fog", "frost", "gale",
        "hail", "mist", "moon", "rain", "snow", "storm", "thunder", "drizzle",
        "sunset", "sunrise", "shadow", "shine", "twinkle", "ember", "lightning", "tide",
        "wind", "winter", "summer", "autumn", "spring", "season", "climate", "chill",
        // Water
        "brook", "canal", "creek", "delta", "fjord", "harbor", "island", "lagoon",
        "lake", "marsh", "ocean", "pond", "river", "reef", "stream", "shore",
        "wave", "well", "drop", "foam", "puddle", "ripple", "splash", "spray",
        "surf", "current", "drip", "soak", "rinse", "sail", "anchor",
        // Landscape
        "canyon", "cave", "cliff", "dune", "field", "forest", "glade", "hill",
        "jungle", "meadow", "mesa", "oasis", "peak", "plain", "valley", "ridge",
        "rock", "sand", "stone", "trail", "path", "lane", "road", "bridge",
        "gate", "fence", "prairie", "tundra", "volcano", "desert", "gorge", "ledge",
        // Home
        "attic", "barn", "cabin", "cellar", "chimney", "couch", "crib", "door",
        "frame", "garden", "hall", "porch", "roof", "room", "bed", "desk",
        "kitchen", "lamp", "rug", "shelf", "sofa", "stair", "table", "wall",
        "window", "yard", "balcony", "closet", "hallway", "nursery", "cushion", "blanket",
        // Kitchen
        "bowl", "cup", "fork", "jar", "kettle", "mug", "pan", "plate",
        "spoon", "apron", "basket", "bottle", "bucket", "napkin", "whisk", "lid",
        // Food
        "apple", "banana", "berry", "bread", "butter", "carrot", "cheese", "cherry",
        "corn", "grape", "honey", "lemon", "mango", "melon", "peach", "pear",
        "plum", "radish", "rice", "salt", "spice", "sugar", "toast", "vanilla",
        "walnut", "wheat", "yogurt", "cinnamon", "ginger", "mint", "pepper", "garlic", "onion",
        "soup", "stew", "pasta", "pizza", "salad", "pancake", "waffle", "noodle",
        "broth", "cookie", "muffin", "pretzel", "caramel", "fudge", "syrup", "pickle",
        // Clothes
        "boot", "button", "cap", "coat", "dress", "glove", "hat", "jacket",
        "jeans", "mitten", "scarf", "shirt", "shoe", "sock", "vest", "cape",
        "cotton", "denim", "fleece", "linen", "silk", "wool", "velvet", "ribbon",
        "sweater", "hoodie", "sandal", "slipper", "yarn", "quilt", "pillow", "shawl",
        // Body
        "arm", "back", "chin", "ear", "elbow", "foot", "hair", "hand",
        "knee", "neck", "nose", "shoulder", "toe", "wrist", "smile", "cheek",
        // Family and people
        "aunt", "baby", "brother", "cousin", "dad", "family", "friend", "grandma",
        "grandpa", "mama", "mom", "papa", "sister", "uncle", "neighbor", "pal",
        "artist", "baker", "builder", "chef", "clown", "dancer", "doctor", "farmer",
        "hero", "jester", "knight", "musician", "painter", "pilot", "poet", "singer", "sailor",
        // Feelings
        "brave", "cheerful", "cozy", "daring", "eager", "fond", "gentle", "happy",
        "jolly", "kind", "lively", "merry", "proud", "quiet", "shy", "glad",
        "bold", "mellow", "sleepy", "wonder", "giggle", "grin", "chuckle", "hope",
        "joy", "peace", "trust", "wish", "dream", "cheer", "bliss", "calm",
        // Motion
        "bounce", "climb", "dash", "drift", "float", "glide", "hop", "jump",
        "leap", "march", "pace", "roam", "rush", "stroll", "amble", "stride",
        "skip", "spin", "sway", "swing", "trot", "wander", "zoom", "scramble",
        "scurry", "shuffle", "swagger", "toddle", "wobble", "wiggle", "dart", "veer",
        // Sound
        "bell", "chime", "echo", "jingle", "melody", "rhythm", "song", "tempo",
        "tune", "chant", "whistle", "whisper", "murmur", "purr", "cuckoo", "hush",
        // Light
        "candle", "flame", "glow", "lantern", "spark", "torch", "beam", "gleam",
        "glimmer", "shade", "tint", "hue", "prism", "rainbow", "pigment", "beacon",
        // Colors and minerals
        "coral", "crimson", "gold", "indigo", "ivory", "jade", "lilac", "ruby",
        "scarlet", "teal", "copper", "silver", "bronze", "pearl", "opal", "quartz",
        // Time
        "hour", "minute", "clock", "noon", "midnight", "evening", "morning", "moment",
        "instant", "era", "epoch", "month", "week", "year", "dusk", "twilight",
        // Objects and tools
        "compass", "feather", "hammock", "kite", "marble", "puzzle", "riddle", "scissors",
        "telescope", "umbrella", "cradle", "locket", "pendant", "buckle", "clasp",
        "hammer", "wrench", "trowel", "harvest",
    )

    /**
     * Builds one passphrase: [WORD_COUNT] words picked with `random` (independent uniform
     * picks — duplicates allowed and counted in the entropy math) joined by "-", then the
     * 2-digit suffix "00".."99". Pass a seeded [Random] for deterministic tests.
     */
    fun generate(random: Random = Random.Default): String = buildString {
        repeat(WORD_COUNT) { index ->
            if (index > 0) append('-')
            append(WORDS[random.nextInt(WORDS.size)])
        }
        append('-')
        append(random.nextInt(100).toString().padStart(2, '0'))
    }
}
