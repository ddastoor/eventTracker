package io.github.ddastoor.eventtracker.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SheetSchemaTest {
    @Test
    fun `types round trip`() {
        val t = EventType("id1", "Haircut", 0xFFD3455B, "✂️", false, 1234)
        assertEquals(t, SheetSchema.rowToType(SheetSchema.typeToRow(t), 0))
    }

    @Test
    fun `events round trip including attachment`() {
        val e = Event("e1", 1_700_000_000, "t1", "note", Attachment("abcdefghijk123", "r.pdf", "application/pdf"))
        val row = SheetSchema.eventToRow(e)
        assertEquals("https://drive.google.com/file/d/abcdefghijk123/view", (row[4] as JsonPrimitive).content)
        assertEquals(e, SheetSchema.rowToEvent(row, setOf("t1"), emptyMap()))
    }

    @Test
    fun `tolerates hand-edited rows`() {
        // Timestamps as doubles, type by name, attachment only as a link, trailing cells missing.
        val row = listOf(JsonPrimitive("e2"), JsonPrimitive(1.7e9), JsonPrimitive("Haircut"), JsonPrimitive(""),
            JsonPrimitive("https://drive.google.com/file/d/XYZ1234567890/view?usp=sharing"))
        val e = SheetSchema.rowToEvent(row, setOf("t1"), mapOf("haircut" to "t1"))!!
        assertEquals(1_700_000_000L, e.timestamp)
        assertEquals("t1", e.typeId)
        assertEquals("XYZ1234567890", e.attachment?.fileId)
        assertNull(SheetSchema.rowToEvent(listOf(JsonPrimitive(""), JsonPrimitive(1)), emptySet(), emptyMap()))
        assertNull(SheetSchema.rowToEvent(listOf(JsonPrimitive("x"), JsonPrimitive("not a time"), JsonPrimitive("t")), emptySet(), emptyMap()))
    }

    @Test
    fun `type defaults when cells are blank`() {
        val t = SheetSchema.rowToType(listOf(JsonPrimitive("a"), JsonPrimitive("Gym")), 1)!!
        assertEquals(EventType.DEFAULT_COLORS[1], t.color)
        assertEquals(true, t.isPreset)
    }

    @Test
    fun `colors`() {
        assertEquals("#1E5AA8", SheetSchema.colorToHex(0xFF1E5AA8))
        assertEquals(0xFF1E5AA8, SheetSchema.hexToColor("#1e5aa8"))
        assertNull(SheetSchema.hexToColor("blue"))
    }
}
