package com.stai.pleos_red_light_voca_app.presentation.playback

import com.stai.pleos_red_light_voca_app.core.entity.Catalog
import com.stai.pleos_red_light_voca_app.core.entity.Chapter
import com.stai.pleos_red_light_voca_app.core.entity.Content

/**
 * 브라우즈 트리 미디어 ID: 루트 → 콘텐츠 → 챕터 (Pleos Music/Audio 가이드, 요구 2장).
 * - 루트: `root`
 * - 콘텐츠: `content/<contentId>`
 * - 챕터: `chapter/<contentId>/<chapterId>`
 * contentId·chapterId에 '/'가 있어도 첫 구분자 기준으로 나눈다(contentId에는 '/'가 없다고 가정, 있으면 파싱 실패).
 */
sealed interface MediaNode {
    data object Root : MediaNode
    data class ContentNode(val contentId: String) : MediaNode
    data class ChapterNode(val contentId: String, val chapterId: String) : MediaNode
}

object MediaIds {
    const val ROOT = "root"
    private const val CONTENT_PREFIX = "content/"
    private const val CHAPTER_PREFIX = "chapter/"

    fun content(contentId: String): String = CONTENT_PREFIX + contentId
    fun chapter(contentId: String, chapterId: String): String = "$CHAPTER_PREFIX$contentId/$chapterId"

    fun parse(mediaId: String?): MediaNode? {
        if (mediaId == null) return null
        if (mediaId == ROOT) return MediaNode.Root
        if (mediaId.startsWith(CONTENT_PREFIX)) {
            val id = mediaId.removePrefix(CONTENT_PREFIX)
            return if (id.isEmpty() || id.contains('/')) null else MediaNode.ContentNode(id)
        }
        if (mediaId.startsWith(CHAPTER_PREFIX)) {
            val rest = mediaId.removePrefix(CHAPTER_PREFIX)
            val slash = rest.indexOf('/')
            if (slash <= 0 || slash == rest.length - 1) return null
            return MediaNode.ChapterNode(rest.substring(0, slash), rest.substring(slash + 1))
        }
        return null
    }

    /** 검색: 단어·뜻·품사 부분 일치(대소문자 무시). 빈 검색어는 결과 없음. */
    fun search(catalog: Catalog, query: String): List<Pair<Content, Chapter>> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return catalog.contents.flatMap { content ->
            content.chapters
                .filter {
                    it.word.contains(q, ignoreCase = true) ||
                        it.meaningKo.contains(q, ignoreCase = true) ||
                        it.partOfSpeech.equals(q, ignoreCase = true)
                }
                .map { content to it }
        }
    }

    /** Media3 paging(page는 0부터) */
    fun <T> page(items: List<T>, page: Int, pageSize: Int): List<T> {
        if (page < 0 || pageSize <= 0) return items
        val from = page.toLong() * pageSize
        if (from >= items.size) return emptyList()
        return items.subList(from.toInt(), minOf(items.size, (from + pageSize).toInt()))
    }
}
