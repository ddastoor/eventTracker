package io.github.ddastoor.eventtracker.data

import io.github.ddastoor.eventtracker.data.SheetSchema.EVENTS_SHEET
import io.github.ddastoor.eventtracker.data.SheetSchema.SHARDS_SHEET
import io.github.ddastoor.eventtracker.data.SheetSchema.TYPES_SHEET
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetsBackendTest {
    private val google = FakeGoogle()
    private val haircut = EventType(id = "t1", name = "Haircut", color = 0xFF1E5AA8, emoji = "✂️", isPreset = true, createdAt = 100)
    private val fridge = EventType(id = "t2", name = "New fridge", isPreset = false, createdAt = 200)

    private fun event(n: Int, type: String = "t1", attachment: Attachment? = null) =
        Event(id = "e$n", timestamp = 1_700_000_000L + n * 86_400L, typeId = type, attachment = attachment)

    private fun mainId() = google.files.values.single { it.props[GoogleApi.APP_KEY] == "data" }.id

    @Test
    fun `first sync creates folder, spreadsheet, tabs and headers`() = runBlocking {
        val backend = SheetsBackend(google)
        val snap = backend.fetch(Snapshot())

        assertEquals(Snapshot(shardVersions = snap.shardVersions), snap)
        val tags = google.files.values.map { it.props[GoogleApi.APP_KEY] }
        assertEquals(listOf("root", "files", "data"), tags)
        val main = mainId()
        assertEquals(listOf(TYPES_SHEET, EVENTS_SHEET, SHARDS_SHEET), google.spreadsheets.getValue(main).map { it.title })
        assertEquals(SheetSchema.TYPE_HEADERS, google.dump(main, TYPES_SHEET)[0])
        assertEquals(SheetSchema.EVENT_HEADERS, google.dump(main, EVENTS_SHEET)[0])
        assertEquals(listOf("1", main), google.dump(main, SHARDS_SHEET)[1].take(2))

        // A second install finds the same files instead of creating new ones.
        SheetsBackend(google).fetch(Snapshot())
        assertEquals(3, google.files.size)
    }

    @Test
    fun `writes, updates and deletes rows`() = runBlocking {
        val backend = SheetsBackend(google)
        backend.apply(Op.UpsertType(haircut))
        backend.apply(Op.UpsertType(fridge))
        backend.apply(Op.UpsertEvent(event(1)))
        val receipt = backend.upload(kotlin.io.path.createTempFile().toFile().apply { writeText("pdf") }, "receipt.pdf", "application/pdf")
        backend.apply(Op.UpsertEvent(event(2, "t2", Attachment(receipt.id, "receipt.pdf", "application/pdf"))))
        backend.apply(Op.UpsertType(haircut.copy(name = "Hair cut")))

        var snap = backend.fetch(Snapshot())
        assertEquals(listOf("Hair cut", "New fridge"), snap.types.map { it.name })
        assertEquals(haircut.copy(name = "Hair cut"), snap.types[0])
        assertEquals(listOf("e1", "e2"), snap.events.map { it.id })
        assertEquals(receipt.id, snap.events[1].attachment?.fileId)
        assertEquals("https://drive.google.com/file/d/${receipt.id}/view", google.dump(mainId(), EVENTS_SHEET)[2][4])

        // Replacing the attachment trashes the old Drive file.
        val e2 = snap.events[1]
        backend.apply(Op.UpsertEvent(e2.copy(note = "warranty 2y", attachment = null)))
        assertTrue(google.files.getValue(receipt.id).trashed)

        backend.apply(Op.DeleteEvent("e1", snap.events[0].shard))
        snap = backend.fetch(snap)
        assertEquals(listOf("e2"), snap.events.map { it.id })
        assertEquals("warranty 2y", snap.events[0].note)
        assertEquals(null, snap.events[0].attachment)
    }

    @Test
    fun `retrying an append does not duplicate the row`() = runBlocking {
        val backend = SheetsBackend(google)
        backend.apply(Op.UpsertEvent(event(1)))
        backend.apply(Op.UpsertEvent(event(1)))
        assertEquals(1, backend.fetch(Snapshot()).events.size)
    }

    @Test
    fun `rolls over to a new spreadsheet when a shard is full and reads across the chain`() = runBlocking {
        val backend = SheetsBackend(google, maxRowsPerShard = 3)
        backend.apply(Op.UpsertType(haircut))
        (1..7).forEach { backend.apply(Op.UpsertEvent(event(it))) }

        val snap = backend.fetch(Snapshot())
        assertEquals((1..7).map { "e$it" }, snap.events.map { it.id })
        val shards = backend.shards
        assertEquals(listOf(1, 2, 3), shards.map { it.seq })
        assertEquals(mainId(), shards[0].spreadsheetId)
        // 3 + 3 + 1 rows, each event tagged with the spreadsheet it lives in.
        assertEquals(listOf(3, 3, 1), shards.map { s -> snap.events.count { it.shard == s.spreadsheetId } })
        assertEquals(4, google.dump(shards[1].spreadsheetId, EVENTS_SHEET).size) // header + 3
        assertEquals(listOf("1", "2", "3"), google.dump(mainId(), SHARDS_SHEET).drop(1).map { it[0] })
        assertEquals(
            listOf("Event Tracker Events 2", "Event Tracker Events 3"),
            google.files.values.filter { it.props[GoogleApi.APP_KEY] == "shard" }.map { it.name },
        )

        // Edits stay in the event's own shard; deletes without a hint walk the chain.
        val e1 = snap.events.first { it.id == "e1" }
        backend.apply(Op.UpsertEvent(e1.copy(note = "edited")))
        backend.apply(Op.DeleteEvent("e5"))
        val after = backend.fetch(snap)
        assertEquals("edited", after.events.first { it.id == "e1" }.note)
        assertEquals(shards[0].spreadsheetId, after.events.first { it.id == "e1" }.shard)
        assertFalse(after.events.any { it.id == "e5" })
        assertEquals(6, after.events.size)
    }

    @Test
    fun `unchanged shards are not downloaded again`() = runBlocking {
        val backend = SheetsBackend(google, maxRowsPerShard = 2)
        backend.apply(Op.UpsertType(haircut))
        (1..5).forEach { backend.apply(Op.UpsertEvent(event(it))) }
        val first = backend.fetch(Snapshot())
        val (s1, s2, s3) = backend.shards.map { it.spreadsheetId }

        val readsBefore = google.reads.toMap()
        backend.apply(Op.UpsertEvent(event(6))) // goes to shard 3
        val second = backend.fetch(first)

        assertEquals((1..6).map { "e$it" }, second.events.map { it.id }.sorted())
        assertEquals("shard 2 untouched", readsBefore[s2], google.reads[s2])
        assertNotEquals(readsBefore[s3], google.reads[s3])
        assertTrue((google.reads[s1] ?: 0) > (readsBefore[s1] ?: 0)) // main file: types are always read
    }

    @Test
    fun `changes made directly in the sheet are picked up`() = runBlocking {
        val backend = SheetsBackend(google, maxRowsPerShard = 2)
        backend.apply(Op.UpsertType(haircut))
        (1..3).forEach { backend.apply(Op.UpsertEvent(event(it))) }
        val first = backend.fetch(Snapshot())
        val shard2 = backend.shards[1].spreadsheetId

        // Someone types a row into shard 2 by hand, naming the type instead of using its id.
        google.appendValues(shard2, "Events!A1:H1", listOf(listOf("manual", 1_800_000_000L, "haircut").map { kotlinx.serialization.json.JsonPrimitive(it.toString()) }))
        val second = SheetsBackend(google, maxRowsPerShard = 2).fetch(first)
        val manual = second.events.single { it.id == "manual" }
        assertEquals("t1", manual.typeId)
        assertEquals(1_800_000_000L, manual.timestamp)
    }

    @Test
    fun `deleting a type removes its events from every shard and trashes their files`() = runBlocking {
        val backend = SheetsBackend(google, maxRowsPerShard = 2)
        backend.apply(Op.UpsertType(haircut))
        backend.apply(Op.UpsertType(fridge))
        val file = backend.upload(kotlin.io.path.createTempFile().toFile(), "r.jpg", "image/jpeg")
        backend.apply(Op.UpsertEvent(event(1, "t2", Attachment(file.id, "r.jpg", "image/jpeg"))))
        backend.apply(Op.UpsertEvent(event(2)))
        backend.apply(Op.UpsertEvent(event(3, "t2")))
        backend.apply(Op.UpsertEvent(event(4)))
        backend.apply(Op.UpsertEvent(event(5, "t2")))

        backend.apply(Op.DeleteType("t2"))
        val snap = backend.fetch(Snapshot())
        assertEquals(listOf(haircut), snap.types)
        assertEquals(listOf("e2", "e4"), snap.events.map { it.id })
        assertTrue(google.files.getValue(file.id).trashed)
    }

    @Test
    fun `a shard file that was never linked is repaired into the chain`() = runBlocking {
        SheetsBackend(google).fetch(Snapshot())
        // Simulate the app being killed right after creating a shard file.
        val orphan = google.createFile("Event Tracker Events 2", SheetsBackend.SHEET_MIME, null, mapOf(GoogleApi.APP_KEY to "shard"))

        val backend = SheetsBackend(google)
        backend.apply(Op.UpsertType(haircut))
        backend.apply(Op.UpsertEvent(event(1)))
        backend.fetch(Snapshot())
        assertEquals(listOf(mainId(), orphan.id), backend.shards.map { it.spreadsheetId })
        // New events go to the newest shard, whose Events tab was created on demand.
        assertEquals(listOf("e1"), google.dump(orphan.id, EVENTS_SHEET).drop(1).map { it[0] })
    }
}
