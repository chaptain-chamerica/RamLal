package com.likkho.app.storage

import com.likkho.app.storage.model.WordAnalysisStorageRecord
import com.likkho.app.storage.model.WordDefinitionStorageRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.AEADBadTagException

class WordAnalysisRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var vaultDir: File
    private lateinit var wordRepository: WordAnalysisRepository
    private val testDek = CryptoUtils.secureRandomBytes(32)

    @Before
    fun setUp() {
        vaultDir = tempFolder.newFolder("v1_words_test")
        wordRepository = WordAnalysisRepository(VaultId.PRIMARY, vaultDir)
    }

    @Test
    fun nonExistentFile_returnsEmptyList() {
        val words = wordRepository.loadWords(testDek)
        assertTrue(words.isEmpty())
    }

    @Test
    fun saveAndLoadWords_roundTrip() {
        val record1 = WordAnalysisStorageRecord(
            word = "Serendipity",
            analyzedAt = 12345L,
            phoneticTranscription = "/ˌsɛrənˈdɪpɪti/",
            syllableMetrics = "se-ren-dip-i-ty (5 syllables)",
            perfectRhymes = listOf("antipathy"),
            nearSlantRhymes = listOf("affinity", "vicinity"),
            definitions = listOf(
                WordDefinitionStorageRecord("noun", "The occurrence and development of events by chance in a happy or beneficial way.")
            ),
            synonyms = listOf("fluke", "chance", "happy coincidence"),
            antonyms = listOf("misfortune"),
            partOfSpeech = listOf("noun"),
            contextualSentimentPolarity = "Positive",
            emotionalValence = "+0.85"
        )

        wordRepository.saveWords(listOf(record1), testDek)
        assertTrue(wordRepository.wordsFile.exists())

        val loaded = wordRepository.loadWords(testDek)
        assertEquals(1, loaded.size)
        val loadedRecord = loaded[0]
        assertEquals("Serendipity", loadedRecord.word)
        assertEquals("/ˌsɛrənˈdɪpɪti/", loadedRecord.phoneticTranscription)
        assertEquals(1, loadedRecord.definitions.size)
        assertEquals("fluke", loadedRecord.synonyms[0])
    }

    @Test
    fun upsertWord_updatesExistingCaseInsensitive() {
        val item1 = WordAnalysisStorageRecord(word = "ephemeral", syllableMetrics = "e-phem-er-al")
        wordRepository.upsertWord(item1, testDek)

        // Upsert same word with uppercase "Ephemeral" and new data
        val item1Updated = WordAnalysisStorageRecord(
            word = "Ephemeral",
            syllableMetrics = "e-phem-er-al (4)",
            synonyms = listOf("transient", "fleeting")
        )
        wordRepository.upsertWord(item1Updated, testDek)

        val loaded = wordRepository.loadWords(testDek)
        assertEquals(1, loaded.size)
        assertEquals("Ephemeral", loaded[0].word)
        assertEquals("transient", loaded[0].synonyms[0])
    }

    @Test
    fun deleteWord_removesCaseInsensitive() {
        val item1 = WordAnalysisStorageRecord(word = "Melancholy")
        val item2 = WordAnalysisStorageRecord(word = "Euphoria")
        wordRepository.saveWords(listOf(item1, item2), testDek)

        wordRepository.deleteWord("melancholy", testDek)

        val loaded = wordRepository.loadWords(testDek)
        assertEquals(1, loaded.size)
        assertEquals("Euphoria", loaded[0].word)
    }

    @Test(expected = AEADBadTagException::class)
    fun wrongKey_failsDecryption() {
        val item = WordAnalysisStorageRecord(word = "Test")
        wordRepository.saveWords(listOf(item), testDek)

        val wrongDek = CryptoUtils.secureRandomBytes(32)
        wordRepository.loadWords(wrongDek)
    }

    @Test(expected = AEADBadTagException::class)
    fun tamperedFile_failsDecryption() {
        val item = WordAnalysisStorageRecord(word = "Test")
        wordRepository.saveWords(listOf(item), testDek)

        val bytes = wordRepository.wordsFile.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()
        wordRepository.wordsFile.writeBytes(bytes)

        wordRepository.loadWords(testDek)
    }
}
