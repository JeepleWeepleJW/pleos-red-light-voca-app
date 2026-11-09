package com.stai.pleos_red_light_voca_app.presentation.learning

import com.stai.pleos_red_light_voca_app.core.entity.Catalog
import com.stai.pleos_red_light_voca_app.domain.learning.LearningState
import com.stai.pleos_red_light_voca_app.domain.session.AudioStatus

/** 학습 화면 상태 */
data class LearningUiState(
    /** 카탈로그 로딩 중 */
    val isLoading: Boolean = true,
    /** 카탈로그 로딩 실패 문구 */
    val loadErrorMessage: String? = null,
    /** 준비 완료 시 카탈로그. null이면 콘텐츠 선택·학습·상태 영역을 그리지 않는다. */
    val catalog: Catalog? = null,
    val learning: LearningState = LearningState(),
    val audio: AudioStatus = AudioStatus.Checking,
    /** "학습 시작" 거절 문구. 학습 종료를 누르면 지운다. */
    val startRefusedMessage: String? = null,
)

/** 화면(LearningScreen)이 읽은 권한 값. Action에 실어 ViewModel로 넘긴다(화면에 표시하지 않음). */
data class PermissionUiModel(
    val speedGranted: Boolean = false,
    /** API 33 이상에서만 알림 권한을 묻는다. */
    val notificationApplicable: Boolean = false,
    val notificationGranted: Boolean = false,
)
