package com.syncstream.ui.client

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomCodeMatcherTest {
    private val a = RoomTarget("10.0.0.2", 8080, "Pixel")
    private val b = RoomTarget("10.0.0.3", 8080, "TV")

    @Test fun firstAcceptingRoomWins() = runTest {
        val tried = mutableListOf<RoomTarget>()
        val result = RoomCodeMatcher.match(listOf(a, b)) { tried += it; if (it == b) AttemptResult.Accepted else AttemptResult.WrongCode }
        assertEquals(MatchResult.Joined(b), result)
        assertEquals(listOf(a, b), tried)
    }

    @Test fun stopsAtFirstAccept() = runTest {
        val tried = mutableListOf<RoomTarget>()
        RoomCodeMatcher.match(listOf(a, b)) { tried += it; AttemptResult.Accepted }
        assertEquals(listOf(a), tried)
    }

    @Test fun allWrongIsNoMatch() = runTest {
        assertEquals(MatchResult.NoMatch, RoomCodeMatcher.match(listOf(a, b)) { AttemptResult.WrongCode })
    }

    @Test fun unreachableAndFullRoomsAreSkipped() = runTest {
        val result = RoomCodeMatcher.match(listOf(a, b)) { if (it == a) AttemptResult.Unreachable else AttemptResult.RoomFull }
        assertEquals(MatchResult.NoMatch, result)
    }

    @Test fun singleFullRoomReportsFull() = runTest {
        assertEquals(MatchResult.RoomFull(a), RoomCodeMatcher.match(listOf(a)) { AttemptResult.RoomFull })
    }

    @Test fun singleWrongRoomReportsWrongCode() = runTest {
        assertEquals(MatchResult.WrongCode, RoomCodeMatcher.match(listOf(a)) { AttemptResult.WrongCode })
    }

    /** The code was never checked, so "No room with that code nearby" would be wrong. */
    @Test fun singleUnreachableRoomReportsUnreachable() = runTest {
        assertEquals(MatchResult.Unreachable(a), RoomCodeMatcher.match(listOf(a)) { AttemptResult.Unreachable })
    }

    @Test fun noRoomsIsNoRooms() = runTest {
        assertEquals(MatchResult.NoRooms, RoomCodeMatcher.match(emptyList()) { error("not called") })
    }

    /** Reasons are the literal strings SignalingServer sends in its rejection Bye (BAD_PIN / FULL). */
    @Test fun classifiesRejectionReasons() {
        assertEquals(AttemptResult.WrongCode, RoomCodeMatcher.classifyRejection("bad pin"))
        assertEquals(AttemptResult.RoomFull, RoomCodeMatcher.classifyRejection("session full"))
        assertEquals(AttemptResult.RoomFull, RoomCodeMatcher.classifyRejection(null))
    }
}
