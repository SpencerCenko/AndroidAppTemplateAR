package com.ifpr.androidapptemplatear.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Todos os números de equilíbrio do jogo ficam aqui. */
object BattleConfig {
    const val PLAYER_MAX_HP = 100
    const val PLAYER_ATTACK = 10
    const val PLAYER_DEFENSE = 6
    const val PLAYER_SPEED = 10

    const val FLASH_POWER = 14
    const val CRIT_CHANCE = 0.10f
    const val STUN_CHANCE = 0.20f

    const val HEAL_FRACTION = 0.35f
    const val START_TRAPS = 3
    const val START_POTIONS = 2

    const val MAX_LOG_LINES = 40
}

enum class BattlePhase {
    NOT_STARTED,
    PLAYER_TURN,
    GHOST_TURN,

    /** Fantasma capturado com a armadilha. */
    CAPTURED,

    /** Vida do fantasma chegou a zero: vitória, mas sem captura. */
    EXORCISED,

    DEFEATED,
    FLED
}

enum class PlayerAction {
    /** Ataca com a lanterna. */
    FLASH,

    /** Reduz à metade o próximo golpe do fantasma. */
    DEFEND,

    /** Usa uma poção. */
    HEAL,

    /** Joga uma armadilha: quanto mais fraco o fantasma, maior a chance. */
    CAPTURE,

    FLEE
}

data class GhostMove(
    val name: String,
    val power: Int,
    /** Fração do dano causado que o fantasma recupera de vida (0f a 1f). */
    val lifeSteal: Float = 0f,
    /** Chance de assustar o jogador (o próximo ataque dele causa metade do dano). */
    val scareChance: Float = 0f
)

data class Ghost(
    val id: String,
    val name: String,
    /** Nome do arquivo .glb em assets, o mesmo usado pelo CameraFragment. */
    val modelFile: String,
    val maxHp: Int,
    val attack: Int,
    val defense: Int,
    val speed: Int,
    val moves: List<GhostMove>
)

object GhostCatalog {

    val all: List<Ghost> = listOf(
        Ghost(
            id = "ghost",
            name = "Fantasma Clássico",
            modelFile = "ghost.glb",
            maxHp = 60,
            attack = 8,
            defense = 4,
            speed = 8,
            moves = listOf(
                GhostMove("Assombrar", power = 10),
                GhostMove("Arrepio gelado", power = 8, scareChance = 0.30f)
            )
        ),
        Ghost(
            id = "stylized_ghost",
            name = "Espectro Estilizado",
            modelFile = "stylized_ghost.glb",
            maxHp = 80,
            attack = 10,
            defense = 5,
            speed = 12,
            moves = listOf(
                GhostMove("Assombrar", power = 10),
                GhostMove("Sussurro sombrio", power = 9, scareChance = 0.40f)
            )
        ),
        Ghost(
            id = "big_boo",
            name = "Boo Gigante",
            modelFile = "big_boo_super_mario_world_custom.glb",
            maxHp = 120,
            attack = 12,
            defense = 8,
            speed = 6,
            moves = listOf(
                GhostMove("Língua gigante", power = 14),
                GhostMove("Sopro gelado", power = 8, lifeSteal = 0.50f)
            )
        ),
        Ghost(
            id = "spider_doll",
            name = "Boneca Aranha",
            modelFile = "spider_doll.glb",
            maxHp = 90,
            attack = 13,
            defense = 6,
            speed = 14,
            moves = listOf(
                GhostMove("Picada", power = 12),
                GhostMove("Teia de pavor", power = 6, scareChance = 0.50f)
            )
        ),
        Ghost(
            id = "monster_psx",
            name = "Monstro PSX",
            modelFile = "monsterpsx_character.glb",
            maxHp = 150,
            attack = 15,
            defense = 10,
            speed = 9,
            moves = listOf(
                GhostMove("Garra", power = 16),
                GhostMove("Drenar alma", power = 10, lifeSteal = 0.60f)
            )
        )
    )

    /** Devolve o fantasma que usa esse modelo 3D, ou null se não houver. */
    fun byModelFile(modelFile: String?): Ghost? =
        all.firstOrNull { it.modelFile == modelFile }

    fun random(random: Random = Random.Default): Ghost = all.random(random)
}

/** Tudo o que a tela precisa para desenhar a batalha. Imutável: cada ação gera um novo estado. */
data class BattleState(
    val phase: BattlePhase = BattlePhase.NOT_STARTED,
    val ghost: Ghost? = null,
    val ghostHp: Int = 0,
    val playerHp: Int = BattleConfig.PLAYER_MAX_HP,
    val playerMaxHp: Int = BattleConfig.PLAYER_MAX_HP,
    val traps: Int = 0,
    val potions: Int = 0,
    val ghostStunned: Boolean = false,
    val playerDefending: Boolean = false,
    val playerFeared: Boolean = false,
    val log: List<String> = emptyList()
) {
    val isFinished: Boolean
        get() = phase == BattlePhase.CAPTURED ||
                phase == BattlePhase.EXORCISED ||
                phase == BattlePhase.DEFEATED ||
                phase == BattlePhase.FLED

    fun addLog(vararg lines: String): BattleState =
        copy(log = (log + lines).takeLast(BattleConfig.MAX_LOG_LINES))
}

/**
 * Batalha por turnos contra um fantasma.
 *
 * Fluxo:
 *  1. startBattle(...) ou startBattleForModel(...)
 *  2. Fase PLAYER_TURN: a tela chama playerAct(ação)
 *  3. Fase GHOST_TURN: a tela chama ghostAct(), de preferência depois de uma
 *     pequena pausa para animação (ex.: 800 ms)
 *  4. Repete até state.isFinished
 *
 * Vida, poções e armadilhas são reiniciadas a cada batalha.
 * Chame os métodos na thread principal.
 */
class BattleViewModel : ViewModel() {

    private val random = Random.Default

    private val _state = MutableLiveData(BattleState())
    val state: LiveData<BattleState> = _state

    private val _captured = MutableLiveData<List<Ghost>>(emptyList())

    /** Fantasmas capturados enquanto o app estiver aberto. */
    val captured: LiveData<List<Ghost>> = _captured

    private fun current(): BattleState = _state.value ?: BattleState()

    // ---------------------------------------------------------------- início

    fun startBattle(ghost: Ghost) {
        val ghostFirst = ghost.speed > BattleConfig.PLAYER_SPEED

        var s = BattleState(
            phase = if (ghostFirst) BattlePhase.GHOST_TURN else BattlePhase.PLAYER_TURN,
            ghost = ghost,
            ghostHp = ghost.maxHp,
            traps = BattleConfig.START_TRAPS,
            potions = BattleConfig.START_POTIONS
        ).addLog("${ghost.name} apareceu!")

        if (ghostFirst) {
            s = s.addLog("${ghost.name} é mais rápido e age primeiro.")
        }

        _state.value = s
    }

    /** Usa o fantasma ligado ao modelo 3D; se não houver, sorteia um. */
    fun startBattleForModel(modelFile: String?) {
        startBattle(GhostCatalog.byModelFile(modelFile) ?: GhostCatalog.random(random))
    }

    fun resetBattle() {
        _state.value = BattleState()
    }

    // ---------------------------------------------------------- turno do jogador

    fun playerAct(action: PlayerAction) {
        val s = current()
        if (s.phase != BattlePhase.PLAYER_TURN || s.ghost == null) return

        val result = when (action) {
            PlayerAction.FLASH -> flash(s, s.ghost)
            PlayerAction.DEFEND -> defend(s)
            PlayerAction.HEAL -> heal(s)
            PlayerAction.CAPTURE -> capture(s, s.ghost)
            PlayerAction.FLEE -> flee(s, s.ghost)
        }

        // O medo vale só para a ação em que foi gasto.
        val next = if (result.phase != BattlePhase.PLAYER_TURN) {
            result.copy(playerFeared = false)
        } else {
            result
        }

        _state.value = next

        if (next.phase == BattlePhase.CAPTURED) {
            next.ghost?.let { g ->
                _captured.value = (_captured.value ?: emptyList()) + g
            }
        }
    }

    /** Chance (0f a 1f) de a armadilha funcionar agora. Útil para mostrar no botão. */
    fun captureChance(s: BattleState = current()): Float {
        val ghost = s.ghost ?: return 0f
        val missing = 1f - s.ghostHp.toFloat() / ghost.maxHp
        return (0.10f + missing * 0.75f).coerceIn(0.05f, 0.90f)
    }

    private fun flash(s: BattleState, ghost: Ghost): BattleState {
        var damage = calcDamage(BattleConfig.FLASH_POWER, BattleConfig.PLAYER_ATTACK, ghost.defense)

        val crit = random.nextFloat() < BattleConfig.CRIT_CHANCE
        if (crit) damage = (damage * 1.5f).toInt()

        if (s.playerFeared) damage = max(1, damage / 2)

        val newHp = max(0, s.ghostHp - damage)
        val stunned = newHp > 0 && random.nextFloat() < BattleConfig.STUN_CHANCE

        var next = s.copy(
            ghostHp = newHp,
            ghostStunned = s.ghostStunned || stunned
        )

        if (s.playerFeared) next = next.addLog("O medo enfraquece o seu ataque.")

        next = if (crit) {
            next.addLog("Flash crítico! ${ghost.name} perde $damage de vida.")
        } else {
            next.addLog("Você usa a lanterna: ${ghost.name} perde $damage de vida.")
        }

        if (stunned) next = next.addLog("${ghost.name} ficou atordoado!")

        return if (newHp == 0) {
            next.copy(phase = BattlePhase.EXORCISED)
                .addLog("${ghost.name} foi exorcizado!")
        } else {
            next.copy(phase = BattlePhase.GHOST_TURN)
        }
    }

    private fun defend(s: BattleState): BattleState =
        s.copy(playerDefending = true, phase = BattlePhase.GHOST_TURN)
            .addLog("Você ergue o amuleto e se prepara para o golpe.")

    private fun heal(s: BattleState): BattleState {
        // Ações inválidas não gastam o turno.
        if (s.potions <= 0) return s.addLog("Você não tem mais poções.")
        if (s.playerHp >= s.playerMaxHp) return s.addLog("Sua vida já está cheia.")

        val amount = (s.playerMaxHp * BattleConfig.HEAL_FRACTION).toInt()
        val newHp = min(s.playerMaxHp, s.playerHp + amount)

        return s.copy(
            playerHp = newHp,
            potions = s.potions - 1,
            phase = BattlePhase.GHOST_TURN
        ).addLog("Você bebe uma poção e recupera ${newHp - s.playerHp} de vida.")
    }

    private fun capture(s: BattleState, ghost: Ghost): BattleState {
        if (s.traps <= 0) return s.addLog("Você não tem mais armadilhas.")

        val success = random.nextFloat() < captureChance(s)

        return if (success) {
            s.copy(traps = s.traps - 1, phase = BattlePhase.CAPTURED)
                .addLog("A armadilha se fecha... ${ghost.name} foi capturado!")
        } else {
            s.copy(traps = s.traps - 1, phase = BattlePhase.GHOST_TURN)
                .addLog("${ghost.name} escapou da armadilha!")
        }
    }

    private fun flee(s: BattleState, ghost: Ghost): BattleState {
        val chance = if (BattleConfig.PLAYER_SPEED >= ghost.speed) 0.70f else 0.40f

        return if (random.nextFloat() < chance) {
            s.copy(phase = BattlePhase.FLED).addLog("Você fugiu em segurança.")
        } else {
            s.copy(phase = BattlePhase.GHOST_TURN)
                .addLog("Você tenta fugir, mas ${ghost.name} bloqueia o caminho!")
        }
    }

    // ----------------------------------------------------------- turno do fantasma

    fun ghostAct() {
        val s = current()
        val ghost = s.ghost ?: return
        if (s.phase != BattlePhase.GHOST_TURN) return

        if (s.ghostStunned) {
            _state.value = s.copy(ghostStunned = false, phase = BattlePhase.PLAYER_TURN)
                .addLog("${ghost.name} está atordoado e perde a vez.")
            return
        }

        val move = chooseMove(s, ghost)

        var damage = calcDamage(move.power, ghost.attack, BattleConfig.PLAYER_DEFENSE)
        if (s.playerDefending) damage = max(1, damage / 2)

        val newPlayerHp = max(0, s.playerHp - damage)

        var next = s.copy(playerHp = newPlayerHp, playerDefending = false)
            .addLog("${ghost.name} usa ${move.name}: você perde $damage de vida.")

        if (s.playerDefending) next = next.addLog("O amuleto absorveu parte do golpe.")

        if (move.lifeSteal > 0f) {
            val healed = (damage * move.lifeSteal).toInt()
            val newGhostHp = min(ghost.maxHp, next.ghostHp + healed)
            if (newGhostHp > next.ghostHp) {
                next = next.copy(ghostHp = newGhostHp)
                    .addLog("${ghost.name} recupera ${newGhostHp - s.ghostHp} de vida.")
            }
        }

        if (newPlayerHp == 0) {
            _state.value = next.copy(phase = BattlePhase.DEFEATED)
                .addLog("Você desmaiou de susto...")
            return
        }

        if (move.scareChance > 0f && random.nextFloat() < move.scareChance) {
            next = next.copy(playerFeared = true)
                .addLog("Você ficou com medo! Seu próximo ataque será mais fraco.")
        }

        _state.value = next.copy(phase = BattlePhase.PLAYER_TURN)
    }

    private fun chooseMove(s: BattleState, ghost: Ghost): GhostMove {
        val weak = s.ghostHp <= ghost.maxHp * 0.3f

        // Com pouca vida, o fantasma tenta se curar quando tem um golpe que drena.
        if (weak && random.nextFloat() < 0.5f) {
            ghost.moves.firstOrNull { it.lifeSteal > 0f }?.let { return it }
        }

        return ghost.moves.random(random)
    }

    // --------------------------------------------------------------------- dano

    private fun calcDamage(power: Int, attack: Int, defense: Int): Int {
        val base = power + attack / 2 - defense / 2
        val variance = 0.85f + random.nextFloat() * 0.30f
        return max(1, (base * variance).toInt())
    }
}
