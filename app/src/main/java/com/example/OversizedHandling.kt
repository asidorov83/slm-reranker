package com.example

import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.delay

suspend fun generateContentWithRetry(
    generativeModel: GenerativeModel,
    prompt: String,
    onStatusUpdate: (String) -> Unit
): String {
    var retryCount = 0
    while (retryCount < 5) {
        try {
            val response = generativeModel.generateContent(prompt)
            return response.candidates.firstOrNull()?.text ?: ""
        } catch (e: Exception) {
            val errorStr = e.toString()
            if (errorStr.contains("BUSY") || errorStr.contains("ErrorCode 9") || errorStr.contains("quota")) {
                retryCount++
                onStatusUpdate("Ожидание AICore (занят или превышена квота), попытка $retryCount/5...")
                delay(2000L * retryCount)
            } else {
                throw e
            }
        }
    }
    throw Exception("Превышено количество попыток обращения к AICore")
}

fun truncateWordsToLast(text: String, wordCount: Int): String {
    val words = text.split(Regex("\\s+"))
    if (words.size <= wordCount) return text
    return words.takeLast(wordCount).joinToString(" ")
}

fun truncateWordsToFirst(text: String, wordCount: Int): String {
    val words = text.split(Regex("\\s+"))
    if (words.size <= wordCount) return text
    return words.take(wordCount).joinToString(" ")
}

suspend fun summarizeField(
    generativeModel: GenerativeModel,
    fieldName: String,
    text: String,
    onStatusUpdate: (String) -> Unit
): String {
    if (text.isBlank()) return text
    onStatusUpdate("Суммаризация поля: $fieldName...")
    var currentText = text
    while (true) {
        val prompt = "You are an assistant. Please summarize the following $fieldName data concisely. Data: $currentText"
        try {
            return generateContentWithRetry(generativeModel, prompt, onStatusUpdate)
        } catch (e: Exception) {
            val errorStr = e.toString()
            if (errorStr.contains("INFERENCE_ERROR") || errorStr.contains("COMPUTE_ERROR") || errorStr.contains("Input text length exceeds")) {
                val wordsCount = currentText.split(Regex("\\s+")).size
                val newCount = wordsCount / 2
                if (newCount < 50) throw Exception("Не удалось суммаризировать $fieldName: $errorStr")
                onStatusUpdate("Ошибка при суммаризации $fieldName. Уменьшаем до $newCount слов...")
                currentText = truncateWordsToLast(currentText, newCount)
            } else {
                throw e
            }
        }
    }
}

fun parseResponse(text: String): List<ScoredItem> {
    return text.lines()
        .filter { it.contains("|") }
        .mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size >= 2) {
                val namePart = parts[0].replace(Regex("^\\d+\\.\\s*"), "").trim()
                val scorePart = parts[1].trim().toIntOrNull() ?: 50
                val reasonPart = if (parts.size >= 3) parts[2].trim() else "Prioritized by Gemini Nano"
                ScoredItem(name = namePart, reason = reasonPart, score = scorePart)
            } else null
        }
        .sortedByDescending { it.score }
}

suspend fun rerankWithSummarizationFallback(
    generativeModel: GenerativeModel,
    identity: String,
    history: String,
    externalContext: String,
    itemsText: String,
    viewedItemsText: String,
    favoritedItemsText: String,
    isLoraActive: Boolean,
    loraSpecialization: String,
    loraRank: Int,
    loraAlpha: Int,
    ragCategories: List<String>,
    onStatusUpdate: (String) -> Unit
): List<ScoredItem> {
    val ragText = ragCategories.joinToString(", ")
    val totalText = "$identity $history $externalContext $viewedItemsText $favoritedItemsText $ragText"
    val totalWords = totalText.split(Regex("\\s+")).size
    val skipSummaryIfSmall = totalWords < 2000

    fun shouldSkip(text: String): Boolean {
        if (!skipSummaryIfSmall) return false
        val words = text.split(Regex("\\s+")).size
        return words < 200
    }

    val sumIdentity = if (shouldSkip(identity)) identity else summarizeField(generativeModel, "User Identity", identity, onStatusUpdate)
    val sumHistory = if (shouldSkip(history)) history else summarizeField(generativeModel, "Purchase History", history, onStatusUpdate)
    val sumContext = if (shouldSkip(externalContext)) externalContext else summarizeField(generativeModel, "External Context", externalContext, onStatusUpdate)
    val sumViewed = if (shouldSkip(viewedItemsText)) viewedItemsText else summarizeField(generativeModel, "Viewed Items", viewedItemsText, onStatusUpdate)
    val sumFavorited = if (shouldSkip(favoritedItemsText)) favoritedItemsText else summarizeField(generativeModel, "Favorited Items", favoritedItemsText, onStatusUpdate)
    val sumRag = if (shouldSkip(ragText)) ragText else summarizeField(generativeModel, "RAG Context", ragText, onStatusUpdate)

    var currentItemsText = itemsText

    while (true) {
        val prompt = buildGeminiPrompt(
            sumIdentity, sumHistory, sumContext, currentItemsText, sumViewed, sumFavorited,
            isLoraActive, loraSpecialization, loraRank, loraAlpha, listOf(sumRag)
        )

        onStatusUpdate("Переранжирование с суммаризированными данными...")
        try {
            val responseText = generateContentWithRetry(generativeModel, prompt, onStatusUpdate)
            val parsed = parseResponse(responseText)
            
            val allItems = currentItemsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val parsedNames = parsed.map { it.name.lowercase() }
            val missing = allItems.filter { name -> parsedNames.none { it.contains(name.lowercase()) } }
            
            val fullList = parsed + missing.map { ScoredItem(it, "Included", 50) }
            return fullList.sortedByDescending { it.score }

        } catch (e: Exception) {
            val errorStr = e.toString()
            if (errorStr.contains("INFERENCE_ERROR") || errorStr.contains("COMPUTE_ERROR") || errorStr.contains("Input text length exceeds")) {
                val wordsCount = currentItemsText.split(Regex("\\s+")).size
                val newCount = wordsCount / 2
                if (newCount < 50) throw Exception("Не удалось переранжировать: $errorStr")
                onStatusUpdate("Ошибка при переранжировании. Уменьшаем items до $newCount слов...")
                currentItemsText = truncateWordsToFirst(currentItemsText, newCount)
            } else {
                throw e
            }
        }
    }
}
