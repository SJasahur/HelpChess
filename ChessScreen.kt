@file:OptIn(ExperimentalMaterial3Api::class)

package com.helpchess.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.helpchess.GameViewModel
import com.helpchess.UiState
import com.helpchess.chess.BISHOP
import com.helpchess.chess.KING
import com.helpchess.chess.KNIGHT
import com.helpchess.chess.PAWN
import com.helpchess.chess.QUEEN
import com.helpchess.chess.ROOK
import com.helpchess.chess.mFrom
import com.helpchess.chess.mTo
import kotlin.math.abs
import kotlin.math.sqrt

private val Green = Color(0xFF3DDC84)
private val Yellow = Color(0xFFFFC107)
private val Red = Color(0xFFFF5252)
private val CardBg = Color(0xFF26262C)
private val LightSq = Color(0xFFF0D9B5)
private val DarkSq = Color(0xFFB58863)

private class Arrow(val from: Int, val to: Int, val color: Color, val alpha: Float)

@Composable
fun HelpChessApp(vm: GameViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF1B1B1F))
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("HelpChess", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.weight(1f))
            Text(
                if (ui.thinking) "анализ…" else if (ui.depth > 0) "глубина ${ui.depth}" else "",
                color = Color(0xFF9E9EA8), fontSize = 13.sp
            )
        }
        Text(ui.status, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)

        BoardView(ui, buildArrows(ui), vm::onSquareClick)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.newGame() }, modifier = Modifier.weight(1f)) { Text("Новая") }
            OutlinedButton(onClick = vm::undo, modifier = Modifier.weight(1f)) { Text("Отмена") }
            OutlinedButton(onClick = vm::flip, modifier = Modifier.weight(1f)) { Text("Доска") }
        }

        if (ui.hintsOn && !ui.gameOver) HintCard(ui)
        ui.report?.let { ReportCard(it) }
        ui.profile?.let { ProfileCard(it) }
        if (ui.moveText.isNotEmpty()) {
            InfoCard("Ходы") { Text(ui.moveText, color = Color(0xFFD0D0D8), fontSize = 14.sp) }
        }
        SettingsCard(ui, vm)
    }

    if (ui.promotion != null) {
        AlertDialog(
            onDismissRequest = vm::cancelPromotion,
            title = { Text("Превращение пешки") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.choosePromotion(QUEEN) }) { Text("♛ Ферзь") }
                    TextButton(onClick = { vm.choosePromotion(ROOK) }) { Text("♜ Ладья") }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { vm.choosePromotion(BISHOP) }) { Text("♝ Слон") }
                    TextButton(onClick = { vm.choosePromotion(KNIGHT) }) { Text("♞ Конь") }
                }
            },
            dismissButton = { TextButton(onClick = vm::cancelPromotion) { Text("Отмена") } }
        )
    }
    ui.message?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissMessage,
            confirmButton = { TextButton(onClick = vm::dismissMessage) { Text("OK") } },
            text = { Text(msg) }
        )
    }
}

// ------------------------------------------------------------------ доска

private fun buildArrows(ui: UiState): List<Arrow> {
    val out = ArrayList<Arrow>()
    if (!ui.hintsOn || ui.gameOver) return out
    if (ui.hintsForUser) {
        for (i in ui.hints.indices.reversed()) {
            val h = ui.hints[i]
            out.add(Arrow(h.move.mFrom, h.move.mTo, if (i == 0) Green else Yellow, if (i == 0) 0.95f else 0.5f))
        }
        ui.threat?.let { out.add(Arrow(it.move.mFrom, it.move.mTo, Red, 0.85f)) }
    } else {
        for (i in ui.hints.indices.reversed()) {
            val h = ui.hints[i]
            out.add(Arrow(h.move.mFrom, h.move.mTo, Red, when (i) { 0 -> 0.9f; 1 -> 0.6f; else -> 0.4f }))
        }
    }
    return out
}

@Composable
private fun BoardView(ui: UiState, arrows: List<Arrow>, onSquare: (Int) -> Unit) {
    val board = ui.position.board
    val checkSq = if (ui.position.inCheck(ui.position.whiteToMove))
        (if (ui.position.whiteToMove) ui.position.whiteKing else ui.position.blackKing) else -1

    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.small)) {
        val cell = maxWidth / 8
        val glyphSize = with(LocalDensity.current) { (cell * 0.78f).toSp() }
        val labelSize = with(LocalDensity.current) { (cell * 0.22f).toSp() }

        Column(Modifier.fillMaxSize()) {
            for (row in 0..7) {
                Row {
                    for (col in 0..7) {
                        val f = if (ui.flipped) 7 - col else col
                        val r = if (ui.flipped) row else 7 - row
                        val sq = r * 8 + f
                        val light = (f + r) % 2 == 1
                        var bg = if (light) LightSq else DarkSq
                        val lm = ui.lastMove
                        if (lm != 0 && (sq == lm.mFrom || sq == lm.mTo)) bg = Color(0xFFCDD26A)
                        if (sq == ui.selected) bg = Color(0xFF7FC97F)
                        if (sq == checkSq) bg = Color(0xFFE57373)

                        Box(
                            Modifier.size(cell).background(bg).clickable { onSquare(sq) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (col == 0) Text(
                                "${r + 1}", fontSize = labelSize, color = if (light) DarkSq else LightSq,
                                modifier = Modifier.align(Alignment.TopStart).padding(start = 2.dp)
                            )
                            if (row == 7) Text(
                                "${'a' + f}", fontSize = labelSize, color = if (light) DarkSq else LightSq,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 2.dp)
                            )
                            val p = board[sq]
                            if (p != 0) PieceGlyph(p, glyphSize)
                            if (sq in ui.targets) {
                                Box(
                                    Modifier
                                        .size(if (p == 0) cell * 0.3f else cell * 0.9f)
                                        .clip(CircleShape)
                                        .background(Color(0x5533AA33))
                                )
                            }
                        }
                    }
                }
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            val c = size.width / 8f
            fun center(sq: Int): Offset {
                val f = sq and 7
                val r = sq shr 3
                val x = if (ui.flipped) 7 - f else f
                val y = if (ui.flipped) r else 7 - r
                return Offset((x + 0.5f) * c, (y + 0.5f) * c)
            }
            for (a in arrows) drawArrow(center(a.from), center(a.to), a.color.copy(alpha = a.alpha), c * 0.16f)
        }
    }
}

private fun DrawScope.drawArrow(from: Offset, to: Offset, color: Color, strokeW: Float) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val len = sqrt(dx * dx + dy * dy)
    if (len < 1f) return
    val ux = dx / len
    val uy = dy / len
    val head = strokeW * 2.6f
    val lineEnd = Offset(to.x - ux * head * 0.8f, to.y - uy * head * 0.8f)
    drawLine(color, from, lineEnd, strokeWidth = strokeW, cap = StrokeCap.Round)
    val px = -uy
    val py = ux
    val path = Path().apply {
        moveTo(to.x, to.y)
        lineTo(to.x - ux * head + px * head * 0.6f, to.y - uy * head + py * head * 0.6f)
        lineTo(to.x - ux * head - px * head * 0.6f, to.y - uy * head - py * head * 0.6f)
        close()
    }
    drawPath(path, color)
}

@Composable
private fun PieceGlyph(piece: Int, size: TextUnit) {
    val glyph = when (abs(piece)) {
        KING -> "♚"
        QUEEN -> "♛"
        ROOK -> "♜"
        BISHOP -> "♝"
        KNIGHT -> "♞"
        else -> "♟"
    } + "︎"
    val white = piece > 0
    Text(
        glyph,
        fontSize = size,
        color = if (white) Color.White else Color(0xFF111111),
        style = TextStyle(
            shadow = if (white) Shadow(Color.Black, Offset(0f, 0f), 7f) else null
        )
    )
}

// ------------------------------------------------------------------ карточки

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardBg), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Color(0xFF9E9EA8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun HintCard(ui: UiState) {
    val title = if (ui.hintsForUser) "Подсказка: куда ходить" else "Куда пойдёт соперник"
    InfoCard(title) {
        if (ui.hints.isEmpty()) {
            Text(if (ui.thinking) "Считаю…" else "—", color = Color(0xFFD0D0D8))
            return@InfoCard
        }
        val best = ui.hints[0]
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                best.san, color = if (ui.hintsForUser) Green else Red,
                fontSize = 28.sp, fontWeight = FontWeight.Bold
            )
            Text("   ${best.eval}", color = Color.White, fontSize = 18.sp)
        }
        if (ui.hintsForUser) {
            best.reply?.let { Text("Ожидаемый ответ соперника: $it", color = Color(0xFFD0D0D8), fontSize = 14.sp) }
        }
        Text(best.line, color = Color(0xFF9E9EA8), fontSize = 13.sp)
        ui.threat?.let { Text(it.text, color = Red, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        if (ui.hints.size > 1) {
            Text(
                "Другие варианты: " + ui.hints.drop(1).joinToString("   ") { "${it.san} (${it.eval})" },
                color = Yellow.copy(alpha = 0.9f), fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun ReportCard(r: com.helpchess.MoveReport) {
    InfoCard(if (r.byOpponent) "Последний ход соперника" else "Ваш последний ход") {
        Text(
            r.title, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            color = when {
                r.lossCp <= 40 -> Green
                r.lossCp <= 90 -> Yellow
                else -> Red
            }
        )
        r.bestSan?.let { Text("Сильнее было: $it", color = Color(0xFFD0D0D8), fontSize = 14.sp) }
    }
}

@Composable
private fun ProfileCard(p: com.helpchess.OppProfile) {
    InfoCard("Профиль соперника") {
        Text(p.style.replaceFirstChar { it.uppercase() }, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(
            "Ходов: ${p.moves} · средняя потеря ${"%.2f".format(p.avgLossCp / 100.0)} · " +
                "зевки ${p.blunders} · ошибки ${p.mistakes} · неточности ${p.inaccuracies}",
            color = Color(0xFF9E9EA8), fontSize = 13.sp
        )
        Text(p.advice, color = Color(0xFFD0D0D8), fontSize = 14.sp)
    }
}

@Composable
private fun SettingsCard(ui: UiState, vm: GameViewModel) {
    var fen by remember { mutableStateOf("") }
    InfoCard("Настройки") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Подсказки", color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = ui.hintsOn, onCheckedChange = vm::setHintsOn)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Играть против встроенного бота", color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = ui.autoBot, onCheckedChange = vm::setAutoBot)
        }
        if (ui.autoBot) {
            Text("Уровень бота: ${ui.botLevel}", color = Color(0xFFD0D0D8), fontSize = 13.sp)
            Slider(
                value = ui.botLevel.toFloat(),
                onValueChange = { vm.setBotLevel(it.toInt()) },
                valueRange = 1f..5f,
                steps = 3
            )
        } else {
            Text(
                "Режим зеркала: повторяйте ходы партии на этой доске — за себя и за соперника.",
                color = Color(0xFF9E9EA8), fontSize = 12.sp
            )
        }
        Text("Я играю", color = Color(0xFFD0D0D8), fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = ui.userIsWhite, onClick = { vm.setUserWhite(true) }, label = { Text("Белыми") })
            FilterChip(selected = !ui.userIsWhite, onClick = { vm.setUserWhite(false) }, label = { Text("Чёрными") })
        }
        Text("Скорость анализа", color = Color(0xFFD0D0D8), fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(300L to "0.3 с", 800L to "0.8 с", 1500L to "1.5 с", 3000L to "3 с").forEach { (ms, label) ->
                FilterChip(selected = ui.timeMs == ms, onClick = { vm.setTime(ms) }, label = { Text(label) })
            }
        }
        OutlinedTextField(
            value = fen,
            onValueChange = { fen = it },
            label = { Text("Позиция в формате FEN") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedButton(onClick = { vm.loadFen(fen) }, enabled = fen.isNotBlank()) { Text("Загрузить позицию") }
    }
}
