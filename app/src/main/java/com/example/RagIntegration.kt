package com.example

import android.content.Context
import com.google.ai.edge.localagents.rag.memory.DefaultVectorStore
import com.google.ai.edge.localagents.rag.memory.VectorStoreRecord
import com.google.ai.edge.localagents.rag.models.EmbedData
import com.google.ai.edge.localagents.rag.models.EmbeddingRequest
import com.google.ai.edge.localagents.rag.models.GeckoEmbeddingModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Optional

suspend fun runNeuralRagRetrieval(
    context: Context,
    csvContent: String, 
    contextKeywords: List<String>, 
    onStatus: suspend (String) -> Unit
): List<String> = withContext(Dispatchers.IO) {
    if (csvContent.isEmpty() || contextKeywords.isEmpty()) return@withContext emptyList()

    val retrievedCategories = mutableSetOf<String>()
    
    try {
        val modelFileName = "universal_sentence_encoder.tflite"
        val modelFile = File(context.filesDir, modelFileName)
        val tempFile = File(context.filesDir, "$modelFileName.tmp")

        if (!modelFile.exists()) {
            onStatus("Загрузка настоящей NPU-модели встраивания (~16MB)...")
            val urls = listOf(
                "https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/1/universal_sentence_encoder.tflite",
                "https://storage.googleapis.com/mediapipe-tasks/text_embedder/universal_sentence_encoder.tflite"
            )
            
            var downloaded = false
            var lastError: Throwable? = null
            
            for (urlString in urls) {
                try {
                    onStatus("Скачивание модели с сервера Google...")
                    val url = URL(urlString)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 15000
                    connection.readTimeout = 15000
                    connection.connect()
                    
                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val totalBytes = connection.contentLength
                        var downloadedBytes = 0
                        connection.inputStream.use { input ->
                            FileOutputStream(tempFile).use { output ->
                                val buffer = ByteArray(8192)
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                    downloadedBytes += bytesRead
                                    if (totalBytes > 0) {
                                        val progress = (downloadedBytes * 100L / totalBytes).toInt()
                                        onStatus("Загрузка NPU-модели: $progress%...")
                                    } else {
                                        onStatus("Загрузка NPU-модели: ${(downloadedBytes / 1024)} KB...")
                                    }
                                }
                            }
                        }
                        if (tempFile.exists() && tempFile.length() > 1000000) {
                            tempFile.renameTo(modelFile)
                            downloaded = true
                            break
                        }
                    }
                } catch (e: Throwable) {
                    lastError = e
                    tempFile.delete()
                }
            }
            
            if (!downloaded) {
                throw IllegalStateException("Не удалось скачать NPU-модель встраивания. Ошибка: ${lastError?.message}")
            }
        }

        onStatus("Инициализация NPU-векторизатора (Edge RAG)...")
        val modelPath = modelFile.absolutePath
        
        // Initialize standard vectorizer from AI Edge SDK
        // Attempt with isNpuEnabled = true first (NPU acceleration), fall back to CPU if it fails
        val vectorizer = try {
            GeckoEmbeddingModel(modelPath, Optional.empty(), true)
        } catch (e: Throwable) {
            onStatus("Инициализация NPU-ускорения не удалась. Переключение на CPU...")
            GeckoEmbeddingModel(modelPath, Optional.empty(), false)
        }
        
        val vectorStore = DefaultVectorStore<String>()
        
        onStatus("Нейро-векторизация базы знаний...")
        val lines = csvContent.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val dataLines = if (lines.firstOrNull()?.contains("Category", ignoreCase = true) == true || lines.firstOrNull()?.contains(",") == true) {
            lines.drop(1)
        } else {
            lines
        }
        
        // Embed and insert each row using the real local NPU model
        for (line in dataLines) {
            try {
                val parts = line.split(Regex("[,;]"))
                if (parts.isEmpty()) continue
                val category = parts.lastOrNull()?.replace("\"", "")?.trim() ?: "Unknown"
                val textContent = line.lowercase()
                
                val req = EmbeddingRequest.create(listOf(EmbedData.create(textContent, EmbedData.TaskType.RETRIEVAL_DOCUMENT)))
                val embeddingResult = vectorizer.getEmbeddings(req)
                val embedding = embeddingResult.get()
                
                val record = VectorStoreRecord.create(category, embedding)
                vectorStore.insert(record)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
        
        onStatus("Семантический нейро-поиск по векторизованной базе...")
        for (kw in contextKeywords) {
            try {
                val queryReq = EmbeddingRequest.create(listOf(EmbedData.create(kw.lowercase(), EmbedData.TaskType.RETRIEVAL_QUERY)))
                val queryEmbedding = vectorizer.getEmbeddings(queryReq).get()
                
                // Find top 3 nearest items
                val nearest = vectorStore.getNearestRecords(queryEmbedding, 3, 0.05f)
                for (rec in nearest) {
                    val cat = rec.data
                    retrievedCategories.add(cat.split(' ').joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } })
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
        
    } catch (e: Throwable) {
        onStatus("NPU RAG не поддерживается или произошла ошибка. Переключение на локальный текстовый RAG...")
        e.printStackTrace()
        
        // Robust keyword-based text RAG fallback
        val lines = csvContent.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val dataLines = if (lines.firstOrNull()?.contains("Category", ignoreCase = true) == true || lines.firstOrNull()?.contains(",") == true) {
            lines.drop(1)
        } else {
            lines
        }
        
        val scoredLines = mutableListOf<Pair<String, Int>>()
        for (line in dataLines) {
            try {
                val parts = line.split(Regex("[,;]"))
                if (parts.isEmpty()) continue
                val category = parts.lastOrNull()?.replace("\"", "")?.trim() ?: "Unknown"
                val textContent = line.lowercase()
                
                var matchScore = 0
                for (kw in contextKeywords) {
                    val kwLower = kw.lowercase()
                    if (textContent.contains(kwLower)) {
                        matchScore += 20
                    }
                    val kwWords = kwLower.split(Regex("\\s+")).filter { it.length > 2 }
                    for (word in kwWords) {
                        if (textContent.contains(word)) {
                            matchScore += 5
                        }
                    }
                }
                if (matchScore > 0) {
                    scoredLines.add(Pair(category, matchScore))
                }
            } catch (ex: Throwable) {
                ex.printStackTrace()
            }
        }
        
        // Sort by matching score descending and take top 5
        val topCategories = scoredLines.sortedByDescending { it.second }
            .map { it.first }
            .distinct()
            .take(5)
        
        for (cat in topCategories) {
            retrievedCategories.add(cat.split(' ').joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } })
        }
    }
    
    return@withContext retrievedCategories.toList()
}
