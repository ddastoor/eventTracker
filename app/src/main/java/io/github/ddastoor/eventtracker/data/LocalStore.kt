package io.github.ddastoor.eventtracker.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** On-device copy of the last synced data plus the queue of changes not yet written to Google. */
class LocalStore(private val dir: File) {

    private val snapshotFile get() = File(dir, "snapshot.json")
    private val opsFile get() = File(dir, "pending_ops.json")

    fun loadSnapshot(): Snapshot = read(snapshotFile) { json.decodeFromString(Snapshot.serializer(), it) } ?: Snapshot()

    fun saveSnapshot(snapshot: Snapshot) = write(snapshotFile, json.encodeToString(Snapshot.serializer(), snapshot))

    fun loadOps(): List<Op> = read(opsFile) { json.decodeFromString(opsSerializer, it) } ?: emptyList()

    fun saveOps(ops: List<Op>) = write(opsFile, json.encodeToString(opsSerializer, ops))

    fun clear() {
        snapshotFile.delete()
        opsFile.delete()
    }

    private fun <T> read(file: File, decode: (String) -> T): T? =
        if (!file.exists()) null else runCatching { decode(file.readText()) }.getOrNull()

    @Synchronized
    private fun write(file: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        /** `classDiscriminator` must not clash with model property names such as `type`. */
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            classDiscriminator = "kind"
        }
        private val opsSerializer = ListSerializer(Op.serializer())
    }
}
