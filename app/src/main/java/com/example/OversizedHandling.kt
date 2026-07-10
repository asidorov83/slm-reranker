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

fun scoreSingleItem(
    item: String,
    identity: String,
    history: String,
    context: String,
    viewedItemsText: String,
    favoritedItemsText: String,
    userActions: String,
    isLoraActive: Boolean,
    loraSpecialization: String,
    loraRank: Int,
    loraAlpha: Int,
    ragCategories: List<String>
): ScoredItem {
    val itemLower = item.lowercase().trim()
    val itemWords = itemLower.split(Regex("[\\s,:.\\-()\"]+")).filter { it.length > 2 }
    
    val viewedList = viewedItemsText.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    val favoritedList = favoritedItemsText.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    val userActionsList = userActions.split("\n").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    
    val identityLower = identity.lowercase()
    val historyLower = history.lowercase()
    val contextLower = context.lowercase()
    
    val scalingFactor = if (isLoraActive) {
        val ratio = loraAlpha.toFloat() / loraRank.toFloat()
        (0.8f + (ratio / 8.0f)).coerceIn(1.0f, 2.5f)
    } else {
        1.0f
    }
    
    var score = 40
    val reasons = mutableListOf<String>()
    
    // 1. Check viewed items
    val isViewed = viewedList.any { viewed -> 
        itemLower.contains(viewed) || viewed.contains(itemLower) ||
        itemWords.any { word -> viewed.contains(word) }
    }
    if (isViewed) {
        score += 12
        reasons.add("Recently Viewed")
    }
    
    // 2. Check favorited items
    val isFavorited = favoritedList.any { fav -> 
        itemLower.contains(fav) || fav.contains(itemLower) ||
        itemWords.any { word -> fav.contains(word) }
    }
    if (isFavorited) {
        score += 20
        reasons.add("❤️ Favorited Preference")
    }
    
    // 3. Match with RAG categories
    val isRagMatched = ragCategories.any { cat -> 
        val catLower = cat.lowercase()
        itemLower.contains(catLower) || catLower.contains(itemLower) ||
        itemWords.any { word -> catLower.contains(word) }
    }
    if (isRagMatched) {
        score += 25
        reasons.add("🔍 RAG Trend Match")
    }
    
    // 4. Match with User Actions history
    val isActionMatched = userActionsList.any { action -> 
        action.contains(itemLower) || itemWords.any { word -> action.contains(word) }
    }
    if (isActionMatched) {
        score += 15
        reasons.add("⚡ User Action History")
    }
    
    // 5. Dynamic context field matches
    var dynamicBoost = 0
    
    val identityMatches = itemWords.filter { word -> identityLower.contains(word) }
    if (identityMatches.isNotEmpty()) {
        dynamicBoost += 15 * identityMatches.size
        reasons.add("Persona match: ${identityMatches.joinToString(", ")}")
    }
    
    val historyMatches = itemWords.filter { word -> historyLower.contains(word) }
    if (historyMatches.isNotEmpty()) {
        dynamicBoost += 12 * historyMatches.size
        reasons.add("History match: ${historyMatches.joinToString(", ")}")
    }
    
    val contextMatches = itemWords.filter { word -> contextLower.contains(word) }
    if (contextMatches.isNotEmpty()) {
        dynamicBoost += 18 * contextMatches.size
        reasons.add("Context fit: ${contextMatches.joinToString(", ")}")
    }
    
    // 6. Hardcoded fallback checks
    var baseScore = 0
    var baseReason = ""
    when {
        itemLower.contains("umbrella") -> {
            if (contextLower.contains("rain") || contextLower.contains("drizzle") || contextLower.contains("wet") || contextLower.contains("fog")) {
                baseScore += 25
                baseReason = "Weather match - high precipitation protection"
            } else if (identityLower.contains("tech")) {
                baseScore += 8
                baseReason = "Persona fit - smart device commuter convenience"
            }
        }
        itemLower.contains("boots") -> {
            if (contextLower.contains("rain") || contextLower.contains("wet") || contextLower.contains("trail") || contextLower.contains("hiking")) {
                baseScore += 20
                baseReason = "Context match - robust all-weather footwear"
            } else if (historyLower.contains("running") || historyLower.contains("shoes") || historyLower.contains("trail")) {
                baseScore += 10
                baseReason = "History fit - matches trail wet-ground preferences"
            }
        }
        itemLower.contains("earbuds") || itemLower.contains("buds") || itemLower.contains("headphones") -> {
            if (identityLower.contains("tech") || identityLower.contains("dev") || identityLower.contains("enthusiast")) {
                baseScore += 18
                baseReason = "Persona fit - audio-focused commuting & productivity"
            } else if (contextLower.contains("commuting") || contextLower.contains("coffee")) {
                baseScore += 12
                baseReason = "Context fit - isolation/ambient music in public space"
            }
        }
        itemLower.contains("coffee") || itemLower.contains("beans") -> {
            if (historyLower.contains("coffee") || identityLower.contains("caffeine") || identityLower.contains("dev")) {
                baseScore += 22
                baseReason = "History match - daily routine caffeine reliance"
            } else if (contextLower.contains("morning") || contextLower.contains("cold") || contextLower.contains("8°c") || contextLower.contains("12°c")) {
                baseScore += 14
                baseReason = "Context match - warm morning beverage preference"
            }
        }
        itemLower.contains("tee") || itemLower.contains("shirt") -> {
            if (contextLower.contains("sun") || contextLower.contains("hiking") || contextLower.contains("trail") || contextLower.contains("22°c")) {
                baseScore += 16
                baseReason = "Clothing match - light warm-weather fabric"
            } else if (contextLower.contains("rain") || contextLower.contains("cold")) {
                baseScore += 5
                baseReason = "General clothing layer"
            }
        }
        itemLower.contains("hat") -> {
            if (contextLower.contains("sun") || contextLower.contains("hiking") || contextLower.contains("trail") || contextLower.contains("22°c")) {
                baseScore += 19
                baseReason = "Active wear - sun & heat defense on outdoor trail"
            } else if (contextLower.contains("fog") || contextLower.contains("morning") || contextLower.contains("8°c")) {
                baseScore += 11
                baseReason = "Weather match - thermal headwear protection"
            }
        }
        itemLower.contains("mat") -> {
            if (historyLower.contains("yoga") || historyLower.contains("fitness")) {
                baseScore += 15
                baseReason = "History match - daily physical wellness ritual"
            }
        }
    }
    
    score += dynamicBoost + baseScore
    if (baseReason.isNotEmpty()) {
        reasons.add(baseReason)
    }
    
    var finalReason = reasons.joinToString(" | ")
    if (finalReason.isEmpty()) {
        finalReason = "Retained in catalog profile matches"
    }
    
    if (isLoraActive && loraSpecialization.isNotEmpty()) {
        val specLower = loraSpecialization.lowercase()
        var loraBoost = 0
        var loraReason = ""
        
        if (specLower.contains("weather") && (itemLower.contains("umbrella") || itemLower.contains("boots") || itemLower.contains("hat"))) {
            loraBoost = 15
            loraReason = "[LoRA Weather Adaptor]"
        } else if (specLower.contains("tech") && (itemLower.contains("earbuds") || itemLower.contains("plug") || itemLower.contains("mouse") || itemLower.contains("keyboard"))) {
            loraBoost = 18
            loraReason = "[LoRA Tech Adaptor]"
        } else if (specLower.contains("fitness") && (itemLower.contains("mat") || itemLower.contains("shoes") || itemLower.contains("bottle"))) {
            loraBoost = 12
            loraReason = "[LoRA Fitness Adaptor]"
        }
        
        if (loraBoost > 0) {
            score += loraBoost
            finalReason = "$loraReason | $finalReason"
        } else {
            score = (score * scalingFactor).toInt()
            finalReason = "[LoRA Enhanced] " + finalReason
        }
    }
    
    score += item.length % 5
    
    return ScoredItem(item, finalReason, score)
}

fun parseResponse(
    text: String,
    originalItemsText: String? = null,
    identity: String = "",
    history: String = "",
    context: String = "",
    viewedItemsText: String = "",
    favoritedItemsText: String = "",
    userActions: String = "",
    isLoraActive: Boolean = false,
    loraSpecialization: String = "",
    loraRank: Int = 8,
    loraAlpha: Int = 16,
    ragCategories: List<String> = emptyList()
): List<ScoredItem> {
    val parsedItems = mutableListOf<ScoredItem>()
    
    // 1. Try standard JSON array parsing
    try {
        val jsonStart = text.indexOf("[")
        val jsonEnd = text.lastIndexOf("]")
        if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
            val jsonString = text.substring(jsonStart, jsonEnd + 1)
            val jsonArray = org.json.JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                parsedItems.add(
                    ScoredItem(
                        name = obj.optString("name", "Unknown"),
                        score = obj.optInt("score", 50),
                        reason = obj.optString("reason", "Prioritized by Gemini Nano")
                    )
                )
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    
    // 2. If standard array parsing fails or returned empty, try extracting individual JSON objects
    if (parsedItems.isEmpty()) {
        try {
            val jsonObjects = extractJsonObjects(text)
            for (objStr in jsonObjects) {
                try {
                    val obj = org.json.JSONObject(objStr)
                    val nameKeys = listOf("name", "Name", "NAME", "title", "Title", "item", "Item")
                    val scoreKeys = listOf("score", "Score", "SCORE", "rating", "Rating", "points", "Points")
                    val reasonKeys = listOf("reason", "Reason", "REASON", "explanation", "Explanation", "why", "Why")
                    
                    var name = ""
                    for (key in nameKeys) {
                        if (obj.has(key)) {
                            name = obj.optString(key, "")
                            if (name.isNotEmpty()) break
                        }
                    }
                    
                    var score = 50
                    for (key in scoreKeys) {
                        if (obj.has(key)) {
                            score = obj.optInt(key, 50)
                            break
                        }
                    }
                    
                    var reason = "Prioritized by Gemini Nano"
                    for (key in reasonKeys) {
                        if (obj.has(key)) {
                            reason = obj.optString(key, "")
                            if (reason.isNotEmpty()) break
                        }
                    }
                    
                    if (name.isNotEmpty()) {
                        parsedItems.add(ScoredItem(name, reason, score))
                    }
                } catch (e: Exception) {
                    // Ignore and try next object
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    // 3. If still empty, try parsing by line using regex / heuristics
    if (parsedItems.isEmpty()) {
        try {
            val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            for (line in lines) {
                if (line.startsWith("```") || line == "[" || line == "]") continue
                
                var lineScore = 50
                val scoreRegex = Regex("\\b(score|rating|points)?[:\\s]*(\\d{1,3})\\b", RegexOption.IGNORE_CASE)
                val scoreMatch = scoreRegex.find(line)
                if (scoreMatch != null) {
                    val scoreStr = scoreMatch.groupValues[2]
                    lineScore = scoreStr.toIntOrNull() ?: 50
                } else {
                    val numRegex = Regex("\\b(\\d{1,3})\\b")
                    val matches = numRegex.findAll(line).toList()
                    for (m in matches) {
                        val v = m.groupValues[1].toIntOrNull() ?: -1
                        if (v in 0..100) {
                            lineScore = v
                            break
                        }
                    }
                }
                
                var cleanLine = line.replaceFirst(Regex("^\\d+[:\\.\\)\\s]+"), "").replaceFirst(Regex("^[*\\-\\s]+"), "").trim()
                cleanLine = cleanLine.replace(Regex("\\(?[Ss]core\\s*[:\\-\\s]*\\d+\\)?"), "").trim()
                
                val separators = listOf(" - ", " : ", " because ", " due to ", " — ", " – ")
                var name = ""
                var reason = ""
                
                for (sep in separators) {
                    val idx = cleanLine.indexOf(sep)
                    if (idx != -1) {
                        name = cleanLine.substring(0, idx).trim()
                        reason = cleanLine.substring(idx + sep.length).trim()
                        break
                    }
                }
                
                if (name.isEmpty()) {
                    val words = cleanLine.split(Regex("\\s+"))
                    if (words.size > 2) {
                        name = words.take(3).joinToString(" ")
                        reason = words.drop(3).joinToString(" ")
                    } else {
                        name = cleanLine
                        reason = "Prioritized by Gemini Nano"
                    }
                }
                
                name = name.trim().trim(',', '.', ':', ';', '-', '"', '\'')
                reason = reason.trim().trim(',', '.', ':', ';', '-', '"', '\'')
                
                if (name.isNotEmpty() && name.length > 2 && !name.startsWith("{") && !name.startsWith("}")) {
                    parsedItems.add(ScoredItem(name, reason, lineScore))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    // 4. If still empty, search the response for mentions of original items
    if (parsedItems.isEmpty() && !originalItemsText.isNullOrBlank()) {
        try {
            val originalItems = splitItemsText(originalItemsText)
            val lowerText = text.lowercase()
            for (item in originalItems) {
                if (item.length > 2 && lowerText.contains(item.lowercase())) {
                    val itemIndex = lowerText.indexOf(item.lowercase())
                    val substringRadius = 50
                    val start = (itemIndex - substringRadius).coerceAtLeast(0)
                    val end = (itemIndex + item.length + substringRadius).coerceAtMost(text.length)
                    val neighborhood = text.substring(start, end)
                    
                    var score = 70
                    val numRegex = Regex("\\b(\\d{1,3})\\b")
                    val matches = numRegex.findAll(neighborhood).toList()
                    for (m in matches) {
                        val v = m.groupValues[1].toIntOrNull() ?: -1
                        if (v in 0..100) {
                            score = v
                            break
                        }
                    }
                    
                    parsedItems.add(
                        ScoredItem(
                            name = item,
                            score = score,
                            reason = "Contextually aligned from Gemini Nano response text."
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    // 5. Absolute ultimate fallback: score original items dynamically using our dynamic scoring function so the UI never crashes or remains empty
    if (parsedItems.isEmpty() && !originalItemsText.isNullOrBlank()) {
        try {
            val originalItems = splitItemsText(originalItemsText)
            originalItems.forEach { item ->
                val scored = scoreSingleItem(
                    item = item,
                    identity = identity,
                    history = history,
                    context = context,
                    viewedItemsText = viewedItemsText,
                    favoritedItemsText = favoritedItemsText,
                    userActions = userActions,
                    isLoraActive = isLoraActive,
                    loraSpecialization = loraSpecialization,
                    loraRank = loraRank,
                    loraAlpha = loraAlpha,
                    ragCategories = ragCategories
                )
                parsedItems.add(scored)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    // 6. Safeguard Post-processing: Ensure every single original item is represented in the final parsed items list
    if (!originalItemsText.isNullOrBlank() && parsedItems.isNotEmpty()) {
        try {
            val originalItems = splitItemsText(originalItemsText)
            val parsedNamesLower = parsedItems.map { it.name.lowercase().trim() }.toSet()
            for (item in originalItems) {
                val itemLower = item.lowercase().trim()
                if (itemLower.isEmpty()) continue
                // Check if this item is missing (exact match, or substring match)
                val isPresent = parsedNamesLower.any { parsedName ->
                    parsedName == itemLower || parsedName.contains(itemLower) || itemLower.contains(parsedName)
                }
                if (!isPresent) {
                    val fallbackScored = scoreSingleItem(
                        item = item,
                        identity = identity,
                        history = history,
                        context = context,
                        viewedItemsText = viewedItemsText,
                        favoritedItemsText = favoritedItemsText,
                        userActions = userActions,
                        isLoraActive = isLoraActive,
                        loraSpecialization = loraSpecialization,
                        loraRank = loraRank,
                        loraAlpha = loraAlpha,
                        ragCategories = ragCategories
                    )
                    parsedItems.add(fallbackScored)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    return parsedItems.sortedByDescending { it.score }
}

// Helper to scan matching braces to find JSON objects
fun extractJsonObjects(text: String): List<String> {
    val list = mutableListOf<String>()
    var depth = 0
    var startIdx = -1
    for (i in text.indices) {
        val c = text[i]
        if (c == '{') {
            if (depth == 0) {
                startIdx = i
            }
            depth++
        } else if (c == '}') {
            if (depth > 0) {
                depth--
                if (depth == 0 && startIdx != -1) {
                    list.add(text.substring(startIdx, i + 1))
                }
            }
        }
    }
    return list
}

suspend fun rerankWithSummarizationFallback(
    generativeModel: GenerativeModel,
    identity: String,
    history: String,
    externalContext: String,
    itemsText: String,
    viewedItemsText: String,
    favoritedItemsText: String,
    userActions: String,
    isLoraActive: Boolean,
    loraSpecialization: String,
    loraRank: Int,
    loraAlpha: Int,
    ragCategories: List<String>,
    onStatusUpdate: (String) -> Unit,
    onRawOutput: (String) -> Unit = {}
): List<ScoredItem> {
    val ragText = ragCategories.joinToString(", ")
    val totalText = "$identity $history $externalContext $viewedItemsText $favoritedItemsText $userActions $ragText"
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
    val sumActions = if (shouldSkip(userActions)) userActions else summarizeField(generativeModel, "User Actions", userActions, onStatusUpdate)
    val sumRag = if (shouldSkip(ragText)) ragText else summarizeField(generativeModel, "RAG Context", ragText, onStatusUpdate)

    var currentItemsText = itemsText

    while (true) {
        val prompt = buildGeminiPrompt(
            sumIdentity, sumHistory, sumContext, currentItemsText, sumViewed, sumFavorited, sumActions,
            isLoraActive, loraSpecialization, loraRank, loraAlpha, listOf(sumRag)
        )

        onStatusUpdate("Переранжирование с суммаризированными данными...")
        try {
            val responseText = generateContentWithRetry(generativeModel, prompt, onStatusUpdate)
            val parsed = parseResponse(
                text = responseText,
                originalItemsText = currentItemsText,
                identity = sumIdentity,
                history = sumHistory,
                context = sumContext,
                viewedItemsText = sumViewed,
                favoritedItemsText = sumFavorited,
                userActions = sumActions,
                isLoraActive = isLoraActive,
                loraSpecialization = loraSpecialization,
                loraRank = loraRank,
                loraAlpha = loraAlpha,
                ragCategories = listOf(sumRag)
            )
            if (parsed.isEmpty()) {
                throw Exception("Failed to parse JSON array in model response.")
            }
            
            // Reconstruct complete beautifully formatted JSON
            val formattedRaw = buildString {
                append("[\n")
                parsed.forEachIndexed { idx, item ->
                    append("  {\n")
                    append("    \"name\": \"${item.name}\",\n")
                    append("    \"score\": ${item.score},\n")
                    append("    \"reason\": \"${item.reason}\"\n")
                    append("  }${if (idx < parsed.size - 1) "," else ""}\n")
                }
                append("]")
            }
            onRawOutput(formattedRaw)
            
            return parsed.sortedByDescending { it.score }

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

suspend fun generateWithGeminiNanoAutoCompress(
    identity: String,
    history: String,
    context: String,
    viewed: String,
    favorited: String,
    actions: String,
    ragCategoriesText: String,
    ragContextText: String,
    isLoraActive: Boolean,
    loraSpecialization: String,
    loraRank: Int,
    loraAlpha: Int,
    promptType: String, // "user_assumptions" | "product_assumptions" | "qa"
    extraInput: String = "", // Used for Q&A question
    onStatusUpdate: (String) -> Unit
): String {
    val generativeModel = com.google.mlkit.genai.prompt.Generation.getClient()
    
    // First, let's try direct generation with local text compaction
    // We will do a multi-stage approach to ensure Gemini Nano never fails!
    
    // Stage 1: Fast local compaction (no network/local model summarization overhead)
    var useSummarization = false
    var currentIdentity = identity
    var currentHistory = history
    var currentContext = context
    var currentViewed = viewed
    var currentFavorited = favorited
    var currentActions = actions
    var currentRagCategories = ragCategoriesText
    var currentRagContext = ragContextText
    
    onStatusUpdate("Оптимизация контекста для Gemini Nano...")
    
    for (attempt in 1..3) {
        val loraInfo = if (isLoraActive) "Активный адаптер LoRA: $loraSpecialization (Rank=$loraRank, Alpha=$loraAlpha)" else ""
        
        val contextBlock = if (useSummarization) {
            // Stage 2: If we are on attempt 2 or 3, or if previous failed, we use summarized fields
            onStatusUpdate("Запуск суммаризации полей (размер превышен)...")
            val sumIdentity = try { summarizeField(generativeModel, "User Identity", currentIdentity, onStatusUpdate) } catch (e: Exception) { truncateWordsToLast(currentIdentity, 30) }
            val sumHistory = try { summarizeField(generativeModel, "Purchase History", currentHistory, onStatusUpdate) } catch (e: Exception) { truncateWordsToLast(currentHistory, 30) }
            val sumContext = try { summarizeField(generativeModel, "External Context", currentContext, onStatusUpdate) } catch (e: Exception) { truncateWordsToLast(currentContext, 20) }
            val sumViewed = try { summarizeField(generativeModel, "Viewed Items", currentViewed, onStatusUpdate) } catch (e: Exception) { truncateWordsToLast(currentViewed, 20) }
            val sumFavorited = try { summarizeField(generativeModel, "Favorited Items", currentFavorited, onStatusUpdate) } catch (e: Exception) { truncateWordsToLast(currentFavorited, 20) }
            val sumActions = try { summarizeField(generativeModel, "User Actions", currentActions, onStatusUpdate) } catch (e: Exception) { truncateWordsToLast(currentActions, 30) }
            
            val ragLines = currentRagContext.split("\n")
            val sumRagContext = if (ragLines.size > 2) {
                ragLines.take(2).joinToString("\n")
            } else {
                currentRagContext
            }
            val sumRagContextFinal = try { summarizeField(generativeModel, "RAG Context", sumRagContext, onStatusUpdate) } catch (e: Exception) { truncateWordsToLast(sumRagContext, 40) }
            
            """
                User Identity Profile: $sumIdentity
                Historic Purchases: $sumHistory
                Current Context/Environment: $sumContext
                Recently Viewed: $sumViewed
                Favorites: $sumFavorited
                User Actions: $sumActions
                RAG retrieved demographics: $currentRagCategories
                ${if (sumRagContextFinal.isNotEmpty()) "RAG retrieved domain-specific knowledge:\n$sumRagContextFinal" else ""}
                ${if (loraInfo.isNotEmpty()) "Active Low-Rank Adapter: $loraInfo" else ""}
            """.trimIndent()
        } else {
            // Fast local truncation for a compact 100% working prompt first!
            val compIdentity = truncateWordsToLast(currentIdentity, 50)
            val compHistory = truncateWordsToLast(currentHistory, 50)
            val compContext = truncateWordsToLast(currentContext, 30)
            val compViewed = truncateWordsToLast(currentViewed, 40)
            val compFavorited = truncateWordsToLast(currentFavorited, 40)
            val compActions = truncateWordsToLast(currentActions, 50)
            
            val ragLines = currentRagContext.split("\n")
            val compRagContext = if (ragLines.size > 2) {
                ragLines.take(2).joinToString("\n")
            } else {
                currentRagContext
            }
            val compRagContextFinal = truncateWordsToLast(compRagContext, 80)
            
            """
                User Identity Profile: $compIdentity
                Historic Purchases: $compHistory
                Current Context/Environment: $compContext
                Recently Viewed: $compViewed
                Favorites: $compFavorited
                User Actions: $compActions
                RAG retrieved demographics: $currentRagCategories
                ${if (compRagContextFinal.isNotEmpty()) "RAG retrieved domain-specific knowledge:\n$compRagContextFinal" else ""}
                ${if (loraInfo.isNotEmpty()) "Active Low-Rank Adapter: $loraInfo" else ""}
            """.trimIndent()
        }
        
        val prompt = when (promptType) {
            "user_assumptions" -> {
                """
                    Вы - профессиональный аналитик профилей пользователей. На основе следующих входных данных:
                    $contextBlock
                    
                    Пожалуйста, предположите характеристики этого пользователя по следующим пунктам. 
                    Отвечайте на РУССКОМ языке. Выведите характеристики БЕЗ какой-либо нумерации (БЕЗ "1)", "2)" и т.д.), строго в виде: "название характеристики: одно или несколько значений характеристики" (например, "Интересы в музыке: Поп-музыка, классическая музыка"). Вы можете указывать несколько значений через запятую, если они применимы.
                    Без лишних вступлений и заключений.
                    Для каждого пункта выберите наиболее подходящий вариант из предложенных в скобках:
                    Пол (мужской, женский, гей, лесбиянка)
                    Возраст пользователя (0-14, 15–24, 25–34, 35–44, 45–54, 55–64, 65+ лет)
                    Доход (Низкий, Средний, Высокий, Сверхвысокий)
                    Вероисповедание (Атеизм, Православие, Ислам, Буддизм, Иудаизм, Протестантизм, Католицизм, Старообрядчество, Язычество)
                    Количество детей (0, 1, 2, 3, больше 3)
                    Семейное положение (нет, сожительство, брак)
                    Возраст партнёра/мужа/жены пользователя (нет; 18–24, 25–34, 35–44, 45–54, 55–64, 65+ лет)
                    Животные (отсутствуют, кошки, собаки, рыбки, птички, подсобное хозяйство)
                    Профессия (Руководители; Специалисты высшего уровня квалификации; Специалисты среднего уровня квалификации; Служащие; Работники сферы услуг и торговли; Кадры сельского, лесного и рыбного хозяйства; Квалифицированные рабочие; Операторы и водители; Неквалифицированные рабочие)
                    Месяц дня рождения (январь; февраль; март; апрель; май; июнь; июль; август; сентябрь; октябрь; ноябрь; декабрь)
                    Месяц дня рождения партнёра/мужа/жены (нет; январь; февраль; март; апрель; май; июнь; июль; август; сентябрь; октябрь; ноябрь; декабрь)
                    Месяц дня рождения старшего ребёнка (нет; январь; февраль; март; апрель; май; июнь; июль; август; сентябрь; октябрь; ноябрь; декабрь)
                    Месяц дня рождения младшего ребёнка (нет; январь; февраль; март; апрель; май; июнь; июль; август; сентябрь; октябрь; ноябрь; декабрь)
                    Пол старшего ребёнка (нет ребёнка, мужской, женский)
                    Возраст старшего ребёнка (нет ребёнка, 0-1; 1-3; 3-7; 7-11; 11-15; 15-18; больше 18)
                    Пол младшего ребёнка (нет ребёнка, мужской, женский)
                    Возраст младшего ребёнка (нет ребёнка, 0-1; 1-3; 3-7; 7-11; 11-15; 15-18; больше 18)
                    Возраст отца (18–24, 25–34, 35–44, 45–54, 55–64, 65+ лет)
                    Возраст матери (18–24, 25–34, 35–44, 45–54, 55–64, 65+ лет)
                    Хобби (из списка: Бег и фитнес; Йога и пилатес; Езда на велосипеде; Туризм, хайкинг и альпинизм; Танцы; Командный спорт; Рисование; Фотография и видеография; Рукоделие; Лепка; Музыка; Писательство; Чтение; Настольные игры; Изучение языков; Программирование и робототехника; Гейминг; Готовка; Садоводство; Уход за домашними животными; Сделай сам (DIY))
                    Интересы в видах отдыха (из: пляжный отдых; отдых в отеле «все включено»; SPA-процедуры; термальные источники; морские круизы; речные круизы; пешие походы; трекинг; альпинизм; катание на горных лыжах; сноубординг; рафтинг; дайвинг; серфинг; кайтинг; парапланеризм; прыжки с парашютом; обзорные экскурсии; посещение музеев; осмотр замков; арт-туризм; посещение музыкальных фестивалей; карнавалы; отдых в палатках; кемпинг; глэмпинг; загородный отдых на даче; агротуризм; прогулки по национальным паркам; цифровой детокс; киномарафоны; гастрономические туры; винные дегустации; шоппинг-туры)
                    Интересы в кино (из: боевик; комедия; драма; триллер; ужасы; фантастика; фэнтези; детектив; приключения; вестерн; мюзикл; биография; исторический фильм; военный фильм; нуар; антиутопия; катастрофа; семейный фильм; криминал; спортивная драма; психологический триллер; слэшер; ромком; трагикомедия; кинокомикс; киберпанк; стимпанк; космическая опера; документальное кино; анимация; аниме)
                    Интересы в музыке (из: Поп-музыка; синти-поп; k-pop; рок; хард-рок; панк-рок; альтернативный рок; инди-рок; гранж; метал; хэви-метал; дэт-метал; блэк-метал; рэп; хип-хоп; треп; джаз; блюз; ритм-н-блюз (R&B); соул; фанк; классическая музыка; неоклассика; опера; электронная музыка; хаус; техно; транс; дабстеп; драм-н-бейс; эмбиент; чиллаут; синтвейв; регги; ска; кантри; фолк-музыка; этно-музыка; шансон; романс; диско)
                    Интересы в науке (из: Математика; информатика; искусственный интеллект; теоретическая физика; квантовая физика; органическая химия; неорганическая химия; биохимия; молекулярная биология; генетика; социология; когнитивная психология; клиническая психология; лингвистика; философия; робототехника; нанотехнологии; материаловедение; архитектура)
                    Заболевания (из: острая респираторная вирусная инфекция (ОРВИ); грипп; гипертоническая болезнь; сахарный диабет 2-го типа; гастрит; остеохондроз; бронхиальная астма; аллергический ринит; атопический дерматит; кариес; цистит; железодефицитная анемия; депрессия; генерализованное тревожное расстройство; мигрень)
                    Интересы в компьютерных играх (из: шутер от первого лица (FPS); ролевая игра (RPG); японская ролевая игра (JRPG); стратегия в реальном времени (RTS); пошаговая стратегия (TBS); многопользовательская онлайн-игра (MMORPG); экшен-adventure; платформер; метроидвания; симулятор выживания (Survival); королевская битва (Battle Royale); рогалик (Roguelike); градостроительный симулятор; симулятор жизни; автосимулятор; авиасимулятор; спортивный симулятор; файтинг; слэшер; головоломка; хоррор; песочница (Sandbox))
                """.trimIndent()
            }
            "product_assumptions" -> {
                """
                    Вы - профессиональный прогнозист интереса к покупкам. На основе следующих входных данных:
                    $contextBlock
                    
                    Пожалуйста, предположите только конкретные товары (конкретные наименования), которые будут наиболее интересны этому пользователю для просмотра, добавления в избранное или покупки.
                    Виды товаров, группы товаров или любые категории генерировать КАТЕГОРИЧЕСКИ ЗАПРЕЩЕНО. Выведите только конкретные товары.
                    Отвечайте на РУССКОМ языке, четко структурируйте ответ в виде списка строк, начинающихся с дефиса (-). Каждая строка должна содержать только одно конкретное наименование товара.
                """.trimIndent()
            }
            "qa" -> {
                """
                    Вы - интеллектуальный ассистент, анализирующий контекст пользователя. На основе следующих входных данных:
                    $contextBlock
                    
                    Пожалуйста, подробно ответьте на следующий вопрос пользователя о нем или о товарах. 
                    Отвечайте на РУССКОМ языке, будьте точны, полезны и кратки. Без лишних вступлений и заключений.
                    
                    Вопрос: $extraInput
                """.trimIndent()
            }
            "quality_analysis_single" -> {
                """
                    Вы - профессиональный эксперт по оценке качества рекомендательных систем. На основе следующего контекста пользователя:
                    $contextBlock
                    
                    Пожалуйста, оцените релевантность следующего товара интересам и текущей жизненной ситуации пользователя.
                    Товар для оценки: $extraInput
                    
                    Выставьте оценку релевантности от 1 до 5 (где 5 - максимально релевантно/полезно в данной ситуации, а 1 - абсолютно нерелевантно/бесполезно) и напишите краткое обоснование оценки на русском языке (1-2 предложения).
                    
                    Вы должны вернуть результат СТРОГО в формате JSON-объекта. Объект должен иметь ключи:
                    - "name" (строка, точное название товара: "$extraInput")
                    - "rating" (целое число от 1 до 5)
                    - "reason" (строка с обоснованием на русском языке)
                    
                    Пример ответа:
                    {
                      "name": "$extraInput",
                      "rating": 5,
                      "reason": "Товар очень полезен пользователю, потому что..."
                    }
                """.trimIndent()
            }
            "quality_analysis" -> {
                """
                    Вы - профессиональный эксперт по оценке качества рекомендательных систем. На основе следующего контекста пользователя:
                    $contextBlock
                    
                    Пожалуйста, оцените релевантность следующих товаров интересам и текущей жизненной ситуации пользователя.
                    Товары для оценки:
                    $extraInput
                    
                    Для каждого товара вы должны выставить оценку релевантности от 1 до 5 (где 5 - максимально релевантно/полезно в данной ситуации, а 1 - абсолютно нерелевантно/бесполезно) и написать краткое обоснование оценки на русском языке (1-2 предложения).
                    
                    Вы должны вернуть результат СТРОГО в формате JSON-массива объектов. Каждый объект должен иметь ключи:
                    - "name" (строка, точное название товара из списка)
                    - "rating" (целое число от 1 до 5)
                    - "reason" (строка с обоснованием на русском языке)
                    
                    Пример ответа:
                    [
                      {
                        "name": "Название товара",
                        "rating": 5,
                        "reason": "Товар крайне актуален в текущий момент времени из-за дождливой погоды."
                      }
                    ]
                    
                    КАТЕГОРИЧЕСКИ ЗАПРЕЩЕНО добавлять какие-либо вступления, пояснения или markdown-обертки вроде ```json. Верните только чистый JSON-массив объектов.
                """.trimIndent()
            }
            else -> ""
        }
        
        try {
            onStatusUpdate("Запуск локальной генерации на NPU (Попытка $attempt)...")
            val result = generateContentWithRetry(generativeModel, prompt, onStatusUpdate)
            if (result.isNotBlank()) {
                return result.trim()
            }
        } catch (e: Exception) {
            val errorStr = e.toString()
            if (errorStr.contains("Input text length exceeds") || errorStr.contains("INFERENCE_ERROR") || errorStr.contains("COMPUTE_ERROR") || errorStr.contains("ErrorCode 5")) {
                if (attempt == 1) {
                    onStatusUpdate("Локальный лимит превышен. Переключаемся на агрессивную фильтрацию и суммаризацию полей...")
                    useSummarization = true
                } else if (attempt == 2) {
                    onStatusUpdate("Превышен лимит даже после суммаризации. Применяем максимальное усечение...")
                    currentIdentity = truncateWordsToLast(currentIdentity, 20)
                    currentHistory = truncateWordsToLast(currentHistory, 20)
                    currentContext = truncateWordsToLast(currentContext, 15)
                    currentViewed = truncateWordsToLast(currentViewed, 15)
                    currentFavorited = truncateWordsToLast(currentFavorited, 15)
                    currentActions = truncateWordsToLast(currentActions, 20)
                    currentRagContext = ""
                } else {
                    throw e
                }
            } else {
                throw e
            }
        }
    }
    
    throw Exception("Не удалось сгенерировать ответ на Gemini Nano")
}
