package com.wmqc.miroot.rear.truthdare

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wmqc.miroot.R
import com.wmqc.miroot.car.VibrationHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

private enum class PlayWheelKind {
    PLAYER,
    TYPE,
    CHOICE,
}

private const val WHEEL_RESULT_HOLD_MS = 1000L
private val PLAYER_HEADER_BOTTOM_GAP = 6.dp

@Composable
private fun RearTruthDarePlayerHeader(
    name: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 24.dp)
                .padding(bottom = PLAYER_HEADER_BOTTOM_GAP),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name,
            color = Color(0xFFF1F5F9),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun TruthDareRearGameScreen(safeStartPx: Float) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val contentStartDp = with(density) { safeStartPx.toDp() }
    val setupExtraStartDp = with(density) { 5.toDp() }
    val resultEndDp = with(density) { (10f / this.density).dp }
    val playContentTopPad = with(density) { (10f / this.density).dp }
    val pagePadding =
        Modifier.padding(start = contentStartDp, end = 6.dp, top = 4.dp, bottom = 6.dp)
    val setupPadding = pagePadding.then(Modifier.padding(start = setupExtraStartDp))
    val resultPadding =
        Modifier.padding(start = contentStartDp, top = playContentTopPad, bottom = 6.dp)

    var config by remember { mutableStateOf(TruthDarePrefs.readConfig(context)) }
    var screen by remember { mutableStateOf(TruthDareRearScreen.SETUP) }
    var spinning by remember { mutableStateOf(false) }
    var roundRobinIndex by remember { mutableIntStateOf(0) }
    var currentPlayer by remember { mutableStateOf("") }
    var usedTruthIds by remember { mutableStateOf(setOf<String>()) }
    var usedDareIds by remember { mutableStateOf(setOf<String>()) }
    var roundResult by remember { mutableStateOf<TruthDareRoundResult?>(null) }
    var playWheelKind by remember { mutableStateOf(PlayWheelKind.PLAYER) }

    val playerRotation = remember { Animatable(0f) }
    val typeRotation = remember { Animatable(0f) }
    val questionSpeaker = remember { TruthDareQuestionSpeaker(context) }

    DisposableEffect(Unit) {
        onDispose { questionSpeaker.shutdown() }
    }

    fun persistConfig(newConfig: TruthDareConfig) {
        config = newConfig
        TruthDarePrefs.writeConfig(context, newConfig)
    }

    fun prepareRoundPlayer() {
        val players = config.players
        if (players.isEmpty()) return
        when (config.playerPickMode) {
            TruthDarePlayerPickMode.ROUND_ROBIN -> {
                val index = roundRobinIndex % players.size
                currentPlayer = players[index]
                roundRobinIndex++
            }
            TruthDarePlayerPickMode.RANDOM -> {
                currentPlayer = players[Random.nextInt(players.size)]
            }
            TruthDarePlayerPickMode.WHEEL -> {
                currentPlayer = ""
            }
        }
    }

    fun initialPlayWheelKind(): PlayWheelKind =
        when {
            config.playerPickMode == TruthDarePlayerPickMode.WHEEL -> PlayWheelKind.PLAYER
            config.challengeMode == TruthDareChallengeMode.WHEEL -> PlayWheelKind.TYPE
            else -> PlayWheelKind.CHOICE
        }

    fun pickQuestion(type: TruthDareType): TruthDareQuestion {
        val question =
            if (type == TruthDareType.TRUTH) {
                TruthDareBank.pickTruth(context, config.difficulty, config.theme, usedTruthIds)
            } else {
                TruthDareBank.pickDare(context, config.difficulty, config.theme, usedDareIds)
            }
        if (type == TruthDareType.TRUTH) {
            usedTruthIds = usedTruthIds + question.id
        } else {
            usedDareIds = usedDareIds + question.id
        }
        return question
    }

    suspend fun spinPlayerWheel(): String {
        val players = config.players
        val targetIndex = Random.nextInt(players.size)
        val target =
            playerWheelSpinTargetRotation(
                currentRotation = playerRotation.value,
                targetIndex = targetIndex,
                playerCount = players.size,
            )
        playerRotation.animateTo(target, tween(2800, easing = FastOutSlowInEasing))
        playerRotation.snapTo(target)
        val landedIndex = segmentAtPlayerPointer(playerRotation.value, players.size)
        currentPlayer = players[landedIndex]
        VibrationHelper.vibrateOneShot(context, 45L, "真心话大冒险选人")
        delay(WHEEL_RESULT_HOLD_MS)
        return currentPlayer
    }

    suspend fun spinTypeWheel(): TruthDareType {
        playWheelKind = PlayWheelKind.TYPE
        val extra = (4 + Random.nextInt(2)) * 360f
        val stop = Random.nextFloat() * 360f
        val target = typeRotation.value + extra + stop
        typeRotation.animateTo(target, tween(2800, easing = FastOutSlowInEasing))
        val segment = segmentAtTypePointer(typeRotation.value)
        val type = if (segment % 2 == 0) TruthDareType.TRUTH else TruthDareType.DARE
        VibrationHelper.vibrateOneShot(context, 45L, "真心话大冒险")
        delay(WHEEL_RESULT_HOLD_MS)
        return type
    }

    fun showResult(type: TruthDareType) {
        val question = pickQuestion(type)
        roundResult = TruthDareRoundResult(currentPlayer, type, question)
        screen = TruthDareRearScreen.RESULT
        VibrationHelper.vibrateOneShot(context, 40L, "真心话大冒险")
    }

    fun onTypeChosen(type: TruthDareType) {
        if (spinning || config.players.size < 2) return
        spinning = true
        scope.launch {
            try {
                showResult(type)
            } finally {
                spinning = false
            }
        }
    }

    fun startGame() {
        config = TruthDarePrefs.readConfig(context)
        usedTruthIds = emptySet()
        usedDareIds = emptySet()
        roundRobinIndex = 0
        roundResult = null
        playWheelKind = initialPlayWheelKind()
        prepareRoundPlayer()
        screen = TruthDareRearScreen.PLAYING
    }

    fun runRound() {
        if (spinning || config.players.size < 2) return
        if (playWheelKind == PlayWheelKind.CHOICE) return

        spinning = true
        scope.launch {
            try {
                if (config.playerPickMode == TruthDarePlayerPickMode.WHEEL && playWheelKind == PlayWheelKind.PLAYER) {
                    spinPlayerWheel()
                    playWheelKind =
                        if (config.challengeMode == TruthDareChallengeMode.WHEEL) {
                            PlayWheelKind.TYPE
                        } else {
                            PlayWheelKind.CHOICE
                        }
                    if (playWheelKind == PlayWheelKind.CHOICE) return@launch
                }

                if (config.challengeMode == TruthDareChallengeMode.WHEEL && playWheelKind == PlayWheelKind.TYPE) {
                    val type = spinTypeWheel()
                    showResult(type)
                }
            } finally {
                spinning = false
            }
        }
    }

    fun nextRound() {
        questionSpeaker.stop()
        roundResult = null
        playWheelKind = initialPlayWheelKind()
        prepareRoundPlayer()
        screen = TruthDareRearScreen.PLAYING
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF0F172A), Color(0xFF020617)))),
    ) {
        when (screen) {
            TruthDareRearScreen.SETUP ->
                SetupScreen(
                    modifier = setupPadding,
                    config = config,
                    onPlayerCountChange = { count ->
                        persistConfig(config.copy(players = TruthDarePrefs.defaultPlayers(count)))
                        roundRobinIndex = 0
                    },
                    onPlayerPickMode = { mode ->
                        persistConfig(config.copy(playerPickMode = mode))
                        roundRobinIndex = 0
                    },
                    onChallengeMode = { mode ->
                        persistConfig(config.copy(challengeMode = mode))
                    },
                    onDifficulty = { difficulty ->
                        persistConfig(config.copy(difficulty = difficulty))
                    },
                    onTheme = { theme ->
                        persistConfig(config.copy(theme = theme))
                    },
                    onStart = { startGame() },
                )
            TruthDareRearScreen.PLAYING ->
                PlayScreen(
                    safeStartPx = safeStartPx,
                    contentTopPad = playContentTopPad,
                    config = config,
                    currentPlayer = currentPlayer,
                    playWheelKind = playWheelKind,
                    playerRotation = playerRotation.value,
                    typeRotation = typeRotation.value,
                    spinning = spinning,
                    onTap = { runRound() },
                    onTypeChosen = { onTypeChosen(it) },
                )
            TruthDareRearScreen.RESULT ->
                roundResult?.let { result ->
                    ResultScreen(
                        modifier = resultPadding,
                        contentEndDp = resultEndDp,
                        config = config,
                        result = result,
                        onTap = { nextRound() },
                        onSpeakRound = { player, question ->
                            questionSpeaker.speakRound(player, question)
                        },
                        onStopSpeak = { questionSpeaker.stop() },
                    )
                }
        }
    }
}

@Composable
private fun SetupScreen(
    modifier: Modifier,
    config: TruthDareConfig,
    onPlayerCountChange: (Int) -> Unit,
    onPlayerPickMode: (TruthDarePlayerPickMode) -> Unit,
    onChallengeMode: (TruthDareChallengeMode) -> Unit,
    onDifficulty: (TruthDareDifficulty) -> Unit,
    onTheme: (TruthDareTheme) -> Unit,
    onStart: () -> Unit,
) {
    val scroll = rememberScrollState()
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.rear_truth_dare_setup_title),
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            SetupLabeledRow(
                label = "人数",
                modifier = Modifier.wrapContentWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CompactIconButton("−") {
                        if (config.players.size > 2) onPlayerCountChange(config.players.size - 1)
                    }
                    Text(
                        text = "${config.players.size}",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(18.dp),
                        textAlign = TextAlign.Center,
                    )
                    CompactIconButton("+") {
                        if (config.players.size < 10) onPlayerCountChange(config.players.size + 1)
                    }
                }
            }
            SetupLabeledRow(
                label = stringResource(R.string.rear_truth_dare_difficulty),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                AdaptiveDifficultyChips(
                    selected = config.difficulty,
                    onDifficulty = onDifficulty,
                )
            }
        }
        SetupLabeledRow(
            label = stringResource(R.string.rear_truth_dare_theme),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeChip(TruthDareTheme.MIXED.displayLabel(), config.theme == TruthDareTheme.MIXED, compact = true) {
                    onTheme(TruthDareTheme.MIXED)
                }
                TruthDareTheme.bankThemes.forEach { theme ->
                    ModeChip(theme.displayLabel(), config.theme == theme, compact = true) { onTheme(theme) }
                }
            }
        }
        SetupDualRow(
            leftLabel = stringResource(R.string.rear_truth_dare_pick_player_mode),
            rightLabel = stringResource(R.string.rear_truth_dare_challenge_mode),
            left = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ModeChip("转盘", config.playerPickMode == TruthDarePlayerPickMode.WHEEL, compact = true) {
                        onPlayerPickMode(TruthDarePlayerPickMode.WHEEL)
                    }
                    ModeChip("轮询", config.playerPickMode == TruthDarePlayerPickMode.ROUND_ROBIN, compact = true) {
                        onPlayerPickMode(TruthDarePlayerPickMode.ROUND_ROBIN)
                    }
                    ModeChip("随机", config.playerPickMode == TruthDarePlayerPickMode.RANDOM, compact = true) {
                        onPlayerPickMode(TruthDarePlayerPickMode.RANDOM)
                    }
                }
            },
            right = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ModeChip("转盘", config.challengeMode == TruthDareChallengeMode.WHEEL, compact = true) {
                        onChallengeMode(TruthDareChallengeMode.WHEEL)
                    }
                    ModeChip("自选", config.challengeMode == TruthDareChallengeMode.PLAYER_CHOICE, compact = true) {
                        onChallengeMode(TruthDareChallengeMode.PLAYER_CHOICE)
                    }
                }
            },
        )
        Spacer(Modifier.height(4.dp))
        val setupButtonHorizontalDp = with(LocalDensity.current) { 20.toDp() }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = setupButtonHorizontalDp)
                    .background(Color(0xFF3B82F6), RoundedCornerShape(10.dp))
                    .clickable(onClick = onStart)
                    .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.rear_truth_dare_start_game),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun AdaptiveDifficultyChips(
    selected: TruthDareDifficulty,
    onDifficulty: (TruthDareDifficulty) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items =
        listOf(
            "混合" to TruthDareDifficulty.MIXED,
            "轻松" to TruthDareDifficulty.EASY,
            "标准" to TruthDareDifficulty.STANDARD,
            "挑战" to TruthDareDifficulty.HARD,
        )
    val spacing = 4.dp
    Layout(
        modifier = modifier,
        content = {
            items.forEach { (label, value) ->
                ModeChip(label, selected == value, compact = true) { onDifficulty(value) }
            }
        },
    ) { measurables, constraints ->
        val spacingPx = spacing.roundToPx()
        val placeables = measurables.map { measurable -> measurable.measure(Constraints()) }
        val singleRowWidth =
            placeables.sumOf { it.width } + spacingPx * (placeables.size - 1).coerceAtLeast(0)
        if (singleRowWidth <= constraints.maxWidth) {
            val height = placeables.maxOf { it.height }
            layout(constraints.maxWidth.coerceAtLeast(singleRowWidth), height) {
                var x = 0
                placeables.forEach { placeable ->
                    placeable.place(x, 0)
                    x += placeable.width + spacingPx
                }
            }
        } else {
            val splitAt = (placeables.size + 1) / 2
            val firstRow = placeables.take(splitAt)
            val secondRow = placeables.drop(splitAt)
            fun rowWidth(row: List<Placeable>) =
                row.sumOf { it.width } + spacingPx * (row.size - 1).coerceAtLeast(0)
            val firstHeight = firstRow.maxOf { it.height }
            val secondHeight = secondRow.maxOfOrNull { it.height } ?: 0
            val width = maxOf(rowWidth(firstRow), rowWidth(secondRow))
            val height =
                if (secondRow.isEmpty()) {
                    firstHeight
                } else {
                    firstHeight + spacingPx + secondHeight
                }
            layout(width, height) {
                var x = 0
                firstRow.forEach { placeable ->
                    placeable.place(x, 0)
                    x += placeable.width + spacingPx
                }
                x = 0
                secondRow.forEach { placeable ->
                    placeable.place(x, firstHeight + spacingPx)
                    x += placeable.width + spacingPx
                }
            }
        }
    }
}

@Composable
private fun SetupLabeledRow(
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(label, color = Color(0x99FFFFFF), fontSize = 10.sp)
        content()
    }
}

@Composable
private fun SetupDualRow(
    leftLabel: String,
    rightLabel: String,
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SetupLabeledRow(leftLabel, Modifier.weight(1f), left)
        SetupLabeledRow(rightLabel, Modifier.weight(1f), right)
    }
}

@Composable
private fun PlayScreen(
    safeStartPx: Float,
    contentTopPad: Dp,
    config: TruthDareConfig,
    currentPlayer: String,
    playWheelKind: PlayWheelKind,
    playerRotation: Float,
    typeRotation: Float,
    spinning: Boolean,
    onTap: () -> Unit,
    onTypeChosen: (TruthDareType) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val contentStartDp = with(density) { safeStartPx.toDp() }
    val showPlayerHeader = config.playerPickMode != TruthDarePlayerPickMode.ROUND_ROBIN
    val headerPlayerName =
        when {
            !showPlayerHeader -> null
            playWheelKind == PlayWheelKind.PLAYER && config.players.isNotEmpty() ->
                config.players[segmentAtPlayerPointer(playerRotation, config.players.size)]
            currentPlayer.isNotEmpty() -> currentPlayer
            else -> null
        }
    val hintText =
        if (spinning) {
            "…"
        } else {
            when (playWheelKind) {
                PlayWheelKind.CHOICE -> context.getString(R.string.rear_truth_dare_pick_type_hint)
                PlayWheelKind.PLAYER -> context.getString(R.string.rear_truth_dare_tap_spin_player)
                PlayWheelKind.TYPE -> context.getString(R.string.rear_truth_dare_tap_spin_type)
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(start = contentStartDp, top = contentTopPad)
                .then(
                    if (playWheelKind != PlayWheelKind.CHOICE) {
                        Modifier.pointerInput(spinning, playWheelKind) {
                            detectTapGestures {
                                if (!spinning) onTap()
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (headerPlayerName != null) {
            RearTruthDarePlayerHeader(name = headerPlayerName)
        }
        if (playWheelKind == PlayWheelKind.CHOICE) {
            TypeChoicePanel(
                modifier = Modifier.weight(1f).fillMaxWidth().fillMaxHeight(),
                enabled = !spinning,
                onTruth = { onTypeChosen(TruthDareType.TRUTH) },
                onDare = { onTypeChosen(TruthDareType.DARE) },
            )
        } else {
            BoxWithConstraints(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                val wheelSize = minOf(maxWidth, maxHeight)
                LiveWheelBox(wheelSize) {
                    when (playWheelKind) {
                        PlayWheelKind.PLAYER -> {
                            rotate(playerRotation) {
                                drawPlayerWheelDisc(
                                    size.width / 2f,
                                    size.height / 2f,
                                    discRadius(size),
                                    config.players,
                                )
                            }
                        }
                        PlayWheelKind.TYPE -> {
                            rotate(typeRotation) {
                                drawTypeWheelDisc(size.width / 2f, size.height / 2f, discRadius(size))
                            }
                        }
                        PlayWheelKind.CHOICE -> Unit
                    }
                    drawWheelPointer(size.width / 2f, size.height / 2f, discRadius(size))
                }
            }
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = hintText,
                color = Color(0x88CBD5E1),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TypeChoicePanel(
    modifier: Modifier,
    enabled: Boolean,
    onTruth: () -> Unit,
    onDare: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ChallengeTypeButton(
            label = context.getString(R.string.rear_truth_dare_type_truth),
            gradient = listOf(TruthColor, TruthColorDark),
            enabled = enabled,
            onClick = onTruth,
            modifier = Modifier.weight(1f).fillMaxWidth().fillMaxHeight(),
        )
        ChallengeTypeButton(
            label = context.getString(R.string.rear_truth_dare_type_dare),
            gradient = listOf(DareColor, DareColorDark),
            enabled = enabled,
            onClick = onDare,
            modifier = Modifier.weight(1f).fillMaxWidth().fillMaxHeight(),
        )
    }
}

@Composable
private fun ChallengeTypeButton(
    label: String,
    gradient: List<Color>,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .background(
                    brush = Brush.verticalGradient(gradient),
                    shape = RoundedCornerShape(16.dp),
                )
                .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
    }
}

@Composable
private fun ResultMetaLine(
    type: TruthDareType,
    config: TruthDareConfig,
    question: TruthDareQuestion,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val typeLabel =
        if (type == TruthDareType.TRUTH) {
            context.getString(R.string.rear_truth_dare_type_truth)
        } else {
            context.getString(R.string.rear_truth_dare_type_dare)
        }
    val typeColor = if (type == TruthDareType.TRUTH) TruthColor else DareColor
    val difficultyLabel =
        if (config.difficulty == TruthDareDifficulty.MIXED) {
            difficultyLevelToTier(question.difficulty).displayLabel()
        } else {
            config.difficulty.displayLabel()
        }
    val themeLabel =
        if (config.theme == TruthDareTheme.MIXED) {
            categoryKeyToTheme(question.category).displayLabel()
        } else {
            config.theme.displayLabel()
        }
    val dotColor = Color(0x66FFFFFF)
    val metaColor = Color(0x99FFFFFF)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(typeLabel, color = typeColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(" · ", color = dotColor, fontSize = 14.sp)
        Text(difficultyLabel, color = metaColor, fontSize = 14.sp)
        Text(" · ", color = dotColor, fontSize = 14.sp)
        Text(themeLabel, color = metaColor, fontSize = 14.sp)
    }
}

@Composable
private fun ResultScreen(
    modifier: Modifier,
    contentEndDp: Dp,
    config: TruthDareConfig,
    result: TruthDareRoundResult,
    onTap: () -> Unit,
    onSpeakRound: (playerName: String?, question: String) -> Unit,
    onStopSpeak: () -> Unit,
) {
    val context = LocalContext.current
    val scroll = rememberScrollState()
    val showPlayerName = config.playerPickMode != TruthDarePlayerPickMode.ROUND_ROBIN
    val speakPlayerName =
        if (showPlayerName && result.playerName.isNotEmpty()) {
            result.playerName
        } else {
            null
        }

    LaunchedEffect(result.question.id, speakPlayerName, config.speakQuestions) {
        if (config.speakQuestions) {
            onSpeakRound(speakPlayerName, result.question.content)
        } else {
            onStopSpeak()
        }
    }

    DisposableEffect(Unit) {
        onDispose { onStopSpeak() }
    }

    val endPad = Modifier.padding(end = contentEndDp)

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { onTap() }
                },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showPlayerName && result.playerName.isNotEmpty()) {
            RearTruthDarePlayerHeader(name = result.playerName)
        }
        ResultMetaLine(
            type = result.type,
            config = config,
            question = result.question,
            modifier = Modifier.fillMaxWidth().then(endPad),
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
        ) {
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color(0xFF1E293B), RoundedCornerShape(12.dp))
                        .verticalScroll(scroll)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = result.question.content,
                    color = Color(0xFFF1F5F9),
                    fontSize = 17.sp,
                    lineHeight = 26.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(contentEndDp))
        }
        Text(
            text = context.getString(R.string.rear_truth_dare_next_round),
            color = Color(0x88CBD5E1),
            fontSize = 10.sp,
            modifier = endPad.padding(top = 4.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CompactIconButton(label: String, onClick: () -> Unit) {
    Box(
        modifier =
            Modifier
                .background(Color(0xFF1E293B), RoundedCornerShape(6.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val hPad = if (compact) 6.dp else 8.dp
    val vPad = if (compact) 2.dp else 3.dp
    val fontSize = if (compact) 9.sp else 10.sp
    Box(
        modifier =
            Modifier
                .background(
                    if (selected) Color(0xFF3B82F6) else Color(0xFF1E293B),
                    RoundedCornerShape(6.dp),
                )
                .clickable(onClick = onClick)
                .padding(horizontal = hPad, vertical = vPad),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = fontSize,
            color = if (selected) Color.White else Color(0xCCFFFFFF),
        )
    }
}

@Composable
private fun LiveWheelBox(
    wheelSize: androidx.compose.ui.unit.Dp,
    draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit,
) {
    Box(modifier = Modifier.size(wheelSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize(), onDraw = draw)
        Text("转", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}
