package com.stai.pleos_red_light_voca_app.data.catalog

import com.stai.pleos_red_light_voca_app.core.entity.Chapter
import com.stai.pleos_red_light_voca_app.domain.repository.CatalogResult

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CatalogParserTest {
    private fun ch(id: String = "c1", word: String = "w", meaning: String = "m") =
        """{"chapterId":"$id","word":"$word","partOfSpeech":"noun","meaningKo":"$meaning"}"""

    private fun content(id: String = "x1", chapters: String = ch()) =
        """{"contentId":"$id","title":"t","chapters":[$chapters]}"""

    private fun doc(contents: String = content(), version: String = "2") =
        """{"schemaVersion":$version,"note":"ignored","contents":[$contents]}"""

    private fun assertFailure(json: String): String {
        val r = CatalogParser.parse(json)
        return (r as? CatalogResult.Failure)?.reason ?: throw AssertionError("expected Failure but was $r")
    }

    @Test fun bomPrefixedInputParses() {
        val r = CatalogParser.parse("\uFEFF" + doc())
        assertTrue("$r", r is CatalogResult.Success)
    }

    @Test fun slashInContentIdFails() {
        assertTrue(assertFailure(doc(content(id = "a/b"))).contains("contentId"))
    }

    @Test fun slashInChapterIdFails() {
        assertTrue(assertFailure(doc(content(chapters = ch(id = "c/1")))).contains("chapterId"))
    }

    @Test fun duplicateKeyFails() {
        val json = """{"schemaVersion":2,"schemaVersion":2,"contents":[${content()}]}"""
        assertTrue(assertFailure(json).contains("duplicate"))
    }

    @Test fun realCatalogFile() {
        val file = listOf(File("src/main/assets/content/catalog.json"), File("app/src/main/assets/content/catalog.json"))
            .first { it.exists() }
        val r = CatalogParser.parse(file.readText(Charsets.UTF_8))
        assertTrue("$r", r is CatalogResult.Success)
        val catalog = (r as CatalogResult.Success).catalog
        assertEquals(2, catalog.contents.size)
        catalog.contents.forEach { assertEquals(10, it.chapters.size) }
        assertEquals(Chapter("d01-01", "signal", "noun", "신호"), catalog.contents[0].chapters[0])
    }

    @Test fun minimalValidAndMissingPartOfSpeechIsEmpty() {
        val json = """{"schemaVersion":2,"contents":[{"contentId":"a","title":"t","chapters":[{"chapterId":"c","word":"w","meaningKo":"m"}]}]}"""
        val c = (CatalogParser.parse(json) as CatalogResult.Success).catalog
        assertEquals("", c.contents[0].chapters[0].partOfSpeech)
    }

    @Test fun schemaVersionWrong() {
        assertFailure(doc(version = "1"))
        assertFailure(doc(version = "\"2\""))
        assertFailure(doc(version = "2.0"))
        assertFailure("""{"contents":[${content()}]}""")
    }

    @Test fun contentsEmptyOrMissing() {
        assertFailure("""{"schemaVersion":2,"contents":[]}""")
        assertFailure("""{"schemaVersion":2}""")
    }

    @Test fun contentFieldsRequired() {
        assertFailure(doc("""{"title":"t","chapters":[${ch()}]}"""))
        assertFailure(doc("""{"contentId":"a","chapters":[${ch()}]}"""))
        assertFailure(doc("""{"contentId":"a","title":"t"}"""))
        assertFailure(doc(content(chapters = "")))
    }

    @Test fun chapterIdMissing() {
        val reason = assertFailure(doc(content(chapters = """{"word":"w","meaningKo":"m"}""")))
        assertTrue(reason, reason.contains("chapterId") && reason.contains("x1"))
    }

    @Test fun duplicateIds() {
        assertTrue(assertFailure(doc(content("a") + "," + content("a"))).contains("a"))
        assertTrue(assertFailure(doc(content(chapters = ch("c") + "," + ch("c")))).contains("c"))
    }

    @Test fun sameChapterIdInDifferentContentsIsOk() {
        assertTrue(CatalogParser.parse(doc(content("a") + "," + content("b"))) is CatalogResult.Success)
    }

    @Test fun emptyWordOrMeaning() {
        assertTrue(assertFailure(doc(content(chapters = ch(word = "")))).contains("word"))
        assertFailure(doc(content(chapters = ch(meaning = ""))))
    }

    @Test fun partOfSpeechWrongType() {
        assertFailure(doc(content(chapters = """{"chapterId":"c","word":"w","partOfSpeech":3,"meaningKo":"m"}""")))
    }

    @Test fun brokenJson() {
        assertTrue(assertFailure("{").isNotEmpty())
        assertFailure("")
        assertFailure("[]")
        assertFailure(doc() + "x")
    }
}
