package com.example.withcrossdemo.domain.inference

import com.example.withcrossdemo.domain.model.SignalState

class SignalStateManager {
    private val window = ArrayDeque<SignalState>(3)
    var state: SignalState = SignalState.NONE
        private set

    /** 推論ラベル → 新しい状態が決まったら true */
    fun update(predicted: Any): Boolean {
        if (window.size == 3) window.removeFirst()
        window.addLast(predicted as SignalState)

        val allSame = window.size == 3 && window.distinct().size == 1
        if (allSame && window.first() != state) {
            state = window.first()
            return true
        }
        return false
    }
}
