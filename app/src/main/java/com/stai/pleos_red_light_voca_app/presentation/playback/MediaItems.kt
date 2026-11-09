package com.stai.pleos_red_light_voca_app.presentation.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.stai.pleos_red_light_voca_app.core.entity.Chapter
import com.stai.pleos_red_light_voca_app.core.entity.Content

/** 브라우즈 트리·세션 표시용 MediaItem. 하위 카테고리는 오디오북(챕터 단위)으로 전달한다. */
object MediaItems {
    fun root(): MediaItem = MediaItem.Builder()
        .setMediaId(MediaIds.ROOT)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("신호대기 단어")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
                .build(),
        )
        .build()

    fun content(content: Content): MediaItem = MediaItem.Builder()
        .setMediaId(MediaIds.content(content.contentId))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(content.title)
                .setTotalTrackCount(content.chapters.size)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                .build(),
        )
        .build()

    /**
     * 챕터 아이템. [uri]가 null이면 표시용(아직 음원 병합 전). [tag]는 재생 중 아이템과 PlayTicket을 맞춰 보기 위해 쓴다.
     */
    fun chapter(content: Content, chapter: Chapter, uri: Uri? = null, tag: Any? = null): MediaItem {
        val index = content.indexOf(chapter.chapterId)
        return MediaItem.Builder()
            .setMediaId(MediaIds.chapter(content.contentId, chapter.chapterId))
            .apply { if (uri != null) setUri(uri).setTag(tag) }
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(chapter.word)
                    .setArtist(chapter.meaningKo)
                    .setSubtitle(chapter.meaningKo)
                    .setAlbumTitle(content.title)
                    .setTrackNumber(if (index >= 0) index + 1 else null)
                    .setTotalTrackCount(content.chapters.size)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                    .build(),
            )
            .build()
    }
}
