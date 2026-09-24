package com.galeria.android

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetailMetadataInstrumentedTest {
    @Test
    fun metadataDialogGroupsFieldsAndHighlightsTheirNames() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val dialog = Ui.showMetadataDialog(activity, "Informações da imagem", listOf(
                    DetailMetadataSection("Arquivo", listOf(
                        DetailMetadataField("Nome", "foto.jpg"),
                        DetailMetadataField("Data", "24/09/2026"),
                        DetailMetadataField("Tamanho", "2 MB")
                    )),
                    DetailMetadataSection("Imagem e câmera", listOf(
                        DetailMetadataField("Resolução", "1920 × 1080")
                    ))
                ))
                try {
                    val labels = descendants(requireNotNull(dialog.window).decorView)
                        .filterIsInstance<TextView>()
                    val text = labels.map { it.text.toString() }
                    assertTrue(text.indexOf("Arquivo") < text.indexOf("Nome"))
                    assertTrue(text.indexOf("Nome") < text.indexOf("Data"))
                    assertTrue(text.indexOf("Data") < text.indexOf("Tamanho"))
                    assertTrue(text.indexOf("Tamanho") < text.indexOf("Imagem e câmera"))
                    assertEquals(true, labels.first { it.text == "Nome" }.typeface.isBold)
                    assertEquals(true, labels.first { it.text == "Data" }.typeface.isBold)
                } finally {
                    dialog.dismiss()
                }
            }
        }
    }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }
}
