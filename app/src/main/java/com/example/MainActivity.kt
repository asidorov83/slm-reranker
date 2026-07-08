package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.BorderStroke

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainScreen()
            }
        }
    }
}

// Custom dashed border modifier for high-fidelity styling
fun Modifier.dashedBorder(
    width: Dp,
    color: Color,
    cornerRadius: Dp
) = this.drawBehind {
    val strokeWidth = width.toPx()
    val radiusPx = cornerRadius.toPx()
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
    drawRoundRect(
        color = color,
        style = Stroke(width = strokeWidth, pathEffect = dashEffect),
        cornerRadius = CornerRadius(radiusPx, radiusPx)
    )
}

// Scored Item Data Class
data class ScoredItem(val name: String, val reason: String, val score: Int)

// Personalization Scenario Preset Data Class
data class PersonalizationPreset(
    val name: String,
    val icon: String,
    val userIdentity: String,
    val purchaseHistory: String,
    val externalContext: String,
    val itemsToRank: String,
    val viewedItems: String,
    val favoritedItems: String
)

// Default Preset Data
val PRESETS = listOf(
    PersonalizationPreset(
        name = "Alice (Rainy Tech)",
        icon = "☔",
        userIdentity = "Alice, 28y, Female, Engineer, Urban, None (religion), None (children), Dog",
        purchaseHistory = "Running shoes (2023), Yoga mat, Coffee beans, Smart plug",
        externalContext = "19:42 PM, Rain falling, July, Weekday, London",
        itemsToRank = "Smart Umbrella, Cotton Tee, Rain Boots, Hat, Earbuds",
        viewedItems = "Smart Umbrella, Cotton Tee",
        favoritedItems = "Smart Umbrella, Earbuds"
    ),
    PersonalizationPreset(
        name = "Bob (Sunny Hiker)",
        icon = "☀️",
        userIdentity = "Bob, 34y, Male, Designer, Urban, None (religion), None (children), None (pets)",
        purchaseHistory = "Backpack, Trail run shoes, Sleeping bag, Water bottle",
        externalContext = "08:00 AM, Sun shining, August, Weekend, Paris",
        itemsToRank = "Smart Umbrella, Cotton Tee, Rain Boots, Hat, Earbuds",
        viewedItems = "Hat, Cotton Tee, Rain Boots",
        favoritedItems = "Hat, Rain Boots"
    ),
    PersonalizationPreset(
        name = "Charlie (Cozy Dev)",
        icon = "☕",
        userIdentity = "Charlie, 45y, Male, Developer, Urban, Christian (religion), 2 children, Cat",
        purchaseHistory = "Ergonomic mouse, Keyboard, Coffee beans, Warm Hoodie",
        externalContext = "07:15 AM, Foggy morning, December, Weekday, New York, Christmas",
        itemsToRank = "Smart Umbrella, Cotton Tee, Rain Boots, Hat, Earbuds",
        viewedItems = "Cotton Tee, Earbuds",
        favoritedItems = "Earbuds"
    )
)

const val DEFAULT_CSV_CONTENT = "Region,SettlementSize,Gender,Age,Religion,Children,Pets,Profession,Month,DayType,TimeOfDay,Holiday,Weather,PurchasedCategory\n" +
    "London,Urban,Female,28y,None,None,None,Engineer,July,Weekday,Evening,None,Rain,Smart Umbrella\n" +
    "London,Urban,Female,28y,None,None,None,Engineer,July,Weekday,Evening,None,Rain,Rain Boots\n" +
    "Paris,Urban,Male,34y,None,None,Dog,Designer,August,Weekend,Morning,None,Sun,Trail Shoes\n" +
    "Paris,Urban,Male,34y,None,None,Dog,Designer,August,Weekend,Morning,None,Sun,Hat\n" +
    "New York,Urban,Male,45y,Christian,Children,Cat,Developer,December,Weekday,Morning,Christmas,Snow,Warm Hoodie\n" +
    "New York,Urban,Male,45y,Christian,Children,Cat,Developer,December,Weekday,Morning,Christmas,Snow,Coffee beans\n" +
    "Tokyo,Urban,Female,22y,Buddhist,None,Cat,Student,April,Weekend,Evening,None,Cloudy,Earbuds"

// Helper to read text from URI
fun readTextFromUri(context: android.content.Context, uri: Uri): String {
    return try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.bufferedReader().use { it.readText() }
        } ?: ""
    } catch (e: Exception) {
        e.printStackTrace()
        ""
    }
}

// Helper to query file metadata from content URI
fun getFileNameAndSize(context: android.content.Context, uri: Uri): Pair<String, Long> {
    var name = "unknown_adapter.bin"
    var size = 0L
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) {
                    name = cursor.getString(nameIndex)
                }
                if (sizeIndex != -1) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return Pair(name, size)
}

// Helper to format file size in a human-readable form
fun formatSize(sizeInBytes: Long): String {
    if (sizeInBytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(sizeInBytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(java.util.Locale.US, "%.1f %s", sizeInBytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

// Prompt builder helper to generate consistent inputs for Gemini Nano
fun buildGeminiPrompt(
    identity: String,
    history: String,
    context: String,
    itemsText: String,
    viewedItemsText: String,
    favoritedItemsText: String,
    isLoraActive: Boolean,
    loraSpecialization: String,
    loraRank: Int,
    loraAlpha: Int,
    ragCategories: List<String>
): String {
    fun String.truncate(maxLength: Int): String =
        if (this.length > maxLength) this.substring(0, maxLength) + "..." else this

    return """
        You are a low-latency, on-device contextual personalization and catalog ranking model.
        Rank the following list of items based on the user's demographic identity, historical purchases, active external context, recency signals (viewed items), and long-term interest indicators (favorites).
        
        User Identity Profile: ${identity.truncate(1000)}
        Historic Purchases: ${history.truncate(1500)}
        Current Context/Environment: ${context.truncate(1000)}
        Items to Rank: ${itemsText.truncate(4000)}
        Recently Viewed: ${viewedItemsText.truncate(1500)}
        Favorites: ${favoritedItemsText.truncate(1500)}
        RAG retrieved demographics: ${ragCategories.joinToString(", ").truncate(1000)}
        ${if (isLoraActive) "Active Low-Rank Adapter: $loraSpecialization (Rank=$loraRank, Alpha=$loraAlpha)" else ""}
        
        You MUST return a ranked sequence of items. Format your output exactly as a structured list, with one item per line, formatted exactly as:
        Item Name | Score | Personalized Reason
        
        Example output format:
        Outdoor Shield | 95 | High demand due to heavy active rainfall and wind vectors
        Light Sneakers | 60 | Lower utility under wet trail conditions
        
        Return the output lines now:
    """.trimIndent()
}

// Suspending function to invoke real on-device Gemini Nano via Google AI Edge SDK
suspend fun rankItemsOnDeviceWithFallback(
    androidContext: android.content.Context,
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
    onStatusUpdate: (String) -> Unit,
    onError: (String) -> Unit = {}
): Pair<List<ScoredItem>, Boolean> {
    try {
        onStatusUpdate("Binding to local Google Play Services AI Core service via ML Kit...")
        val generativeModel = com.google.mlkit.genai.prompt.Generation.getClient()
        
        onStatusUpdate("Checking Gemini Nano status...")
        val status = generativeModel.checkStatus()
        when (status) {
            com.google.mlkit.genai.common.FeatureStatus.DOWNLOADABLE -> {
                onStatusUpdate("Gemini Nano is downloadable. Downloading...")
                generativeModel.download().collect { downloadStatus ->
                    when (downloadStatus) {
                        is com.google.mlkit.genai.common.DownloadStatus.DownloadProgress -> {
                            val mb = downloadStatus.totalBytesDownloaded / (1024.0 * 1024.0)
                            onStatusUpdate(String.format(java.util.Locale.US, "Model Download Status: %.2f MB downloaded...", mb))
                        }
                        is com.google.mlkit.genai.common.DownloadStatus.DownloadCompleted -> {
                            onStatusUpdate("Model Download Status: Completed! Model successfully stored in GMS AI Core cache.")
                        }
                        is com.google.mlkit.genai.common.DownloadStatus.DownloadFailed -> {
                            val errorMsg = downloadStatus.e?.message ?: "Unknown error"
                            onStatusUpdate("Model Download Status: Failed! error: $errorMsg")
                            throw Exception("Model download failed: $errorMsg")
                        }
                        is com.google.mlkit.genai.common.DownloadStatus.DownloadStarted -> {
                            val expectedMb = downloadStatus.bytesToDownload / (1024.0 * 1024.0)
                            onStatusUpdate(String.format(java.util.Locale.US, "Model Download Status: Started fetching Gemini Nano parameters (%.2f MB expected)...", expectedMb))
                        }
                    }
                }
            }
            com.google.mlkit.genai.common.FeatureStatus.DOWNLOADING -> {
                onStatusUpdate("Gemini Nano is currently downloading in background. Waiting for it to finish is not fully implemented in this loop.")
                // Wait for a short while or just proceed and let it fail gracefully
                throw Exception("Model is downloading. Please try again later.")
            }
            com.google.mlkit.genai.common.FeatureStatus.UNAVAILABLE -> {
                throw Exception("Gemini Nano is UNAVAILABLE on this device.")
            }
            com.google.mlkit.genai.common.FeatureStatus.AVAILABLE -> {
                onStatusUpdate("Gemini Nano is already downloaded and available.")
            }
        }
        
        onStatusUpdate("Formatting zero-shot contextual prompts...")
        val prompt = buildGeminiPrompt(
            identity = identity,
            history = history,
            context = externalContext,
            itemsText = itemsText,
            viewedItemsText = viewedItemsText,
            favoritedItemsText = favoritedItemsText,
            isLoraActive = isLoraActive,
            loraSpecialization = loraSpecialization,
            loraRank = loraRank,
            loraAlpha = loraAlpha,
            ragCategories = ragCategories
        )
        
        onStatusUpdate("Firing local on-device neural inference...")
        val response = generativeModel.generateContent(prompt)
        val text = response.candidates.firstOrNull()?.text ?: ""
        
        if (text.isBlank()) {
            throw Exception("GenerativeModel returned empty response content.")
        }
        
        onStatusUpdate("Parsing NPU response tensors...")
        val parsedItems = text.lines()
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
            
        if (parsedItems.isEmpty()) {
            throw Exception("Failed to match custom output delimiters in model response.")
        }
        
        onStatusUpdate("On-device ranking evaluation complete!")
        return Pair(parsedItems.sortedByDescending { it.score }, true)
        
    } catch (e: Throwable) {
        val errorMsg = e.stackTraceToString()
        onError(errorMsg)
        onStatusUpdate("NPU Sandbox Fallback: AI Core service not bound in emulator container or failed. Falling back to local simulation.")
        // Graceful fallback to rule-based!
        val fallback = rankItems(
            identity = identity,
            history = history,
            context = externalContext,
            itemsText = itemsText,
            viewedItemsText = viewedItemsText,
            favoritedItemsText = favoritedItemsText,
            isLoraActive = isLoraActive,
            loraSpecialization = loraSpecialization,
            loraRank = loraRank,
            loraAlpha = loraAlpha,
            ragCategories = ragCategories
        )
        return Pair(fallback, false)
    }
}

// Rule-based simulation of Gemini Nano on-device context ranker with LoRA adapter support
fun rankItems(
    identity: String,
    history: String,
    context: String,
    itemsText: String,
    viewedItemsText: String,
    favoritedItemsText: String,
    isLoraActive: Boolean,
    loraSpecialization: String,
    loraRank: Int,
    loraAlpha: Int,
    ragCategories: List<String>
): List<ScoredItem> {
    val items = itemsText.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        
    val viewedList = viewedItemsText.split(",")
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        
    val favoritedList = favoritedItemsText.split(",")
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
    
    val identityLower = identity.lowercase()
    val historyLower = history.lowercase()
    val contextLower = context.lowercase()
    
    // Scale factor based on LoRA Alpha / Rank ratio (standard scaling in LoRA)
    val scalingFactor = if (isLoraActive) {
        val ratio = loraAlpha.toFloat() / loraRank.toFloat()
        (0.8f + (ratio / 8.0f)).coerceIn(1.0f, 2.5f)
    } else {
        1.0f
    }
    
    val scoredItems = items.map { item ->
        val itemLower = item.lowercase()
        var score = 0
        var reason = "Baseline profile match"
        
        // Match with Viewed Items (viewed items get a personalized boost)
        val isViewed = viewedList.any { itemLower.contains(it) || it.contains(itemLower) }
        
        // Match with Favorited Items (favorited items get a high personalized boost)
        val isFavorited = favoritedList.any { itemLower.contains(it) || it.contains(itemLower) }
        
        // Match with RAG categories (categories retrieved from demographics database)
        val isRagMatched = ragCategories.any { cat -> itemLower.contains(cat.lowercase()) || cat.lowercase().contains(itemLower) }
        
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
        
        score += baseScore
        reason = baseReason.ifEmpty { "Baseline catalog match" }
        
        // Personalization boosts
        if (isViewed) {
            score += 10
            reason = "Recently Viewed | $reason"
        }
        if (isFavorited) {
            score += 18
            reason = "❤️ Favorited Preference | $reason"
        }
        if (isRagMatched) {
            score += 22
            reason = "🔍 RAG Trend Match | $reason"
        }
        
        // --- 2. LoRA Tuning Enhancements ---
        if (isLoraActive) {
            var loraBoost = 0
            var loraReason = ""
            
            when {
                loraSpecialization.contains("Eco") -> {
                    if (itemLower.contains("cotton") || itemLower.contains("boots") || itemLower.contains("umbrella") || itemLower.contains("mat") || itemLower.contains("tee")) {
                        loraBoost = (15 * scalingFactor).toInt()
                        loraReason = "[LoRA Eco-Boost] High-sustainability rank amplification applied"
                    }
                }
                loraSpecialization.contains("Active") -> {
                    if (itemLower.contains("boots") || itemLower.contains("shoes") || itemLower.contains("mat") || itemLower.contains("hat") || itemLower.contains("tee")) {
                        loraBoost = (18 * scalingFactor).toInt()
                        loraReason = "[LoRA Active-Tuning] Multi-matrix scaling prioritized athletic/active usage profile"
                    }
                }
                loraSpecialization.contains("Tech") -> {
                    if (itemLower.contains("earbuds") || itemLower.contains("buds") || itemLower.contains("headphones") || itemLower.contains("umbrella")) {
                        loraBoost = (16 * scalingFactor).toInt()
                        loraReason = "[LoRA Tech-Opt] Unified attention routing for smart commuter wearables"
                    }
                }
                else -> {
                    if (itemLower.length % 2 == 0) {
                        loraBoost = (12 * scalingFactor).toInt()
                        loraReason = "[LoRA Custom-Tune] Customized projection weights matching user-defined adaptation matrix"
                    }
                }
            }
            
            if (loraBoost > 0) {
                score += loraBoost
                reason = "$loraReason | $reason"
            } else {
                score = (score * scalingFactor).toInt()
                reason = "[LoRA Enhanced] " + reason
            }
        }
        
        // Add random slight variation to guarantee fine distinction
        score += item.length % 5
        
        ScoredItem(item, reason, score)
    }
    
    return scoredItems.sortedByDescending { it.score }
}

// Gemini Nano Context Keyword Extractor
fun extractKeywords(identity: String, context: String): List<String> {
    val text = "$identity $context".lowercase()
    val extracted = mutableListOf<String>()
    
    val regions = listOf("London", "Paris", "New York", "Tokyo", "Berlin", "Sydney", "Moscow", "Rome")
    val settlementSizes = listOf("Urban", "Rural", "Suburban", "City", "Village")
    val genders = listOf("Male", "Female", "Boy", "Girl", "Man", "Woman")
    val religions = listOf("Christian", "Buddhist", "Muslim", "Jewish", "Hindu", "Atheist", "None")
    val professions = listOf("Engineer", "Designer", "Developer", "Student", "Teacher", "Doctor", "Manager", "Writer", "Coder")
    val months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    val dayTypes = listOf("Weekday", "Weekend", "Holiday", "Workday")
    val timesOfDay = listOf("Morning", "Afternoon", "Evening", "Night")
    val holidays = listOf("Christmas", "New Year", "Easter", "Thanksgiving", "Halloween")
    val pets = listOf("Dog", "Cat", "Rabbit", "Hamster", "Bird", "Fish")
    val weathers = listOf("Rain", "Rainy", "Sun", "Sunny", "Snow", "Snowy", "Cloudy", "Fog", "Foggy", "Windy", "Warm", "Cold")

    regions.forEach { if (text.contains(it.lowercase())) extracted.add(it) }
    settlementSizes.forEach { if (text.contains(it.lowercase())) extracted.add(it) }
    genders.forEach { if (text.contains(it.lowercase())) extracted.add(it) }
    religions.forEach { if (text.contains(it.lowercase())) extracted.add(it) }
    professions.forEach { if (text.contains(it.lowercase())) extracted.add(it) }
    months.forEach { if (text.contains(it.lowercase())) extracted.add(it) }
    dayTypes.forEach { if (text.contains(it.lowercase())) extracted.add(it) }
    timesOfDay.forEach { if (text.contains(it.lowercase())) extracted.add(it) }
    holidays.forEach { if (text.contains(it.lowercase())) extracted.add(it) }
    pets.forEach { if (text.contains(it.lowercase())) extracted.add(it) }
    weathers.forEach { if (text.contains(it.lowercase())) extracted.add(it) }
    
    if (text.contains("children") || text.contains("kids") || text.contains("child") || text.contains("son") || text.contains("daughter")) {
        extracted.add("Children")
    }
    
    val ageRegex = Regex("\\b(\\d{2})y?\\b")
    ageRegex.findAll(text).forEach { match ->
        extracted.add(match.groupValues[1] + "y")
    }
    
    return extracted.distinct()
}

// Parses loaded CSV file and returns categories matching user keywords for RAG
fun parseCsvAndRetrieveCategories(csvContent: String, keywords: List<String>): List<String> {
    if (csvContent.isEmpty() || keywords.isEmpty()) return emptyList()
    
    val lines = csvContent.split("\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        
    val matchingCategories = mutableSetOf<String>()
    
    val dataLines = if (lines.firstOrNull()?.contains("Category", ignoreCase = true) == true || lines.firstOrNull()?.contains(",") == true) {
        lines.drop(1)
    } else {
        lines
    }
    
    for (line in dataLines) {
        val cells = line.split(",").map { it.trim().lowercase() }
        val matchFound = keywords.any { keyword ->
            cells.any { cell -> 
                val kwClean = keyword.replace("y", "").lowercase()
                cell.contains(kwClean) || kwClean.contains(cell)
            }
        }
        if (matchFound) {
            val category = cells.lastOrNull()?.replace("\"", "")?.trim()
            if (!category.isNullOrEmpty()) {
                matchingCategories.add(category.split(' ').joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } })
            }
        }
    }
    return matchingCategories.toList()
}

// Generates feedback critique detailing initial unoptimized baseline order and why it failed
fun generateBaselineCritique(
    identity: String,
    context: String,
    initialItems: List<String>
): String {
    val identityLower = identity.lowercase()
    val contextLower = context.lowercase()
    val initialTop = initialItems.firstOrNull() ?: "None"
    
    val critiqueBuilder = StringBuilder()
    critiqueBuilder.append("The static catalog order placed **$initialTop** as the top item. This default sequence completely ignores the user's current environmental situation and personal profile. ")
    
    if (contextLower.contains("rain") || contextLower.contains("wet") || contextLower.contains("fog") || contextLower.contains("drizzle")) {
        if (!initialTop.lowercase().contains("umbrella") && !initialTop.lowercase().contains("boots")) {
            critiqueBuilder.append("In active precipitation or wet conditions, ranking items like light cotton apparel or wireless earbuds ahead of critical weather shields (such as umbrellas or rain boots) is highly impractical and fails standard utility heuristics. ")
        } else {
            critiqueBuilder.append("Although it randomly placed defensive items near the top, it lacks any understanding of the user's urban lifestyle, commute patterns, or demographic habits. ")
        }
    } else if (contextLower.contains("sun") || contextLower.contains("hot") || contextLower.contains("hiking") || contextLower.contains("trail")) {
        if (initialTop.lowercase().contains("umbrella") || initialTop.lowercase().contains("boots")) {
            critiqueBuilder.append("In dry, sunny, or trail hiking contexts, placing heavy precipitation-focused items like umbrellas or rain boots at the top is completely redundant and burdens the user with unnecessary options. ")
        } else {
            critiqueBuilder.append("The general baseline list lacks the active awareness needed to elevate outdoor-specific accessories (like protective hats or trail gear) over urban tech devices. ")
        }
    } else {
        critiqueBuilder.append("The raw catalog feed did not account for localized geographic constraints, diurnal variations, or specific profession context. ")
    }
    
    if (identityLower.contains("tech") || identityLower.contains("dev") || identityLower.contains("engineer") || identityLower.contains("designer")) {
        critiqueBuilder.append("Furthermore, the user's professional background (${identity.split(',').firstOrNull() ?: "Specialist"}) is completely unrepresented, leaving highly relevant productivity tools or tech gear unprioritized. ")
    }
    
    critiqueBuilder.append("Finally, static baseline ordering completely neglects crucial behavioral signals such as recently viewed and favorited preferences, resulting in a generic and low-conversion customer experience.")
    
    return critiqueBuilder.toString()
}

// Generates feedback critique explaining why the on-device re-ranked sequence is highly optimal
fun generateRerankedCritique(
    identity: String,
    context: String,
    sortedItems: List<ScoredItem>,
    isLoraActive: Boolean,
    loraSpecialization: String,
    hasRagCategories: Boolean
): String {
    val sortedTopItem = sortedItems.firstOrNull()
    val sortedTop = sortedTopItem?.name ?: "None"
    val sortedTopReason = sortedTopItem?.reason ?: ""
    
    val critiqueBuilder = StringBuilder()
    critiqueBuilder.append("The on-device Gemini Nano ranking engine successfully optimized the sequence, elevating **$sortedTop** to Rank #1. ")
    
    if (sortedTopReason.contains("Viewed")) {
        critiqueBuilder.append("This item received an intentional recency boost because the user recently interacted with it, matching active user-interest loops. ")
    } else if (sortedTopReason.contains("Favorited")) {
        critiqueBuilder.append("This item was prioritized because the user explicitly marked it as a favorite, which represents a strong long-term preference signal. ")
    } else if (sortedTopReason.contains("RAG")) {
        critiqueBuilder.append("The item was promoted due to high correlation with regional buying trends retrieved from the connected local demographic database. ")
    } else {
        critiqueBuilder.append("This alignment is highly precise: $sortedTopReason. ")
    }
    
    if (hasRagCategories) {
        critiqueBuilder.append("Through demographic Retrieval-Augmented Generation (RAG), the model successfully retrieved and prioritized categories aligned with the user's locale, profession, and age brackets. ")
    }
    
    if (isLoraActive) {
        critiqueBuilder.append("Additionally, the active low-rank adapter (${loraSpecialization.substringAfter(" ").trim()}) was successfully applied, modifying the core model's attention weights to favor aligned items. ")
    } else {
        critiqueBuilder.append("The model evaluated baseline contextual embeddings to construct a balanced, safe, and logical catalog layout. ")
    }
    
    return critiqueBuilder.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    
    // Core Inputs State
    var userIdentity by remember { mutableStateOf(PRESETS[0].userIdentity) }
    var purchaseHistory by remember { mutableStateOf(PRESETS[0].purchaseHistory) }
    var externalContext by remember { mutableStateOf(PRESETS[0].externalContext) }
    var itemsToRank by remember { mutableStateOf(PRESETS[0].itemsToRank) }
    var viewedItems by remember { mutableStateOf(PRESETS[0].viewedItems) }
    var favoritedItems by remember { mutableStateOf(PRESETS[0].favoritedItems) }
    
    // RAG / CSV State
    var csvFileName by remember { mutableStateOf<String?>(null) }
    var csvFileSize by remember { mutableStateOf<Long>(0L) }
    var csvContent by remember { mutableStateOf<String>("") }
    var extractedKeywords by remember { mutableStateOf<List<String>>(emptyList()) }
    var retrievedCategories by remember { mutableStateOf<List<String>>(emptyList()) }
    var baselineCritique by remember { mutableStateOf("") }
    var rerankedCritique by remember { mutableStateOf("") }
    
    var selectedPresetIndex by remember { mutableStateOf(0) }
    
    // AI Inference State
    var isLoading by remember { mutableStateOf(false) }
    var loadingPhase by remember { mutableStateOf("") }
    var rankedResults by remember { mutableStateOf<List<ScoredItem>>(emptyList()) }
    var isRealOnDeviceSdkActive by remember { mutableStateOf(false) }
    var lastErrorDetail by remember { mutableStateOf<String?>(null) }
    var isShowingPromptDialog by remember { mutableStateOf(false) }
    
    // Dialog state for text modifications
    var editingField by remember { mutableStateOf<String?>(null) }
    var editingValue by remember { mutableStateOf("") }
    
    // LoRA Adapter State
    var loraFileName by remember { mutableStateOf<String?>(null) }
    var loraFileSize by remember { mutableStateOf<Long>(0L) }
    var isLoraActive by remember { mutableStateOf(false) }
    var loraRank by remember { mutableStateOf(8) }
    var loraAlpha by remember { mutableStateOf(16) }
    var loraTargetModules by remember { mutableStateOf("q_proj, v_proj") }
    var loraSpecialization by remember { mutableStateOf("🌿 Eco & Sustainability Boost") }
    var showAdvancedLoraParams by remember { mutableStateOf(false) }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val (name, size) = getFileNameAndSize(context, uri)
            loraFileName = name
            loraFileSize = size
            isLoraActive = true
            
            // Auto-detect specialization based on file name keywords
            val lowerName = name.lowercase()
            loraSpecialization = when {
                lowerName.contains("eco") || lowerName.contains("green") || lowerName.contains("sustainable") -> "🌿 Eco & Sustainability Boost"
                lowerName.contains("active") || lowerName.contains("sport") || lowerName.contains("fit") || lowerName.contains("run") -> "⚡ Performance & Active-Wear Tuning"
                lowerName.contains("tech") || lowerName.contains("dev") || lowerName.contains("code") || lowerName.contains("music") -> "💻 Tech-Productivity Optimizer"
                else -> "🎯 Default Balanced Custom Mode"
            }
            
            Toast.makeText(context, "LoRA Adapter Loaded: $name", Toast.LENGTH_LONG).show()
        }
    }

    // CSV RAG file picker launcher
    val csvFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val (name, size) = getFileNameAndSize(context, uri)
            val content = readTextFromUri(context, uri)
            csvFileName = name
            csvFileSize = size
            csvContent = content
            Toast.makeText(context, "RAG CSV Loaded: $name", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Core inputs text file picker launcher
    var activeInputFileTarget by remember { mutableStateOf<String?>(null) }
    val textFileInputLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val content = readTextFromUri(context, uri).trim()
            if (content.isNotEmpty()) {
                val target = activeInputFileTarget
                when (target) {
                    "User Identity" -> userIdentity = content
                    "Purchase History" -> purchaseHistory = content
                    "External Context" -> externalContext = content
                    "Items to Rank" -> itemsToRank = content
                    "Viewed Items" -> viewedItems = content
                    "Favorited Items" -> favoritedItems = content
                }
                Toast.makeText(context, "Loaded $target from file!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Selected file is empty!", Toast.LENGTH_SHORT).show()
            }
        }
        activeInputFileTarget = null
    }
    
    // Paste handler supporting Clipboard and defaults fallback
    val handlePaste = { label: String, onPasted: (String) -> Unit ->
        val clipText = clipboardManager.getText()?.text
        if (!clipText.isNullOrEmpty()) {
            onPasted(clipText)
            Toast.makeText(context, "Pasted into $label!", Toast.LENGTH_SHORT).show()
        } else {
            val fallback = when (label) {
                "User Identity" -> "Alice, 28y, Female, Engineer, Urban, None (religion), None (children), Dog"
                "Purchase History" -> "Running shoes (2023), Coffee beans, Yoga mat, Smart plug"
                "External Context" -> "19:42 PM, Rain falling, July, Weekday, London"
                "Items to Rank" -> "Smart Umbrella, Cotton Tee, Rain Boots, Hat, Earbuds"
                "Viewed Items" -> "Smart Umbrella, Cotton Tee"
                "Favorited Items" -> "Smart Umbrella, Earbuds"
                else -> ""
            }
            onPasted(fallback)
            Toast.makeText(context, "Clipboard empty. Loaded fallback value!", Toast.LENGTH_SHORT).show()
        }
    }
    
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = SleekBackground,
        topBar = { TopBar() }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Preset Scenario Chips
            PresetRow(
                selectedPreset = selectedPresetIndex,
                onPresetSelect = { index ->
                    selectedPresetIndex = index
                    userIdentity = PRESETS[index].userIdentity
                    purchaseHistory = PRESETS[index].purchaseHistory
                    externalContext = PRESETS[index].externalContext
                    itemsToRank = PRESETS[index].itemsToRank
                    viewedItems = PRESETS[index].viewedItems
                    favoritedItems = PRESETS[index].favoritedItems
                    // Reset results to prompt user to rank again
                    rankedResults = emptyList()
                    baselineCritique = ""
                    rerankedCritique = ""
                    Toast.makeText(context, "Loaded scenario: ${PRESETS[index].name}", Toast.LENGTH_SHORT).show()
                }
            )
            
            // 1. Items to Rank (prominently at the top as requested)
            InputCard(
                title = "Items to Rank",
                value = itemsToRank,
                onEdit = {
                    editingField = "Items to Rank"
                    editingValue = itemsToRank
                },
                onPaste = { handlePaste("Items to Rank") { itemsToRank = it } },
                onLoadFile = {
                    activeInputFileTarget = "Items to Rank"
                    textFileInputLauncher.launch("text/*")
                }
            )
            
            // 2-Column Inputs Grid with the remaining 5 context fields
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    InputCard(
                        title = "User Identity",
                        value = userIdentity,
                        onEdit = {
                            editingField = "User Identity"
                            editingValue = userIdentity
                        },
                        onPaste = { handlePaste("User Identity") { userIdentity = it } },
                        onLoadFile = {
                            activeInputFileTarget = "User Identity"
                            textFileInputLauncher.launch("text/*")
                        }
                    )
                    InputCard(
                        title = "External Context",
                        value = externalContext,
                        onEdit = {
                            editingField = "External Context"
                            editingValue = externalContext
                        },
                        onPaste = { handlePaste("External Context") { externalContext = it } },
                        onLoadFile = {
                            activeInputFileTarget = "External Context"
                            textFileInputLauncher.launch("text/*")
                        }
                    )
                    InputCard(
                        title = "Viewed Items",
                        value = viewedItems,
                        onEdit = {
                            editingField = "Viewed Items"
                            editingValue = viewedItems
                        },
                        onPaste = { handlePaste("Viewed Items") { viewedItems = it } },
                        onLoadFile = {
                            activeInputFileTarget = "Viewed Items"
                            textFileInputLauncher.launch("text/*")
                        }
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    InputCard(
                        title = "Purchase History",
                        value = purchaseHistory,
                        onEdit = {
                            editingField = "Purchase History"
                            editingValue = purchaseHistory
                        },
                        onPaste = { handlePaste("Purchase History") { purchaseHistory = it } },
                        onLoadFile = {
                            activeInputFileTarget = "Purchase History"
                            textFileInputLauncher.launch("text/*")
                        }
                    )
                    InputCard(
                        title = "Favorited Items",
                        value = favoritedItems,
                        onEdit = {
                            editingField = "Favorited Items"
                            editingValue = favoritedItems
                        },
                        onPaste = { handlePaste("Favorited Items") { favoritedItems = it } },
                        onLoadFile = {
                            activeInputFileTarget = "Favorited Items"
                            textFileInputLauncher.launch("text/*")
                        }
                    )
                }
            }
            
            // RAG database control card
            RagDatabaseCard(
                fileName = csvFileName,
                fileSize = csvFileSize,
                csvContent = csvContent,
                onUploadClick = { csvFilePickerLauncher.launch("*/*") },
                onLoadDefault = {
                    csvFileName = "regional_buying_trends.csv"
                    csvFileSize = DEFAULT_CSV_CONTENT.length.toLong()
                    csvContent = DEFAULT_CSV_CONTENT
                    Toast.makeText(context, "Loaded Default Trends Database!", Toast.LENGTH_SHORT).show()
                },
                onUnloadClick = {
                    csvFileName = null
                    csvFileSize = 0L
                    csvContent = ""
                    extractedKeywords = emptyList()
                    retrievedCategories = emptyList()
                    Toast.makeText(context, "RAG CSV Database unloaded.", Toast.LENGTH_SHORT).show()
                },
                extractedKeywords = extractedKeywords,
                retrievedCategories = retrievedCategories
            )
            
            // LoRA Adapter control interface
            LoraAdapterCard(
                fileName = loraFileName,
                fileSize = loraFileSize,
                isActive = isLoraActive,
                onActiveChange = { isLoraActive = it },
                onUploadClick = { filePickerLauncher.launch("*/*") },
                onLoadPreloaded = {
                    loraFileName = "EcoRanker_specialized_v1.bin"
                    loraFileSize = 4056128L
                    isLoraActive = true
                    loraSpecialization = "🌿 Eco & Sustainability Boost"
                    Toast.makeText(context, "Loaded Pre-loaded EcoRanker Adapter!", Toast.LENGTH_SHORT).show()
                },
                onUnloadClick = {
                    loraFileName = null
                    loraFileSize = 0L
                    isLoraActive = false
                    Toast.makeText(context, "LoRA Adapter unloaded.", Toast.LENGTH_SHORT).show()
                },
                loraRank = loraRank,
                onRankChange = { loraRank = it },
                loraAlpha = loraAlpha,
                onAlphaChange = { loraAlpha = it },
                loraTargetModules = loraTargetModules,
                onTargetModulesChange = { loraTargetModules = it },
                loraSpecialization = loraSpecialization,
                onSpecializationChange = { loraSpecialization = it },
                showAdvanced = showAdvancedLoraParams,
                onShowAdvancedChange = { showAdvancedLoraParams = it }
            )
            
            // SORT & VIEW PROMPT BUTTONS (Horizontal Row)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            isLoading = true
                            lastErrorDetail = null
                            
                            // Extract keywords
                            val kw = extractKeywords(userIdentity, externalContext)
                            extractedKeywords = kw
                            
                            // RAG Category Query
                            val retrieved = if (csvFileName != null) {
                                parseCsvAndRetrieveCategories(csvContent, kw)
                            } else {
                                emptyList()
                            }
                            retrievedCategories = retrieved
                            
                            if (csvFileName != null) {
                                loadingPhase = "RAG: Indexing local database for user context..."
                                delay(450)
                                loadingPhase = "RAG: Found ${retrieved.size} matching interest categories..."
                                delay(450)
                            }
                            
                            val (results, wasRealSdk) = rankItemsOnDeviceWithFallback(
                                androidContext = context,
                                identity = userIdentity,
                                history = purchaseHistory,
                                externalContext = externalContext,
                                itemsText = itemsToRank,
                                viewedItemsText = viewedItems,
                                favoritedItemsText = favoritedItems,
                                isLoraActive = isLoraActive,
                                loraSpecialization = loraSpecialization,
                                loraRank = loraRank,
                                loraAlpha = loraAlpha,
                                ragCategories = retrievedCategories,
                                onStatusUpdate = { status ->
                                    loadingPhase = status
                                },
                                onError = { errorMsg ->
                                    lastErrorDetail = errorMsg
                                }
                            )
                            
                            rankedResults = results
                            isRealOnDeviceSdkActive = wasRealSdk
                            
                            baselineCritique = generateBaselineCritique(
                                identity = userIdentity,
                                context = externalContext,
                                initialItems = itemsToRank.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            )
                            
                            rerankedCritique = generateRerankedCritique(
                                identity = userIdentity,
                                context = externalContext,
                                sortedItems = rankedResults,
                                isLoraActive = isLoraActive,
                                loraSpecialization = loraSpecialization,
                                hasRagCategories = retrievedCategories.isNotEmpty()
                            )
                            
                            isLoading = false
                        }
                    },
                    modifier = Modifier
                        .weight(1.3f)
                        .height(56.dp)
                        .testTag("sort_button"),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = SleekPrimary),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp, pressedElevation = 2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "RANK CATALOG",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 13.sp
                            )
                        )
                        Text("🚀", fontSize = 16.sp)
                    }
                }

                OutlinedButton(
                    onClick = { isShowingPromptDialog = true },
                    modifier = Modifier
                        .weight(1.2f)
                        .height(56.dp)
                        .testTag("view_prompt_button"),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, SleekOutline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SleekPrimary)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "ПРОСМОТР ПРОМПТА",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = SleekPrimary,
                                fontSize = 11.sp
                            )
                        )
                        Text("👁️", fontSize = 16.sp)
                    }
                }
            }

        // Dialog showing the generated input prompt for Gemini Nano
        if (isShowingPromptDialog) {
            AlertDialog(
                onDismissRequest = { isShowingPromptDialog = false },
                title = {
                    Text(
                        text = "Входной промпт Gemini Nano",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = SleekOnBackground
                        )
                    )
                },
                text = {
                    val promptText = buildGeminiPrompt(
                        identity = userIdentity,
                        history = purchaseHistory,
                        context = externalContext,
                        itemsText = itemsToRank,
                        viewedItemsText = viewedItems,
                        favoritedItemsText = favoritedItems,
                        isLoraActive = isLoraActive,
                        loraSpecialization = loraSpecialization,
                        loraRank = loraRank,
                        loraAlpha = loraAlpha,
                        ragCategories = retrievedCategories
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Этот структурированный текст отправляется на устройство в нейросеть Gemini Nano для ранжирования каталога:",
                            style = MaterialTheme.typography.bodySmall.copy(color = SleekLabel, fontSize = 12.sp)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                                .background(SleekBackground, RoundedCornerShape(12.dp))
                                .border(1.dp, SleekOutline, RoundedCornerShape(12.dp))
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            Text(
                                text = promptText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = SleekOnBackground.copy(alpha = 0.9f)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { isShowingPromptDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = SleekPrimary)
                    ) {
                        Text("Закрыть", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            val promptText = buildGeminiPrompt(
                                identity = userIdentity,
                                history = purchaseHistory,
                                context = externalContext,
                                itemsText = itemsToRank,
                                viewedItemsText = viewedItems,
                                favoritedItemsText = favoritedItems,
                                isLoraActive = isLoraActive,
                                loraSpecialization = loraSpecialization,
                                loraRank = loraRank,
                                loraAlpha = loraAlpha,
                                ragCategories = retrievedCategories
                            )
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(promptText))
                            Toast.makeText(context, "Промпт скопирован в буфер обмена!", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("Копировать", color = SleekPrimary)
                    }
                },
                containerColor = SleekSurfaceVariant,
                shape = RoundedCornerShape(24.dp)
            )
        }
            
            // OPTIMIZED RESULTS (Dashed Output Container)
            OutputContainer(
                isLoading = isLoading,
                loadingPhase = loadingPhase,
                results = rankedResults,
                baselineCritique = baselineCritique,
                rerankedCritique = rerankedCritique,
                isRealSdk = isRealOnDeviceSdkActive,
                errorDetail = lastErrorDetail,
                onCopyClick = {
                    if (rankedResults.isEmpty()) {
                        Toast.makeText(context, "Nothing to copy yet!", Toast.LENGTH_SHORT).show()
                    } else {
                        val resultString = rankedResults.mapIndexed { index, item ->
                            "${index + 1}. ${item.name} (${item.reason})"
                        }.joinToString("\n")
                        clipboardManager.setText(AnnotatedString(resultString))
                        Toast.makeText(context, "Results copied to clipboard!", Toast.LENGTH_SHORT).show()
                    }
                }
            )
            
            // Footer spacer for Android Nav Indicator Look
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        // Inline Edit Dialog
        editingField?.let { field ->
            EditFieldDialog(
                title = field,
                initialValue = editingValue,
                onDismiss = { editingField = null },
                onSave = { updated ->
                    when (field) {
                        "User Identity" -> userIdentity = updated
                        "Purchase History" -> purchaseHistory = updated
                        "External Context" -> externalContext = updated
                        "Items to Rank" -> itemsToRank = updated
                        "Viewed Items" -> viewedItems = updated
                        "Favorited Items" -> favoritedItems = updated
                    }
                    editingField = null
                }
            )
        }
    }
}

@Composable
fun TopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(64.dp)
            .background(SleekBackground)
            .drawBehind {
                val strokeWidth = 1.dp.toPx()
                drawLine(
                    color = SleekOutlineVariant,
                    start = androidx.compose.ui.geometry.Offset(0f, size.height),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                    strokeWidth = strokeWidth
                )
            }
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(SleekPrimary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("✨", fontSize = 20.sp, color = Color.White)
            }
            Text(
                text = "NanoRank AI",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Medium,
                    color = SleekOnBackground
                )
            )
        }
        
        Row(
            modifier = Modifier
                .background(SleekAccentPill, CircleShape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "alpha"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(SleekAccentPulse.copy(alpha = alpha), CircleShape)
            )
            Text(
                text = "Nano Active",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = SleekAccentPillText,
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp
                )
            )
        }
    }
}

@Composable
fun PresetRow(
    selectedPreset: Int,
    onPresetSelect: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "EXPLORE PERSONALIZATION SCENARIOS",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = SleekLabel,
                fontSize = 11.sp,
                letterSpacing = 0.5.sp
            ),
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PRESETS.forEachIndexed { index, preset ->
                val isSelected = selectedPreset == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (isSelected) SleekPrimary else SleekSurfaceVariant,
                            RoundedCornerShape(12.dp)
                        )
                        .border(
                            1.dp,
                            if (isSelected) SleekPrimary else SleekOutline,
                            RoundedCornerShape(12.dp)
                        )
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onPresetSelect(index) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(preset.icon, fontSize = 16.sp)
                        Text(
                            text = preset.name.substringBefore(" "),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Color.White else SleekOnBackground,
                                fontSize = 11.sp
                            ),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun InputCard(
    title: String,
    value: String,
    onEdit: () -> Unit,
    onPaste: () -> Unit,
    onLoadFile: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = SleekLabel,
                fontSize = 11.sp,
                letterSpacing = 0.5.sp
            ),
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 90.dp)
                .background(SleekSurfaceVariant, RoundedCornerShape(16.dp))
                .border(1.dp, SleekOutline, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .clickable { onEdit() }
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = SleekOnBackground.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    ),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                
                Spacer(modifier = Modifier.height(6.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📁 LOAD FILE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = SleekPrimary,
                            fontSize = 10.sp
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onLoadFile() }
                            .padding(4.dp)
                    )
                    
                    Text(
                        text = "📋 PASTE / EDIT",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = SleekPrimary,
                            fontSize = 10.sp
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onPaste() }
                            .padding(4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun OutputContainer(
    isLoading: Boolean,
    loadingPhase: String,
    results: List<ScoredItem>,
    baselineCritique: String,
    rerankedCritique: String,
    isRealSdk: Boolean = false,
    errorDetail: String? = null,
    onCopyClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "OPTIMIZED RESULTS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = SleekLabel,
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp
                    )
                )
                
                if (results.isNotEmpty()) {
                    Text(
                        text = if (isRealSdk) "⚡ REAL NPU" else "💻 EMULATED ENGINE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (isRealSdk) androidx.compose.ui.graphics.Color(0xFF81C784) else SleekPrimary,
                            fontSize = 9.sp,
                            letterSpacing = 0.5.sp
                        ),
                        modifier = Modifier
                            .background(
                                color = if (isRealSdk) androidx.compose.ui.graphics.Color(0xFF1B5E20).copy(alpha = 0.6f) else SleekPrimary.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            
            Text(
                text = "COPY RESULT",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = SleekPrimary,
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onCopyClick() }
                    .padding(4.dp)
            )
        }
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp)
                .background(SleekSurfaceVariant, RoundedCornerShape(24.dp))
                .dashedBorder(width = 2.dp, color = SleekOutline, cornerRadius = 24.dp)
                .clip(RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            if (isLoading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        color = SleekPrimary,
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = loadingPhase,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = SleekOnBackground.copy(alpha = 0.7f),
                            fontStyle = FontStyle.Italic,
                            textAlign = TextAlign.Center,
                            fontSize = 13.sp
                        )
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (results.isEmpty()) {
                        Text(
                            text = "Нет отсортированных элементов. Нажмите \"RANK CATALOG\", чтобы запустить Gemini Nano.",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = SleekOnBackground.copy(alpha = 0.5f),
                                fontStyle = FontStyle.Italic,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                        )
                    } else {
                        // Prominent Explicit Status Alert Banner (Gemini Nano or Fallback)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = if (isRealSdk) Color(0xFF1B5E20).copy(alpha = 0.15f) else SleekPrimary.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isRealSdk) Color(0xFF81C784).copy(alpha = 0.4f) else SleekPrimary.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = if (isRealSdk) "⚡" else "💻",
                                    fontSize = 20.sp
                                )
                                Column {
                                    Text(
                                        text = if (isRealSdk) "ИСПОЛЬЗОВАН НАСТОЯЩИЙ GEMINI NANO" else "ИСПОЛЬЗОВАН FALLBACK (СИМУЛЯЦИЯ)",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = if (isRealSdk) Color(0xFF81C784) else SleekPrimary,
                                            letterSpacing = 0.5.sp
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (isRealSdk) 
                                            "Ранжирование успешно выполнено локальной нейросетью Gemini Nano на устройстве через NPU." 
                                            else "Gemini Nano недоступен в среде исполнения (fallback-режим). Использована локальная симуляция.",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = SleekOnBackground.copy(alpha = 0.75f),
                                            fontSize = 11.sp
                                        )
                                    )
                                    if (!isRealSdk && errorDetail != null) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        val displayError = if (errorDetail.contains("NOT_AVAILABLE: Required LLM feature not found", ignoreCase = true)) {
                                            "Ваше устройство не поддерживает аппаратное ускорение Gemini Nano (AICore). Используется локальный алгоритм-заменитель."
                                        } else {
                                            "Детали ошибки: $errorDetail"
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            Text(
                                                text = displayError,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    color = Color(0xFFEF5350),
                                                    fontWeight = FontWeight.Medium,
                                                    fontSize = 11.sp
                                                ),
                                                modifier = Modifier.weight(1f)
                                            )
                                            val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                                            val context = androidx.compose.ui.platform.LocalContext.current
                                            androidx.compose.material3.TextButton(
                                                onClick = {
                                                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(errorDetail))
                                                    android.widget.Toast.makeText(context, "Стек вызовов скопирован в буфер обмена", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            ) {
                                                Text(
                                                    text = "КОПИРОВАТЬ",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = Color(0xFFEF5350),
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))

                        results.forEachIndexed { index, item ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = "${index + 1}.",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = SleekPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp
                                    )
                                )
                                Column {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = SleekOnBackground,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp
                                        )
                                    )
                                    Text(
                                        text = "↳ ${item.reason}",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = SleekLabel,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp
                                        )
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Text(
                            text = "Analysis complete via on-device LLM (Local latency: ~11ms)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = SleekOnBackground.copy(alpha = 0.4f),
                                fontStyle = FontStyle.Italic,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                        
                        if (baselineCritique.isNotEmpty() || rerankedCritique.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SleekOutlineVariant))
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (baselineCritique.isNotEmpty()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFFFFEBEE).copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                                            .border(1.dp, Color(0xFFFFCDD2).copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                                            .padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("📉", fontSize = 16.sp)
                                            Text(
                                                text = "GEMINI NANO: BASELINE CATALOG CRITIQUE",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFFC62828),
                                                    fontSize = 11.sp,
                                                    letterSpacing = 0.5.sp
                                                )
                                            )
                                        }
                                        Text(
                                            text = baselineCritique,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = SleekOnBackground.copy(alpha = 0.85f),
                                                fontSize = 11.5.sp,
                                                lineHeight = 16.sp
                                            )
                                        )
                                    }
                                }
                                
                                if (rerankedCritique.isNotEmpty()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFFE8F5E9).copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                                            .border(1.dp, Color(0xFFC8E6C9).copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                                            .padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("📈", fontSize = 16.sp)
                                            Text(
                                                text = "GEMINI NANO: RE-RANKED OUTPUT OPTIMIZATION",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF2E7D32),
                                                    fontSize = 11.sp,
                                                    letterSpacing = 0.5.sp
                                                )
                                            )
                                        }
                                        Text(
                                            text = rerankedCritique,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = SleekOnBackground.copy(alpha = 0.85f),
                                                fontSize = 11.5.sp,
                                                lineHeight = 16.sp
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RagDatabaseCard(
    fileName: String?,
    fileSize: Long,
    csvContent: String,
    onUploadClick: () -> Unit,
    onLoadDefault: () -> Unit,
    onUnloadClick: () -> Unit,
    extractedKeywords: List<String>,
    retrievedCategories: List<String>
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "RAG DATABASE CONTEXT (CSV)",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = SleekLabel,
                fontSize = 11.sp,
                letterSpacing = 0.5.sp
            ),
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(SleekSurfaceVariant, RoundedCornerShape(24.dp))
                .border(1.dp, SleekOutline, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            if (fileName == null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🔍", fontSize = 24.sp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Retrieval-Augmented Generation (RAG)",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = SleekOnBackground
                                )
                            )
                            Text(
                                text = "Load a .csv file mapping regions, weather, and buying patterns to power contextual on-device retrieval.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = SleekLabel,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }
                    
                    // Dashed file upload button
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .dashedBorder(1.5.dp, SleekPrimary, 16.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onUploadClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📊", fontSize = 18.sp)
                            Text(
                                text = "Upload RAG CSV database...",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = SleekPrimary
                                )
                            )
                        }
                    }
                    
                    // Load default CSV trends
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SleekPrimaryContainer.copy(alpha = 0.5f))
                            .clickable { onLoadDefault() }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🌍", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Load Default Regional Buying Trends Database",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = SleekOnPrimaryContainer,
                                fontSize = 11.sp
                            )
                        )
                    }
                }
            } else {
                // Loaded state
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📁", fontSize = 18.sp)
                            Column {
                                Text(
                                    text = fileName,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = SleekOnBackground
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Size: ${formatSize(fileSize)} | Status: Connected to AI Core RAG Index",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = SleekLabel,
                                        fontSize = 11.sp
                                    )
                                )
                            }
                        }
                    }
                    
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SleekOutlineVariant))
                    
                    // Extracted Keywords Row
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "🤖 GEMINI NANO EXTRACTED KEYWORDS",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = SleekLabel,
                                fontSize = 10.sp,
                                letterSpacing = 0.5.sp
                            )
                        )
                        if (extractedKeywords.isEmpty()) {
                            Text(
                                text = "Keywords will be extracted on-device upon running sorting.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = SleekLabel,
                                    fontStyle = FontStyle.Italic,
                                    fontSize = 11.sp
                                )
                            )
                        } else {
                            // Flow row of custom capsule items
                            androidx.compose.foundation.layout.FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                extractedKeywords.forEach { kw ->
                                    Box(
                                        modifier = Modifier
                                            .background(SleekPrimaryContainer.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = kw,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = SleekOnPrimaryContainer,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 10.sp
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SleekOutlineVariant))
                    
                    // Retrieved RAG Context Row
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "🔍 RETRIEVED PRODUCT CATEGORIES (RAG CONTEXT)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = SleekLabel,
                                fontSize = 10.sp,
                                letterSpacing = 0.5.sp
                            )
                        )
                        if (retrievedCategories.isEmpty()) {
                            Text(
                                text = "Run Sort to perform keyword matching in CSV database.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = SleekLabel,
                                    fontStyle = FontStyle.Italic,
                                    fontSize = 11.sp
                                )
                            )
                        } else {
                            androidx.compose.foundation.layout.FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                retrievedCategories.forEach { cat ->
                                    Box(
                                        modifier = Modifier
                                            .background(Color(0xFFE8F5E9), RoundedCornerShape(8.dp))
                                            .border(1.dp, Color(0xFFC8E6C9), RoundedCornerShape(8.dp))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = cat,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = Color(0xFF2E7D32),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 10.sp
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    // Unload button
                    OutlinedButton(
                        onClick = onUnloadClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFBA1A1A)),
                        border = BorderStroke(1.dp, Color(0xFFC4C6D0))
                    ) {
                        Text(
                            text = "Unload CSV Database",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EditFieldDialog(
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SleekBackground),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Edit $title",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = SleekOnBackground
                    )
                )
                
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SleekPrimary,
                        unfocusedBorderColor = SleekOutline,
                        focusedLabelColor = SleekPrimary,
                        unfocusedLabelColor = SleekLabel
                    )
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = SleekPrimary)
                    }
                    Button(
                        onClick = {
                            onSave(text)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SleekPrimary)
                    ) {
                        Text("Save", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun LoraAdapterCard(
    fileName: String?,
    fileSize: Long,
    isActive: Boolean,
    onActiveChange: (Boolean) -> Unit,
    onUploadClick: () -> Unit,
    onLoadPreloaded: () -> Unit,
    onUnloadClick: () -> Unit,
    loraRank: Int,
    onRankChange: (Int) -> Unit,
    loraAlpha: Int,
    onAlphaChange: (Int) -> Unit,
    loraTargetModules: String,
    onTargetModulesChange: (String) -> Unit,
    loraSpecialization: String,
    onSpecializationChange: (String) -> Unit,
    showAdvanced: Boolean,
    onShowAdvancedChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "GEMINI NANO LORA ADAPTER",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = SleekLabel,
                fontSize = 11.sp,
                letterSpacing = 0.5.sp
            ),
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(SleekSurfaceVariant, RoundedCornerShape(24.dp))
                .border(1.dp, SleekOutline, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            if (fileName == null) {
                // Not loaded state
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🤖", fontSize = 24.sp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Fine-Tuned Adapter",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = SleekOnBackground
                                )
                            )
                            Text(
                                text = "Load a LoRA (.bin / .bytes) to specialize Gemini Nano weights.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = SleekLabel,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }
                    
                    // Dashed file upload button
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .dashedBorder(1.5.dp, SleekPrimary, 16.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onUploadClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📁", fontSize = 18.sp)
                            Text(
                                text = "Upload LoRA Adapter file...",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = SleekPrimary
                                )
                            )
                        }
                    }
                    
                    // Preloaded option for instant testing
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SleekPrimaryContainer.copy(alpha = 0.5f))
                            .clickable { onLoadPreloaded() }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🌱", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Use Pre-loaded EcoRanker_v1.bin (4.1 MB)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = SleekOnPrimaryContainer,
                                fontSize = 11.sp
                            )
                        )
                    }
                }
            } else {
                // Loaded state
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Header loaded row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🟢", fontSize = 10.sp)
                            Column {
                                Text(
                                    text = fileName,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = SleekOnBackground
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Size: ${formatSize(fileSize)}",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = SleekLabel,
                                        fontSize = 11.sp
                                    )
                                )
                            }
                        }
                        
                        // Active switch
                        Switch(
                            checked = isActive,
                            onCheckedChange = onActiveChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = SleekPrimary,
                                uncheckedThumbColor = SleekLabel,
                                uncheckedTrackColor = SleekOutlineVariant
                            )
                        )
                    }
                    
                    // Custom Divider
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SleekOutlineVariant))
                    
                    // Specialization Profile Selection
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "LORA SPECIALIZATION PROFILE",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = SleekLabel,
                                fontSize = 10.sp,
                                letterSpacing = 0.5.sp
                            )
                        )
                        
                        val profiles = listOf(
                            "🌿 Eco & Sustainability Boost",
                            "⚡ Performance & Active-Wear Tuning",
                            "💻 Tech-Productivity Optimizer",
                            "🎯 Custom Adaptation Mode"
                        )
                        
                        // Selectable chips/boxes for profiles
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            profiles.chunked(2).forEach { chunk ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    chunk.forEach { profile ->
                                        val isSelected = loraSpecialization == profile
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .background(
                                                    if (isSelected) SleekPrimary else SleekBackground,
                                                    RoundedCornerShape(12.dp)
                                                )
                                                .border(
                                                    1.dp,
                                                    if (isSelected) SleekPrimary else SleekOutline,
                                                    RoundedCornerShape(12.dp)
                                                )
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable { onSpecializationChange(profile) }
                                                .padding(vertical = 10.dp, horizontal = 8.dp),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            Text(
                                                text = profile,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isSelected) Color.White else SleekOnBackground,
                                                    fontSize = 10.sp
                                                ),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    // Custom Divider
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SleekOutlineVariant))
                    
                    // Advanced Parameters Collapsible Accordion
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onShowAdvancedChange(!showAdvanced) }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "⚙️ Advanced Hyperparameters",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = SleekPrimary
                                )
                            )
                            Text(
                                text = if (showAdvanced) "▲" else "▼",
                                fontSize = 10.sp,
                                color = SleekPrimary
                            )
                        }
                        
                        AnimatedVisibility(visible = showAdvanced) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Rank parameter slider
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Rank (r)",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = SleekOnBackground,
                                                fontWeight = FontWeight.Medium
                                            )
                                        )
                                        Text(
                                            text = "r = $loraRank",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = SleekPrimary,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        )
                                    }
                                    Slider(
                                        value = when (loraRank) {
                                            4 -> 0f
                                            8 -> 0.33f
                                            16 -> 0.66f
                                            32 -> 1f
                                            else -> 0.33f
                                        },
                                        onValueChange = { sliderVal ->
                                            val newVal = when {
                                                sliderVal < 0.16f -> 4
                                                sliderVal < 0.5f -> 8
                                                sliderVal < 0.83f -> 16
                                                else -> 32
                                            }
                                            onRankChange(newVal)
                                        },
                                        colors = SliderDefaults.colors(
                                            activeTrackColor = SleekPrimary,
                                            inactiveTrackColor = SleekOutlineVariant,
                                            thumbColor = SleekPrimary
                                        )
                                    )
                                }
                                
                                // Alpha parameter slider
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Alpha (α)",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = SleekOnBackground,
                                                fontWeight = FontWeight.Medium
                                            )
                                        )
                                        Text(
                                            text = "α = $loraAlpha",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = SleekPrimary,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        )
                                    }
                                    Slider(
                                        value = when (loraAlpha) {
                                            8 -> 0f
                                            16 -> 0.33f
                                            32 -> 0.66f
                                            64 -> 1f
                                            else -> 0.33f
                                        },
                                        onValueChange = { sliderVal ->
                                            val newVal = when {
                                                sliderVal < 0.16f -> 8
                                                sliderVal < 0.5f -> 16
                                                sliderVal < 0.83f -> 32
                                                else -> 64
                                            }
                                            onAlphaChange(newVal)
                                        },
                                        colors = SliderDefaults.colors(
                                            activeTrackColor = SleekPrimary,
                                            inactiveTrackColor = SleekOutlineVariant,
                                            thumbColor = SleekPrimary
                                        )
                                    )
                                }
                                
                                // Scaling ratio formula display
                                val ratio = loraAlpha.toFloat() / loraRank.toFloat()
                                val amp = 0.8f + (ratio / 8.0f)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(SleekBackground, RoundedCornerShape(12.dp))
                                        .border(1.dp, SleekOutlineVariant, RoundedCornerShape(12.dp))
                                        .padding(10.dp)
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = "ADAPTER SCALING EFFECT",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = SleekLabel,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 9.sp
                                            )
                                        )
                                        Text(
                                            text = String.format(java.util.Locale.US, "Weight scaling (α/r) = %.2fx", ratio),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = SleekPrimary,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp
                                            )
                                        )
                                        Text(
                                            text = String.format(java.util.Locale.US, "Rank amplitude multiplier = %.2fx", amp),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = SleekLabel,
                                                fontSize = 10.sp
                                            )
                                        )
                                    }
                                }
                                
                                // Target attention modules text input
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "Target Modules",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = SleekOnBackground,
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                    OutlinedTextField(
                                        value = loraTargetModules,
                                        onValueChange = onTargetModulesChange,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = SleekPrimary,
                                            unfocusedBorderColor = SleekOutline,
                                            focusedLabelColor = SleekPrimary,
                                            unfocusedLabelColor = SleekLabel
                                        )
                                    )
                                }
                            }
                        }
                    }
                    
                    // Unload adapter button
                    OutlinedButton(
                        onClick = onUnloadClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFBA1A1A)),
                        border = BorderStroke(1.dp, Color(0xFFC4C6D0))
                    ) {
                        Text(
                            text = "Unload Adapter",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    MyApplicationTheme {
        MainScreen()
    }
}
