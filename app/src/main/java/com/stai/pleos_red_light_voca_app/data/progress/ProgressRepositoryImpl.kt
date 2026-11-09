package com.stai.pleos_red_light_voca_app.data.progress

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.stai.pleos_red_light_voca_app.core.entity.Progress
import com.stai.pleos_red_light_voca_app.domain.repository.ProgressRepository
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.IOException
import timber.log.Timber

/**
 * DataStore(Preferences) 기반 진도 저장소. 저장하는 값은 contentId와 chapterId뿐이다.
 * - `completed.<contentId>` = 마지막 완료 chapterId
 * - `activeContentId` = 마지막 학습 콘텐츠 ID
 */
class ProgressRepositoryImpl(private val dataStore: DataStore<Preferences>) : ProgressRepository {

    override suspend fun load(): Progress {
        val prefs = dataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .first()
        return prefs.toProgress()
    }

    override suspend fun saveCompleted(contentId: String, chapterId: String) {
        dataStore.edit { it[completedKey(contentId)] = chapterId }
    }

    override suspend fun clear(contentId: String) {
        dataStore.edit { it.remove(completedKey(contentId)) }
    }

    override suspend fun restartContent(contentId: String) {
        dataStore.edit {
            it.remove(completedKey(contentId))
            it[ACTIVE_KEY] = contentId
        }
    }

    override suspend fun saveActiveContent(contentId: String) {
        dataStore.edit { it[ACTIVE_KEY] = contentId }
    }

    companion object {
        internal const val COMPLETED_PREFIX = "completed."
        internal val ACTIVE_KEY = stringPreferencesKey("activeContentId")
        private fun completedKey(contentId: String) = stringPreferencesKey(COMPLETED_PREFIX + contentId)

        @Volatile private var instance: ProgressRepositoryImpl? = null

        /**
         * 같은 파일에 활성 DataStore가 둘이면 예외가 나므로 프로세스당 하나만 만든다.
         * 이 함수가 단일 인스턴스(더블 체크 락 싱글턴)를 보장하므로 여러 번 호출해도 안전하다.
         * [log]는 진도 파일 손상 시 경고 로그(기본은 Timber 경고).
         */
        fun create(context: Context, log: (String) -> Unit = { Timber.w(it) }): ProgressRepositoryImpl =
            instance ?: synchronized(this) {
                instance ?: ProgressRepositoryImpl(
                    PreferenceDataStoreFactory.create(
                        corruptionHandler = ReplaceFileCorruptionHandler { e ->
                            log("progress file corrupted, reset to empty: ${e.message}")
                            emptyPreferences()
                        },
                    ) {
                        context.applicationContext.preferencesDataStoreFile("learning_progress")
                    },
                ).also { instance = it }
            }
    }
}

private fun Preferences.toProgress(): Progress {
    val completed = LinkedHashMap<String, String>()
    for ((key, value) in asMap()) {
        if (key.name.startsWith(ProgressRepositoryImpl.COMPLETED_PREFIX) && value is String) {
            completed[key.name.removePrefix(ProgressRepositoryImpl.COMPLETED_PREFIX)] = value
        }
    }
    return Progress(completed, this[ProgressRepositoryImpl.ACTIVE_KEY])
}
