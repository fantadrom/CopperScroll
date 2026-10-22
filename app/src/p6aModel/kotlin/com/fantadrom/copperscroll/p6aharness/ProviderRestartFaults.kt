package com.fantadrom.copperscroll.p6aharness

import java.io.File
import java.io.FileOutputStream

/** Test-only process-restart fault journal. Not evidence of power-loss durability. */
internal class ProviderRestartFaults(private val directory: File) {
    enum class Action { Allow, Die, Unavailable }
    private fun record(id: String): File {
        require(id.matches(Regex("[abc](:[a-f0-9-]{36})?")))
        return File(directory,id.replace(':','_')) // Host fixtures also run on Windows.
    }
    @Synchronized fun state(id: String): String {
        val file=record(id)
        if(!file.exists()) return "none"
        check(file.isFile && file.length() in 1..64) { "Invalid restart fault record" }
        val state=file.readText(Charsets.US_ASCII)
        check(state in setOf("once-armed","once-consumed","persistent-armed","unavailable")) { "Invalid restart fault state" }
        return state
    }
    private fun write(id: String,state: String) {
        check(directory.isDirectory || directory.mkdirs())
        FileOutputStream(record(id)).use { out -> out.write(state.toByteArray(Charsets.US_ASCII)); out.fd.sync() }
    }
    @Synchronized fun arm(id: String,persistent: Boolean) = write(id,if(persistent) "persistent-armed" else "once-armed")
    @Synchronized fun beforeOpen(id: String): Action = when(state(id)) {
        "once-armed" -> { write(id,"once-consumed"); Action.Die }
        "persistent-armed" -> { write(id,"unavailable"); Action.Die }
        "unavailable" -> Action.Unavailable
        else -> Action.Allow
    }
    @Synchronized fun clear(id: String) {
        val file=record(id); check(!file.exists() || file.delete())
    }
}
