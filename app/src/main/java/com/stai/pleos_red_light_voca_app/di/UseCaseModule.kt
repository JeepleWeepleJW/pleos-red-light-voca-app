package com.stai.pleos_red_light_voca_app.di

import com.stai.pleos_red_light_voca_app.core.common.Logger
import com.stai.pleos_red_light_voca_app.domain.session.AppSession
import com.stai.pleos_red_light_voca_app.domain.usecase.AttachPlayerUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.DetachPlayerUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.GetLearningStateUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.HandlePlayCommandUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.ObserveLearningStateUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.ObserveSessionStateUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.ObserveAudioStatusUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.PrepareChapterAudioUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.ReportPauseCommandUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.ReportPlaybackUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.ReportStopCommandUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.RestartVehicleUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.SelectContentUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.StartAppUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.StartLearningUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.StopLearningUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.ValidatePlayTicketUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** domain UseCase는 @Inject를 붙이지 않으므로 여기서 만든다. 상태가 없으므로 범위 지정 없음. */
@Module
@InstallIn(SingletonComponent::class)
object UseCaseModule {
    // 앱·화면
    @Provides fun provideStartAppUseCase(session: AppSession) = StartAppUseCase(session)
    @Provides fun provideObserveSessionStateUseCase(session: AppSession) = ObserveSessionStateUseCase(session)
    @Provides fun provideObserveAudioStatusUseCase(session: AppSession) = ObserveAudioStatusUseCase(session)
    @Provides fun provideObserveLearningStateUseCase(session: AppSession) = ObserveLearningStateUseCase(session)
    @Provides fun provideRestartVehicleUseCase(session: AppSession) = RestartVehicleUseCase(session)
    @Provides fun provideStartLearningUseCase(session: AppSession) = StartLearningUseCase(session)
    @Provides fun provideStopLearningUseCase(session: AppSession) = StopLearningUseCase(session)
    @Provides fun provideSelectContentUseCase(session: AppSession) = SelectContentUseCase(session)

    // 미디어 서비스·GatedPlayer
    @Provides fun provideGetLearningStateUseCase(session: AppSession) = GetLearningStateUseCase(session)
    @Provides fun provideHandlePlayCommandUseCase(session: AppSession, logger: Logger) = HandlePlayCommandUseCase(session, logger)
    @Provides fun provideReportPauseCommandUseCase(session: AppSession) = ReportPauseCommandUseCase(session)
    @Provides fun provideReportStopCommandUseCase(session: AppSession) = ReportStopCommandUseCase(session)
    @Provides fun provideReportPlaybackUseCase(session: AppSession) = ReportPlaybackUseCase(session)
    @Provides fun provideValidatePlayTicketUseCase(session: AppSession) = ValidatePlayTicketUseCase(session)
    @Provides fun providePrepareChapterAudioUseCase(session: AppSession) = PrepareChapterAudioUseCase(session)
    @Provides fun provideAttachPlayerUseCase(session: AppSession) = AttachPlayerUseCase(session)
    @Provides fun provideDetachPlayerUseCase(session: AppSession) = DetachPlayerUseCase(session)
}
