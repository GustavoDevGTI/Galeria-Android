package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCinemaGestureControllerTest {
    @Test
    fun verticalGestureOnLeftControlsBrightness() {
        val controller = VideoCinemaGestureController()
        controller.start(100f, 500f, 1000f, 1000f, 0.5f, 0.4f)

        val update = controller.update(105f, 250f, 20f)

        assertTrue(update.consumed)
        assertEquals(CinemaGestureKind.BRIGHTNESS, update.kind)
        assertEquals(0.75f, update.fraction, 0.001f)
        assertTrue(controller.finish())
    }

    @Test
    fun verticalGestureOnRightControlsVolumeAndClampsIt() {
        val controller = VideoCinemaGestureController()
        controller.start(900f, 500f, 1000f, 1000f, 0.5f, 0.8f)

        val update = controller.update(895f, -500f, 20f)

        assertEquals(CinemaGestureKind.VOLUME, update.kind)
        assertEquals(1f, update.fraction, 0.001f)
    }

    @Test
    fun horizontalGesturePassesThroughToAlbumNavigation() {
        val controller = VideoCinemaGestureController()
        controller.start(100f, 500f, 1000f, 1000f, 0.5f, 0.4f)

        val update = controller.update(300f, 510f, 20f)

        assertFalse(update.consumed)
        assertFalse(controller.finish())
    }

    @Test
    fun tapRemainsAvailableWhenNoAdjustmentStarted() {
        val controller = VideoCinemaGestureController()
        controller.start(100f, 500f, 1000f, 1000f, 0.5f, 0.4f)

        assertFalse(controller.finish())
    }
}
