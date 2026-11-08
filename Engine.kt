package com.helpchess.chess

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class Line(val move: Int, val score: Int, val pv: List<Int>)

class SearchResult(val lines: List<Line>, val depth: Int, val nodes: Long, val elapsedMs: Long) {
    val best: Line? get() = lines.firstOrNull()
}

/**
 * Небольшой, но быстрый шахматный движок на чистом Kotlin:
 * итеративное углубление, negamax + alpha-beta (PVS), таблица транспозиций,
 * нулевой ход, LMR, killer/history, поиск тихих ходов, несколько лучших линий (MultiPV).
 */
class Engine {
    companion object {
        const val INF = 1_000_000
        const val MATE = 100_000
        const val MAX_PLY = 64
        private const val TT_BITS = 18
        private const val TT_SIZE = 1 shl TT_BITS
        private const val TT_MASK = (TT_SIZE - 1).toLong()
    }

    @Volatile
    var abort = false

    private var stop = false
    private var deadline = 0L
    private var nodes = 0L

    private val ttKey = LongArray(TT_SIZE)
    private val ttDepth = IntArray(TT_SIZE)
    private val ttScore = IntArray(TT_SIZE)
    private val ttFlag = IntArray(TT_SIZE)
    private val ttMove = IntArray(TT_SIZE)

    private val killers = Array(MAX_PLY + 2) { IntArray(2) }
    private val history = Array(2) { Array(64) { IntArray(64) } }
    private val pvTable = Array(MAX_PLY + 2) { IntArray(MAX_PLY + 2) }
    private val pvLen = IntArray(MAX_PLY + 2)
    private val moveBufs = Array(MAX_PLY + 2) { MoveList() }
    private val scoreBufs = Array(MAX_PLY + 2) { IntArray(512) }

    private val keys = LongArray(2048)
    private var rootCount = 1

    fun clearTables() {
        java.util.Arrays.fill(ttFlag, 0)
        java.util.Arrays.fill(ttKey, 0L)
    }

    fun search(
        pos: Position,
        maxDepth: Int,
        timeMs: Long,
        multiPv: Int = 1,
        gameHashes: List<Long> = emptyList()
    ): SearchResult {
        val start = System.currentTimeMillis()
        deadline = start + timeMs
        stop = false
        nodes = 0

        val h = if (gameHashes.size > 200) gameHashes.subList(gameHashes.size - 200, gameHashes.size) else gameHashes
        for (i in h.indices) keys[i] = h[i]
        rootCount = h.size + 1
        keys[rootCount - 1] = pos.hash

        for (k in killers) {
            k[0] = 0
            k[1] = 0
        }
        for (side in history) for (row in side) for (i in row.indices) row[i] = row[i] / 8

        val rootMoves = pos.legalMoves()
        if (rootMoves.isEmpty()) return SearchResult(emptyList(), 0, 0, 0)

        var ordered = rootMoves.sortedByDescending { scoreMove(pos, it, 0, 0) }.toIntArray()
        val mpv = min(max(1, multiPv), ordered.size)
        var best: List<Line> = emptyList()
        var doneDepth = 0

        for (depth in 1..maxDepth) {
            val lines = searchRoot(pos, ordered, depth, mpv)
            if (lines == null || stop) break
            best = lines
            doneDepth = depth
            val first = lines.map { it.move }
            ordered = (first + ordered.filter { it !in first }).toIntArray()
            if (abs(lines[0].score) > MATE - 100 && depth >= 4) break
            if (ordered.size == 1 && depth >= 6) break
            if (System.currentTimeMillis() - start > timeMs * 45 / 100) break
        }
        if (best.isEmpty()) {
            val m = ordered[0]
            best = listOf(Line(m, 0, listOf(m)))
        }
        return SearchResult(best, doneDepth, nodes, System.currentTimeMillis() - start)
    }

    private fun searchRoot(pos: Position, moves: IntArray, depth: Int, mpv: Int): List<Line>? {
        val results = ArrayList<Line>()
        for (m in moves) {
            val child = pos.makeMove(m)
            var score: Int
            if (results.size < mpv) {
                score = -negamax(child, depth - 1, -INF, INF, 1, true)
            } else {
                val lb = results[results.size - 1].score
                score = -negamax(child, depth - 1, -lb - 1, -lb, 1, true)
                if (score > lb && !stop) score = -negamax(child, depth - 1, -INF, -lb, 1, true)
            }
            if (stop) return null
            if (results.size < mpv || score > results[results.size - 1].score) {
                val pv = ArrayList<Int>()
                pv.add(m)
                for (j in 1 until pvLen[1]) pv.add(pvTable[1][j])
                results.add(Line(m, score, pv))
                results.sortByDescending { it.score }
                if (results.size > mpv) results.removeAt(results.size - 1)
            }
        }
        return results
    }

    private fun negamax(pos: Position, depthIn: Int, alphaIn: Int, betaIn: Int, ply: Int, allowNull: Boolean): Int {
        if (stop) return 0
        pvLen[ply] = ply
        if ((++nodes and 1023L) == 0L && (abort || System.currentTimeMillis() >= deadline)) {
            stop = true
            return 0
        }
        if (ply >= MAX_PLY - 2) return Eval.evaluate(pos)
        keys[rootCount - 1 + ply] = pos.hash

        var alpha = alphaIn
        var beta = betaIn
        val isPv = betaIn - alphaIn > 1
        if (ply > 0) {
            if (pos.halfmove >= 100 || isRepetition(pos, ply)) return 0
            alpha = max(alpha, -MATE + ply)
            beta = min(beta, MATE - ply - 1)
            if (alpha >= beta) return alpha
        }

        val white = pos.whiteToMove
        val inCheck = pos.inCheck(white)
        var depth = depthIn
        if (inCheck) depth++
        if (depth <= 0) return quiesce(pos, alpha, beta, ply)

        val ttIdx = (pos.hash and TT_MASK).toInt()
        var ttMoveHint = 0
        if (ttKey[ttIdx] == pos.hash && ttFlag[ttIdx] != 0) {
            ttMoveHint = ttMove[ttIdx]
            if (!isPv && ply > 0 && ttDepth[ttIdx] >= depth) {
                val s = fromTT(ttScore[ttIdx], ply)
                when (ttFlag[ttIdx]) {
                    1 -> return s
                    2 -> if (s >= beta) return s
                    3 -> if (s <= alpha) return s
                }
            }
        }

        // Нулевой ход
        if (!isPv && !inCheck && allowNull && depth >= 3 && ply > 0 &&
            abs(beta) < MATE - 1000 && hasPieces(pos, white)
        ) {
            val r = if (depth >= 6) 3 else 2
            val s = -negamax(pos.makeNull(), depth - 1 - r, -beta, -beta + 1, ply + 1, false)
            if (stop) return 0
            if (s >= beta) return beta
        }

        val list = moveBufs[ply]
        list.size = 0
        pos.generate(list, false)
        val sc = scoreBufs[ply]
        for (i in 0 until list.size) sc[i] = scoreMove(pos, list.a[i], ttMoveHint, ply)

        var best = -INF
        var bestMove = 0
        var legal = 0
        var flag = 3
        val side = if (white) 0 else 1

        for (i in 0 until list.size) {
            pickBest(list.a, sc, i, list.size)
            val m = list.a[i]
            val child = pos.makeMove(m)
            if (child.inCheck(white)) continue
            legal++
            val capture = pos.board[m.mTo] != 0 ||
                (abs(pos.board[m.mFrom]) == PAWN && (m.mFrom and 7) != (m.mTo and 7))
            val quiet = !capture && m.mPromo == 0

            var score: Int
            if (legal == 1) {
                score = -negamax(child, depth - 1, -beta, -alpha, ply + 1, true)
            } else {
                var reduction = 0
                if (depth >= 3 && legal > 4 && quiet && !inCheck && !child.inCheck(child.whiteToMove)) {
                    reduction = if (legal > 10) 2 else 1
                }
                score = -negamax(child, depth - 1 - reduction, -alpha - 1, -alpha, ply + 1, true)
                if (score > alpha && reduction > 0) {
                    score = -negamax(child, depth - 1, -alpha - 1, -alpha, ply + 1, true)
                }
                if (score > alpha && score < beta) {
                    score = -negamax(child, depth - 1, -beta, -alpha, ply + 1, true)
                }
            }
            if (stop) return 0

            if (score > best) {
                best = score
                bestMove = m
                if (score > alpha) {
                    alpha = score
                    flag = 1
                    pvTable[ply][ply] = m
                    val childLen = pvLen[ply + 1]
                    for (j in ply + 1 until childLen) pvTable[ply][j] = pvTable[ply + 1][j]
                    pvLen[ply] = max(childLen, ply + 1)
                    if (alpha >= beta) {
                        flag = 2
                        if (quiet) {
                            if (killers[ply][0] != m) {
                                killers[ply][1] = killers[ply][0]
                                killers[ply][0] = m
                            }
                            val hv = history[side][m.mFrom][m.mTo] + depth * depth
                            history[side][m.mFrom][m.mTo] = min(hv, 60000)
                        }
                        break
                    }
                }
            }
        }

        if (legal == 0) return if (inCheck) -MATE + ply else 0

        ttKey[ttIdx] = pos.hash
        ttDepth[ttIdx] = depth
        ttScore[ttIdx] = toTT(best, ply)
        ttFlag[ttIdx] = flag
        ttMove[ttIdx] = bestMove
        return best
    }

    private fun quiesce(pos: Position, alphaIn: Int, beta: Int, ply: Int): Int {
        if (stop) return 0
        pvLen[ply] = ply
        if ((++nodes and 1023L) == 0L && (abort || System.currentTimeMillis() >= deadline)) {
            stop = true
            return 0
        }
        if (ply >= MAX_PLY - 2) return Eval.evaluate(pos)

        var alpha = alphaIn
        val white = pos.whiteToMove
        val inCheck = pos.inCheck(white)
        var best: Int
        var standPat = -INF
        if (inCheck) {
            best = -MATE + ply
        } else {
            standPat = Eval.evaluate(pos)
            best = standPat
            if (best >= beta) return best
            if (best > alpha) alpha = best
        }

        val list = moveBufs[ply]
        list.size = 0
        pos.generate(list, !inCheck)
        val sc = scoreBufs[ply]
        for (i in 0 until list.size) sc[i] = scoreMove(pos, list.a[i], 0, ply)

        for (i in 0 until list.size) {
            pickBest(list.a, sc, i, list.size)
            val m = list.a[i]
            if (!inCheck && m.mPromo == 0) {
                var victim = abs(pos.board[m.mTo])
                if (victim == 0) victim = PAWN
                if (standPat + Eval.VALUE[victim] + 200 < alpha) continue
            }
            val child = pos.makeMove(m)
            if (child.inCheck(white)) continue
            val score = -quiesce(child, -beta, -alpha, ply + 1)
            if (stop) return 0
            if (score > best) {
                best = score
                if (score > alpha) {
                    alpha = score
                    if (alpha >= beta) break
                }
            }
        }
        return best
    }

    private fun isRepetition(pos: Position, ply: Int): Boolean {
        val idx = rootCount - 1 + ply
        val h = pos.hash
        var i = idx - 2
        val limit = max(0, idx - pos.halfmove)
        while (i >= limit) {
            if (keys[i] == h) return true
            i -= 2
        }
        return false
    }

    private fun hasPieces(pos: Position, white: Boolean): Boolean {
        for (sq in 0..63) {
            val p = pos.board[sq]
            if (p == 0 || (p > 0) != white) continue
            val t = abs(p)
            if (t != PAWN && t != KING) return true
        }
        return false
    }

    private fun scoreMove(pos: Position, m: Int, ttMoveHint: Int, ply: Int): Int {
        if (m == ttMoveHint && m != 0) return 1_000_000
        val attacker = abs(pos.board[m.mFrom])
        var victim = abs(pos.board[m.mTo])
        val ep = attacker == PAWN && victim == 0 && (m.mFrom and 7) != (m.mTo and 7)
        if (ep) victim = PAWN
        return when {
            victim != 0 -> 100_000 + victim * 10 - attacker
            m.mPromo != 0 -> 90_000 + m.mPromo
            killers[ply][0] == m -> 80_000
            killers[ply][1] == m -> 79_000
            else -> history[if (pos.whiteToMove) 0 else 1][m.mFrom][m.mTo]
        }
    }

    private fun pickBest(a: IntArray, sc: IntArray, from: Int, size: Int) {
        var bi = from
        var bs = sc[from]
        for (j in from + 1 until size) {
            if (sc[j] > bs) {
                bs = sc[j]
                bi = j
            }
        }
        if (bi != from) {
            val tm = a[from]; a[from] = a[bi]; a[bi] = tm
            val ts = sc[from]; sc[from] = sc[bi]; sc[bi] = ts
        }
    }

    private fun toTT(s: Int, ply: Int): Int = if (s > MATE - 1000) s + ply else if (s < -MATE + 1000) s - ply else s
    private fun fromTT(s: Int, ply: Int): Int = if (s > MATE - 1000) s - ply else if (s < -MATE + 1000) s + ply else s
}
