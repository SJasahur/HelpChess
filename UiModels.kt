package com.helpchess

import com.helpchess.chess.Position

data class HintLine(
    val move: Int,
    val san: String,
    val eval: String,
    val line: String,
    val reply: String?
)

data class ThreatInfo(val move: Int, val text: String)

data class MoveReport(
    val byOpponent: Boolean,
    val san: String,
    val title: String,
    val lossCp: Int,
    val bestSan: String?
)

data class OppProfile(
    val moves: Int,
    val avgLossCp: Int,
    val blunders: Int,
    val mistakes: Int,
    val inaccuracies: Int,
    val style: String,
    val advice: String
)

data class PromotionRequest(val from: Int, val to: Int)

data class UiState(
    val position: Position = Position.start(),
    val userIsWhite: Boolean = true,
    val flipped: Boolean = false,
    val selected: Int = -1,
    val targets: Set<Int> = emptySet(),
    val lastMove: Int = 0,
    val moveText: String = "",
    val thinking: Boolean = false,
    val hints: List<HintLine> = emptyList(),
    val hintsForUser: Boolean = true,
    val depth: Int = 0,
    val threat: ThreatInfo? = null,
    val report: MoveReport? = null,
    val profile: OppProfile? = null,
    val status: String = "",
    val gameOver: Boolean = false,
    val autoBot: Boolean = false,
    val botLevel: Int = 3,
    val timeMs: Long = 800,
    val hintsOn: Boolean = true,
    val promotion: PromotionRequest? = null,
    val message: String? = null
)
