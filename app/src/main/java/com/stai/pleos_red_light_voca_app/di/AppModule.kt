package com.stai.pleos_red_light_voca_app.di

import android.content.Context
import com.stai.pleos_red_light_voca_app.core.common.LearningConfig
import com.stai.pleos_red_light_voca_app.core.common.Logger
import com.stai.pleos_red_light_voca_app.core.common.MonotonicClock
import com.stai.pleos_red_light_voca_app.data.catalog.CatalogLocalDataSource
import com.stai.pleos_red_light_voca_app.data.catalog.CatalogRepositoryImpl
import com.stai.pleos_red_light_voca_app.data.log.TimberLogger
import com.stai.pleos_red_light_voca_app.data.progress.ProgressRepositoryImpl
import com.stai.pleos_red_light_voca_app.data.time.SystemMonotonicClock
import com.stai.pleos_red_light_voca_app.data.audio.AndroidAudioAssets
import com.stai.pleos_red_light_voca_app.data.audio.AssetChapterAudioSource
import com.stai.pleos_red_light_voca_app.data.vehicle.VehicleStateSourceImpl
import com.stai.pleos_red_light_voca_app.domain.repository.CatalogRepository
import com.stai.pleos_red_light_voca_app.domain.repository.ProgressRepository
import com.stai.pleos_red_light_voca_app.domain.session.AppSession
import com.stai.pleos_red_light_voca_app.domain.source.ChapterAudioSource
import com.stai.pleos_red_light_voca_app.domain.source.VehicleStateSourceFactory
import com.stai.pleos_red_light_voca_app.presentation.ApplicationScope
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 프로세스 단위 공통 객체. 화면과 미디어 서비스가 같은 인스턴스를 쓴다. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideLearningConfig(): LearningConfig = LearningConfig()

    /**
     * 프로세스 단위 코루틴 스코프(메인 스레드). AppSession·LearningController와 미디어 서비스·GatedPlayer가 쓴다.
     * 한정자 [ApplicationScope]는 presentation에 있다(presentation이 di를 보지 않도록).
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Provides
    @Singleton
    fun provideMonotonicClock(): MonotonicClock = SystemMonotonicClock()

    @Provides
    @Singleton
    fun provideLogger(): Logger = TimberLogger()

    @Provides
    @Singleton
    fun provideAppSession(
        catalogRepository: CatalogRepository,
        progressRepository: ProgressRepository,
        audioSource: ChapterAudioSource,
        vehicleSourceFactory: VehicleStateSourceFactory,
        clock: MonotonicClock,
        @ApplicationScope scope: CoroutineScope,
        config: LearningConfig,
        logger: Logger,
    ): AppSession = AppSession(
        catalogRepository = catalogRepository,
        progressRepository = progressRepository,
        audioSource = audioSource,
        vehicleSourceFactory = vehicleSourceFactory,
        clock = clock,
        scope = scope,
        config = config,
        logger = logger,
    )
}

/** data 구현을 domain 인터페이스로 묶는다. */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideCatalogRepository(@ApplicationContext context: Context): CatalogRepository =
        CatalogRepositoryImpl(CatalogLocalDataSource(context))

    /** 같은 파일에 활성 DataStore가 둘이면 예외가 나므로 프로세스당 하나. */
    @Provides
    @Singleton
    fun provideProgressRepository(@ApplicationContext context: Context): ProgressRepository =
        ProgressRepositoryImpl.create(context)

    @Provides
    @Singleton
    fun provideChapterAudioSource(
        @ApplicationContext context: Context,
        config: LearningConfig,
    ): ChapterAudioSource = AssetChapterAudioSource(
        assets = AndroidAudioAssets(context.assets),
        cacheRoot = File(context.cacheDir, "chapter-audio"),
        config = config,
        legacyCacheDirs = listOf(File(context.cacheDir, "tts")),
    )

    /**
     * 실제 차량 입력 소스 팩토리. 실제 소스(`VehicleStateSourceImpl`)는 여기서만 만든다.
     * 빌드 변형별 `VariantModule`이 이것을 받아 앱이 쓰는 [VehicleStateSourceFactory]를 제공한다.
     */
    @Provides
    @RealVehicleSource
    fun provideRealVehicleSourceFactory(@ApplicationContext context: Context): VehicleStateSourceFactory =
        VehicleStateSourceFactory { VehicleStateSourceImpl(context) }
}

/** 실제 차량 입력 소스 팩토리(디버그 시뮬레이션으로 감싸기 전) 한정자. di 안에서만 쓴다. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RealVehicleSource
