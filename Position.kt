package com.helpchess.chess

import kotlin.math.abs

const val PAWN = 1
const val KNIGHT = 2
const val BISHOP = 3
const val ROOK = 4
const val QUEEN = 5
const val KING = 6

const val CR_WK = 1
const val CR_WQ = 2
const val CR_BK = 4
const val CR_BQ = 8

// Ход кодируется в Int: from (6 бит) | to (6 бит) | превращение (3 бита)
fun encodeMove(from: Int, to: Int, promo: Int = 0): Int = from or (to shl 6) or (promo shl 12)
val Int.mFrom: Int get() = this and 63
val Int.mTo: Int get() = (this shr 6) and 63
val Int.mPromo: Int get() = (this shr 12) and 7

fun squareName(sq: Int): String = "" + ('a' + (sq and 7)) + ('1' + (sq shr 3))
fun parseSquare(s: String): Int = (s[0] - 'a') + (s[1] - '1') * 8

enum class Outcome { ONGOING, CHECKMATE, STALEMATE, DRAW_FIFTY, DRAW_MATERIAL }

object Zobrist {
    val piece = Array(13) { LongArray(64) }
    val castle = LongArray(16)
    val epFile = LongArray(8)
    val side: Long

    init {
        val rnd = java.util.Random(0x5EEDL)
        for (p in 0..12) for (s in 0..63) piece[p][s] = rnd.nextLong()
        for (i in 0..15) castle[i] = rnd.nextLong()
        for (i in 0..7) epFile[i] = rnd.nextLong()
        side = rnd.nextLong()
    }
}

object Tables {
    private val KN_DF = intArrayOf(1, 2, 2, 1, -1, -2, -2, -1)
    private val KN_DR = intArrayOf(2, 1, -1, -2, -2, -1, 1, 2)
    private val KG_DF = intArrayOf(1, 1, 1, 0, 0, -1, -1, -1)
    private val KG_DR = intArrayOf(1, 0, -1, 1, -1, 1, 0, -1)

    val knight: Array<IntArray> = Array(64) { build(it, KN_DF, KN_DR) }
    val king: Array<IntArray> = Array(64) { build(it, KG_DF, KG_DR) }

    val BISHOP_DF = intArrayOf(1, 1, -1, -1)
    val BISHOP_DR = intArrayOf(1, -1, 1, -1)
    val ROOK_DF = intArrayOf(1, -1, 0, 0)
    val ROOK_DR = intArrayOf(0, 0, 1, -1)

    private fun build(sq: Int, df: IntArray, dr: IntArray): IntArray {
        val f = sq and 7
        val r = sq shr 3
        val out = ArrayList<Int>()
        for (i in df.indices) {
            val nf = f + df[i]
            val nr = r + dr[i]
            if (nf in 0..7 && nr in 0..7) out.add(nr * 8 + nf)
        }
        return out.toIntArray()
    }
}

class MoveList {
    val a = IntArray(512)
    var size = 0
    fun add(m: Int) {
        a[size++] = m
    }
}

/**
 * Неизменяемая позиция. Клетка = rank * 8 + file (a1 = 0, h1 = 7, a8 = 56).
 * Фигуры: белые > 0, чёрные < 0 (PAWN..KING).
 */
class Position(
    val board: IntArray,
    val whiteToMove: Boolean,
    val castling: Int,
    val ep: Int,
    val halfmove: Int,
    val fullmove: Int
) {
    val whiteKing: Int
    val blackKing: Int
    val hash: Long

    init {
        var wk = -1
        var bk = -1
        var h = 0L
        for (sq in 0..63) {
            val p = board[sq]
            if (p != 0) {
                h = h xor Zobrist.piece[p + 6][sq]
                if (p == KING) wk = sq else if (p == -KING) bk = sq
            }
        }
        if (!whiteToMove) h = h xor Zobrist.side
        h = h xor Zobrist.castle[castling]
        if (ep >= 0) h = h xor Zobrist.epFile[ep and 7]
        whiteKing = wk
        blackKing = bk
        hash = h
    }

    companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        fun start(): Position = fromFen(START_FEN)

        fun fromFen(fen: String): Position {
            val parts = fen.trim().split(Regex("\\s+"))
            require(parts.isNotEmpty() && parts[0].isNotEmpty()) { "Пустой FEN" }
            val b = IntArray(64)
            var rank = 7
            var file = 0
            for (c in parts[0]) {
                when {
                    c == '/' -> {
                        rank--
                        file = 0
                    }
                    c.isDigit() -> file += c - '0'
                    else -> {
                        val t = when (c.lowercaseChar()) {
                            'p' -> PAWN
                            'n' -> KNIGHT
                            'b' -> BISHOP
                            'r' -> ROOK
                            'q' -> QUEEN
                            'k' -> KING
                            else -> throw IllegalArgumentException("Неверный символ в FEN: $c")
                        }
                        require(rank in 0..7 && file in 0..7) { "Неверная расстановка в FEN" }
                        b[rank * 8 + file] = if (c.isUpperCase()) t else -t
                        file++
                    }
                }
            }
            val white = parts.getOrElse(1) { "w" } != "b"
            val cs = parts.getOrElse(2) { "-" }
            var cr = 0
            if ('K' in cs) cr = cr or CR_WK
            if ('Q' in cs) cr = cr or CR_WQ
            if ('k' in cs) cr = cr or CR_BK
            if ('q' in cs) cr = cr or CR_BQ
            val epStr = parts.getOrElse(3) { "-" }
            val ep = if (epStr == "-" || epStr.length != 2) -1 else parseSquare(epStr)
            val hm = parts.getOrElse(4) { "0" }.toIntOrNull() ?: 0
            val fm = parts.getOrElse(5) { "1" }.toIntOrNull() ?: 1
            val pos = Position(b, white, cr, ep, hm, fm)
            require(pos.whiteKing >= 0 && pos.blackKing >= 0) { "В позиции должны быть оба короля" }
            require(!pos.inCheck(!white)) { "Некорректная позиция: сторона, не имеющая хода, под шахом" }
            return pos
        }
    }

    fun toFen(): String {
        val sb = StringBuilder()
        for (r in 7 downTo 0) {
            var empty = 0
            for (f in 0..7) {
                val p = board[r * 8 + f]
                if (p == 0) {
                    empty++
                } else {
                    if (empty > 0) {
                        sb.append(empty)
                        empty = 0
                    }
                    val ch = "pnbrqk"[abs(p) - 1]
                    sb.append(if (p > 0) ch.uppercaseChar() else ch)
                }
            }
            if (empty > 0) sb.append(empty)
            if (r > 0) sb.append('/')
        }
        sb.append(if (whiteToMove) " w " else " b ")
        if (castling == 0) sb.append('-') else {
            if (castling and CR_WK != 0) sb.append('K')
            if (castling and CR_WQ != 0) sb.append('Q')
            if (castling and CR_BK != 0) sb.append('k')
            if (castling and CR_BQ != 0) sb.append('q')
        }
        sb.append(' ').append(if (ep >= 0) squareName(ep) else "-")
        sb.append(' ').append(halfmove).append(' ').append(fullmove)
        return sb.toString()
    }

    fun inCheck(white: Boolean): Boolean {
        val k = if (white) whiteKing else blackKing
        return k >= 0 && isAttacked(k, !white)
    }

    fun isAttacked(sq: Int, byWhite: Boolean): Boolean {
        val f = sq and 7
        val r = sq shr 3
        val pr = if (byWhite) r - 1 else r + 1
        if (pr in 0..7) {
            val pawn = if (byWhite) PAWN else -PAWN
            if (f > 0 && board[pr * 8 + f - 1] == pawn) return true
            if (f < 7 && board[pr * 8 + f + 1] == pawn) return true
        }
        val kn = if (byWhite) KNIGHT else -KNIGHT
        for (t in Tables.knight[sq]) if (board[t] == kn) return true
        val kg = if (byWhite) KING else -KING
        for (t in Tables.king[sq]) if (board[t] == kg) return true
        val bishop = if (byWhite) BISHOP else -BISHOP
        val rook = if (byWhite) ROOK else -ROOK
        val queen = if (byWhite) QUEEN else -QUEEN
        for (d in 0..3) {
            var cf = f + Tables.BISHOP_DF[d]
            var cr = r + Tables.BISHOP_DR[d]
            while (cf in 0..7 && cr in 0..7) {
                val p = board[cr * 8 + cf]
                if (p != 0) {
                    if (p == bishop || p == queen) return true
                    break
                }
                cf += Tables.BISHOP_DF[d]
                cr += Tables.BISHOP_DR[d]
            }
        }
        for (d in 0..3) {
            var cf = f + Tables.ROOK_DF[d]
            var cr = r + Tables.ROOK_DR[d]
            while (cf in 0..7 && cr in 0..7) {
                val p = board[cr * 8 + cf]
                if (p != 0) {
                    if (p == rook || p == queen) return true
                    break
                }
                cf += Tables.ROOK_DF[d]
                cr += Tables.ROOK_DR[d]
            }
        }
        return false
    }

    private fun addPromos(list: MoveList, from: Int, to: Int) {
        list.add(encodeMove(from, to, QUEEN))
        list.add(encodeMove(from, to, ROOK))
        list.add(encodeMove(from, to, BISHOP))
        list.add(encodeMove(from, to, KNIGHT))
    }

    private fun slide(list: MoveList, sq: Int, white: Boolean, dfs: IntArray, drs: IntArray, capturesOnly: Boolean) {
        val f = sq and 7
        val r = sq shr 3
        for (d in dfs.indices) {
            var cf = f + dfs[d]
            var cr = r + drs[d]
            while (cf in 0..7 && cr in 0..7) {
                val to = cr * 8 + cf
                val tp = board[to]
                if (tp == 0) {
                    if (!capturesOnly) list.add(encodeMove(sq, to))
                } else {
                    if ((tp > 0) != white) list.add(encodeMove(sq, to))
                    break
                }
                cf += dfs[d]
                cr += drs[d]
            }
        }
    }

    /** Псевдолегальные ходы (без проверки шаха своему королю). */
    fun generate(list: MoveList, capturesOnly: Boolean) {
        val white = whiteToMove
        for (sq in 0..63) {
            val p = board[sq]
            if (p == 0 || (p > 0) != white) continue
            val f = sq and 7
            val r = sq shr 3
            when (abs(p)) {
                PAWN -> {
                    val dir = if (white) 1 else -1
                    val startRank = if (white) 1 else 6
                    val promoRank = if (white) 7 else 0
                    val nr = r + dir
                    if (nr in 0..7) {
                        val fwd = nr * 8 + f
                        if (board[fwd] == 0) {
                            if (nr == promoRank) {
                                addPromos(list, sq, fwd)
                            } else if (!capturesOnly) {
                                list.add(encodeMove(sq, fwd))
                                if (r == startRank && board[fwd + dir * 8] == 0) {
                                    list.add(encodeMove(sq, fwd + dir * 8))
                                }
                            }
                        }
                        for (df in -1..1 step 2) {
                            val nf = f + df
                            if (nf !in 0..7) continue
                            val to = nr * 8 + nf
                            val target = board[to]
                            if (target != 0 && (target > 0) != white) {
                                if (nr == promoRank) addPromos(list, sq, to) else list.add(encodeMove(sq, to))
                            } else if (target == 0 && to == ep) {
                                list.add(encodeMove(sq, to))
                            }
                        }
                    }
                }
                KNIGHT -> for (to in Tables.knight[sq]) {
                    val tp = board[to]
                    if (tp == 0) {
                        if (!capturesOnly) list.add(encodeMove(sq, to))
                    } else if ((tp > 0) != white) list.add(encodeMove(sq, to))
                }
                BISHOP -> slide(list, sq, white, Tables.BISHOP_DF, Tables.BISHOP_DR, capturesOnly)
                ROOK -> slide(list, sq, white, Tables.ROOK_DF, Tables.ROOK_DR, capturesOnly)
                QUEEN -> {
                    slide(list, sq, white, Tables.BISHOP_DF, Tables.BISHOP_DR, capturesOnly)
                    slide(list, sq, white, Tables.ROOK_DF, Tables.ROOK_DR, capturesOnly)
                }
                KING -> {
                    for (to in Tables.king[sq]) {
                        val tp = board[to]
                        if (tp == 0) {
                            if (!capturesOnly) list.add(encodeMove(sq, to))
                        } else if ((tp > 0) != white) list.add(encodeMove(sq, to))
                    }
                    if (!capturesOnly) {
                        if (white && sq == 4) {
                            if ((castling and CR_WK) != 0 && board[5] == 0 && board[6] == 0 && board[7] == ROOK &&
                                !isAttacked(4, false) && !isAttacked(5, false)
                            ) list.add(encodeMove(4, 6))
                            if ((castling and CR_WQ) != 0 && board[3] == 0 && board[2] == 0 && board[1] == 0 && board[0] == ROOK &&
                                !isAttacked(4, false) && !isAttacked(3, false)
                            ) list.add(encodeMove(4, 2))
                        } else if (!white && sq == 60) {
                            if ((castling and CR_BK) != 0 && board[61] == 0 && board[62] == 0 && board[63] == -ROOK &&
                                !isAttacked(60, true) && !isAttacked(61, true)
                            ) list.add(encodeMove(60, 62))
                            if ((castling and CR_BQ) != 0 && board[59] == 0 && board[58] == 0 && board[57] == 0 && board[56] == -ROOK &&
                                !isAttacked(60, true) && !isAttacked(59, true)
                            ) list.add(encodeMove(60, 58))
                        }
                    }
                }
            }
        }
    }

    fun makeMove(m: Int): Position {
        val nb = board.copyOf()
        val from = m.mFrom
        val to = m.mTo
        val promo = m.mPromo
        val piece = nb[from]
        val white = piece > 0
        val type = abs(piece)
        var newCastling = castling
        var newEp = -1
        var resetClock = nb[to] != 0 || type == PAWN
        nb[to] = piece
        nb[from] = 0
        if (type == PAWN) {
            if (to == ep && (to and 7) != (from and 7)) {
                nb[if (white) to - 8 else to + 8] = 0
                resetClock = true
            }
            if (abs(to - from) == 16) newEp = (from + to) / 2
            if (promo != 0) nb[to] = if (white) promo else -promo
        } else if (type == KING) {
            if (abs(to - from) == 2) {
                if (to > from) {
                    nb[from + 1] = nb[from + 3]
                    nb[from + 3] = 0
                } else {
                    nb[from - 1] = nb[from - 4]
                    nb[from - 4] = 0
                }
            }
            newCastling = newCastling and (if (white) (CR_WK or CR_WQ).inv() else (CR_BK or CR_BQ).inv())
        }
        if (from == 0 || to == 0) newCastling = newCastling and CR_WQ.inv()
        if (from == 7 || to == 7) newCastling = newCastling and CR_WK.inv()
        if (from == 56 || to == 56) newCastling = newCastling and CR_BQ.inv()
        if (from == 63 || to == 63) newCastling = newCastling and CR_BK.inv()
        return Position(
            nb,
            !whiteToMove,
            newCastling,
            newEp,
            if (resetClock) 0 else halfmove + 1,
            if (whiteToMove) fullmove else fullmove + 1
        )
    }

    /** «Пустой» ход — нужен для поиска угроз соперника. */
    fun makeNull(): Position = Position(board, !whiteToMove, castling, -1, halfmove + 1, if (whiteToMove) fullmove else fullmove + 1)

    fun legalMoves(): IntArray {
        val list = MoveList()
        generate(list, false)
        val out = IntArray(list.size)
        var n = 0
        for (i in 0 until list.size) {
            val m = list.a[i]
            if (!makeMove(m).inCheck(whiteToMove)) out[n++] = m
        }
        return out.copyOf(n)
    }

    fun insufficientMaterial(): Boolean {
        var minors = 0
        for (sq in 0..63) {
            val t = abs(board[sq])
            if (t == PAWN || t == ROOK || t == QUEEN) return false
            if (t == KNIGHT || t == BISHOP) minors++
        }
        return minors <= 1
    }

    fun outcome(): Outcome {
        if (legalMoves().isEmpty()) return if (inCheck(whiteToMove)) Outcome.CHECKMATE else Outcome.STALEMATE
        if (halfmove >= 100) return Outcome.DRAW_FIFTY
        if (insufficientMaterial()) return Outcome.DRAW_MATERIAL
        return Outcome.ONGOING
    }

    /** Запись хода в стандартной алгебраической нотации (SAN). */
    fun toSan(m: Int): String {
        val from = m.mFrom
        val to = m.mTo
        val p = board[from]
        val t = abs(p)
        val sb = StringBuilder()
        if (t == KING && abs(to - from) == 2) {
            sb.append(if (to > from) "O-O" else "O-O-O")
        } else {
            val capture = board[to] != 0 || (t == PAWN && (from and 7) != (to and 7))
            if (t == PAWN) {
                if (capture) sb.append('a' + (from and 7)).append('x')
            } else {
                sb.append("?PNBRQK"[t])
                val others = legalMoves().filter { it != m && it.mTo == to && abs(board[it.mFrom]) == t }
                if (others.isNotEmpty()) {
                    val sameFile = others.any { (it.mFrom and 7) == (from and 7) }
                    val sameRank = others.any { (it.mFrom shr 3) == (from shr 3) }
                    when {
                        !sameFile -> sb.append('a' + (from and 7))
                        !sameRank -> sb.append('1' + (from shr 3))
                        else -> sb.append(squareName(from))
                    }
                }
                if (capture) sb.append('x')
            }
            sb.append(squareName(to))
            if (m.mPromo != 0) sb.append('=').append("?PNBRQK"[m.mPromo])
        }
        val child = makeMove(m)
        if (child.inCheck(child.whiteToMove)) {
            sb.append(if (child.legalMoves().isEmpty()) '#' else '+')
        }
        return sb.toString()
    }
}
