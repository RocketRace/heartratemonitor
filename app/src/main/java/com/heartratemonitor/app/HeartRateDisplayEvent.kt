package com.heartratemonitor.app

import java.time.LocalDateTime

class HeartRateDisplayEvent(val bpm: Int, val timestamp: LocalDateTime) {
    var highlight: HighlightKind? = null
}