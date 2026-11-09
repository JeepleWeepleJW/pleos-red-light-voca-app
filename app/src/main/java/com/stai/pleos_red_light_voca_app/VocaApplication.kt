package com.stai.pleos_red_light_voca_app

import android.app.Application
import com.stai.pleos_red_light_voca_app.di.plantLogTree
import com.stai.pleos_red_light_voca_app.domain.usecase.StartAppUseCase
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** 프로세스 시작 시 학습 세션을 시작한다. 진도만 복원하고 학습은 OFF, 재생하지 않는다. */
@HiltAndroidApp
class VocaApplication : Application() {
    @Inject lateinit var startApp: StartAppUseCase

    override fun onCreate() {
        plantLogTree()
        super.onCreate()
        startApp()
    }
}
