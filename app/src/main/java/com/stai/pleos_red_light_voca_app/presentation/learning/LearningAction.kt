package com.stai.pleos_red_light_voca_app.presentation.learning

/** 사용자가 한 일. 권한 값은 그 일이 일어난 때 화면이 읽어 함께 싣는다. */
sealed interface LearningAction {
    data object ClickStart : LearningAction
    data object ClickStop : LearningAction
    data class SelectContent(val contentId: String) : LearningAction

    /** 화면 "듣기"(재생 중이면 "멈춤") 버튼 */
    data object ClickListen : LearningAction

    /**
     * 화면을 열었다(처음 그릴 때 읽은 권한 값). 차량 수집은 다시 시작하지 않는다.
     * ViewModel이 처음 받을 때만 허용되지 않은 권한의 요청 창을 띄운다(구성 변경 뒤 다시 받아도 요청하지 않음).
     */
    data class OpenScreen(val permissions: PermissionUiModel) : LearningAction

    /** 권한 요청 창에 답했다(그 뒤 읽은 권한 값). 차량 수집을 다시 시작한다. */
    data class AnswerPermissionRequest(val permissions: PermissionUiModel) : LearningAction

    /** 화면으로 돌아왔다(ON_RESUME에서 다시 읽은 권한 값). CAR_SPEED가 바뀌었을 때만 차량 수집을 다시 시작한다. */
    data class ReturnToScreen(val permissions: PermissionUiModel) : LearningAction
}
