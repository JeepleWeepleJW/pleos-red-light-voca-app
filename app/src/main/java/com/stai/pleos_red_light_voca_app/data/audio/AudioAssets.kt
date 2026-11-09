package com.stai.pleos_red_light_voca_app.data.audio

import android.content.res.AssetManager

/** 단어 음원 읽기. 테스트에서는 메모리 구현으로 바꾼다. */
interface AudioAssets {
    /** [dir] 바로 아래 파일 이름 목록. 폴더가 없으면 빈 목록. @throws java.io.IOException 읽기 실패 */
    fun list(dir: String): List<String>

    /** @throws java.io.FileNotFoundException 파일이 없음 @throws java.io.IOException 읽기 실패 */
    fun read(path: String): ByteArray
}

/** APK assets에서 읽는다. */
class AndroidAudioAssets(private val assets: AssetManager) : AudioAssets {
    override fun list(dir: String): List<String> = assets.list(dir)?.toList().orEmpty()
    override fun read(path: String): ByteArray = assets.open(path).use { it.readBytes() }
}
