package com.stai.pleos_red_light_voca_app.presentation.learning

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.stai.pleos_red_light_voca_app.presentation.debug.DebugPanel
import com.stai.pleos_red_light_voca_app.presentation.playback.AppMediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 학습 화면. ViewModel 상태를 그리고, 권한 읽기·요청 창 띄우기와 ON_RESUME 권한 재확인 같은 Android 쪽 일을 한다.
 * 요청 여부와 시점은 ViewModel이 정한다(처음 화면을 열 때 허용되지 않은 권한만 한 번).
 */
@Composable
fun LearningScreen(modifier: Modifier = Modifier, viewModel: LearningViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val onAction by rememberUpdatedState(viewModel::onAction)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onAction(LearningAction.AnswerPermissionRequest(readPermissions(context)))
    }

    LaunchedEffect(viewModel) {
        // Main.immediate에서 수집해, 받은 이벤트를 처리하기 전에 구성 변경으로 효과가 취소되어 버려지는 창을 없앤다.
        withContext(Dispatchers.Main.immediate) {
            viewModel.events.collect { event ->
                when (event) {
                    is LearningEvent.RequestPermissions -> launcher.launch(event.permissions.toTypedArray())
                    is LearningEvent.SendListenCommand -> AppMediaController.send(context, event.command)
                }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // 처음 표시 때 권한 값을 넣은 뒤 옵저버를 단다. 이미 RESUMED면 등록 직후 ON_RESUME이 오지만
        // 같은 값이라 차량 수집을 다시 시작하지 않는다(세션이 이미 수집 중).
        onAction(LearningAction.OpenScreen(readPermissions(context)))
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onAction(LearningAction.ReturnToScreen(readPermissions(context)))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LearningContent(
        state = state,
        onAction = viewModel::onAction,
        modifier = modifier,
        // '상태' 카드는 debug(디버그 가능) 빌드에서만 보인다(사용자 결정 2026-11-09). 실사용자(release)에게는 보이지 않는다.
        showStatus = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        debugPanel = { DebugPanel() },
    )
}

private fun readPermissions(context: Context): PermissionUiModel {
    fun granted(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    val notif = Build.VERSION.SDK_INT >= 33
    return PermissionUiModel(
        speedGranted = granted(PERM_CAR_SPEED),
        notificationApplicable = notif,
        notificationGranted = !notif || granted(PERM_POST_NOTIFICATIONS),
    )
}
