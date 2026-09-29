package com.galeria.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPhotoRulesTest {
    @Test fun acceptsMp4FileTypeBox() {
        val bytes = byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray() + ByteArray(12)
        assertTrue(MotionPhotoRules.isMp4Header(bytes, 4))
    }

    @Test fun rejectsTextOrInvalidBoxLength() {
        assertFalse(MotionPhotoRules.isMp4Header("ordinary photo".toByteArray(), 4))
        val bytes = byteArrayOf(0, 0, 0, 4) + "ftypisom".toByteArray()
        assertFalse(MotionPhotoRules.isMp4Header(bytes, 4))
    }
}
