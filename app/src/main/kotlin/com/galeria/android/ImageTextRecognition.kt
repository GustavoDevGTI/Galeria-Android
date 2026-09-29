package com.galeria.android

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

internal object ImageTextRecognition {
    private val decodeExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun recognize(context: Context, uri: Uri, onComplete: (Result<String>) -> Unit) {
        val appContext = context.applicationContext
        decodeExecutor.execute {
            val image = runCatching { InputImage.fromFilePath(appContext, uri) }
                .getOrElse { error ->
                    mainHandler.post { onComplete(Result.failure(error)) }
                    return@execute
                }
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    recognizer.close()
                    onComplete(Result.success(result.text))
                }
                .addOnFailureListener { error ->
                    recognizer.close()
                    onComplete(Result.failure(error))
                }
        }
    }
}
