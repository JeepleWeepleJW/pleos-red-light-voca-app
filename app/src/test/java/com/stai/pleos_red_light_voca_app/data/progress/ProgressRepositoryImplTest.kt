package com.stai.pleos_red_light_voca_app.data.progress

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertThrows
import java.io.IOException
import com.stai.pleos_red_light_voca_app.core.entity.Progress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProgressRepositoryImplTest {
    @get:Rule val tmp = TemporaryFolder()

    private val job = Job()
    private val scopes = mutableListOf<Job>()
    private lateinit var file: File

    private fun open(): Pair<ProgressRepositoryImpl, Job> {
        val j = Job()
        scopes += j
        val ds = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + j)) { file }
        return ProgressRepositoryImpl(ds) to j
    }

    private fun repo(): ProgressRepositoryImpl {
        file = File(tmp.root, "p.preferences_pb") // 존재하지 않는 경로여야 한다
        return open().first
    }

    @After fun tearDown() { scopes.forEach { it.cancel() }; job.cancel() }

    private class FailingDataStore(private val error: Throwable) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw error }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw UnsupportedOperationException()
    }

    @Test fun loadReturnsEmptyProgressOnIOException() = runBlocking {
        val r = ProgressRepositoryImpl(FailingDataStore(IOException("corrupt")))
        assertEquals(Progress(), r.load())
    }

    @Test fun loadPropagatesNonIOException() {
        val r = ProgressRepositoryImpl(FailingDataStore(IllegalStateException("boom")))
        val e = assertThrows(IllegalStateException::class.java) { runBlocking { r.load() } }
        assertEquals("boom", e.message)
    }

    @Test fun emptyLoad() = runBlocking {
        assertEquals(Progress(), repo().load())
    }

    @Test fun saveCompletedThenLoad() = runBlocking {
        val r = repo()
        r.saveCompleted("a", "a-01")
        assertEquals(mapOf("a" to "a-01"), r.load().lastCompletedChapterIds)
        assertNull(r.load().activeContentId)
    }

    @Test fun overwriteSameContent() = runBlocking {
        val r = repo()
        r.saveCompleted("a", "a-01")
        r.saveCompleted("a", "a-02")
        assertEquals(mapOf("a" to "a-02"), r.load().lastCompletedChapterIds)
    }

    @Test fun multipleContents() = runBlocking {
        val r = repo()
        r.saveCompleted("a", "a-03")
        r.saveCompleted("b.c", "b-01") // 점이 들어간 ID도 접두어만 제거되어 복원
        assertEquals(mapOf("a" to "a-03", "b.c" to "b-01"), r.load().lastCompletedChapterIds)
    }

    @Test fun clearRemovesOnlyThatContent() = runBlocking {
        val r = repo()
        r.saveCompleted("a", "a-03")
        r.saveCompleted("b", "b-01")
        r.saveActiveContent("a")
        r.clear("a")
        val p = r.load()
        assertEquals(mapOf("b" to "b-01"), p.lastCompletedChapterIds)
        assertEquals("a", p.activeContentId)
        r.clear("zzz") // 없는 콘텐츠도 오류 없음
    }

    @Test fun restartContentClearsAndSetsActiveAtOnce() = runBlocking {
        val r = repo()
        r.saveCompleted("a", "a-03")
        r.saveCompleted("b", "b-01")
        r.saveActiveContent("b")
        r.restartContent("a")
        assertEquals(Progress(mapOf("b" to "b-01"), "a"), r.load())
    }

    @Test fun saveActiveContent() = runBlocking {
        val r = repo()
        r.saveActiveContent("a")
        r.saveActiveContent("b")
        assertEquals("b", r.load().activeContentId)
        assertEquals(emptyMap<String, String>(), r.load().lastCompletedChapterIds)
    }

    @Test fun restoredAfterRestart() = runBlocking {
        file = File(tmp.root, "p.preferences_pb")
        val (first, firstJob) = open()
        first.saveCompleted("a", "a-05")
        first.saveActiveContent("a")
        firstJob.cancelAndJoin()

        val (second, _) = open()
        assertEquals(Progress(mapOf("a" to "a-05"), "a"), second.load())
    }
}
