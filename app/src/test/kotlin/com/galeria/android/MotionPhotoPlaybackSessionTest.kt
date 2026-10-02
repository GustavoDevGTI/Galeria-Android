package com.galeria.android

import org.junit.Assert.*
import org.junit.Test

class MotionPhotoPlaybackSessionTest {
    private class Fixture {
        var live = true
        val events = arrayListOf<String>()
        val player = Any()
        lateinit var complete: (Result<String>) -> Unit
        var onCreate: (() -> Unit)? = null
        var onBind: (() -> Unit)? = null
        val session = MotionPhotoPlaybackSession<String, Any>(
            { live }, { events.add("create"); onCreate?.invoke(); player },
            { owned, file -> assertSame(player, owned); assertEquals("clip", file); events.add("bind"); onBind?.invoke() },
            { assertSame(player, it); events.add("play") },
            { assertSame(player, it); events.add("pause") },
            { assertSame(player, it); events.add("release") },
            { events.add("resume") }, { events.add("suspend") },
            { events.add("unbind") }, { events.add("error:${it.message}") }
        )
        fun load() { session.load { complete = it } }
        fun ready() { complete(Result.success("clip")) }
    }
    @Test fun activeSessionBindsBeforePlayingAndOwnsPlayer() {
        val f = Fixture(); f.load(); f.session.start(); f.ready()
        assertEquals(listOf("resume", "create", "bind", "play"), f.events); assertSame(f.player, f.session.player)
    }
    @Test fun stoppedSessionCanPrepareButDoesNotAutoplayOrForceResume() {
        val f = Fixture(); f.load(); f.session.start(); f.session.stop(); f.ready()
        assertEquals(listOf("resume", "suspend", "create", "bind", "suspend"), f.events)
        f.session.start(); assertEquals("resume", f.events.last()); assertFalse(f.events.contains("play"))
    }
    @Test fun stopPausesOwnedPlayerWithoutReleasingAndStartKeepsIt() {
        val f = Fixture(); f.load(); f.session.start(); f.ready(); f.session.stop(); f.session.start()
        assertSame(f.player, f.session.player); assertEquals(1, f.events.count { it == "play" })
        assertEquals(1, f.events.count { it == "pause" }); assertFalse(f.events.contains("release"))
    }
    @Test fun closedSessionCannotCreateFromLateExtractionOrResume() {
        val f = Fixture(); f.load(); f.session.close(); f.ready(); f.session.start(); f.session.stop()
        f.session.load { fail("No second extraction after close") }; assertEquals(listOf("unbind"), f.events)
    }
    @Test fun duplicateExtractionAndDeliveryCannotCreateOrBindAgain() {
        val f = Fixture(); f.load(); f.session.load { fail("Only one extraction") }; f.ready(); f.ready()
        assertEquals(1, f.events.count { it == "create" }); assertEquals(1, f.events.count { it == "bind" })
    }
    @Test fun closeUnbindsBeforeReleasingExactlyOnce() {
        val f = Fixture(); f.load(); f.ready(); f.session.close(); f.session.close(); f.ready()
        assertEquals(listOf("create", "bind", "suspend", "unbind", "release"), f.events); assertNull(f.session.player)
    }
    @Test fun errorIsDeliveredOnceAndNeverCreatesPlayer() {
        val f = Fixture(); f.load(); f.complete(Result.failure(IllegalStateException("extract"))); f.ready()
        assertEquals(listOf("error:extract"), f.events); assertNull(f.session.player)
    }
    @Test fun finishingHostRejectsExtractionEvenIfDuplicateArrivesLater() {
        val f = Fixture(); f.load(); f.live = false; f.ready(); f.live = true; f.ready()
        assertTrue(f.events.isEmpty()); assertNull(f.session.player)
    }
    @Test fun closingWhileBindingDoesNotPlayReleasedPlayer() {
        val f = Fixture(); f.load(); f.session.start(); f.onBind = { f.session.close() }; f.ready()
        assertEquals(listOf("resume", "create", "bind", "unbind", "release"), f.events); assertNull(f.session.player)
    }
    @Test fun closingWhileCreatingStillReleasesNewResourceWithoutBinding() {
        val f = Fixture(); f.load(); f.onCreate = { f.session.close() }; f.ready()
        assertEquals(listOf("create", "unbind", "release"), f.events); assertNull(f.session.player)
    }
}
