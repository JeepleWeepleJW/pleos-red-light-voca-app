package com.stai.pleos_red_light_voca_app.presentation.playback

import com.stai.pleos_red_light_voca_app.core.entity.Catalog
import com.stai.pleos_red_light_voca_app.core.entity.Chapter
import com.stai.pleos_red_light_voca_app.core.entity.Content
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaIdsTest {
    private val catalog = Catalog(
        listOf(
            Content("day1", "Day 1", listOf(Chapter("c1", "signal", "noun", "신호"), Chapter("c2", "brake", "verb", "멈추다"))),
            Content("day2", "Day 2", listOf(Chapter("c1", "lane", "noun", "차선"))),
        ),
    )

    @Test fun round_trip_ids() {
        assertEquals(MediaNode.Root, MediaIds.parse(MediaIds.ROOT))
        assertEquals(MediaNode.ContentNode("day1"), MediaIds.parse(MediaIds.content("day1")))
        assertEquals(MediaNode.ChapterNode("day1", "c2"), MediaIds.parse(MediaIds.chapter("day1", "c2")))
        assertEquals(MediaNode.ChapterNode("day1", "a/b"), MediaIds.parse("chapter/day1/a/b"))
    }

    @Test fun invalid_ids() {
        assertNull(MediaIds.parse(null))
        assertNull(MediaIds.parse(""))
        assertNull(MediaIds.parse("content/"))
        assertNull(MediaIds.parse("content/a/b"))
        assertNull(MediaIds.parse("chapter/day1"))
        assertNull(MediaIds.parse("chapter/day1/"))
        assertNull(MediaIds.parse("chapter//c1"))
        assertNull(MediaIds.parse("other"))
    }

    @Test fun search_matches_word_meaning_pos_case_insensitive() {
        assertEquals(listOf("c1"), MediaIds.search(catalog, "SIG").map { it.second.chapterId })
        assertEquals(listOf("day2"), MediaIds.search(catalog, "차선").map { it.first.contentId })
        assertEquals(2, MediaIds.search(catalog, "noun").size)
        assertEquals(0, MediaIds.search(catalog, "  ").size)
    }

    @Test fun paging() {
        val items = (1..5).toList()
        assertEquals(listOf(1, 2), MediaIds.page(items, 0, 2))
        assertEquals(listOf(5), MediaIds.page(items, 2, 2))
        assertEquals(emptyList<Int>(), MediaIds.page(items, 3, 2))
        assertEquals(items, MediaIds.page(items, 0, Int.MAX_VALUE))
        assertEquals(items, MediaIds.page(items, -1, 0))
        assertEquals(emptyList<Int>(), MediaIds.page(items, Int.MAX_VALUE, Int.MAX_VALUE))
    }
}
