package com.galeria.android

import org.junit.Assert.*
import org.junit.Test

class OcrDocumentTest {
    @Test fun rotatedWordHitTestingRejectsEmptyCornersOfItsBoundingBox() {
        val diamond = floatArrayOf(1f, 0f, 2f, 1f, 1f, 2f, 0f, 1f)
        assertTrue(OcrGeometry.contains(diamond, 1f, 1f))
        assertFalse(OcrGeometry.contains(diamond, 0.1f, 0.1f))
    }
    @Test fun everyOcrRotationMapsBackToTheSameOriginalPoint() {
        val expected = OcrPoint(0.25f, 0.30f)
        val inputs = listOf(0 to (100f to 60f), 90 to (140f to 100f),
            180 to (300f to 140f), 270 to (60f to 300f))
        for ((rotation, point) in inputs) {
            val actual = OcrGeometry.fromRotated(point.first, point.second, 400, 200, rotation)
            assertEquals(expected.x, actual.x, 0.00001f)
            assertEquals(expected.y, actual.y, 0.00001f)
        }
    }

    @Test fun geometryIsIndependentOfOcrWorkingResolution() {
        assertEquals(OcrGeometry.fromRotated(60f, 100f, 400, 200, 270),
            OcrGeometry.fromRotated(120f, 200f, 800, 400, 270))
    }

    @Test fun selectionCopiesOnlySelectedWordsAndPreservesLinesAndParagraphs() {
        val document = OcrDocument("Original text", listOf(
            OcrWord("Um", emptyList(), 0, 0), OcrWord("texto.", emptyList(), 0, 0),
            OcrWord("Outra", emptyList(), 1, 0), OcrWord("linha", emptyList(), 1, 0),
            OcrWord("Parágrafo", emptyList(), 2, 1)))
        assertEquals("texto.\nOutra linha", document.selectedText(1, 3))
        assertEquals("texto.\nOutra linha", document.selectedText(3, 1))
        assertEquals("Um texto.\nOutra linha\n\nParágrafo", document.selectedText(0, 4))
    }

    @Test fun emptyOrOutOfBoundsSelectionsDoNotCrash() {
        assertEquals("", OcrDocument("").selectedText(0, 4))
        assertEquals("Palavra", OcrDocument("Palavra", listOf(OcrWord("Palavra", emptyList(), 0, 0))).selectedText(-1, 20))
    }
}
