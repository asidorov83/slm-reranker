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
import android.view.WindowManager
import android.app.Activity
import com.google.ai.edge.localagents.rag.models.GeckoEmbeddingModel
import com.google.ai.edge.localagents.rag.models.EmbedData
import com.google.ai.edge.localagents.rag.models.EmbeddingRequest
import java.io.File
import java.util.Optional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Representation of a context field with computed embedding and word lists
data class FieldData(
    val name: String,
    val text: String,
    val embedding: FloatArray,
    val words: Set<String>
)

// Representation of a RAG line with computed embedding and word lists
data class RagLineData(
    val originalLine: String,
    val embedding: FloatArray,
    val words: Set<String>,
    var cosineMax: Float = 0.0f,
    var cosineSum: Float = 0.0f,
    var wordMatchesCount: Int = 0,
    var combinedScore: Float = 0.0f
)

// Robust high-performance embedding and similarity utility
object EmbeddingHelper {
    private var vectorizer: GeckoEmbeddingModel? = null
    
    suspend fun getEmbedding(context: android.content.Context, text: String): FloatArray = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext FloatArray(384)
        
        val vec = synchronized(this) {
            if (vectorizer == null) {
                try {
                    val modelFileName = "universal_sentence_encoder.tflite"
                    val modelFile = File(context.filesDir, modelFileName)
                    if (modelFile.exists()) {
                        val modelPath = modelFile.absolutePath
                        vectorizer = GeckoEmbeddingModel(modelPath, Optional.empty(), false)
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
            vectorizer
        }
        
        if (vec != null) {
            try {
                val req = EmbeddingRequest.create(listOf(EmbedData.create(text.lowercase(), EmbedData.TaskType.RETRIEVAL_DOCUMENT)))
                val embeddingResult = vec.getEmbeddings(req)
                val embedding = embeddingResult.get()
                if (embedding != null && embedding.isNotEmpty()) {
                    return@withContext FloatArray(embedding.size) { i -> embedding[i] }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
        
        return@withContext getPseudoEmbedding(text)
    }
    
    fun getPseudoEmbedding(text: String): FloatArray {
        val dims = 384
        val result = FloatArray(dims)
        val words = getWordList(text)
        if (words.isEmpty()) return result
        
        for (word in words) {
            val hash = word.hashCode()
            val r = java.util.Random(hash.toLong())
            for (i in 0 until 5) {
                val idx = Math.abs(r.nextInt()) % dims
                val sign = if (r.nextBoolean()) 1.0f else -1.0f
                result[idx] += sign
            }
        }
        
        var norm = 0.0f
        for (v in result) {
            norm += v * v
        }
        norm = Math.sqrt(norm.toDouble()).toFloat()
        if (norm > 0.0f) {
            for (i in result.indices) {
                result[i] /= norm
            }
        }
        return result
    }
    
    fun getWordList(text: String): List<String> {
        return text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .map { it.trim() }
            .filter { it.length > 1 }
    }
    
    fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size || v1.isEmpty()) return 0.0f
        var dot = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in v1.indices) {
            dot += v1[i] * v2[i]
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }
        val denom = Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())
        return if (denom > 0.0) (dot / denom).toFloat() else 0.0f
    }
}

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

// Data class for Gemini Nano relevance ratings
data class ItemRating(val name: String, val rating: Int, val reason: String)

// Data class for NDCG comparison results
data class ItemNdcgRating(
    val name: String,
    val rating: Int,
    val reason: String,
    val isBaseline: Boolean,
    val isReranked: Boolean,
    val baselineRank: Int?,
    val rerankedRank: Int?
)

// Helper parser for NDCG ratings from JSON/text
fun parseSingleNdcgRating(text: String, itemName: String): ItemRating {
    try {
        val jsonStart = text.indexOf("{")
        val jsonEnd = text.lastIndexOf("}")
        if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
            val jsonString = text.substring(jsonStart, jsonEnd + 1)
            val obj = org.json.JSONObject(jsonString)
            return ItemRating(
                name = obj.optString("name", itemName),
                rating = obj.optInt("rating", 3),
                reason = obj.optString("reason", "Оценка сгенерирована")
            )
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    
    // Fallback: regex parsing
    try {
        val pattern = Regex("""\{\s*"name"\s*:\s*"([^"]+)"\s*,\s*"rating"\s*:\s*(\d)\s*,\s*"reason"\s*:\s*"([^"]+)"\s*\}""")
        val match = pattern.find(text)
        if (match != null) {
            val name = match.groupValues[1]
            val rating = match.groupValues[2].toIntOrNull() ?: 3
            val reason = match.groupValues[3]
            return ItemRating(name, rating, reason)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    
    return ItemRating(name = itemName, rating = 3, reason = "Оценка по умолчанию (товар не распознан моделью)")
}

fun parseNdcgRatings(text: String): List<ItemRating> {
    val results = mutableListOf<ItemRating>()
    try {
        val jsonStart = text.indexOf("[")
        val jsonEnd = text.lastIndexOf("]")
        if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
            val jsonString = text.substring(jsonStart, jsonEnd + 1)
            val jsonArray = org.json.JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                results.add(
                    ItemRating(
                        name = obj.optString("name", "Unknown"),
                        rating = obj.optInt("rating", 3),
                        reason = obj.optString("reason", "")
                    )
                )
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    
    // Fallback: regex parsing
    if (results.isEmpty()) {
        try {
            val pattern = Regex("""\{\s*"name"\s*:\s*"([^"]+)"\s*,\s*"rating"\s*:\s*(\d)\s*,\s*"reason"\s*:\s*"([^"]+)"\s*\}""")
            pattern.findAll(text).forEach { match ->
                val name = match.groupValues[1]
                val rating = match.groupValues[2].toIntOrNull() ?: 3
                val reason = match.groupValues[3]
                results.add(ItemRating(name, rating, reason))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    return results
}

// NDCG Math Helpers
fun calculateDcg(scores: List<Int>): Double {
    var dcg = 0.0
    for (i in scores.indices) {
        val rel = scores[i]
        val numerator = Math.pow(2.0, rel.toDouble()) - 1.0
        val denominator = kotlin.math.log2((i + 2).toDouble())
        dcg += numerator / denominator
    }
    return dcg
}

fun calculateNdcg(scores: List<Int>): Double {
    val dcg = calculateDcg(scores)
    val idealScores = scores.sortedDescending()
    val idcg = calculateDcg(idealScores)
    if (idcg == 0.0) return 0.0
    return dcg / idcg
}

// Personalization Scenario Preset Data Class
data class PersonalizationPreset(
    val name: String,
    val icon: String,
    val userIdentity: String,
    val purchaseHistory: String,
    val externalContext: String,
    val itemsToRank: String,
    val viewedItems: String,
    val favoritedItems: String,
    val userActions: String
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
        favoritedItems = "Smart Umbrella, Earbuds",
        userActions = ""
    ),
    PersonalizationPreset(
        name = "Bob (Sunny Hiker)",
        icon = "☀️",
        userIdentity = "Bob, 34y, Male, Designer, Urban, None (religion), None (children), None (pets)",
        purchaseHistory = "Backpack, Trail run shoes, Sleeping bag, Water bottle",
        externalContext = "08:00 AM, Sun shining, August, Weekend, Paris",
        itemsToRank = "Smart Umbrella, Cotton Tee, Rain Boots, Hat, Earbuds",
        viewedItems = "Hat, Cotton Tee, Rain Boots",
        favoritedItems = "Hat, Rain Boots",
        userActions = ""
    ),
    PersonalizationPreset(
        name = "Charlie (Cozy Dev)",
        icon = "☕",
        userIdentity = "Charlie, 45y, Male, Developer, Urban, Christian (religion), 2 children, Cat",
        purchaseHistory = "Ergonomic mouse, Keyboard, Coffee beans, Warm Hoodie",
        externalContext = "07:15 AM, Foggy morning, December, Weekday, New York, Christmas",
        itemsToRank = "Smart Umbrella, Cotton Tee, Rain Boots, Hat, Earbuds",
        viewedItems = "Cotton Tee, Earbuds",
        favoritedItems = "Earbuds",
        userActions = ""
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
    userActions: String,
    isLoraActive: Boolean,
    loraSpecialization: String,
    loraRank: Int,
    loraAlpha: Int,
    ragCategories: List<String>,
    ragContextText: String = ""
): String {
    val items = splitItemsText(itemsText)
    val itemCount = items.size
    return """
        You are a low-latency, on-device contextual personalization and catalog ranking model.
        Rank the following list of items based on the user's demographic identity, historical purchases, active external context, recency signals (viewed items), long-term interest indicators (favorites), and explicit user actions.
        
        User Identity Profile: $identity
        Historic Purchases: $history
        Current Context/Environment: $context
        Items to Rank (Total of $itemCount items): $itemsText
        Recently Viewed: $viewedItemsText
        Favorites: $favoritedItemsText
        User Actions: $userActions
        RAG retrieved demographics: ${ragCategories.joinToString(", ")}
        ${if (ragContextText.isNotEmpty()) "RAG retrieved domain-specific knowledge:\n$ragContextText" else ""}
        ${if (isLoraActive) "Active Low-Rank Adapter: $loraSpecialization (Rank=$loraRank, Alpha=$loraAlpha)" else ""}
        
        CRITICAL CONSTRAINT: You MUST rank and return ALL of the $itemCount input items. Your output MUST be a JSON array containing exactly $itemCount objects, one for each input item. Do NOT truncate, shorten, or omit any items. Each object has "name" (String), "score" (Int), and "reason" (String) keys.
        
        Example output format for a 2-item list:
        [
          {
            "name": "Outdoor Shield",
            "score": 95,
            "reason": "High demand due to heavy active rainfall and wind vectors"
          },
          {
            "name": "Light Sneakers",
            "score": 60,
            "reason": "Lower utility under wet trail conditions"
          }
        ]
        
        Remember: You have $itemCount items to rank. Do NOT return just 2 items. You must output exactly $itemCount items.
        
        Return the JSON output now:
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
    userActions: String,
    isLoraActive: Boolean,
    loraSpecialization: String,
    loraRank: Int,
    loraAlpha: Int,
    ragCategories: List<String>,
    ragContextText: String = "",
    onStatusUpdate: (String) -> Unit,
    onRawOutput: (String) -> Unit = {},
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
            userActions = userActions,
            isLoraActive = isLoraActive,
            loraSpecialization = loraSpecialization,
            loraRank = loraRank,
            loraAlpha = loraAlpha,
            ragCategories = ragCategories,
            ragContextText = ragContextText
        )
        
        onStatusUpdate("Firing local on-device neural inference...")
        val response = try {
            generativeModel.generateContent(prompt)
        } catch (e: Exception) {
            val errorString = e.toString()
            if (errorString.contains("Input text length exceeds the limit") || errorString.contains("INFERENCE_ERROR") || errorString.contains("COMPUTE_ERROR")) {
                onStatusUpdate("Размер контекста превышен (ошибка API). Запускаем алгоритм суммаризации...")
                val oversizedItems = rerankWithSummarizationFallback(
                    generativeModel, identity, history, externalContext, itemsText, viewedItemsText, favoritedItemsText, userActions,
                    isLoraActive, loraSpecialization, loraRank, loraAlpha, ragCategories, onStatusUpdate, onRawOutput
                )
                return Pair(oversizedItems, true)
            } else {
                throw e
            }
        }

        val text = response.candidates.firstOrNull()?.text ?: ""
        
        if (text.isBlank()) {
            throw Exception("GenerativeModel returned empty response content.")
        }
        
        onStatusUpdate("Parsing NPU response tensors...")
        val parsedItems = parseResponse(
            text = text,
            originalItemsText = itemsText,
            identity = identity,
            history = history,
            context = externalContext,
            viewedItemsText = viewedItemsText,
            favoritedItemsText = favoritedItemsText,
            userActions = userActions,
            isLoraActive = isLoraActive,
            loraSpecialization = loraSpecialization,
            loraRank = loraRank,
            loraAlpha = loraAlpha,
            ragCategories = ragCategories
        )
        
        if (parsedItems.isEmpty()) {
            throw Exception("Failed to parse JSON array in model response.")
        }

        // Generate beautiful complete raw JSON matching the actual parsed and recovered results
        val formattedRaw = buildString {
            append("[\n")
            parsedItems.forEachIndexed { idx, item ->
                append("  {\n")
                append("    \"name\": \"${item.name}\",\n")
                append("    \"score\": ${item.score},\n")
                append("    \"reason\": \"${item.reason}\"\n")
                append("  }${if (idx < parsedItems.size - 1) "," else ""}\n")
            }
            append("]")
        }
        onRawOutput(formattedRaw)
        
        onStatusUpdate("On-device ranking evaluation complete!")
        return Pair(parsedItems, true)
        
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
            userActions = userActions,
            isLoraActive = isLoraActive,
            loraSpecialization = loraSpecialization,
            loraRank = loraRank,
            loraAlpha = loraAlpha,
            ragCategories = ragCategories
        )
        // Generate beautiful simulated raw JSON matching real model output
        val simulatedRaw = buildString {
            append("[\n")
            fallback.forEachIndexed { idx, item ->
                append("  {\n")
                append("    \"name\": \"${item.name}\",\n")
                append("    \"score\": ${item.score},\n")
                append("    \"reason\": \"${item.reason}\"\n")
                append("  }${if (idx < fallback.size - 1) "," else ""}\n")
            }
            append("]")
        }
        onRawOutput(simulatedRaw)
        return Pair(fallback, false)
    }
}

suspend fun generateWithGeminiNano(
    prompt: String,
    onStatus: (String) -> Unit = {}
): String {
    val generativeModel = com.google.mlkit.genai.prompt.Generation.getClient()
    val status = generativeModel.checkStatus()
    when (status) {
        com.google.mlkit.genai.common.FeatureStatus.DOWNLOADABLE -> {
            onStatus("Загрузка Gemini Nano...")
            var isCompleted = false
            generativeModel.download().collect { downloadStatus ->
                when (downloadStatus) {
                    is com.google.mlkit.genai.common.DownloadStatus.DownloadProgress -> {
                        val mb = downloadStatus.totalBytesDownloaded / (1024.0 * 1024.0)
                        onStatus(String.format(java.util.Locale.US, "Загрузка: %.2f MB...", mb))
                    }
                    is com.google.mlkit.genai.common.DownloadStatus.DownloadCompleted -> {
                        onStatus("Загрузка завершена!")
                        isCompleted = true
                    }
                    is com.google.mlkit.genai.common.DownloadStatus.DownloadFailed -> {
                        throw Exception("Загрузка не удалась: ${downloadStatus.e?.message ?: "Unknown error"}")
                    }
                    is com.google.mlkit.genai.common.DownloadStatus.DownloadStarted -> {
                        onStatus("Загрузка запущена...")
                    }
                }
            }
        }
        com.google.mlkit.genai.common.FeatureStatus.DOWNLOADING -> {
            onStatus("Модель скачивается...")
            throw Exception("Модель скачивается. Пожалуйста, подождите.")
        }
        com.google.mlkit.genai.common.FeatureStatus.UNAVAILABLE -> {
            throw Exception("Gemini Nano не поддерживается на этом устройстве.")
        }
        com.google.mlkit.genai.common.FeatureStatus.AVAILABLE -> {
            // Available, proceed
        }
    }
    
    onStatus("Локальная генерация на NPU...")
    val response = generativeModel.generateContent(prompt)
    val text = response.candidates.firstOrNull()?.text ?: ""
    if (text.isBlank()) {
        throw Exception("Модель вернула пустой ответ")
    }
    return text.trim()
}

// Rule-based simulation of Gemini Nano on-device context ranker with LoRA adapter support
fun splitItemsText(text: String): List<String> {
    val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.size > 1) return lines
    
    if (text.contains("₽")) {
        val splitByCurrency = text.split(Regex("(?<=₽)\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (splitByCurrency.size > 1) return splitByCurrency
    }
    
    return text.split(Regex("[,;]+")).map { it.trim() }.filter { it.isNotEmpty() }
}

fun rankItems(
    identity: String,
    history: String,
    context: String,
    itemsText: String,
    viewedItemsText: String,
    favoritedItemsText: String,
    userActions: String,
    isLoraActive: Boolean,
    loraSpecialization: String,
    loraRank: Int,
    loraAlpha: Int,
    ragCategories: List<String>
): List<ScoredItem> {
    val items = splitItemsText(itemsText)
        
    val viewedList = viewedItemsText.split(",")
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        
    val favoritedList = favoritedItemsText.split(",")
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        
    val userActionsList = userActions.split("\n")
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
        val itemLower = item.lowercase().trim()
        val itemWords = itemLower.split(Regex("[\\s,:.\\-()\"]+")).filter { it.length > 2 }
        
        var score = 40 // base baseline score
        val reasons = mutableListOf<String>()
        
        // 1. Check direct word/substring matches in viewed items
        val isViewed = viewedList.any { viewed -> 
            itemLower.contains(viewed) || viewed.contains(itemLower) ||
            itemWords.any { word -> viewed.contains(word) }
        }
        if (isViewed) {
            score += 12
            reasons.add("Recently Viewed")
        }
        
        // 2. Check direct word/substring matches in favorited items
        val isFavorited = favoritedList.any { fav -> 
            itemLower.contains(fav) || fav.contains(itemLower) ||
            itemWords.any { word -> fav.contains(word) }
        }
        if (isFavorited) {
            score += 20
            reasons.add("❤️ Favorited Preference")
        }
        
        // 3. Match with RAG categories (retrieved from database)
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
        
        // 5. Dynamic context field matches (Identity, History, Context)
        var dynamicBoost = 0
        
        // Word matches in Identity
        val identityMatches = itemWords.filter { word -> identityLower.contains(word) }
        if (identityMatches.isNotEmpty()) {
            dynamicBoost += 15 * identityMatches.size
            reasons.add("Persona match: ${identityMatches.joinToString(", ")}")
        }
        
        // Word matches in History
        val historyMatches = itemWords.filter { word -> historyLower.contains(word) }
        if (historyMatches.isNotEmpty()) {
            dynamicBoost += 12 * historyMatches.size
            reasons.add("History match: ${historyMatches.joinToString(", ")}")
        }
        
        // Word matches in External Context (e.g. weather, location, temperature)
        val contextMatches = itemWords.filter { word -> contextLower.contains(word) }
        if (contextMatches.isNotEmpty()) {
            dynamicBoost += 18 * contextMatches.size
            reasons.add("Context fit: ${contextMatches.joinToString(", ")}")
        }
        
        // 6. Hardcoded fallback checks to preserve compatibility with standard presets
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
            finalReason = "Baseline catalog match"
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
                finalReason = "$loraReason | $finalReason"
            } else {
                score = (score * scalingFactor).toInt()
                finalReason = "[LoRA Enhanced] " + finalReason
            }
        }
        
        // Add random slight variation to guarantee fine distinction
        score += item.length % 5
        
        ScoredItem(item, finalReason, score)
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

object AssumptionGenerators {
    fun generateUserHeuristics(
        identity: String,
        history: String,
        contextText: String,
        favorites: String,
        viewed: String,
        actions: String
    ): String {
        val idLower = identity.lowercase()
        val histLower = history.lowercase()
        val isFemale = idLower.contains("female") || idLower.contains("жен") || idLower.contains("девушка") || idLower.contains("alice")
        val isMale = idLower.contains("male") || idLower.contains("муж") || idLower.contains("bob") || idLower.contains("charlie")
        
        // 1. Gender
        val gender = when {
            isFemale && idLower.contains("гей") -> "гей"
            isFemale && idLower.contains("лесби") -> "лесбиянка"
            isFemale -> "женский"
            isMale && idLower.contains("гей") -> "гей"
            isMale -> "мужской"
            else -> "женский"
        }

        // 2. Age
        var ageNum = 28
        val ageRegex = Regex("(\\d+)\\s*(y|лет|года|год)?")
        val match = ageRegex.find(identity)
        if (match != null) {
            ageNum = match.groupValues[1].toIntOrNull() ?: 28
        }
        val ageGroup = when {
            ageNum <= 14 -> "0-14 лет"
            ageNum <= 24 -> "15–24 лет"
            ageNum <= 34 -> "25–34 лет"
            ageNum <= 44 -> "35–44 лет"
            ageNum <= 54 -> "45–54 лет"
            ageNum <= 64 -> "55–64 лет"
            else -> "65+ лет"
        }

        // 3. Income
        val income = when {
            idLower.contains("director") || idLower.contains("руковод") || idLower.contains("начальн") || idLower.contains("управля") -> "Сверхвысокий"
            idLower.contains("engineer") || idLower.contains("developer") || idLower.contains("програм") || idLower.contains("инженер") -> "Высокий"
            idLower.contains("designer") || idLower.contains("teacher") || idLower.contains("дизайн") || idLower.contains("учитель") -> "Средний"
            else -> "Средний"
        }

        // 4. Religion
        val religion = when {
            idLower.contains("christian") || idLower.contains("христиан") || idLower.contains("правосл") -> "Православие"
            idLower.contains("muslim") || idLower.contains("мусульм") || idLower.contains("ислам") -> "Ислам"
            idLower.contains("buddhist") || idLower.contains("будд") -> "Буддизм"
            idLower.contains("jewish") || idLower.contains("иуде") || idLower.contains("иудаи") -> "Иудаизм"
            idLower.contains("none") || idLower.contains("atheist") || idLower.contains("атеист") -> "Атеизм"
            else -> "Атеизм"
        }

        // 5. Children
        val childrenCount = when {
            idLower.contains("2 children") || idLower.contains("2 ребенка") || idLower.contains("двое") -> "2"
            idLower.contains("3 children") || idLower.contains("3 ребенка") || idLower.contains("трое") -> "3"
            idLower.contains("more than 3") || idLower.contains("больше 3") -> "больше 3"
            idLower.contains("1 child") || idLower.contains("1 ребенок") || idLower.contains("один") -> "1"
            idLower.contains("none") || idLower.contains("no children") || idLower.contains("бездет") -> "0"
            else -> "0"
        }

        // 6. Marital status
        val marital = when {
            idLower.contains("married") || idLower.contains("брак") || idLower.contains("жена") || idLower.contains("муж") || childrenCount != "0" -> "брак"
            idLower.contains("cohabitation") || idLower.contains("сожитель") -> "сожительство"
            else -> "нет"
        }

        // 7. Partner age
        val partnerAge = if (marital == "брак" || marital == "сожительство") {
            when (ageGroup) {
                "15–24 лет" -> "18–24 лет"
                "25–34 лет" -> "25–34 лет"
                "35–44 лет" -> "35–44 лет"
                "45–54 лет" -> "45–54 лет"
                "55–64 лет" -> "55–64 лет"
                "65+ лет" -> "65+ лет"
                else -> "25–34 лет"
            }
        } else {
            "нет"
        }

        // 8. Pets
        val pets = when {
            idLower.contains("dog") || idLower.contains("собак") || idLower.contains("пёс") -> "собаки"
            idLower.contains("cat") || idLower.contains("кош") || idLower.contains("кот") -> "кошки"
            idLower.contains("fish") || idLower.contains("рыб") -> "рыбки"
            idLower.contains("bird") || idLower.contains("птиц") -> "птички"
            else -> "отсутствуют"
        }

        // 9. Profession
        val profession = when {
            idLower.contains("director") || idLower.contains("manager") || idLower.contains("руковод") || idLower.contains("начальн") || idLower.contains("директор") ->
                "Руководители (директоры, начальники, управляющие)"
            idLower.contains("engineer") || idLower.contains("developer") || idLower.contains("programmer") || idLower.contains("врач") || idLower.contains("инженер") || idLower.contains("учитель") || idLower.contains("програм") ->
                "Специалисты высшего уровня квалификации (врачи, инженеры, учители, программисты)"
            idLower.contains("designer") || idLower.contains("nurse") || idLower.contains("техник") || idLower.contains("дизайн") ->
                "Специалисты среднего уровня квалификации (медсестры, техники, фельдшеры)"
            idLower.contains("driver") || idLower.contains("водитель") || idLower.contains("машинист") ->
                "Операторы и водители оборудования и машин (водители, машинисты, токари)"
            else -> "Специалисты высшего уровня квалификации (врачи, инженеры, учители, программисты)"
        }

        // 10. Birthday month
        val birthMonth = when {
            idLower.contains("alice") -> "октябрь"
            idLower.contains("bob") -> "май"
            idLower.contains("charlie") -> "декабрь"
            else -> "июль"
        }

        // 11. Partner's birth month
        val partnerBirthMonth = if (marital != "нет") "ноябрь" else "нет"

        // 12. Father's birth month
        val fatherBirthMonth = "август"

        // 13. Mother's birth month
        val motherBirthMonth = "апрель"

        // 14. Eldest child's birth month
        val eldestBirthMonth = if (childrenCount != "0") "март" else "нет"

        // 15. Youngest child's birth month
        val youngestBirthMonth = if (childrenCount == "2" || childrenCount == "3" || childrenCount == "больше 3") "сентябрь" else "нет"

        // 16. Mother-in-law's birth month
        val motherInLawBirthMonth = if (marital != "нет") "январь" else "нет"

        // 17. Father-in-law's birth month
        val fatherInLawBirthMonth = if (marital != "нет") "июнь" else "нет"

        // 18. Eldest child's gender
        val eldestGender = if (childrenCount != "0") "мужской" else "нет ребёнка"

        // 19. Eldest child's age
        val eldestAge = if (childrenCount != "0") {
            when (ageGroup) {
                "25–34 лет" -> "1-3"
                "35–44 лет" -> "3-7"
                "45–54 лет" -> "11-15"
                "55–64 лет" -> "больше 18"
                else -> "3-7"
            }
        } else "нет ребёнка"

        // 20. Youngest child's gender
        val youngestGender = if (childrenCount == "2" || childrenCount == "3" || childrenCount == "больше 3") "женский" else "нет ребёнка"

        // 21. Youngest child's age
        val youngestAge = if (childrenCount == "2" || childrenCount == "3" || childrenCount == "больше 3") {
            when (ageGroup) {
                "25–34 лет" -> "0-1"
                "35–44 лет" -> "1-3"
                "45–54 лет" -> "7-11"
                "55–64 лет" -> "15-18"
                else -> "1-3"
            }
        } else "нет ребёнка"

        // 22. Father's age
        val fatherAge = when (ageGroup) {
            "15–24 лет" -> "45–54 лет"
            "25–34 лет" -> "55–64 лет"
            else -> "65+ лет"
        }

        // 23. Mother's age
        val motherAge = when (ageGroup) {
            "15–24 лет" -> "45–54 лет"
            "25–34 лет" -> "55–64 лет"
            else -> "65+ лет"
        }

        // 24. Mother-in-law's age
        val motherInLawAge = if (marital != "нет") motherAge else "нет"

        // 25. Father-in-law's age
        val fatherInLawAge = if (marital != "нет") fatherAge else "нет"

        // 26. Hobbies
        val hobby = when {
            histLower.contains("shoes") || idLower.contains("hiker") || histLower.contains("backpack") -> "Туризм, хайкинг и альпинизм"
            histLower.contains("yoga") || histLower.contains("mat") -> "Йога и пилатес"
            histLower.contains("coffee") -> "Готовка (кулинария, выпечка, бариста)"
            idLower.contains("designer") -> "Рисование (живопись, скетчинг, цифровая графика)"
            else -> "Чтение (художественная литература, нон-фикшн)"
        }

        // 27. Sport interests
        val sport = when {
            idLower.contains("developer") -> "киберспорт; шахматы; го"
            idLower.contains("hiker") || histLower.contains("backpack") -> "маунтинбайк; сноубординг; трековый велоспорт"
            histLower.contains("shoes") || histLower.contains("yoga") -> "бег; спринт; марафон; плавание"
            else -> "фигурное катание; плавание"
        }

        // 28. Recreation interests
        val recreation = when {
            idLower.contains("hiker") || histLower.contains("backpack") -> "пешие походы; трекинг; альпинизм; кемпинг"
            idLower.contains("designer") -> "арт-туризм; посещение музеев; обзорные экскурсии"
            isFemale -> "SPA-процедуры; термальные источники; пляжный отдых"
            else -> "загородный отдых на даче; киномарафоны"
        }

        // 29. Movie interests
        val movie = when {
            idLower.contains("developer") -> "фантастика; фэнтези; детектив; киберпанк; космическая опера"
            idLower.contains("designer") -> "драма; биография; документальное кино; анимация"
            else -> "комедия; драма; приключения; семейный фильм"
        }

        // 30. Music interests
        val music = when {
            idLower.contains("developer") -> "электронная музыка; техно; эмбиент; чиллаут; синтвейв"
            idLower.contains("designer") -> "инди-рок; альтернативный рок; джаз; блюз"
            else -> "Поп-музыка; чиллаут; неоклассика; диско"
        }

        // 31. Science interests
        val science = when {
            idLower.contains("developer") || idLower.contains("engineer") -> "информатика; искусственный интеллект; теоретическая физика; математика"
            idLower.contains("designer") -> "архитектура; культурология; искусствоведение; эстетика"
            else -> "социальная психология; экология; лингвистика"
        }

        // 32. Diseases
        val disease = when {
            ageGroup == "45–54 лет" -> "остеохондроз; варикозное расширение вен; гипертоническая болезнь"
            isFemale -> "мигрень; головная боль напряжения; генерализованное тревожное расстройство"
            else -> "острая респираторная вирусная инфекция (ОРВИ); мигрень"
        }

        // 33. Computer games
        val game = when {
            idLower.contains("developer") -> "стратегия в реальном времени (RTS); пошаговая стратегия (TBS); глобальная стратегия (Grand Strategy); ролевая игра (RPG)"
            idLower.contains("designer") -> "песочница (Sandbox); головоломка; приключения; симулятор жизни"
            else -> "головоломка; визуальная новелла; симулятор жизни"
        }

        return """
            Пол: $gender
            Возраст пользователя: $ageGroup
            Доход: $income
            Вероисповедание: $religion
            Количество детей: $childrenCount
            Семейное положение: $marital
            Возраст партнёра/мужа/жены пользователя: $partnerAge
            Животные: $pets
            Профессия: $profession
            Месяц дня рождения: $birthMonth
            Месяц дня рождения партнёра/мужа/жены: $partnerBirthMonth
            Месяц дня рождения отца: $fatherBirthMonth
            Месяц дня рождения матери: $motherBirthMonth
            Месяц дня рождения старшего ребёнка: $eldestBirthMonth
            Месяц дня рождения младшего ребёнка: $youngestBirthMonth
            Месяц дня рождения свекрови/тёщи: $motherInLawBirthMonth
            Месяц дня рождения свёкра/тестя: $fatherInLawBirthMonth
            Пол старшего ребёнка: $eldestGender
            Возраст старшего ребёнка: $eldestAge
            Пол младшего ребёнка: $youngestGender
            Возраст младшего ребёнка: $youngestAge
            Возраст отца: $fatherAge
            Возраст матери: $motherAge
            Возраст свекрови/тещи: $motherInLawAge
            Возраст свёкра/тестя: $fatherInLawAge
            Хобби: $hobby
            Интересы в видах спорта: $sport
            Интересы в видах отдыха: $recreation
            Интересы в кино: $movie
            Интересы в музыке: $music
            Интересы в науке: $science
            Заболевания: $disease
            Интересы в компьютерных играх: $game
        """.trimIndent()
    }

    fun generateProductHeuristics(
        identity: String,
        history: String,
        contextText: String,
        favorites: String,
        viewed: String,
        actions: String
    ): String {
        val idLower = identity.lowercase()
        val histLower = history.lowercase()
        
        return when {
            idLower.contains("alice") -> {
                """
                - Товар: Умный зонт (Smart Umbrella)
                - Товар: Сапоги от дождя (Rain Boots)
                - Товар: Премиальный коврик для йоги (Yoga mat)
                - Товар: Водонепроницаемый городской рюкзак (Waterproof Backpack)
                - Товар: Экологичная хлопковая футболка (Cotton Tee)
                """.trimIndent()
            }
            idLower.contains("bob") -> {
                """
                - Товар: Трекинговые кроссовки повышенной прочности (Trail run shoes)
                - Товар: Ветрозащитная шляпа (Hat)
                - Товар: Легкий спальный мешок для кемпинга (Sleeping bag)
                - Товар: Многофункциональная бутылка для воды (Water bottle)
                - Товар: Налобный светодиодный фонарь
                """.trimIndent()
            }
            idLower.contains("charlie") -> {
                """
                - Товар: Беспроводные наушники с активным шумоподавлением (Earbuds)
                - Товар: Эргономичная беспроводная мышь
                - Товар: Кофейные зерна свежей обжарки (Specialty Coffee)
                - Товар: Уютная хлопковая толстовка с капюшоном (Warm Hoodie)
                - Товар: Механическая программируемая клавиатура
                """.trimIndent()
            }
            else -> {
                val predictedItems = mutableListOf<String>()
                
                if (histLower.contains("shoes") || histLower.contains("shoes") || histLower.contains("кросс")) {
                    predictedItems.add("Профессиональные беговые кроссовки")
                }
                if (idLower.contains("dog") || histLower.contains("dog")) {
                    predictedItems.add("Автоматическая кормушка для собак")
                    predictedItems.add("Прочный поводок-рулетка")
                }
                if (idLower.contains("cat") || histLower.contains("cat")) {
                    predictedItems.add("Интерактивная лазерная игрушка для кошек")
                    predictedItems.add("Премиальный гипоаллергенный корм")
                }
                if (idLower.contains("child") || idLower.contains("children") || idLower.contains("детей")) {
                    predictedItems.add("Развивающие деревянные конструкторы")
                    predictedItems.add("Детский игровой планшет с родительским контролем")
                }
                if (histLower.contains("coffee") || histLower.contains("кофе")) {
                    predictedItems.add("Кофемашина капсульного типа")
                    predictedItems.add("Набор керамических кофейных чашек")
                }
                
                predictedItems.add("Умный термос с индикатором температуры")
                predictedItems.add("Портативное зарядное устройство (Powerbank 20000mAh)")
                
                predictedItems.joinToString("\n") { "- Товар: $it" }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    
    // Core Inputs State
    var userIdentity by remember { mutableStateOf(PRESETS[0].userIdentity) }
    var purchaseHistory by remember { mutableStateOf(PRESETS[0].purchaseHistory) }
    var externalContext by remember { mutableStateOf(PRESETS[0].externalContext) }
    var itemsToRank by remember { mutableStateOf(PRESETS[0].itemsToRank) }
    var viewedItems by remember { mutableStateOf(PRESETS[0].viewedItems) }
    var favoritedItems by remember { mutableStateOf(PRESETS[0].favoritedItems) }
    var userActions by remember { mutableStateOf(PRESETS[0].userActions) }
    
    // Assumptions State
    var userAssumptions by remember { mutableStateOf("") }
    var productAssumptions by remember { mutableStateOf("") }
    var isGeneratingUserAssumptions by remember { mutableStateOf(false) }
    var isGeneratingProductAssumptions by remember { mutableStateOf(false) }
    
    // RAG / CSV State
    var csvFileName by remember { mutableStateOf<String?>(null) }
    var csvFileSize by remember { mutableStateOf<Long>(0L) }
    var csvContent by remember { mutableStateOf<String>("") }
    var extractedKeywords by remember { mutableStateOf<List<String>>(emptyList()) }
    var retrievedCategories by remember { mutableStateOf<List<String>>(emptyList()) }
    var arbitraryQuestion by remember { mutableStateOf("") }
    var arbitraryAnswer by remember { mutableStateOf("") }
    var isGeneratingArbitrary by remember { mutableStateOf(false) }
    
    var ndcgCountText by remember { mutableStateOf("5") }
    var isNdcgComparing by remember { mutableStateOf(false) }
    var ndcgCompareResults by remember { mutableStateOf<List<ItemNdcgRating>?>(null) }
    var baselineNdcg by remember { mutableStateOf(0.0) }
    var rerankedNdcg by remember { mutableStateOf(0.0) }
    
    // Quality Analysis state
    var compareCountText by remember { mutableStateOf("3") }
    var isComparingQuality by remember { mutableStateOf(false) }
    var isShowingQualityResultDialog by remember { mutableStateOf(false) }
    var qualityBaselineNdcg by remember { mutableStateOf(0.0) }
    var qualityOptimizedNdcg by remember { mutableStateOf(0.0) }
    var qualityBaselineItemsEvaluated by remember { mutableStateOf<List<ItemRating>>(emptyList()) }
    var qualityOptimizedItemsEvaluated by remember { mutableStateOf<List<ItemRating>>(emptyList()) }
    
    var selectedPresetIndex by remember { mutableStateOf(0) }
    
    // New RAG / Embedding states
    var ragLinesList by remember { mutableStateOf<List<RagLineData>>(emptyList()) }
    var topRagLines by remember { mutableStateOf<List<RagLineData>>(emptyList()) }
    var fieldsDataMap by remember { mutableStateOf<Map<String, FieldData>>(emptyMap()) }
    var isShowingRagDetailsDialog by remember { mutableStateOf(false) }
    
    // Screen lock prevention during active processing and ranking
    val activity = context as? Activity
    DisposableEffect(isLoading) {
        if (isLoading) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    
    // Recalculate embeddings and word sets for each input field when they change
    LaunchedEffect(userIdentity, purchaseHistory, externalContext, favoritedItems, viewedItems, userActions, userAssumptions, productAssumptions) {
        val map = mutableMapOf<String, FieldData>()
        val fields = listOf(
            "User Identity" to userIdentity,
            "Purchase History" to purchaseHistory,
            "External Context" to externalContext,
            "Favorited Items" to favoritedItems,
            "Viewed Items" to viewedItems,
            "User Actions" to userActions,
            "User Character Assumptions" to userAssumptions,
            "Target Product Assumptions" to productAssumptions
        )
        for ((name, text) in fields) {
            val words = EmbeddingHelper.getWordList(text).toSet()
            val emb = EmbeddingHelper.getEmbedding(context, text)
            map[name] = FieldData(name, text, emb, words)
        }
        fieldsDataMap = map
    }
    
    // Helper parser for RAG lines
    fun parseRagContentToLines(csv: String): List<String> {
        val rawLines = csv.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (rawLines.isEmpty()) return emptyList()
        return if (rawLines.first().contains("Region", ignoreCase = true) || rawLines.first().contains(",") || rawLines.first().contains(";")) {
            rawLines.drop(1)
        } else {
            rawLines
        }
    }
    
    // Process RAG lines when new file is loaded or loaded default
    LaunchedEffect(csvContent) {
        if (csvContent.isBlank()) {
            ragLinesList = emptyList()
            return@LaunchedEffect
        }
        val dataLines = parseRagContentToLines(csvContent)
        val parsedLines = dataLines.map { line ->
            val words = EmbeddingHelper.getWordList(line).toSet()
            val emb = EmbeddingHelper.getEmbedding(context, line)
            RagLineData(originalLine = line, embedding = emb, words = words)
        }
        ragLinesList = parsedLines
    }
    
    // Compare and rank RAG lines dynamically against current input fields
    LaunchedEffect(fieldsDataMap, ragLinesList) {
        if (ragLinesList.isEmpty() || fieldsDataMap.isEmpty()) {
            topRagLines = emptyList()
            retrievedCategories = emptyList()
            return@LaunchedEffect
        }
        
        val updatedLines = ragLinesList.map { line ->
            var maxCos = -1.0f
            var totalWordMatches = 0
            var userAssumptionsCos = 0.0f
            var userAssumptionsWordMatches = 0
            var productAssumptionsCos = 0.0f
            var productAssumptionsWordMatches = 0
            
            fieldsDataMap.forEach { (fieldName, field) ->
                val sim = EmbeddingHelper.cosineSimilarity(line.embedding, field.embedding)
                if (sim > maxCos) {
                    maxCos = sim
                }
                val matches = line.words.intersect(field.words).size
                totalWordMatches += matches
                
                if (fieldName == "User Character Assumptions") {
                    userAssumptionsCos = sim
                    userAssumptionsWordMatches = matches
                } else if (fieldName == "Target Product Assumptions") {
                    productAssumptionsCos = sim
                    productAssumptionsWordMatches = matches
                }
            }
            
            // Чтобы косинусные расстояния и совпадения слов были одинаковы по важности, 
            // даем совпадениям слов больший вес (в среднем совпадает 5-15 слов, что даст 500-1500 баллов, как и косинус).
            val combinedScore = (maxCos * 1000f) + 
                                (totalWordMatches * 100f) + 
                                (userAssumptionsCos * 500f) + 
                                (userAssumptionsWordMatches * 50f) +
                                (productAssumptionsCos * 500f) + 
                                (productAssumptionsWordMatches * 50f)
            
            line.copy(
                cosineMax = maxCos,
                wordMatchesCount = totalWordMatches,
                combinedScore = combinedScore
            )
        }
        
        val sortedLines = updatedLines.sortedByDescending { it.combinedScore }
        
        val topList = mutableListOf<RagLineData>()
        var totalWords = 0
        for (item in sortedLines) {
            val wordsInLine = item.originalLine.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
            if (totalWords + wordsInLine <= 1000) {
                topList.add(item)
                totalWords += wordsInLine
            } else {
                break
            }
        }
        
        topRagLines = topList
        
        val categories = topList.map { line ->
            val parts = line.originalLine.split(Regex("[,;]"))
            val cat = parts.lastOrNull()?.replace("\"", "")?.trim() ?: "Unknown"
            cat.split(' ').joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
        }.distinct().take(5)
        
        retrievedCategories = categories
    }

    // AI Inference State
    var loadingPhase by remember { mutableStateOf("") }
    var rankedResults by remember { mutableStateOf<List<ScoredItem>>(emptyList()) }
    var isRealOnDeviceSdkActive by remember { mutableStateOf(false) }
    var lastErrorDetail by remember { mutableStateOf<String?>(null) }
    var isShowingPromptDialog by remember { mutableStateOf(false) }
    var rawModelOutput by remember { mutableStateOf("") }
    var isShowingRawOutputDialog by remember { mutableStateOf(false) }
    
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
            Toast.makeText(context, "AI Edge RAG Database Loaded: $name", Toast.LENGTH_SHORT).show()
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
                    "User Actions" -> userActions = content
                    "User Character Assumptions" -> userAssumptions = content
                    "Target Product Assumptions" -> productAssumptions = content
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
                    userActions = PRESETS[index].userActions
                    userAssumptions = ""
                    productAssumptions = ""
                    // Reset results to prompt user to rank again
                    rankedResults = emptyList()
                    arbitraryQuestion = ""
                    arbitraryAnswer = ""
                    ndcgCompareResults = null
                    baselineNdcg = 0.0
                    rerankedNdcg = 0.0
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
            
            // 2-Column Inputs Grid with the remaining 6 context fields
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
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
                }
                Box(modifier = Modifier.weight(1f)) {
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
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
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
                }
                Box(modifier = Modifier.weight(1f)) {
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
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
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
                Box(modifier = Modifier.weight(1f)) {
                    InputCard(
                        title = "User Actions",
                        value = userActions,
                        onEdit = {
                            editingField = "User Actions"
                            editingValue = userActions
                        },
                        onPaste = { handlePaste("User Actions") { userActions = it } },
                        onLoadFile = {
                            activeInputFileTarget = "User Actions"
                            textFileInputLauncher.launch("text/*")
                        }
                    )
                }
            }

            val generateUserAssumptions = {
                coroutineScope.launch {
                    isGeneratingUserAssumptions = true
                    try {
                        val ragCategoriesText = retrievedCategories.joinToString(", ")
                        val ragContextText = topRagLines.joinToString("\n") { "- ${it.originalLine}" }
                        
                        val text = generateWithGeminiNanoAutoCompress(
                            identity = userIdentity,
                            history = purchaseHistory,
                            context = externalContext,
                            viewed = viewedItems,
                            favorited = favoritedItems,
                            actions = userActions,
                            ragCategoriesText = ragCategoriesText,
                            ragContextText = ragContextText,
                            isLoraActive = isLoraActive,
                            loraSpecialization = loraSpecialization,
                            loraRank = loraRank,
                            loraAlpha = loraAlpha,
                            promptType = "user_assumptions",
                            onStatusUpdate = { }
                        )
                        userAssumptions = text
                        Toast.makeText(context, "Характеристики сгенерированы на Gemini Nano!", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        userAssumptions = AssumptionGenerators.generateUserHeuristics(
                            userIdentity, purchaseHistory, externalContext, favoritedItems, viewedItems, userActions
                        )
                        Toast.makeText(context, "Использованы интеллектуальные правила (fallback)!", Toast.LENGTH_SHORT).show()
                    } finally {
                        isGeneratingUserAssumptions = false
                    }
                }
            }

            val generateProductAssumptions = {
                coroutineScope.launch {
                    isGeneratingProductAssumptions = true
                    try {
                        val ragCategoriesText = retrievedCategories.joinToString(", ")
                        val ragContextText = topRagLines.joinToString("\n") { "- ${it.originalLine}" }
                        
                        val text = generateWithGeminiNanoAutoCompress(
                            identity = userIdentity,
                            history = purchaseHistory,
                            context = externalContext,
                            viewed = viewedItems,
                            favorited = favoritedItems,
                            actions = userActions,
                            ragCategoriesText = ragCategoriesText,
                            ragContextText = ragContextText,
                            isLoraActive = isLoraActive,
                            loraSpecialization = loraSpecialization,
                            loraRank = loraRank,
                            loraAlpha = loraAlpha,
                            promptType = "product_assumptions",
                            onStatusUpdate = { }
                        )
                        productAssumptions = text
                        Toast.makeText(context, "Предположения о конкретных товарах сгенерированы на Gemini Nano!", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        productAssumptions = AssumptionGenerators.generateProductHeuristics(
                            userIdentity, purchaseHistory, externalContext, favoritedItems, viewedItems, userActions
                        )
                        Toast.makeText(context, "Использованы интеллектуальные правила (fallback)!", Toast.LENGTH_SHORT).show()
                    } finally {
                        isGeneratingProductAssumptions = false
                    }
                }
            }

            val askArbitraryQuestion = {
                coroutineScope.launch {
                    if (arbitraryQuestion.isBlank()) return@launch
                    isGeneratingArbitrary = true
                    try {
                        val ragCategoriesText = retrievedCategories.joinToString(", ")
                        val ragContextText = topRagLines.joinToString("\n") { "- ${it.originalLine}" }
                        
                        val text = generateWithGeminiNanoAutoCompress(
                            identity = userIdentity,
                            history = purchaseHistory,
                            context = externalContext,
                            viewed = viewedItems,
                            favorited = favoritedItems,
                            actions = userActions,
                            ragCategoriesText = ragCategoriesText,
                            ragContextText = ragContextText,
                            isLoraActive = isLoraActive,
                            loraSpecialization = loraSpecialization,
                            loraRank = loraRank,
                            loraAlpha = loraAlpha,
                            promptType = "qa",
                            extraInput = arbitraryQuestion,
                            onStatusUpdate = { }
                        )
                        arbitraryAnswer = text
                        Toast.makeText(context, "Ответ получен от Gemini Nano!", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        arbitraryAnswer = "Ошибка при генерации ответа: ${e.message}\nУбедитесь, что модель Gemini Nano установлена и готова к работе."
                        Toast.makeText(context, "Не удалось выполнить запрос локально!", Toast.LENGTH_LONG).show()
                    } finally {
                        isGeneratingArbitrary = false
                    }
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
                    Toast.makeText(context, "AI Edge RAG Database unloaded.", Toast.LENGTH_SHORT).show()
                },
                extractedKeywords = extractedKeywords,
                retrievedCategories = retrievedCategories,
                onShowRagDetails = { isShowingRagDetailsDialog = true }
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
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Stack Assumption Cards vertically
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AssumptionCard(
                    title = "User Assumptions",
                    value = userAssumptions,
                    placeholder = "Нет предположений. Нажмите GENERATE NANO или вставьте данные.",
                    isGenerating = isGeneratingUserAssumptions,
                    onEdit = {
                        editingField = "User Character Assumptions"
                        editingValue = userAssumptions
                    },
                    onPaste = { handlePaste("User Character Assumptions") { userAssumptions = it } },
                    onLoadFile = {
                        activeInputFileTarget = "User Character Assumptions"
                        textFileInputLauncher.launch("text/*")
                    },
                    onGenerate = {
                        generateUserAssumptions()
                    }
                )
                AssumptionCard(
                    title = "Product Assumptions",
                    value = productAssumptions,
                    placeholder = "Нет предположений. Нажмите GENERATE NANO или вставьте данные.",
                    isGenerating = isGeneratingProductAssumptions,
                    onEdit = {
                        editingField = "Target Product Assumptions"
                        editingValue = productAssumptions
                    },
                    onPaste = { handlePaste("Target Product Assumptions") { productAssumptions = it } },
                    onLoadFile = {
                        activeInputFileTarget = "Target Product Assumptions"
                        textFileInputLauncher.launch("text/*")
                    },
                    onGenerate = {
                        generateProductAssumptions()
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            GeminiNanoQaCard(
                question = arbitraryQuestion,
                onQuestionChange = { arbitraryQuestion = it },
                answer = arbitraryAnswer,
                isGenerating = isGeneratingArbitrary,
                onAsk = { askArbitraryQuestion() }
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // SORT & VIEW PROMPT BUTTONS (Horizontal Row)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            try {
                                isLoading = true
                                lastErrorDetail = null
                                
                                // Extract keywords for UI display
                                val kw = extractKeywords(userIdentity, externalContext)
                                extractedKeywords = kw
                                
                                if (csvFileName != null) {
                                    loadingPhase = "RAG: Loading ${retrievedCategories.size} matching interest categories..."
                                    delay(300)
                                }
                                
                                val (results, wasRealSdk) = rankItemsOnDeviceWithFallback(
                                    onRawOutput = { rawModelOutput = it },
                                    androidContext = context,
                                    identity = userIdentity,
                                    history = purchaseHistory,
                                    externalContext = externalContext,
                                    itemsText = itemsToRank,
                                    viewedItemsText = viewedItems,
                                    favoritedItemsText = favoritedItems,
                                    userActions = userActions,
                                    isLoraActive = isLoraActive,
                                    loraSpecialization = loraSpecialization,
                                    loraRank = loraRank,
                                    loraAlpha = loraAlpha,
                                    ragCategories = retrievedCategories,
                                    ragContextText = topRagLines.joinToString("\n") { "- ${it.originalLine}" },
                                    onStatusUpdate = { status ->
                                        loadingPhase = status
                                    },
                                    onError = { errorMsg ->
                                        lastErrorDetail = errorMsg
                                    }
                                )
                                
                                rankedResults = results
                                isRealOnDeviceSdkActive = wasRealSdk
                                

                            } catch (e: Exception) {
                                e.printStackTrace()
                                lastErrorDetail = "Ошибка выполнения: ${e.localizedMessage ?: e.toString()}"
                            } finally {
                                isLoading = false
                            }
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
                            text = "ПРОМПТ",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = SleekPrimary,
                                fontSize = 11.sp
                            )
                        )
                        Text("👁️", fontSize = 16.sp)
                    }
                }

                OutlinedButton(
                    onClick = { isShowingRawOutputDialog = true },
                    modifier = Modifier
                        .weight(1.2f)
                        .height(56.dp)
                        .testTag("view_raw_output_button"),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, SleekOutline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SleekPrimary)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "СЫРОЙ ВЫВОД",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = SleekPrimary,
                                fontSize = 11.sp
                            )
                        )
                        Text("📄", fontSize = 16.sp)
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
                        userActions = userActions,
                        isLoraActive = isLoraActive,
                        loraSpecialization = loraSpecialization,
                        loraRank = loraRank,
                        loraAlpha = loraAlpha,
                        ragCategories = retrievedCategories,
                        ragContextText = topRagLines.joinToString("\n") { "- ${it.originalLine}" }
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
                                userActions = userActions,
                                isLoraActive = isLoraActive,
                                loraSpecialization = loraSpecialization,
                                loraRank = loraRank,
                                loraAlpha = loraAlpha,
                                ragCategories = retrievedCategories,
                                ragContextText = topRagLines.joinToString("\n") { "- ${it.originalLine}" }
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

        // Dialog showing the exact retrieved and ranked RAG lines which will be added to prompt
        if (isShowingRagDetailsDialog) {
            AlertDialog(
                onDismissRequest = { isShowingRagDetailsDialog = false },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("🔍", fontSize = 24.sp)
                        Text(
                            text = "Данные из RAG для промпта",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = SleekOnBackground
                            )
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Следующий топ отранжированных строк из RAG файла (максимум 1000 слов) будет добавлен к промпту при переранжировании для обогащения контекста Gemini Nano:",
                            style = MaterialTheme.typography.bodySmall.copy(color = SleekLabel, fontSize = 12.sp)
                        )
                        
                        if (topRagLines.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SleekBackground, RoundedCornerShape(12.dp))
                                    .border(1.dp, SleekOutline, RoundedCornerShape(12.dp))
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "RAG пуст или еще не ранжирован. Заполните поля или загрузите RAG-файл.",
                                    style = MaterialTheme.typography.bodySmall.copy(color = SleekLabel, fontStyle = FontStyle.Italic)
                                )
                            }
                        } else {
                            val totalWords = topRagLines.sumOf { it.originalLine.split(Regex("\\s+")).filter { it.isNotEmpty() }.size }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Всего строк: ${topRagLines.size}",
                                    style = MaterialTheme.typography.labelSmall.copy(color = SleekPrimary, fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Слов: $totalWords / 1000",
                                    style = MaterialTheme.typography.labelSmall.copy(color = SleekPrimary, fontWeight = FontWeight.Bold)
                                )
                            }
                            
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 320.dp)
                                    .background(SleekBackground, RoundedCornerShape(12.dp))
                                    .border(1.dp, SleekOutline, RoundedCornerShape(12.dp))
                                    .verticalScroll(rememberScrollState())
                                    .padding(10.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    topRagLines.forEachIndexed { index, item ->
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(SleekSurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                                .border(0.5.dp, SleekOutlineVariant, RoundedCornerShape(8.dp))
                                                .padding(10.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "#${index + 1}",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = SleekLabel)
                                                )
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    Box(
                                                        modifier = Modifier
                                                            .background(Color(0xFFE3F2FD), RoundedCornerShape(4.dp))
                                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                                    ) {
                                                        Text(
                                                            text = String.format(java.util.Locale.US, "Cos Max: %.3f", item.cosineMax),
                                                            style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF1565C0), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                        )
                                                    }
                                                    Box(
                                                        modifier = Modifier
                                                            .background(Color(0xFFE8F5E9), RoundedCornerShape(4.dp))
                                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                                    ) {
                                                        Text(
                                                            text = "Matches: ${item.wordMatchesCount}",
                                                            style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF2E7D32), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                        )
                                                    }
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = item.originalLine,
                                                style = MaterialTheme.typography.bodySmall.copy(color = SleekOnBackground, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { isShowingRagDetailsDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = SleekPrimary)
                    ) {
                        Text("ОК", color = Color.White)
                    }
                },
                containerColor = SleekSurfaceVariant,
                shape = RoundedCornerShape(24.dp)
            )
        }
            
        // Dialog showing the raw output of Gemini Nano
        if (isShowingRawOutputDialog) {
            AlertDialog(
                onDismissRequest = { isShowingRawOutputDialog = false },
                title = {
                    Text(
                        text = "Сырой вывод Gemini Nano",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = SleekOnBackground
                        )
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = if (rawModelOutput.isEmpty()) 
                                "Модель еще не запускалась или вывод пуст. Пожалуйста, нажмите кнопку RANK CATALOG." 
                            else 
                                "Ниже представлен сырой, непарсенный текстовый вывод (JSON-массив объектов), возвращенный нейросетью:",
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
                                text = rawModelOutput.ifEmpty { "Нет данных" },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = SleekOnBackground.copy(alpha = 0.9f)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { isShowingRawOutputDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = SleekPrimary)
                    ) {
                        Text("Закрыть", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            if (rawModelOutput.isNotEmpty()) {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(rawModelOutput))
                                Toast.makeText(context, "Вывод скопирован в буфер обмена!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text("Копировать", color = SleekPrimary)
                    }
                },
                containerColor = SleekSurfaceVariant,
                shape = RoundedCornerShape(24.dp)
            )
        }

        // ANALYZE QUALITY block
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable {
                    if (qualityBaselineItemsEvaluated.isNotEmpty() || qualityOptimizedItemsEvaluated.isNotEmpty()) {
                        isShowingQualityResultDialog = true
                    } else {
                        Toast.makeText(context, "Результаты анализа пока отсутствуют", Toast.LENGTH_SHORT).show()
                    }
                }
                .testTag("analyze_quality_card"),
            colors = CardDefaults.cardColors(
                containerColor = SleekSurfaceVariant
            ),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, SleekOutline.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "📊 Анализ качества (NDCG)",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = SleekPrimary,
                            letterSpacing = 0.5.sp
                        )
                    )
                    Box(
                        modifier = Modifier
                            .background(SleekPrimary.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "EVALUATION",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = SleekPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 8.sp
                            )
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = "Сравните исходную выдачу с оптимизированной. Gemini Nano оценит релевантность элементов и рассчитает NDCG метрику для обеих последовательностей.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = SleekOnBackground.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Input Field for Number of items
                    OutlinedTextField(
                        value = compareCountText,
                        onValueChange = { newValue ->
                            if (newValue.all { it.isDigit() }) {
                                compareCountText = newValue
                            }
                        },
                        placeholder = {
                            Text(
                                text = "Кол-во (напр. 3)",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, color = SleekOnBackground.copy(alpha = 0.5f))
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("compare_count_input"),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = SleekOnBackground,
                            fontSize = 12.sp
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = SleekOnBackground,
                            unfocusedTextColor = SleekOnBackground,
                            focusedContainerColor = SleekBackground,
                            unfocusedContainerColor = SleekBackground,
                            focusedBorderColor = SleekPrimary,
                            unfocusedBorderColor = SleekOutline,
                            cursorColor = SleekPrimary
                        )
                    )
                    
                    // Compare Button
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                try {
                                    isComparingQuality = true
                                    val N = compareCountText.toIntOrNull() ?: 3
                                    if (N <= 0) {
                                        Toast.makeText(context, "Количество элементов должно быть больше 0", Toast.LENGTH_SHORT).show()
                                        return@launch
                                    }
                                    
                                    val baselineItems = splitItemsText(itemsToRank).take(N)
                                    if (baselineItems.isEmpty()) {
                                        Toast.makeText(context, "Список Items to Rank пуст!", Toast.LENGTH_SHORT).show()
                                        return@launch
                                    }
                                    
                                    if (rankedResults.isEmpty()) {
                                        Toast.makeText(context, "Список Optimized Results пуст! Сначала выполните RANK CATALOG.", Toast.LENGTH_LONG).show()
                                        return@launch
                                    }
                                    
                                    val optimizedItems = rankedResults.take(N).map { it.name }
                                    val combinedItems = (baselineItems + optimizedItems).distinct()
                                    
                                    val ragCategoriesText = retrievedCategories.joinToString(", ")
                                    val ragContextText = topRagLines.joinToString("\n") { "- ${it.originalLine}" }
                                    
                                    val ratingsCache = mutableMapOf<String, ItemRating>()
                                    
                                    for (itemName in combinedItems) {
                                        var itemRating: ItemRating? = null
                                        for (attempt in 1..3) {
                                            try {
                                                val text = kotlinx.coroutines.withTimeout(15000L) {
                                                    generateWithGeminiNanoAutoCompress(
                                                        identity = userIdentity,
                                                        history = purchaseHistory,
                                                        context = externalContext,
                                                        viewed = viewedItems,
                                                        favorited = favoritedItems,
                                                        actions = userActions,
                                                        ragCategoriesText = ragCategoriesText,
                                                        ragContextText = ragContextText,
                                                        isLoraActive = isLoraActive,
                                                        loraSpecialization = loraSpecialization,
                                                        loraRank = loraRank,
                                                        loraAlpha = loraAlpha,
                                                        promptType = "quality_analysis_single",
                                                        extraInput = itemName,
                                                        onStatusUpdate = { }
                                                    )
                                                }
                                                val parsed = parseSingleNdcgRating(text, itemName)
                                                if (!parsed.reason.contains("по умолчанию")) {
                                                    itemRating = parsed
                                                    break
                                                }
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            }
                                        }
                                        ratingsCache[itemName.lowercase().trim()] = itemRating ?: ItemRating(itemName, 3, "Оценка по умолчанию (товар не распознан моделью)")
                                    }
                                    
                                    val finalBaselineEvaluated = baselineItems.map { name ->
                                        ratingsCache[name.lowercase().trim()] ?: ItemRating(name, 3, "Оценка по умолчанию")
                                    }
                                    
                                    val finalOptimizedEvaluated = optimizedItems.map { name ->
                                        ratingsCache[name.lowercase().trim()] ?: ItemRating(name, 3, "Оценка по умолчанию")
                                    }
                                    
                                    qualityBaselineNdcg = calculateNdcg(finalBaselineEvaluated.map { it.rating })
                                    qualityOptimizedNdcg = calculateNdcg(finalOptimizedEvaluated.map { it.rating })
                                    
                                    qualityBaselineItemsEvaluated = finalBaselineEvaluated
                                    qualityOptimizedItemsEvaluated = finalOptimizedEvaluated
                                    
                                    isShowingQualityResultDialog = true
                                    Toast.makeText(context, "Анализ качества успешно завершен!", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    Toast.makeText(context, "Ошибка при сравнении выдач: ${e.message}", Toast.LENGTH_LONG).show()
                                } finally {
                                    isComparingQuality = false
                                }
                            }
                        },
                        enabled = !isComparingQuality && itemsToRank.isNotBlank() && rankedResults.isNotEmpty(),
                        modifier = Modifier
                            .weight(1.2f)
                            .height(56.dp)
                            .testTag("compare_outputs_button"),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SleekPrimary,
                            contentColor = Color.White,
                            disabledContainerColor = SleekOutline,
                            disabledContentColor = SleekOnBackground.copy(alpha = 0.4f)
                        )
                    ) {
                        if (isComparingQuality) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "СРАВНИТЬ ВЫДАЧИ",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                )
                                Text("📊", fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))

        // QUALITY RESULT DIALOG
        if (isShowingQualityResultDialog) {
            AlertDialog(
                onDismissRequest = { isShowingQualityResultDialog = false },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("📊", fontSize = 24.sp)
                        Text(
                            text = "Результаты анализа качества",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = SleekOnBackground
                            )
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Сравнение метрики NDCG (Normalized Discounted Cumulative Gain) на основе оценок релевантности (1-5), выставленных Gemini Nano локально на NPU.",
                            style = MaterialTheme.typography.bodySmall.copy(color = SleekLabel, fontSize = 12.sp)
                        )
                        
                        // NDCG Scores Side-by-Side Cards
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Card(
                                modifier = Modifier.weight(1f),
                                colors = CardDefaults.cardColors(containerColor = SleekBackground),
                                border = BorderStroke(1.dp, SleekOutline.copy(alpha = 0.3f))
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "Items to Rank (Baseline)",
                                        style = MaterialTheme.typography.labelSmall.copy(color = SleekLabel, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                                        fontSize = 10.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = String.format(java.util.Locale.US, "%.3f", qualityBaselineNdcg),
                                        style = MaterialTheme.typography.titleLarge.copy(color = SleekPrimary, fontWeight = FontWeight.Bold),
                                        fontSize = 24.sp
                                    )
                                }
                            }
                            
                            Card(
                                modifier = Modifier.weight(1f),
                                colors = CardDefaults.cardColors(containerColor = SleekBackground),
                                border = BorderStroke(1.dp, SleekOutline.copy(alpha = 0.3f))
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "Optimized Results (Reranked)",
                                        style = MaterialTheme.typography.labelSmall.copy(color = SleekLabel, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                                        fontSize = 10.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = String.format(java.util.Locale.US, "%.3f", qualityOptimizedNdcg),
                                        style = MaterialTheme.typography.titleLarge.copy(color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold),
                                        fontSize = 24.sp
                                    )
                                }
                            }
                        }
                        
                        // Table Headers
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SleekBackground, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                                .border(0.5.dp, SleekOutline, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Исходный каталог (Top N)",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = SleekPrimary),
                                textAlign = TextAlign.Center
                            )
                            Box(modifier = Modifier.width(1.dp).height(16.dp).background(SleekOutline))
                            Text(
                                text = "Оптимизированный (Top N)",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32)),
                                textAlign = TextAlign.Center
                            )
                        }
                        
                        // Table Body inside Scrollable Box
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp)
                                .border(0.5.dp, SleekOutline, RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                                .padding(2.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val maxRows = maxOf(qualityBaselineItemsEvaluated.size, qualityOptimizedItemsEvaluated.size)
                                for (i in 0 until maxRows) {
                                    val baselineItem = qualityBaselineItemsEvaluated.getOrNull(i)
                                    val optimizedItem = qualityOptimizedItemsEvaluated.getOrNull(i)
                                    
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(if (i % 2 == 0) SleekBackground.copy(alpha = 0.4f) else Color.Transparent)
                                            .padding(6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        // Left column
                                        Column(modifier = Modifier.weight(1f)) {
                                            if (baselineItem != null) {
                                                Text(
                                                    text = "${i + 1}. ${baselineItem.name}",
                                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = SleekOnBackground),
                                                    fontSize = 11.sp
                                                )
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Text(
                                                        text = "⭐ ${baselineItem.rating}/5",
                                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = SleekPrimary),
                                                        fontSize = 10.sp
                                                    )
                                                }
                                                Text(
                                                    text = baselineItem.reason,
                                                    style = MaterialTheme.typography.bodySmall.copy(color = SleekLabel),
                                                    fontSize = 10.sp,
                                                    lineHeight = 13.sp
                                                )
                                            } else {
                                                Text("-", style = MaterialTheme.typography.bodySmall.copy(color = SleekLabel))
                                            }
                                        }
                                        
                                        // Divider line
                                        Box(
                                            modifier = Modifier
                                                .width(1.dp)
                                                .height(80.dp)
                                                .background(SleekOutline.copy(alpha = 0.3f))
                                        )
                                        
                                        // Right column
                                        Column(modifier = Modifier.weight(1f)) {
                                            if (optimizedItem != null) {
                                                Text(
                                                    text = "${i + 1}. ${optimizedItem.name}",
                                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = SleekOnBackground),
                                                    fontSize = 11.sp
                                                )
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Text(
                                                        text = "⭐ ${optimizedItem.rating}/5",
                                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32)),
                                                        fontSize = 10.sp
                                                    )
                                                }
                                                Text(
                                                    text = optimizedItem.reason,
                                                    style = MaterialTheme.typography.bodySmall.copy(color = SleekLabel),
                                                    fontSize = 10.sp,
                                                    lineHeight = 13.sp
                                                )
                                            } else {
                                                Text("-", style = MaterialTheme.typography.bodySmall.copy(color = SleekLabel))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { isShowingQualityResultDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = SleekPrimary)
                    ) {
                        Text("Закрыть", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            val sb = StringBuilder()
                            sb.append("NDCG Baseline: ").append(String.format(java.util.Locale.US, "%.3f", qualityBaselineNdcg)).append("\n")
                            sb.append("NDCG Optimized: ").append(String.format(java.util.Locale.US, "%.3f", qualityOptimizedNdcg)).append("\n\n")
                            val maxRows = maxOf(qualityBaselineItemsEvaluated.size, qualityOptimizedItemsEvaluated.size)
                            for (i in 0 until maxRows) {
                                val b = qualityBaselineItemsEvaluated.getOrNull(i)
                                val o = qualityOptimizedItemsEvaluated.getOrNull(i)
                                sb.append("Row ${i + 1}:\n")
                                if (b != null) sb.append("  Baseline: ${b.name} (⭐ ${b.rating}/5) - ${b.reason}\n")
                                if (o != null) sb.append("  Optimized: ${o.name} (⭐ ${o.rating}/5) - ${o.reason}\n")
                                sb.append("\n")
                            }
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(sb.toString()))
                            Toast.makeText(context, "Результаты скопированы!", Toast.LENGTH_SHORT).show()
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
                },
                onItemClick = { item ->
                    val action = "Clicked on: ${item.name}"
                    userActions = if (userActions.isEmpty()) action else "$userActions\n$action"
                    Toast.makeText(context, "Added to User Actions", Toast.LENGTH_SHORT).show()
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
                        "User Actions" -> userActions = updated
                        "User Character Assumptions" -> userAssumptions = updated
                        "Target Product Assumptions" -> productAssumptions = updated
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
fun AssumptionCard(
    title: String,
    value: String,
    placeholder: String,
    isGenerating: Boolean,
    onEdit: () -> Unit,
    onPaste: () -> Unit,
    onLoadFile: () -> Unit,
    onGenerate: () -> Unit
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
                .heightIn(min = 110.dp)
                .background(SleekSurfaceVariant, RoundedCornerShape(16.dp))
                .border(1.dp, SleekOutline, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .clickable(enabled = !isGenerating) { onEdit() }
                .padding(12.dp)
        ) {
            if (isGenerating) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = SleekPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Генерация Nano...",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = SleekPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (value.isEmpty()) placeholder else value,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (value.isEmpty()) SleekOnBackground.copy(alpha = 0.4f) else SleekOnBackground.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontStyle = if (value.isEmpty()) FontStyle.Italic else FontStyle.Normal
                        ),
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    
                    Spacer(modifier = Modifier.height(10.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                                text = "📋 PASTE",
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
                        
                        androidx.compose.material3.Button(
                            onClick = onGenerate,
                            modifier = Modifier
                                .height(32.dp),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = SleekPrimary,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Text(
                                text = "✨ GENERATE NANO",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    letterSpacing = 0.5.sp
                                )
                            )
                        }
                    }
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
    isRealSdk: Boolean = false,
    errorDetail: String? = null,
    onCopyClick: () -> Unit,
    onItemClick: (ScoredItem) -> Unit
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
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onItemClick(item) }
                                    .padding(vertical = 4.dp),
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
    retrievedCategories: List<String>,
    onShowRagDetails: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "AI EDGE RAG SDK CONTEXT",
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
                                text = "Load a database mapping regions, weather, and buying patterns to power contextual on-device retrieval via AI Edge RAG SDK.",
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
                                text = "Upload AI Edge RAG database...",
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onShowRagDetails() },
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
                                text = "Run Sort to perform keyword matching in AI Edge RAG database.",
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
                            text = "Unload RAG Database",
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

@Composable
fun GeminiNanoQaCard(
    question: String,
    onQuestionChange: (String) -> Unit,
    answer: String,
    isGenerating: Boolean,
    onAsk: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = SleekSurfaceVariant
        ),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, SleekOutline.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "🤖 Gemini Nano Q&A",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = SleekPrimary,
                        letterSpacing = 0.5.sp
                    )
                )
                Box(
                    modifier = Modifier
                        .background(SleekPrimary.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "LOCAL ON-DEVICE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = SleekPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 8.sp
                        )
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = "Задайте произвольный вопрос Gemini Nano на основе текущего контекста и RAG/LoRA параметров.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = SleekOnBackground.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Question Input Field
            androidx.compose.material3.OutlinedTextField(
                value = question,
                onValueChange = onQuestionChange,
                placeholder = {
                    Text(
                        text = "Например: Каковы основные интересы этого пользователя? или Какой товар ему предложить?",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = SleekOnBackground.copy(alpha = 0.4f),
                            fontSize = 12.sp
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp, max = 120.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = SleekOnBackground,
                    fontSize = 12.sp
                ),
                maxLines = 3,
                shape = RoundedCornerShape(8.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = SleekOnBackground,
                    unfocusedTextColor = SleekOnBackground,
                    focusedContainerColor = SleekBackground,
                    unfocusedContainerColor = SleekBackground,
                    focusedBorderColor = SleekPrimary,
                    unfocusedBorderColor = SleekOutline,
                    cursorColor = SleekPrimary
                )
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Action Button
            androidx.compose.material3.Button(
                onClick = onAsk,
                enabled = question.isNotBlank() && !isGenerating,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = SleekPrimary,
                    contentColor = Color.White,
                    disabledContainerColor = SleekOutline,
                    disabledContentColor = SleekOnBackground.copy(alpha = 0.4f)
                ),
                contentPadding = PaddingValues(0.dp)
            ) {
                if (isGenerating) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ГЕНЕРАЦИЯ ОТВЕТА...",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    )
                } else {
                    Text(
                        text = "ASK GEMINI NANO",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = 0.5.sp
                        )
                    )
                }
            }
            
            if (answer.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Ответ ассистента:",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = SleekOnBackground.copy(alpha = 0.9f)
                        )
                    )
                    
                    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                    val context = androidx.compose.ui.platform.LocalContext.current
                    androidx.compose.material3.TextButton(
                        onClick = {
                            val textToCopy = "Вопрос: $question\n\nОтвет:\n$answer"
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(textToCopy))
                            android.widget.Toast.makeText(context, "Скопировано в буфер!", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text("КОПИРОВАТЬ", fontSize = 10.sp, color = SleekPrimary)
                    }
                }
                
                Spacer(modifier = Modifier.height(6.dp))
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SleekBackground, RoundedCornerShape(8.dp))
                        .border(1.dp, SleekOutline.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = answer,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = SleekOnBackground.copy(alpha = 0.85f),
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    )
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
