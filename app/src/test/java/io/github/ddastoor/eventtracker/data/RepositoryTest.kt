package io.github.ddastoor.eventtracker.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class RepositoryTest {
    private val dir: File = Files.createTempDirectory("repo").toFile()
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val attachments = AttachmentStore(
        File(dir, "pending"),
        File(dir, "cache"),
        GoogleApi(object : TokenProvider {
            override suspend fun token() = error("not used")
            override suspend fun invalidate(token: String) = Unit
        }),
    )

    /** Applies ops to an in-memory snapshot; can be switched offline. */
    private class FakeBackend : RemoteBackend {
        var offline = false
        var data = Snapshot()
        val applied = mutableListOf<Op>()
        val uploads = mutableListOf<String>()

        override suspend fun fetch(previous: Snapshot): Snapshot {
            if (offline) throw IOException("offline")
            return data
        }

        override suspend fun apply(op: Op) {
            if (offline) throw IOException("offline")
            applied += op
            data = data.apply(op)
        }

        override suspend fun upload(file: File, name: String, mimeType: String): DriveFile {
            if (offline) throw IOException("offline")
            uploads += file.readText()
            return DriveFile("drive-${uploads.size}", name, mimeType)
        }
    }

    private fun repo() = Repository(LocalStore(File(dir, "data")), attachments, scope)

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private val type = EventType("t1", "Haircut")
    private fun event(n: Int) = Event("e$n", 1_700_000_000L + n, "t1")

    @Test
    fun `changes show immediately, queue while offline and sync in order later`() = runBlocking {
        val backend = FakeBackend().apply { offline = true }
        val repo = repo().also { it.backend = backend }

        repo.submit(Op.UpsertType(type))
        repo.submit(Op.UpsertEvent(event(1)))
        repo.submit(Op.UpsertEvent(event(2)))
        repo.submit(Op.DeleteEvent("e1"))

        assertEquals(listOf("e2"), repo.snapshot.value.events.map { it.id })
        assertEquals(4, repo.pendingOps.value.size)
        assertNotNull(repo.status.value.error)

        // The queue survives an app restart.
        assertEquals(4, LocalStore(File(dir, "data")).loadOps().size)

        backend.offline = false
        repo.syncNow()
        assertEquals(listOf("upsertType", "upsertEvent", "upsertEvent", "deleteEvent"), backend.applied.map { it.kind() })
        assertTrue(repo.pendingOps.value.isEmpty())
        assertNull(repo.status.value.error)
        assertNotNull(repo.status.value.lastSuccess)
        assertEquals(listOf("e2"), repo.snapshot.value.events.map { it.id })
        assertEquals(listOf("e2"), LocalStore(File(dir, "data")).loadSnapshot().events.map { it.id })
    }

    @Test
    fun `new files are uploaded once and the event points to the Drive copy`() = runBlocking {
        val backend = FakeBackend().apply { offline = true }
        val repo = repo().also { it.backend = backend }
        val (local, file) = attachments.newPhotoTarget()
        file.writeText("jpeg bytes")

        repo.submit(Op.UpsertType(type))
        repo.submit(Op.UpsertEvent(event(1).copy(attachment = local)))
        repo.submit(Op.UpsertEvent(event(1).copy(note = "edited offline", attachment = local)))

        backend.offline = false
        repo.syncNow()

        assertEquals(listOf("jpeg bytes"), backend.uploads)
        val synced = repo.snapshot.value.events.single()
        assertEquals("drive-1", synced.attachment?.fileId)
        assertEquals("edited offline", synced.note)
        assertFalse(file.exists())
        assertEquals("jpeg bytes", attachments.open(synced.attachment!!).readText()) // served from cache
    }

    @Test
    fun `discarding drops queued changes`() = runBlocking {
        val backend = FakeBackend().apply { offline = true }
        val repo = repo().also { it.backend = backend }
        repo.submit(Op.UpsertType(type))
        repo.discardPending()
        assertTrue(repo.pendingOps.value.isEmpty())
        assertTrue(repo.snapshot.value.types.isEmpty())
    }

    private fun Op.kind() = when (this) {
        is Op.UpsertType -> "upsertType"
        is Op.DeleteType -> "deleteType"
        is Op.UpsertEvent -> "upsertEvent"
        is Op.DeleteEvent -> "deleteEvent"
    }
}
