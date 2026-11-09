package com.stai.pleos_red_light_voca_app.di

import com.stai.pleos_red_light_voca_app.data.log.VocaLogTree
import timber.log.Timber

/** 로그 트리를 심는다. debug·release 모두 같은 태그(VocaApp)로 남긴다. Application.onCreate 맨 앞에서 부른다. */
fun plantLogTree() {
    Timber.plant(VocaLogTree())
}
