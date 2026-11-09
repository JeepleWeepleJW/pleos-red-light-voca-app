package com.stai.pleos_red_light_voca_app.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** Play 출처별 허용 규칙(domain). 컨트롤러 분류는 presentation의 같은 이름 테스트. */
class PlaySourcePolicyTest {
    @Test fun gate_rules() {
        // 학습 OFF: 키·debug 컨트롤러만 학습 ON 전환, 그 밖은 학습을 켜지 않음
        assertEquals(PlayGate.FORWARD, PlaySourcePolicy.gate(PlaySource.MEDIA_KEY, learningOn = false))
        assertEquals(PlayGate.FORWARD, PlaySourcePolicy.gate(PlaySource.SELF, learningOn = false))
        assertEquals(PlayGate.IGNORE_NOT_USER_KEY, PlaySourcePolicy.gate(PlaySource.OTHER_CONTROLLER, learningOn = false))
        assertEquals(PlayGate.IGNORE_SYSTEM_AUTOPLAY, PlaySourcePolicy.gate(PlaySource.SYSTEM_AUTOPLAY, learningOn = false))
        // 학습 ON: 사용자 명령은 모두 컨트롤러로, 시스템 자동 재생은 무시
        assertEquals(PlayGate.FORWARD, PlaySourcePolicy.gate(PlaySource.OTHER_CONTROLLER, learningOn = true))
        assertEquals(PlayGate.FORWARD, PlaySourcePolicy.gate(PlaySource.MEDIA_KEY, learningOn = true))
        assertEquals(PlayGate.IGNORE_SYSTEM_AUTOPLAY, PlaySourcePolicy.gate(PlaySource.SYSTEM_AUTOPLAY, learningOn = true))
    }

    @Test fun app_ui_button_plays_only_when_learning_on() {
        assertEquals(PlayGate.IGNORE_NOT_USER_KEY, PlaySourcePolicy.gate(PlaySource.APP_UI, learningOn = false))
        assertEquals(PlayGate.IGNORE_NOT_USER_KEY, PlaySourcePolicy.gate(PlaySource.APP_UI, learningOn = false, audioReady = false))
        assertEquals(PlayGate.FORWARD, PlaySourcePolicy.gate(PlaySource.APP_UI, learningOn = true))
    }

    @Test fun audio_not_ready_blocks_learning_on_only() {
        assertEquals(PlayGate.IGNORE_AUDIO_NOT_READY, PlaySourcePolicy.gate(PlaySource.MEDIA_KEY, learningOn = false, audioReady = false))
        assertEquals(PlayGate.IGNORE_AUDIO_NOT_READY, PlaySourcePolicy.gate(PlaySource.SELF, learningOn = false, audioReady = false))
        assertEquals(PlayGate.IGNORE_NOT_USER_KEY, PlaySourcePolicy.gate(PlaySource.OTHER_CONTROLLER, learningOn = false, audioReady = false))
        assertEquals(PlayGate.FORWARD, PlaySourcePolicy.gate(PlaySource.MEDIA_KEY, learningOn = true, audioReady = false))
    }
}
