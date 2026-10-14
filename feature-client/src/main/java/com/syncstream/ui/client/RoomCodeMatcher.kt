package com.syncstream.ui.client

/** A room the guest can dial: a discovered master or one chosen on Home. */
data class RoomTarget(val host: String, val port: Int, val label: String)

/** Outcome of one connection attempt with a code. */
enum class AttemptResult { Accepted, WrongCode, RoomFull, Unreachable }

sealed interface MatchResult {
    data class Joined(val room: RoomTarget) : MatchResult
    /** A single chosen room rejected the code. */
    data object WrongCode : MatchResult
    data class RoomFull(val room: RoomTarget) : MatchResult
    /** A single chosen room could not be reached at all, so the code was never checked. */
    data class Unreachable(val room: RoomTarget) : MatchResult
    /** Several rooms were tried and none accepted the code. */
    data object NoMatch : MatchResult
    data object NoRooms : MatchResult
}

/**
 * Tries a room code against candidate rooms in order; the first room that accepts wins.
 * With exactly one candidate the specific failure is reported, so the UI can say
 * "Wrong code", "Room is full" or "Couldn't reach" instead of the generic "No room with that
 * code nearby".
 */
object RoomCodeMatcher {
    /**
     * The reason `SignalingServer` sends in its rejection `Bye` for a wrong PIN. The server's
     * constant is private, so it is mirrored here; any other rejection reason (today only
     * "session full") means the room refused us for capacity.
     */
    private const val BAD_PIN = "bad pin"

    suspend fun match(rooms: List<RoomTarget>, attempt: suspend (RoomTarget) -> AttemptResult): MatchResult {
        if (rooms.isEmpty()) return MatchResult.NoRooms
        var last: AttemptResult = AttemptResult.Unreachable
        for (room in rooms) {
            last = attempt(room)
            if (last == AttemptResult.Accepted) return MatchResult.Joined(room)
        }
        if (rooms.size == 1) {
            return when (last) {
                AttemptResult.WrongCode -> MatchResult.WrongCode
                AttemptResult.RoomFull -> MatchResult.RoomFull(rooms.single())
                AttemptResult.Unreachable -> MatchResult.Unreachable(rooms.single())
                AttemptResult.Accepted -> MatchResult.Joined(rooms.single()) // not reached: Accepted returns in the loop
            }
        }
        return MatchResult.NoMatch
    }

    fun classifyRejection(reason: String?): AttemptResult =
        if (reason == BAD_PIN) AttemptResult.WrongCode else AttemptResult.RoomFull
}
