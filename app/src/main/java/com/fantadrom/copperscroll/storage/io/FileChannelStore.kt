package com.fantadrom.copperscroll.storage.io

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/** JVM/local synthetic adapter. FileChannel.force and cooperative locks are NOT SAF/device evidence.
 * createIdentity is an explicit synthetic host contract; default denies creation ACK.
 */
class FileChannelStore private constructor(private val channel: FileChannel, private val path: Path,
                                          override val guarantees: StoreGuarantees,
                                          override val identity: StoreIdentity) : RandomAccessStore {
    private var closed = false
    override val size get() = io { channel.size() }
    override fun readFullyAt(offset: Long, length: Int): ByteArray = io {
        val end = endAt(offset, length.toLong()); requireIo(end <= channel.size(), IoCode.Eio)
        val bytes = ByteArray(length); val buffer = ByteBuffer.wrap(bytes); var position = offset
        FullIo.transfer(buffer,position) { b,p -> channel.read(b,p) }
        bytes
    }
    override fun writeFullyAt(offset: Long, bytes: ByteArray) = io {
        endAt(offset, bytes.size.toLong()); val buffer = ByteBuffer.wrap(bytes); var position = offset
        FullIo.transfer(buffer,position) { b,p -> channel.write(b,p) }
    }
    override fun force() = io { channel.force(true) }
    override fun forceIdentity() = io {
        requireIo(guarantees.durableCreateIdentity, IoCode.UnsupportedDurability)
        FileChannel.open(path.parent, StandardOpenOption.READ).use { it.force(true) }
    }
    override fun truncate(length: Long): Boolean = io { endAt(length, 0); channel.truncate(length); true }
    override fun acquireExclusive(): AutoCloseable = io {
        try { channel.tryLock() ?: throw StoreException(IoCode.Busy) }
        catch (_: OverlappingFileLockException) { throw StoreException(IoCode.Busy) }
    }
    @Synchronized override fun close() {
        if(!closed) try { io { channel.close() } } finally {
            closed = true; synchronized(handles) { handles.remove(identity) }
        }
    }
    private inline fun <T> io(block: () -> T): T = try { block() }
        catch (e: StoreException) { throw e }
        catch (_: IOException) { throw StoreException(IoCode.Eio) }
    companion object {
        private val handles = HashSet<StoreIdentity>()
        internal fun identityOf(path: Path): StoreIdentity {
            val attributes = Files.readAttributes(path,BasicFileAttributes::class.java)
            return StoreIdentity("jvm-file",attributes.fileKey()?.toString() ?: path.toRealPath().toString())
        }
        fun open(path: Path, createNew: Boolean = false, syntheticCreateIdentity: Boolean = false): FileChannelStore {
            val absolute = path.toAbsolutePath().normalize()
            val options = mutableSetOf(StandardOpenOption.READ, StandardOpenOption.WRITE)
            if (createNew) options.add(StandardOpenOption.CREATE_NEW)
            // Check BEFORE opening another FD: closing a duplicate FD can release process POSIX locks.
            synchronized(handles) {
                var channel: FileChannel? = null; var selected: StoreIdentity? = null
                return try {
                    if(!createNew) { selected = identityOf(absolute); requireIo(selected !in handles,IoCode.Busy) }
                    channel = FileChannel.open(absolute, options)
                    if(selected == null) selected = identityOf(absolute)
                    requireIo(handles.add(selected),IoCode.Busy)
                    FileChannelStore(channel,absolute,StoreGuarantees(true, syntheticCreateIdentity),selected)
                } catch(e: IOException) {
                    channel?.close()
                    if(e is StoreException) throw e
                    throw StoreException(IoCode.Eio)
                }
            }
        }
    }
}

internal object FullIo {
    fun transfer(buffer: ByteBuffer, offset: Long, operation: (ByteBuffer,Long) -> Int) {
        var position = offset
        while(buffer.hasRemaining()) {
            val before = buffer.position(); val n = operation(buffer,position)
            requireIo(n > 0 && buffer.position()-before == n,IoCode.Eio)
            position = endAt(position,n.toLong())
        }
    }
}
