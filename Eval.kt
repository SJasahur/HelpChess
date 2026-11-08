package com.helpchess.chess

import kotlin.math.abs
import kotlin.math.min

/** Оценка позиции в сотых пешки с точки зрения стороны, которая ходит. */
object Eval {
    val VALUE = intArrayOf(0, 100, 320, 330, 500, 900, 0)

    // Таблицы записаны с точки зрения белых, первая строка = 8-я горизонталь.
    private val PAWN_T = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0,
        50, 50, 50, 50, 50, 50, 50, 50,
        10, 10, 20, 30, 30, 20, 10, 10,
        5, 5, 10, 25, 25, 10, 5, 5,
        0, 0, 0, 20, 20, 0, 0, 0,
        5, -5, -10, 0, 0, -10, -5, 5,
        5, 10, 10, -20, -20, 10, 10, 5,
        0, 0, 0, 0, 0, 0, 0, 0
    )
    private val KNIGHT_T = intArrayOf(
        -50, -40, -30, -30, -30, -30, -40, -50,
        -40, -20, 0, 0, 0, 0, -20, -40,
        -30, 0, 10, 15, 15, 10, 0, -30,
        -30, 5, 15, 20, 20, 15, 5, -30,
        -30, 0, 15, 20, 20, 15, 0, -30,
        -30, 5, 10, 15, 15, 10, 5, -30,
        -40, -20, 0, 5, 5, 0, -20, -40,
        -50, -40, -30, -30, -30, -30, -40, -50
    )
    private val BISHOP_T = intArrayOf(
        -20, -10, -10, -10, -10, -10, -10, -20,
        -10, 0, 0, 0, 0, 0, 0, -10,
        -10, 0, 5, 10, 10, 5, 0, -10,
        -10, 5, 5, 10, 10, 5, 5, -10,
        -10, 0, 10, 10, 10, 10, 0, -10,
        -10, 10, 10, 10, 10, 10, 10, -10,
        -10, 5, 0, 0, 0, 0, 5, -10,
        -20, -10, -10, -10, -10, -10, -10, -20
    )
    private val ROOK_T = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0,
        5, 10, 10, 10, 10, 10, 10, 5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        0, 0, 0, 5, 5, 0, 0, 0
    )
    private val QUEEN_T = intArrayOf(
        -20, -10, -10, -5, -5, -10, -10, -20,
        -10, 0, 0, 0, 0, 0, 0, -10,
        -10, 0, 5, 5, 5, 5, 0, -10,
        -5, 0, 5, 5, 5, 5, 0, -5,
        0, 0, 5, 5, 5, 5, 0, -5,
        -10, 5, 5, 5, 5, 5, 0, -10,
        -10, 0, 5, 0, 0, 0, 0, -10,
        -20, -10, -10, -5, -5, -10, -10, -20
    )
    private val KING_MG = intArrayOf(
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -20, -30, -30, -40, -40, -30, -30, -20,
        -10, -20, -20, -20, -20, -20, -20, -10,
        20, 20, 0, 0, 0, 0, 20, 20,
        20, 30, 10, 0, 0, 10, 30, 20
    )
    private val KING_EG = intArrayOf(
        -50, -40, -30, -20, -20, -30, -40, -50,
        -30, -20, -10, 0, 0, -10, -20, -30,
        -30, -10, 20, 30, 30, 20, -10, -30,
        -30, -10, 30, 40, 40, 30, -10, -30,
        -30, -10, 30, 40, 40, 30, -10, -30,
        -30, -10, 20, 30, 30, 20, -10, -30,
        -30, -30, 0, 0, 0, 0, -30, -30,
        -50, -30, -30, -30, -30, -30, -30, -50
    )

    private val PST = arrayOf(IntArray(64), PAWN_T, KNIGHT_T, BISHOP_T, ROOK_T, QUEEN_T, KING_MG)
    private val PASSED = intArrayOf(0, 5, 10, 20, 35, 60, 100, 0)

    fun evaluate(pos: Position): Int {
        val b = pos.board
        var score = 0
        var phase = 0
        var wBishops = 0
        var bBishops = 0
        val wPawns = IntArray(8)
        val bPawns = IntArray(8)
        val wMin = IntArray(8) { 8 }   // самая «задняя» белая пешка на вертикали
        val bMax = IntArray(8) { -1 }  // самая «задняя» (по ходу чёрных) чёрная пешка на вертикали
        var wk = -1
        var bk = -1

        for (sq in 0..63) {
            val p = b[sq]
            if (p == 0) continue
            val t = abs(p)
            val f = sq and 7
            val r = sq shr 3
            if (t == KING) {
                if (p > 0) wk = sq else bk = sq
                continue
            }
            if (p > 0) {
                score += VALUE[t] + PST[t][(7 - r) * 8 + f]
                when (t) {
                    PAWN -> {
                        wPawns[f]++
                        if (r < wMin[f]) wMin[f] = r
                    }
                    BISHOP -> { wBishops++; phase++ }
                    KNIGHT -> phase++
                    ROOK -> phase += 2
                    QUEEN -> phase += 4
                }
            } else {
                score -= VALUE[t] + PST[t][r * 8 + f]
                when (t) {
                    PAWN -> {
                        bPawns[f]++
                        if (r > bMax[f]) bMax[f] = r
                    }
                    BISHOP -> { bBishops++; phase++ }
                    KNIGHT -> phase++
                    ROOK -> phase += 2
                    QUEEN -> phase += 4
                }
            }
        }

        if (wBishops >= 2) score += 30
        if (bBishops >= 2) score -= 30

        // Структура пешек
        for (f in 0..7) {
            if (wPawns[f] > 1) score -= 12 * (wPawns[f] - 1)
            if (bPawns[f] > 1) score += 12 * (bPawns[f] - 1)
            if (wPawns[f] > 0 && (f == 0 || wPawns[f - 1] == 0) && (f == 7 || wPawns[f + 1] == 0)) score -= 12 * wPawns[f]
            if (bPawns[f] > 0 && (f == 0 || bPawns[f - 1] == 0) && (f == 7 || bPawns[f + 1] == 0)) score += 12 * bPawns[f]
        }
        // Проходные пешки
        for (sq in 8..55) {
            val p = b[sq]
            if (p == PAWN) {
                val f = sq and 7
                val r = sq shr 3
                var passed = true
                for (ff in maxOf(0, f - 1)..minOf(7, f + 1)) if (bMax[ff] > r) passed = false
                if (passed) score += PASSED[r]
            } else if (p == -PAWN) {
                val f = sq and 7
                val r = sq shr 3
                var passed = true
                for (ff in maxOf(0, f - 1)..minOf(7, f + 1)) if (wMin[ff] < r) passed = false
                if (passed) score -= PASSED[7 - r]
            }
        }

        // Король: плавный переход от миттельшпиля к эндшпилю
        val ph = min(24, phase)
        if (wk >= 0) {
            val i = (7 - (wk shr 3)) * 8 + (wk and 7)
            score += (KING_MG[i] * ph + KING_EG[i] * (24 - ph)) / 24
        }
        if (bk >= 0) {
            val i = (bk shr 3) * 8 + (bk and 7)
            score -= (KING_MG[i] * ph + KING_EG[i] * (24 - ph)) / 24
        }

        return (if (pos.whiteToMove) score else -score) + 10
    }
}
