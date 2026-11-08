package com.helpchess.chess

import java.util.Locale
import kotlin.math.abs

object Fmt {
    fun pieceName(t: Int): String = when (t) {
        PAWN -> "пешку"
        KNIGHT -> "коня"
        BISHOP -> "слона"
        ROOK -> "ладью"
        QUEEN -> "ферзя"
        KING -> "короля"
        else -> "фигуру"
    }

    /**
     * Оценка с точки зрения пользователя: «+» — хорошо для вас.
     * [scoreForMover] — оценка с позиции стороны, которая ходит; [moverIsUser] — это ход пользователя?
     */
    fun eval(scoreForMover: Int, moverIsUser: Boolean): String {
        val u = if (moverIsUser) scoreForMover else -scoreForMover
        if (abs(scoreForMover) > Engine.MATE - 1000) {
            val plies = Engine.MATE - abs(scoreForMover)
            val n = (plies + 1) / 2
            return if (u > 0) "мат в $n" else "вам мат в $n"
        }
        return String.format(Locale.US, "%+.2f", u / 100.0)
    }
}

/** Линия ходов в SAN с номерами, например «12. Nf3 Nc6 13. Bb5». Некорректные ходы отбрасываются. */
fun sanLine(start: Position, moves: List<Int>, limit: Int = 6): String {
    var p = start
    val sb = StringBuilder()
    var count = 0
    for (m in moves) {
        if (count >= limit) break
        if (!p.legalMoves().contains(m)) break
        if (p.whiteToMove) sb.append(p.fullmove).append(". ")
        else if (count == 0) sb.append(p.fullmove).append("... ")
        sb.append(p.toSan(m)).append(' ')
        p = p.makeMove(m)
        count++
    }
    return sb.toString().trim()
}
