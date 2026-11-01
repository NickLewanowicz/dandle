package app.dandle.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log2
import kotlin.random.Random

/**
 * Pins the create-account passphrase generator (brief 06 §4): word-style, lowercase + digit
 * suffix only, deterministic under a seeded Random, and ALWAYS at or above the brief's
 * 60-bit entropy floor — recomputed from the live wordlist so a future list edit that drops
 * below the floor fails here instead of shipping a weaker key.
 */
class PassphraseGeneratorTest {

    @Test
    fun `format is six lowercase words joined by dashes plus a two digit suffix`() {
        repeat(50) {
            val passphrase = PassphraseGenerator.generate()
            assertTrue(
                "unexpected shape: $passphrase",
                passphrase.matches(Regex("^[a-z]+(-[a-z]+){${PassphraseGenerator.WORD_COUNT - 1}}-[0-9]{2}$")),
            )
            val parts = passphrase.split("-")
            assertEquals("word count", PassphraseGenerator.WORD_COUNT + 1, parts.size)
            assertEquals("suffix is 2 digits", 2, parts.last().length)
            assertTrue("suffix in 00..99", parts.last().toInt() in 0..99)
        }
    }

    @Test
    fun `every word comes from the built in wordlist`() {
        val words = PassphraseGenerator.WORDS.toSet()
        repeat(50) {
            PassphraseGenerator.generate().split("-").dropLast(1).forEach { word ->
                assertTrue("word not in wordlist: $word", word in words)
            }
        }
    }

    @Test
    fun `charset is lowercase letters only, digits only in the suffix`() {
        repeat(100) {
            val passphrase = PassphraseGenerator.generate()
            assertTrue(
                "non-lowercase or stray digit: $passphrase",
                passphrase.matches(Regex("^[a-z0-9-]+$")) && !passphrase.dropLast(3).any { it.isDigit() },
            )
        }
    }

    @Test
    fun `wordlist is clean - unique lowercase words, no blanks`() {
        val words = PassphraseGenerator.WORDS
        assertTrue("wordlist should be compact-but-large, was ${words.size}", words.size in 384..1024)
        assertEquals(
            "duplicate words would silently reduce entropy: ${words.groupBy { it }.filterValues { it.size > 1 }.keys}",
            words.size,
            words.toSet().size,
        )
        assertTrue(words.all { it == it.lowercase() && it.length in 3..9 })
    }

    @Test
    fun `entropy stays at or above the 60 bit floor`() {
        // 6 independent picks from N words + a 2-digit suffix:
        // log2(N) * WORD_COUNT + log2(100) must clear the brief 06 floor.
        val bits = log2(PassphraseGenerator.WORDS.size.toDouble()) * PassphraseGenerator.WORD_COUNT +
            log2(100.0)
        assertTrue("entropy $bits bits is below the 60-bit floor", bits >= 60.0)
        assertEquals(
            "documented math (512-word list): 6*9 + 6.64",
            60.64,
            bits,
            0.01,
        )
    }

    @Test
    fun `seeded random is deterministic`() {
        val a = PassphraseGenerator.generate(Random(42))
        val b = PassphraseGenerator.generate(Random(42))
        assertEquals(a, b)
        assertNotEquals(a, PassphraseGenerator.generate(Random(43)))
    }

    @Test
    fun `runs are unique across a realistic sample`() {
        val passphrases = (1..100).map { PassphraseGenerator.generate() }.toSet()
        assertEquals("a collision in 100 runs suggests far too little entropy", 100, passphrases.size)
    }
}
