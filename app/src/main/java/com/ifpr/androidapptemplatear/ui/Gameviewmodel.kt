package com.ifpr.androidapptemplatear.ui

import androidx.lifecycle.ViewModel

/**
 * Estado da partida compartilhado por todos os inimigos.
 * Fica na Activity, então não reseta ao trocar de inimigo
 * nem ao trocar de aba (câmera <-> change).
 */
class GameViewModel : ViewModel() {

    companion object {
        const val MAX_LIVES = 3
    }

    var lives: Int = MAX_LIVES
    var monstersDefeated: Int = 0

    fun reset() {
        lives = MAX_LIVES
        monstersDefeated = 0
    }
}
