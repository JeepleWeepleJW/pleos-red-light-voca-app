package com.stai.pleos_red_light_voca_app.data.audio

import com.stai.pleos_red_light_voca_app.core.common.LearningConfig
import com.stai.pleos_red_light_voca_app.core.entity.Chapter
import com.stai.pleos_red_light_voca_app.core.entity.Content
import com.stai.pleos_red_light_voca_app.domain.source.AudioAvailability
import com.stai.pleos_red_light_voca_app.domain.speech.ChapterAudioResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** 메모리 음원. 경로 → WAV 바이트. */
private class MemoryAssets(val files: MutableMap<String, ByteArray>) : AudioAssets {
    var reads = 0
    var failList = false
    override fun list(dir: String): List<String> {
        if (failList) throw IOException("list failed")
        return files.keys.filter { it.substringBeforeLast('/') == dir }.map { it.substringAfterLast('/') }
    }
    override fun read(path: String): ByteArray {
        reads++
        return files[path] ?: throw FileNotFoundException(path)
    }
}

class AssetChapterAudioSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val rate = 1000 // 1ms = 1프레임으로 계산을 쉽게
    private fun wav(value: Int, frames: Int, sampleRate: Int = rate, channels: Int = 1) =
        WavCodec.encode(PcmAudio(sampleRate, channels, ShortArray(frames * channels) { value.toShort() }))

    private val ch1 = Chapter("c1", "signal", "noun", "신호")
    private val ch2 = Chapter("c2", "merge", "verb", "합류하다")
    private val day = Content("day1", "1일차", listOf(ch1, ch2))
    private val logs = mutableListOf<String>()

    private val texts = mapOf(
        "day1/c1.en.wav" to "signal", "day1/c1.ko.wav" to "신호",
        "day1/c2.en.wav" to "merge", "day1/c2.ko.wav" to "합류하다",
    )

    /** 생성 스크립트와 같은 형식의 manifest.tsv */
    private fun manifest(t: Map<String, String> = texts): ByteArray =
        ("# path\ttext_sha256\tvoice\n" + t.entries.joinToString("") { (k, v) -> "$k\t${AudioAssetPaths.textHash(v)}\tVoice\n" })
            .toByteArray(Charsets.UTF_8)

    private fun allFiles() = mutableMapOf(
        "audio/day1/c1.en.wav" to wav(1000, 50),
        "audio/day1/c1.ko.wav" to wav(2000, 80),
        "audio/day1/c2.en.wav" to wav(3000, 40),
        "audio/day1/c2.ko.wav" to wav(4000, 60),
        AudioAssetPaths.MANIFEST to manifest(),
    )

    private fun source(
        assets: AudioAssets,
        config: LearningConfig = LearningConfig(chapterSilenceMs = 600),
        io: CoroutineDispatcher = Dispatchers.Unconfined,
        legacy: List<File> = emptyList(),
    ) = AssetChapterAudioSource(assets, tmp.root, config, io, legacy) { logs += it }

    @Test fun check_all_present_is_ready() = runTest {
        val r = source(MemoryAssets(allFiles())).checkAvailability(listOf(day))
        assertEquals(AudioAvailability.Checked(emptyMap()), r)
        assertTrue(logs.toString(), logs.any { it.contains("필요 파일 4개, 문제 0개") })
    }

    @Test fun manifest_hash_matches_script_format() {
        // printf '%s' 신호 | shasum -a 256
        assertEquals("617aa217dbc92e8aaffc86e422e2d40c85771de5a4393d06ea145762067316e4", AudioAssetPaths.textHash("신호"))
        assertEquals(
            mapOf("audio/day1/c1.en.wav" to "ab"),
            AudioAssetPaths.parseManifest("# path\ttext_sha256\tvoice\r\nday1/c1.en.wav\tAB\tSamantha\r\nbroken line\n\n"),
        )
    }

    @Test fun check_without_manifest_marks_all_files() = runTest {
        val files = allFiles().apply { remove(AudioAssetPaths.MANIFEST) }
        val r = source(MemoryAssets(files)).checkAvailability(listOf(day)) as AudioAvailability.Checked
        assertEquals(4, r.missing.getValue("day1").size)
        assertTrue(r.missing.toString(), r.missing.getValue("day1").all { it.endsWith("(지문 없음)") })
        assertTrue(logs.toString(), logs.any { it.startsWith("음원 지문 파일 없음") })
    }

    @Test fun check_reports_changed_catalog_text() = runTest {
        val changed = day.copy(chapters = listOf(ch1.copy(word = "signals"), ch2))
        val r = source(MemoryAssets(allFiles())).checkAvailability(listOf(changed))
        assertEquals(
            AudioAvailability.Checked(mapOf("day1" to listOf("audio/day1/c1.en.wav (catalog 문구와 다름)"))),
            r,
        )
    }

    @Test fun check_reports_broken_and_silent_wav() = runTest {
        val files = allFiles().apply {
            put("audio/day1/c1.en.wav", ByteArray(0))
            put("audio/day1/c2.ko.wav", wav(4000, 0))
        }
        val r = source(MemoryAssets(files)).checkAvailability(listOf(day)) as AudioAvailability.Checked
        val day1 = r.missing.getValue("day1")
        assertEquals(2, day1.size)
        assertTrue(day1[0], day1[0].startsWith("audio/day1/c1.en.wav (WAV 오류: "))
        assertEquals("audio/day1/c2.ko.wav (소리 없음)", day1[1])
    }

    @Test fun legacy_cache_is_deleted_once() = runTest {
        val legacy = tmp.newFolder("tts")
        java.io.File(legacy, "x.wav").writeBytes(ByteArray(3))
        val src = source(MemoryAssets(allFiles()), legacy = listOf(legacy))
        src.checkAvailability(listOf(day))
        assertFalse(legacy.exists())
        src.checkAvailability(listOf(day))
        assertEquals(1, logs.count { it.startsWith("이전 캐시 삭제") })
    }

    @Test fun check_reports_missing_files_per_content() = runTest {
        val files = allFiles().apply { remove("audio/day1/c2.ko.wav") }
        val other = Content("day2", "2일차", listOf(Chapter("x", "w", "", "뜻")))
        val r = source(MemoryAssets(files)).checkAvailability(listOf(day, other))
        assertTrue(logs.toString(), logs.any { it.startsWith("음원 문제 day1: audio/day1/c2.ko.wav") })
        assertEquals(
            AudioAvailability.Checked(
                mapOf(
                    "day1" to listOf("audio/day1/c2.ko.wav"),
                    "day2" to listOf("audio/day2/x.en.wav", "audio/day2/x.ko.wav"),
                ),
            ),
            r,
        )
    }

    @Test fun check_list_failure_is_error() = runTest {
        val assets = MemoryAssets(allFiles()).apply { failList = true }
        assertEquals(AudioAvailability.Error("list failed"), source(assets).checkAvailability(listOf(day)))
    }

    @Test fun merges_in_reading_order_with_silence() = runTest {
        val r = source(MemoryAssets(allFiles())).chapterAudio("day1", ch1)
        val file = (r as ChapterAudioResult.Ready).wavFile
        val pcm = WavCodec.decode(file.readBytes())
        assertEquals(rate, pcm.sampleRate)
        assertEquals(1, pcm.channels)
        // 영어 50 → 무음 600 → 한국어 80 → 무음 600 → 영어 50
        val expected = ShortArray(50) { 1000 } + ShortArray(600) + ShortArray(80) { 2000 } + ShortArray(600) + ShortArray(50) { 1000 }
        assertArrayEquals(expected, pcm.samples)
    }

    @Test fun converts_korean_piece_to_english_format() = runTest {
        val files = allFiles().apply { put("audio/day1/c1.ko.wav", wav(2000, 160, sampleRate = 2000, channels = 2)) }
        val file = (source(MemoryAssets(files)).chapterAudio("day1", ch1) as ChapterAudioResult.Ready).wavFile
        val pcm = WavCodec.decode(file.readBytes())
        assertEquals(rate, pcm.sampleRate)
        assertEquals(1, pcm.channels)
        assertEquals(50 + 600 + 80 + 600 + 50, pcm.frames)
    }

    @Test fun missing_file_at_play_time_fails_without_cache() = runTest {
        val files = allFiles().apply { remove("audio/day1/c1.ko.wav") }
        val r = source(MemoryAssets(files)).chapterAudio("day1", ch1)
        assertTrue(r is ChapterAudioResult.Failed)
        assertTrue((r as ChapterAudioResult.Failed).message, r.message.contains("음원 없음: audio/day1/c1.ko.wav"))
        assertTrue(logs.toString(), logs.any { it.startsWith("챕터 음원 준비 실패 day1/c1") })
        assertTrue(tmp.root.walk().none { it.isFile && it.extension == "wav" })
    }

    @Test fun broken_wav_fails() = runTest {
        val files = allFiles().apply { put("audio/day1/c1.en.wav", ByteArray(10)) }
        assertTrue(source(MemoryAssets(files)).chapterAudio("day1", ch1) is ChapterAudioResult.Failed)
    }

    @Test fun cache_is_reused_and_replaced_when_audio_changes() = runTest {
        val files = allFiles()
        val src = source(MemoryAssets(files))
        val first = (src.chapterAudio("day1", ch1) as ChapterAudioResult.Ready).wavFile
        val stamp = first.lastModified()
        val again = (src.chapterAudio("day1", ch1) as ChapterAudioResult.Ready).wavFile
        assertEquals(first, again)
        assertEquals(stamp, again.lastModified())
        assertEquals("병합은 한 번", 1, logs.count { it.startsWith("챕터 음원 병합 day1/c1") })

        // 음원을 다시 만들면(바이트가 바뀌면) 새 캐시 파일을 쓰고 이전 파일은 지운다
        files["audio/day1/c1.ko.wav"] = wav(2500, 80)
        val changed = (src.chapterAudio("day1", ch1) as ChapterAudioResult.Ready).wavFile
        assertNotEquals(first, changed)
        assertTrue(changed.isFile)
        assertFalse(first.exists())
    }

    @Test fun silence_setting_changes_cache_file() = runTest {
        val files = allFiles()
        val a = (source(MemoryAssets(files)).chapterAudio("day1", ch1) as ChapterAudioResult.Ready).wavFile
        val b = (source(MemoryAssets(files), LearningConfig(chapterSilenceMs = 300)).chapterAudio("day1", ch1) as ChapterAudioResult.Ready).wavFile
        assertNotEquals(a, b)
        assertEquals(50 + 300 + 80 + 300 + 50, WavCodec.decode(b.readBytes()).frames)
    }

    @Test fun concurrent_requests_merge_once() = runTest {
        val src = source(MemoryAssets(allFiles()), io = StandardTestDispatcher(testScheduler))
        val a = async { src.chapterAudio("day1", ch1) }
        val b = async { src.chapterAudio("day1", ch1) }
        val ra = a.await() as ChapterAudioResult.Ready
        val rb = b.await() as ChapterAudioResult.Ready
        assertEquals(ra.wavFile, rb.wavFile)
        assertEquals("병합은 한 번", 1, logs.count { it.startsWith("챕터 음원 병합 day1/c1") })
    }

    @Test fun leftover_temp_file_of_chapter_is_removed_on_write() = runTest {
        val files = allFiles()
        val src = source(MemoryAssets(files))
        val first = (src.chapterAudio("day1", ch1) as ChapterAudioResult.Ready).wavFile
        val leftover = File(first.parentFile, "${first.name}.dead.tmp").apply { writeBytes(ByteArray(5)) }
        val otherChapter = File(first.parentFile, "c2-00000000-0123456789abcdef.wav.x.tmp").apply { writeBytes(ByteArray(5)) }
        files["audio/day1/c1.en.wav"] = wav(1100, 50)
        src.chapterAudio("day1", ch1)
        assertFalse(leftover.exists())
        assertTrue("다른 챕터 임시 파일은 그대로", otherChapter.exists())
    }

    @Test fun prefetch_merges_given_chapters_and_counts_failures() = runTest {
        val files = allFiles().apply { remove("audio/day1/c2.en.wav") }
        source(MemoryAssets(files)).prefetch("day1", listOf(ch2, ch1))
        assertTrue(logs.toString(), logs.contains("prefetch 끝 day1 성공 1/실패 1"))
        val merged = logs.filter { it.startsWith("챕터 음원 병합") || it.startsWith("챕터 음원 준비 실패") }
        assertTrue("넘긴 순서대로(c2 먼저)", merged[0].contains("day1/c2"))
    }
}
