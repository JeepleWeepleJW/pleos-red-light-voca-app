package com.stai.pleos_red_light_voca_app.presentation.learning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stai.pleos_red_light_voca_app.domain.session.SessionState
import com.stai.pleos_red_light_voca_app.domain.session.StartOutcome
import com.stai.pleos_red_light_voca_app.domain.usecase.ObserveLearningStateUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.ObserveSessionStateUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.ObserveAudioStatusUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.RestartVehicleUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.SelectContentUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.StartLearningUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.StopLearningUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal const val PERM_CAR_SPEED = "android.car.permission.CAR_SPEED"
internal const val PERM_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

@HiltViewModel
class LearningViewModel @Inject constructor(
    observeSessionState: ObserveSessionStateUseCase,
    observeLearningState: ObserveLearningStateUseCase,
    observeAudioStatus: ObserveAudioStatusUseCase,
    private val restartVehicle: RestartVehicleUseCase,
    private val startLearning: StartLearningUseCase,
    private val stopLearning: StopLearningUseCase,
    private val selectContent: SelectContentUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LearningUiState())
    val uiState: StateFlow<LearningUiState> = _uiState.asStateFlow()

    private val _events = Channel<LearningEvent>(Channel.BUFFERED)
    val events: Flow<LearningEvent> = _events.receiveAsFlow()

    /** 화면이 마지막으로 읽어 넘긴 권한 값. ON_RESUME에서 CAR_SPEED가 바뀌었는지 비교한다. */
    private var permissions = PermissionUiModel()

    /**
     * 권한 자동 요청을 이미 했는지. ViewModel은 구성 변경(회전 등)에도 남으므로 화면을 다시 그려도 다시 요청하지 않는다.
     * ON_RESUME(ReturnToScreen)에서는 요청하지 않는다.
     */
    private var permissionsRequested = false

    init {
        // viewModelScope는 Main.immediate라 StateFlow의 현재 값이 생성 직후 바로 반영된다.
        viewModelScope.launch {
            observeSessionState().collectLatest { st ->
                when (st) {
                    SessionState.Loading -> _uiState.update {
                        it.copy(isLoading = true, loadErrorMessage = null, catalog = null)
                    }
                    is SessionState.Failed -> _uiState.update {
                        it.copy(isLoading = false, loadErrorMessage = st.message, catalog = null)
                    }
                    is SessionState.Ready -> observeLearningState().collect { learning ->
                        _uiState.update {
                            it.copy(isLoading = false, loadErrorMessage = null, catalog = st.catalog, learning = learning)
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            observeAudioStatus().collect { audio -> _uiState.update { it.copy(audio = audio) } }
        }
    }

    fun onAction(action: LearningAction) {
        when (action) {
            LearningAction.ClickStart -> {
                val id = _uiState.value.learning.contentId ?: return
                viewModelScope.launch {
                    val message = when (val r = startLearning(id)) {
                        StartOutcome.Started -> null
                        is StartOutcome.Refused -> r.message
                    }
                    _uiState.update { it.copy(startRefusedMessage = message) }
                }
            }
            LearningAction.ClickStop -> {
                _uiState.update { it.copy(startRefusedMessage = null) }
                stopLearning()
            }
            is LearningAction.SelectContent -> {
                // 거절 문구는 그 콘텐츠에 대한 것이므로 다른 콘텐츠를 고르면 지운다.
                _uiState.update { it.copy(startRefusedMessage = null) }
                selectContent(action.contentId)
            }
            LearningAction.ClickListen -> {
                // 버튼 표시와 같은 기준으로 보낼 명령을 정한다. 재생 허가 자체는 세션 게이트가 한다.
                val command = ListenButton.of(_uiState.value.learning, _uiState.value.audio).command ?: return
                _events.trySend(LearningEvent.SendListenCommand(command))
            }
            is LearningAction.OpenScreen -> {
                permissions = action.permissions
                if (!permissionsRequested) {
                    permissionsRequested = true
                    val missing = missingPermissions(action.permissions)
                    if (missing.isNotEmpty()) _events.trySend(LearningEvent.RequestPermissions(missing))
                }
            }
            is LearningAction.AnswerPermissionRequest -> {
                permissions = action.permissions
                restartVehicle()
            }
            is LearningAction.ReturnToScreen -> {
                // 이전에 본 값과 달라졌으면(앱 밖 adb pm grant, 앱 설정 등) 차량 수집을 다시 시작한다.
                val changed = action.permissions.speedGranted != permissions.speedGranted
                permissions = action.permissions
                if (changed) restartVehicle()
            }
        }
    }

    /** 허용되지 않은 권한만(알림은 API 33 이상에서만). */
    private fun missingPermissions(p: PermissionUiModel): List<String> = buildList {
        if (!p.speedGranted) add(PERM_CAR_SPEED)
        if (p.notificationApplicable && !p.notificationGranted) add(PERM_POST_NOTIFICATIONS)
    }
}
