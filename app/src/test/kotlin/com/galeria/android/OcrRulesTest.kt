package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrRulesTest {
    @Test fun statusBarDoesNotStopSearchingForDocumentText() {
        val status = OcrReading("22:37 5G VoLTE", 0.98f)
        val document = OcrReading("Resumo da Balança Econômica Anual\nTotal de perdas setoriais", 0.9f)
        assertFalse(OcrRules.isReliable(status))
        assertSame(document, OcrRules.best(status, document))
        assertTrue(OcrRules.isReliable(document))
    }

    @Test fun comparesAllOrientationsInsteadOfAcceptingFirstNonEmptyResult() {
        val results = listOf(OcrReading("22:37"), OcrReading("GALERIA DOCUMENTO PARA COPIAR 2026"), OcrReading("I I X"), OcrReading(""))
        assertEquals(listOf(0, 90, 270, 180), OcrRules.rotations)
        assertEquals(results[1], results.reduce(OcrRules::best))
    }

    @Test fun confidenceHelpsRejectGarbledReadingOfTheSameSize() {
        val garbled = OcrReading("abc def ghi jkl mno", 0.1f)
        val readable = OcrReading("Uma foto para copiar", 0.95f)
        assertSame(readable, OcrRules.best(garbled, readable))
        assertFalse(OcrRules.isReliable(garbled))
    }

    @Test fun emptyReadingsStayEmptyAndTiesKeepEarlierOrientation() {
        assertEquals(0.0, OcrRules.score(OcrReading("\n ! ")), 0.0)
        val first = OcrReading("GALERIA", 0.8f)
        assertSame(first, OcrRules.best(first, first.copy()))
    }

    @Test fun accentsNumbersAndShortTextRemainEligibleForCopying() {
        assertTrue(OcrRules.score(OcrReading("R$ 62,5 bilhões")) > 0)
        assertTrue(OcrRules.score(OcrReading("Olá")) > 0)
        assertFalse(OcrRules.isReliable(OcrReading("Olá")))
    }

    @Test fun identifiesDarkScreenshotsWithoutInvertingWhiteDocuments() {
        assertTrue(OcrRules.isDarkBackground(IntArray(100) { if (it < 80) 0xff222326.toInt() else -1 }))
        assertFalse(OcrRules.isDarkBackground(IntArray(100) { if (it < 80) -1 else 0xff000000.toInt() }))
        assertFalse(OcrRules.isDarkBackground(intArrayOf()))
    }

    @Test fun decodePreservesAspectRatioAndLimitsMemoryWithoutUpscaling() {
        assertEquals(1490 to 1080, OcrRules.targetSize(1490, 1080, true))
        assertEquals(1400 to 400, OcrRules.targetSize(1400, 400, false))
        for (detailed in listOf(false, true)) {
            val size = OcrRules.targetSize(12000, 9000, detailed)
            assertTrue(size.first.toLong() * size.second <= if (detailed) 6_000_000 else 3_000_000)
            assertTrue(kotlin.math.abs(size.first.toDouble() / size.second - 4.0 / 3.0) < 0.002)
        }
    }
}
