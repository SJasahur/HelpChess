package com.helpchess

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.helpchess.chess.Engine
import com.helpchess.chess.Fmt
import com.helpchess.chess.Outcome
import com.helpchess.chess.PAWN
import com.helpchess.chess.Position
import com.helpchess.chess.SearchResult
import com.helpchess.chess.mFrom
import com.helpchess.chess.mTo
import com.helpchess.chess.sanLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private class MoveRecord(
    val byWhite: Boolean,
    val capture: Boolean,
    val check: Boolean,
    var loss: Int? = null
)

class GameViewModel : ViewModel() {

    private val engine = Engine()
    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(null, r, "helpchess-engine", 8L * 1024 * 1024).apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var position: Position = Position.start()
    private val positions = ArrayList<Position>()   // позиции ДО каждого хода
    private val moves = ArrayList<Int>()
    private val sans = ArrayList<String>()
    private val records = ArrayList<MoveRecord>()
    private val analyses = HashMap<Int, SearchResult>() // ключ — число ходов, сделанных до позиции
    private var searchJob: Job? = null

    init {
        afterPositionChange()
    }

    // ---------------------------------------------------------------- ввод

    fun onSquareClick(sq: Int) {
        val s = _ui.value
        if (s.gameOver || s.promotion != null) return
        if (s.autoBot && position.whiteToMove != s.userIsWhite) return
        val piece = position.board[sq]
        val mine = piece != 0 && (piece > 0) == position.whiteToMove
        if (s.selected >= 0 && sq in s.targets) {
            val cands = position.legalMoves().filter { it.mFrom == s.selected && it.mTo == sq }
            if (cands.size > 1) {
                _ui.update { it.copy(promotion = PromotionRequest(s.selected, sq)) }
            } else if (cands.size == 1) {
                playMove(cands[0])
            }
            return
        }
        if (mine) {
            val targets = position.legalMoves().filter { it.mFrom == sq }.map { it.mTo }.toSet()
            _ui.update { it.copy(selected = sq, targets = targets) }
        } else {
            _ui.update { it.copy(selected = -1, targets = emptySet()) }
        }
    }

    fun choosePromotion(piece: Int) {
        val req = _ui.value.promotion ?: return
        val m = position.legalMoves().firstOrNull {
            it.mFrom == req.from && it.mTo == req.to && ((it shr 12) and 7) == piece
        }
        _ui.update { it.copy(promotion = null) }
        if (m != null) playMove(m)
    }

    fun cancelPromotion() {
        _ui.update { it.copy(promotion = null, selected = -1, targets = emptySet()) }
    }

    // ---------------------------------------------------------------- партия

    fun newGame(userWhite: Boolean = _ui.value.userIsWhite, fen: String? = null) {
        val start = try {
            if (fen.isNullOrBlank()) Position.start() else Position.fromFen(fen)
        } catch (e: Exception) {
            _ui.update { it.copy(message = "FEN не принят: ${e.message}") }
            return
        }
        searchJob?.cancel()
        engine.abort = true
        position = start
        positions.clear()
        moves.clear()
        sans.clear()
        records.clear()
        analyses.clear()
        _ui.update {
            it.copy(
                userIsWhite = userWhite,
                flipped = !userWhite,
                report = null,
                profile = null,
                message = null
            )
        }
        afterPositionChange()
    }

    fun loadFen(fen: String) = newGame(_ui.value.userIsWhite, fen)

    fun setUserWhite(white: Boolean) = newGame(white, null)

    fun undo() {
        if (moves.isEmpty()) return
        searchJob?.cancel()
        engine.abort = true
        do {
            position = positions.removeAt(positions.size - 1)
            moves.removeAt(moves.size - 1)
            sans.removeAt(sans.size - 1)
            records.removeAt(records.size - 1)
            analyses.remove(moves.size + 1)
        } while (_ui.value.autoBot && position.whiteToMove != _ui.value.userIsWhite && moves.isNotEmpty())
        _ui.update { it.copy(report = null, profile = buildProfile(it.userIsWhite)) }
        afterPositionChange()
    }

    fun flip() = _ui.update { it.copy(flipped = !it.flipped) }

    fun setAutoBot(on: Boolean) {
        _ui.update { it.copy(autoBot = on) }
        afterPositionChange()
    }

    fun setBotLevel(level: Int) = _ui.update { it.copy(botLevel = level.coerceIn(1, 5)) }
    fun setTime(ms: Long) = _ui.update { it.copy(timeMs = ms) }

    fun setHintsOn(on: Boolean) {
        _ui.update { it.copy(hintsOn = on) }
        afterPositionChange()
    }

    fun dismissMessage() = _ui.update { it.copy(message = null) }

    // ---------------------------------------------------------------- ход

    private fun playMove(m: Int) {
        val before = position
        val san = before.toSan(m)
        val after = before.makeMove(m)
        val piece = abs(before.board[m.mFrom])
        val capture = before.board[m.mTo] != 0 || (piece == PAWN && (m.mFrom and 7) != (m.mTo and 7))
        positions.add(before)
        moves.add(m)
        sans.add(san)
        records.add(MoveRecord(before.whiteToMove, capture, after.inCheck(after.whiteToMove)))
        position = after
        afterPositionChange()
    }

    private fun moveText(): String {
        val sb = StringBuilder()
        for (i in moves.indices) {
            val p = positions[i]
            if (p.whiteToMove) sb.append(p.fullmove).append(". ")
            else if (i == 0) sb.append(p.fullmove).append("... ")
            sb.append(sans[i]).append(' ')
        }
        return sb.toString().trim()
    }

    private fun afterPositionChange() {
        searchJob?.cancel()
        engine.abort = true

        var status = ""
        var over = false
        when (position.outcome()) {
            Outcome.CHECKMATE -> {
                over = true
                status = "Мат — победили ${if (position.whiteToMove) "чёрные" else "белые"}"
            }
            Outcome.STALEMATE -> { over = true; status = "Пат — ничья" }
            Outcome.DRAW_FIFTY -> { over = true; status = "Ничья по правилу 50 ходов" }
            Outcome.DRAW_MATERIAL -> { over = true; status = "Ничья: недостаточно материала" }
            Outcome.ONGOING -> {
                if (positions.count { it.hash == position.hash } >= 2) {
                    over = true
                    status = "Ничья: троекратное повторение"
                }
            }
        }
        if (!over) {
            val s = _ui.value
            status = if (position.whiteToMove == s.userIsWhite) "Ваш ход" else "Ход соперника"
            if (position.inCheck(position.whiteToMove)) status += " · шах"
        }

        _ui.update {
            it.copy(
                position = position,
                selected = -1,
                targets = emptySet(),
                lastMove = moves.lastOrNull() ?: 0,
                moveText = moveText(),
                gameOver = over,
                status = status,
                thinking = false,
                hints = emptyList(),
                threat = null,
                promotion = null,
                depth = 0
            )
        }
        if (over) return
        val s = _ui.value
        val botTurn = s.autoBot && position.whiteToMove != s.userIsWhite
        if (s.hintsOn || botTurn) requestAnalysis()
    }

    // ---------------------------------------------------------------- анализ

    private fun requestAnalysis() {
        val pos = position
        val idx = moves.size
        val s = _ui.value
        val userTurn = pos.whiteToMove == s.userIsWhite
        val botTurn = s.autoBot && !userTurn
        val hs = positions.map { it.hash }
        val timeMs = s.timeMs
        val level = s.botLevel

        _ui.update { it.copy(thinking = true) }
        searchJob = viewModelScope.launch(dispatcher) {
            engine.abort = false
            if (!isActive) return@launch

            val res = engine.search(pos, 24, timeMs, 3, hs)
            if (!isActive) return@launch

            val hintLines = res.lines.map { l ->
                val reply = if (l.pv.size > 1) {
                    val next = pos.makeMove(l.move)
                    if (next.legalMoves().contains(l.pv[1])) next.toSan(l.pv[1]) else null
                } else null
                HintLine(
                    move = l.move,
                    san = pos.toSan(l.move),
                    eval = Fmt.eval(l.score, userTurn),
                    line = sanLine(pos, l.pv, 6),
                    reply = reply
                )
            }

            val threat = if (userTurn && res.best != null) findThreat(pos, res.best!!.score) else null
            if (!isActive) return@launch

            var botMove = 0
            if (botTurn && res.best != null) botMove = pickBotMove(pos, res, level, hs)
            if (!isActive) return@launch

            withContext(Dispatchers.Main) {
                if (position !== pos) return@withContext
                applyAnalysis(pos, idx, res, hintLines, threat, userTurn)
                if (botMove != 0) playMove(botMove)
            }
        }
    }

    /** Угроза соперника: что он сыграет, если мы «пропустим» ход. */
    private fun findThreat(pos: Position, ourScore: Int): ThreatInfo? {
        if (pos.inCheck(pos.whiteToMove)) return null
        if (ourScore > Engine.MATE - 1000) return null
        val np = pos.makeNull()
        val r = engine.search(np, 7, 220, 1, emptyList())
        val l = r.best ?: return null
        if (!np.legalMoves().contains(l.move)) return null
        val gain = l.score + ourScore
        val mate = l.score > Engine.MATE - 1000
        if (!mate && gain < 120) return null
        val san = np.toSan(l.move)
        val victim = abs(np.board[l.move.mTo])
        val text = when {
            mate -> "Угроза мата: $san"
            victim != 0 -> "Угроза: $san — соперник бьёт ${Fmt.pieceName(victim)}"
            else -> "Угроза: $san (выгода ≈ +" + String.format(Locale.US, "%.1f", gain / 100.0) + ")"
        }
        return ThreatInfo(l.move, text)
    }

    private fun pickBotMove(pos: Position, full: SearchResult, level: Int, hs: List<Long>): Int {
        val best = full.best ?: return 0
        if (level >= 5) return best.move
        val depth = intArrayOf(1, 2, 3, 5)[level - 1]
        val mpv = intArrayOf(5, 4, 3, 2)[level - 1]
        val margin = intArrayOf(600, 250, 120, 50)[level - 1]
        engine.clearTables()
        val r = engine.search(pos, depth, 150, mpv, hs)
        val top = r.best ?: return best.move
        val cands = r.lines.filter { it.score >= top.score - margin }
        return cands.random().move
    }

    private fun clampCp(s: Int) = s.coerceIn(-1500, 1500)

    private fun qualityLabel(loss: Int, isBest: Boolean): String = when {
        isBest -> "Лучший ход"
        loss <= 15 -> "Отличный ход"
        loss <= 40 -> "Хороший ход"
        loss <= 90 -> "Неточность"
        loss <= 200 -> "Ошибка"
        else -> "Зевок"
    }

    private fun applyAnalysis(
        pos: Position,
        idx: Int,
        res: SearchResult,
        hintLines: List<HintLine>,
        threat: ThreatInfo?,
        userTurn: Boolean
    ) {
        analyses[idx] = res
        val userIsWhite = _ui.value.userIsWhite
        var report: MoveReport? = null

        if (idx >= 1) {
            val prev = analyses[idx - 1]
            val prevBest = prev?.best
            val cur = res.best
            val rec = records[idx - 1]
            if (prev != null && prevBest != null && cur != null) {
                val played = moves[idx - 1]
                val bestScore = clampCp(prevBest.score)
                val nowScore = clampCp(-cur.score)
                var loss = max(0, bestScore - nowScore)
                val playedLine = prev.lines.firstOrNull { it.move == played }
                if (playedLine != null) loss = min(loss, max(0, bestScore - clampCp(playedLine.score)))
                val isBest = prevBest.move == played
                if (isBest) loss = 0
                rec.loss = loss
                val byOpp = rec.byWhite != userIsWhite
                val bestSan = if (isBest) null else positions[idx - 1].toSan(prevBest.move)
                val who = if (byOpp) "Соперник" else "Вы"
                val label = qualityLabel(loss, isBest)
                val lossText = if (loss > 15) " (−" + String.format(Locale.US, "%.1f", loss / 100.0) + ")" else ""
                report = MoveReport(byOpp, sans[idx - 1], "$who: ${sans[idx - 1]} — $label$lossText", loss, bestSan)
            }
        }

        _ui.update {
            it.copy(
                thinking = false,
                hints = hintLines,
                hintsForUser = userTurn,
                depth = res.depth,
                threat = threat,
                report = report ?: it.report,
                profile = buildProfile(userIsWhite)
            )
        }
    }

    private fun buildProfile(userIsWhite: Boolean): OppProfile? {
        val opp = records.filter { it.byWhite != userIsWhite }
        if (opp.isEmpty()) return null
        val evaluated = opp.mapNotNull { it.loss }
        val n = opp.size
        val avg = if (evaluated.isEmpty()) 0 else evaluated.sumOf { min(it, 300) } / evaluated.size
        val blunders = evaluated.count { it > 200 }
        val mistakes = evaluated.count { it in 91..200 }
        val inacc = evaluated.count { it in 41..90 }
        val active = opp.count { it.capture || it.check }.toDouble() / n

        val accuracy = when {
            evaluated.size < 3 -> "мало данных"
            avg < 25 -> "играет очень точно"
            avg < 60 -> "играет уверенно"
            avg < 120 -> "часто неточен"
            else -> "много зевает"
        }
        val manner = when {
            n < 4 -> ""
            active > 0.45 -> ", агрессивный стиль"
            active < 0.2 -> ", спокойный позиционный стиль"
            else -> ""
        }
        val advice = when {
            evaluated.size < 3 -> "Нужно ещё несколько ходов, чтобы понять стиль соперника."
            avg >= 120 -> "Соперник слабый: играйте просто, забирайте подаренный материал и не рискуйте."
            avg >= 60 -> "Соперник ошибается в тактике: создавайте угрозы и усложняйте позицию."
            else -> "Соперник играет точно: не форсируйте события и избегайте рискованных ходов."
        }
        return OppProfile(n, avg, blunders, mistakes, inacc, accuracy + manner, advice)
    }

    override fun onCleared() {
        searchJob?.cancel()
        engine.abort = true
        super.onCleared()
    }
}
