package com.likkho.app.storage.model

import kotlinx.serialization.Serializable

@Serializable
data class WordDefinitionStorageRecord(
    val partOfSpeech: String,
    val definition: String,
    val example: String? = null
)

@Serializable
data class WordAnalysisStorageRecord(
    val word: String,
    val analyzedAt: Long = System.currentTimeMillis(),
    // 1 Phonetics
    val phoneticTranscription: String = "",
    val syllableMetrics: String = "",
    val perfectRhymes: List<String> = emptyList(),
    val nearSlantRhymes: List<String> = emptyList(),
    val meterAndRhythmScore: String = "",
    // 2 Lexical
    val definitions: List<WordDefinitionStorageRecord> = emptyList(),
    val synonyms: List<String> = emptyList(),
    val antonyms: List<String> = emptyList(),
    val hypernyms: List<String> = emptyList(),
    val hyponyms: List<String> = emptyList(),
    val collocations: List<String> = emptyList(),
    // 3 Morphology
    val partOfSpeech: List<String> = emptyList(),
    val lemmatizationAndRoots: String = "",
    val affixBreakdown: String = "",
    val etymologyAndOrigin: String = "",
    val grammaticalDependency: String = "",
    // 4 Pragmatics
    val contextualSentimentPolarity: String = "",
    val emotionalValence: String = "",
    val formalityAndRegister: String = "",
    val connotationAndTone: String = "",
    // 5 Knowledge graph
    val namedEntityRecognition: String = "",
    val entityDisambiguation: List<String> = emptyList(),
    val externalKnowledgeLinks: List<Pair<String, String>> = emptyList(),
    // 6 Style / metrics
    val styleAndGrammarNotes: List<Pair<String, String>> = emptyList()
)

@Serializable
data class WordAnalysisBundleRecord(
    val version: Int = 1,
    val words: List<WordAnalysisStorageRecord> = emptyList()
)
