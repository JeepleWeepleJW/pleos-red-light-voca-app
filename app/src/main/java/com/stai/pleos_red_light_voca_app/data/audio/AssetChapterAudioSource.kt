package com.stai.pleos_red_light_voca_app.data.audio

import com.stai.pleos_red_light_voca_app.core.common.LearningConfig
import com.stai.pleos_red_light_voca_app.core.entity.Chapter
import com.stai.pleos_red_light_voca_app.core.entity.Content
import com.stai.pleos_red_light_voca_app.domain.source.AudioAvailability
import com.stai.pleos_red_light_voca_app.domain.source.ChapterAudioSource
import com.stai.pleos_red_light_voca_app.domain.speech.ChapterAudioResult
import com.stai.pleos_red_light_voca_app.domain.speech.ChapterScript
import com.stai.pleos_red_light_voca_app.domain.speech.ChapterSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 앱에 넣은 단어 음원([AudioAssetPaths])을 챕터 읽기 순서([ChapterScript]: 영어 → 무음 → 한국어 → 무음 → 영어)대로
 * WAV 1개로 병합해 [cacheRoot]에 캐시한다. 챕터 1개 = WAV 1개 = 미디어 아이템 1개.
 *
 * @param legacyCacheDirs 이전 구현이 쓰던 캐시 폴더. 첫 음원 확인 때 한 번 지운다.
 */
class AssetChapterAudioSource(
    private val assets: AudioAssets,
    private val cacheRoot: File,
    private val config: LearningConfig = LearningConfig(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val legacyCacheDirs: List<File> = emptyList(),
    private val log: (String) -> Unit = { Timber.i(it) },
) : ChapterAudioSource {

    /** 같은 캐시 파일을 동시에 병합하지 않도록 진행 중인 병합을 키(파일 경로)별로 둔다. */
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    @Volatile private var legacyCleaned = false

    /**
     * 음원마다 (1) 파일이 있는지 (2) manifest의 텍스트 지문이 지금 catalog 문구와 같은지 (3) WAV로 읽혀 소리 길이가 0보다 큰지 본다.
     * 하나라도 아니면 그 음원을 `<경로> (<이유>)`로(파일이 없으면 경로만) 그 콘텐츠의 준비되지 않은 목록에 넣는다.
     */
    override suspend fun checkAvailability(contents: List<Content>): AudioAvailability = withContext(io) {
        cleanLegacyCache()
        try {
            val manifest = readManifest()
            if (manifest == null) log("음원 지문 파일 없음: ${AudioAssetPaths.MANIFEST}")
            val missing = LinkedHashMap<String, List<String>>()
            var total = 0
            for (c in contents) {
                val present = assets.list(AudioAssetPaths.contentDir(c.contentId)).toSet()
                val need = c.chapters.flatMap { AudioAssetPaths.required(c.contentId, it, config) }
                total += need.size
                val lack = need.mapNotNull { problem(it, present, manifest) }
                if (lack.isNotEmpty()) missing[c.contentId] = lack
            }
            log("음원 확인: 콘텐츠 ${contents.size}개, 필요 파일 ${total}개, 문제 ${missing.values.sumOf { it.size }}개")
            missing.forEach { (id, files) -> log("음원 문제 $id: ${files.joinToString()}") }
            AudioAvailability.Checked(missing)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            log("음원 확인 실패: $msg")
            AudioAvailability.Error(msg)
        }
    }

    private fun readManifest(): Map<String, String>? = try {
        AudioAssetPaths.parseManifest(assets.read(AudioAssetPaths.MANIFEST).toString(Charsets.UTF_8))
    } catch (_: FileNotFoundException) {
        null
    }

    /** 문제가 없으면 null. */
    private fun problem(e: AudioAssetPaths.Entry, present: Set<String>, manifest: Map<String, String>?): String? {
        if (e.path.substringAfterLast('/') !in present) return e.path
        val hash = manifest?.get(e.path) ?: return "${e.path} (지문 없음)"
        if (hash != AudioAssetPaths.textHash(e.text)) return "${e.path} (catalog 문구와 다름)"
        val pcm = try {
            WavCodec.decode(assets.read(e.path))
        } catch (x: FileNotFoundException) {
            return e.path
        } catch (x: IllegalArgumentException) {
            return "${e.path} (WAV 오류: ${x.message})"
        }
        if (pcm.frames == 0) return "${e.path} (소리 없음)"
        return null
    }

    private fun cleanLegacyCache() {
        if (legacyCleaned) return
        legacyCleaned = true
        legacyCacheDirs.filter { it.exists() }.forEach { dir ->
            val ok = dir.deleteRecursively()
            log("이전 캐시 삭제 ${dir.name}: ${if (ok) "성공" else "일부 실패"}")
        }
    }

    override suspend fun chapterAudio(contentId: String, chapter: Chapter): ChapterAudioResult = try {
        ChapterAudioResult.Ready(prepare(contentId, chapter))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        val msg = "챕터 음원 준비 실패 $contentId/${chapter.chapterId}: ${e.message ?: e.javaClass.simpleName}"
        log(msg)
        ChapterAudioResult.Failed(msg)
    }

    private suspend fun prepare(contentId: String, chapter: Chapter): File {
        while (true) {
            val (pieces, file) = withContext(io) {
                val pieces = readPieces(contentId, chapter)
                pieces to ChapterAudioCacheKey.file(cacheRoot, contentId, chapter.chapterId, pieces)
            }
            if (withContext(io) { file.isFile && file.length() > WavCodec.HEADER_SIZE }) return file

            val mine = CompletableDeferred<Unit>()
            val other = inFlight.putIfAbsent(file.path, mine)
            if (other != null) {
                // 같은 파일을 병합 중이면 끝나기를 기다렸다가 캐시 확인부터 다시 한다(실패·취소였으면 직접 병합).
                try {
                    other.await()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                } catch (_: Throwable) {
                }
                continue
            }
            try {
                withContext(io) { write(file, ChapterAudioAssembler.assemble(pieces), contentId, chapter.chapterId) }
                mine.complete(Unit)
            } catch (e: Throwable) {
                mine.completeExceptionally(e)
                throw e
            } finally {
                inFlight.remove(file.path, mine)
            }
            log("챕터 음원 병합 $contentId/${chapter.chapterId} -> ${file.name}")
            return file
        }
    }

    /** 읽기 순서대로 조각을 만든다. 같은 음원은 한 번만 읽는다. */
    private fun readPieces(contentId: String, chapter: Chapter): List<AudioPiece> {
        val read = HashMap<String, ByteArray>()
        return ChapterScript.of(chapter, config).map { seg ->
            when (seg) {
                is ChapterSegment.Silence -> AudioPiece.Silence(seg.durationMs)
                is ChapterSegment.Speech -> {
                    val path = AudioAssetPaths.speechPath(contentId, chapter.chapterId, seg.languageTag, config)
                    AudioPiece.Wav(read.getOrPut(path) { readAsset(path) })
                }
            }
        }
    }

    private fun readAsset(path: String): ByteArray = try {
        assets.read(path)
    } catch (e: FileNotFoundException) {
        throw IllegalStateException("음원 없음: $path", e)
    }

    /** 임시 파일에 쓴 뒤 바꿔 넣고, 같은 챕터의 이전 캐시(음원·설정이 바뀌기 전)와 남은 임시 파일은 지운다. */
    private fun write(target: File, wav: ByteArray, contentId: String, chapterId: String) {
        val dir = target.parentFile ?: throw IllegalStateException("캐시 폴더 없음")
        dir.mkdirs()
        val tmp = File(dir, "${target.name}.${UUID.randomUUID()}.tmp")
        try {
            tmp.writeBytes(wav)
            if (!tmp.renameTo(target)) throw IllegalStateException("캐시 파일 교체 실패")
        } finally {
            tmp.delete()
        }
        dir.listFiles()?.forEach { f ->
            val stale = f.name != target.name &&
                (ChapterAudioCacheKey.isCacheOf(f.name, contentId, chapterId) || ChapterAudioCacheKey.isTempOf(f.name, contentId, chapterId))
            if (stale) f.delete()
        }
    }

    /** [chapters] 순서 그대로 병합한다(호출자가 현재 챕터부터 넘긴다). */
    override suspend fun prefetch(contentId: String, chapters: List<Chapter>) {
        log("prefetch 시작 $contentId 챕터 ${chapters.size}개")
        var ok = 0
        var fail = 0
        for (c in chapters) {
            when (chapterAudio(contentId, c)) {
                is ChapterAudioResult.Ready -> ok++
                is ChapterAudioResult.Failed -> fail++ // 재생 시점에 다시 시도
            }
        }
        log("prefetch 끝 $contentId 성공 $ok/실패 $fail")
    }
}
