package com.stai.pleos_red_light_voca_app.core.entity

/** catalog.json(schemaVersion 2) 모델. 콘텐츠(하루 묶음) > 챕터(단어 1개). */
data class Catalog(val contents: List<Content>) {
    fun content(contentId: String): Content? = contents.firstOrNull { it.contentId == contentId }
}

data class Content(
    val contentId: String,
    val title: String,
    val chapters: List<Chapter>,
) {
    fun indexOf(chapterId: String): Int = chapters.indexOfFirst { it.chapterId == chapterId }
}

data class Chapter(
    val chapterId: String,
    val word: String,
    val partOfSpeech: String,
    val meaningKo: String,
)
