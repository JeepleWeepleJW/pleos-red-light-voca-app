package com.stai.pleos_red_light_voca_app.data.catalog

import com.stai.pleos_red_light_voca_app.core.entity.Catalog
import com.stai.pleos_red_light_voca_app.core.entity.Chapter
import com.stai.pleos_red_light_voca_app.core.entity.Content
import com.stai.pleos_red_light_voca_app.domain.repository.CatalogResult

import com.stai.pleos_red_light_voca_app.core.common.LearningConfig
import com.stai.pleos_red_light_voca_app.data.catalog.json.MiniJson

/** catalog.json(schemaVersion 2) 파서·검증기. 예외를 밖으로 던지지 않는다. */
object CatalogParser {
    fun parse(json: String): CatalogResult {
        val root = try {
            MiniJson.parse(json.removePrefix("\uFEFF"))
        } catch (e: IllegalArgumentException) {
            return CatalogResult.Failure("JSON 파싱 오류: ${e.message}")
        }
        return try {
            CatalogResult.Success(build(root))
        } catch (e: Invalid) {
            CatalogResult.Failure(e.message ?: "invalid catalog")
        }
    }

    private class Invalid(message: String) : Exception(message)

    private fun bad(msg: String): Nothing = throw Invalid(msg)

    private fun build(root: Any?): Catalog {
        val obj = root.asObject("루트")
        val version = obj["schemaVersion"]
        if (version !is Long || version != LearningConfig.CATALOG_SCHEMA_VERSION.toLong()) {
            bad("schemaVersion은 정수 ${LearningConfig.CATALOG_SCHEMA_VERSION}이어야 합니다: $version")
        }
        val contents = obj["contents"] as? List<*> ?: bad("contents 배열이 필요합니다")
        if (contents.isEmpty()) bad("contents가 비어 있습니다")
        val seen = HashSet<String>()
        val result = contents.mapIndexed { ci, c ->
            val content = buildContent(c, ci)
            if (!seen.add(content.contentId)) bad("contentId 중복: ${content.contentId}")
            content
        }
        return Catalog(result)
    }

    private fun buildContent(raw: Any?, ci: Int): Content {
        val where = "contents[$ci]"
        val o = raw.asObject(where)
        val id = o.requiredString("contentId", where)
        if ('/' in id) bad("$where.contentId에 '/'를 쓸 수 없습니다: $id")
        val w = "$where($id)"
        val title = o.requiredString("title", w)
        val chapters = o["chapters"] as? List<*> ?: bad("$w.chapters 배열이 필요합니다")
        if (chapters.isEmpty()) bad("$w.chapters가 비어 있습니다")
        val seen = HashSet<String>()
        val list = chapters.mapIndexed { i, ch ->
            val cw = "$w.chapters[$i]"
            val co = ch.asObject(cw)
            val chId = co.requiredString("chapterId", cw)
            if ('/' in chId) bad("$cw.chapterId에 '/'를 쓸 수 없습니다: $chId")
            val cw2 = "$cw($chId)"
            val word = co.requiredString("word", cw2)
            val meaning = co.requiredString("meaningKo", cw2)
            val pos = when (val p = co["partOfSpeech"]) {
                null -> if (co.containsKey("partOfSpeech")) bad("$cw2.partOfSpeech는 문자열이어야 합니다") else ""
                is String -> p
                else -> bad("$cw2.partOfSpeech는 문자열이어야 합니다")
            }
            if (!seen.add(chId)) bad("$w 안에 chapterId 중복: $chId")
            Chapter(chId, word, pos, meaning)
        }
        return Content(id, title, list)
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asObject(where: String): Map<String, Any?> =
        this as? Map<String, Any?> ?: bad("$where 는 객체여야 합니다")

    private fun Map<String, Any?>.requiredString(key: String, where: String): String {
        val v = this[key] as? String ?: bad("$where.$key 문자열이 필요합니다")
        if (v.isBlank()) bad("$where.$key 가 비어 있습니다")
        return v
    }
}
