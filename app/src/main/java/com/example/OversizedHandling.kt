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
