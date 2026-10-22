package com.fantadrom.copperscroll.storage.maintenance

import com.fantadrom.copperscroll.storage.io.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Explicit HOST/SYNTHETIC fixture adapter, never a production SAF profile. Directory force via
 * FileChannelStore is exercised only where supported; no Windows/Android power-loss promise.
 * All names/cleanup stay within caller-owned test roots. No automatic retention deletion.
 */
class JvmMaintenanceDocuments(private val sourcePath: Path, backupRoot: Path, candidateRoot: Path) : MaintenanceDocuments {
    override val profile = MaintenanceProfile.JvmSynthetic
    private val backup = backupRoot.toRealPath()
    private val candidate = candidateRoot.toRealPath()
    private val created = HashSet<Artifact>()
    val source = Artifact(sourcePath.toUri().toString(),FileChannelStore.identityOf(sourcePath))
    val destinations = MaintenanceDestinations(source,backup.toUri().toString(),candidate.toUri().toString())
    private fun root(uri: String): Path {
        requireIo(uri == backup.toUri().toString() || uri == candidate.toUri().toString(),IoCode.AccessLost)
        return if(uri == backup.toUri().toString()) backup else candidate
    }
    private fun path(artifact: Artifact): Path {
        val p = java.nio.file.Paths.get(java.net.URI(artifact.uri)).toAbsolutePath().normalize()
        requireIo(p == sourcePath.toAbsolutePath().normalize() || p.parent == backup || p.parent == candidate,IoCode.AccessLost)
        requireIo(!Files.isSymbolicLink(p),IoCode.AccessLost); return p
    }
    override fun checkSource(source: Artifact) { requireIo(source.identity == this.source.identity,IoCode.AccessLost); checkIdentity(source) }
    override fun checkDestination(destination: String) {
        val p = root(destination)
        requireIo(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS) && Files.isReadable(p) && Files.isWritable(p),IoCode.AccessLost)
    }
    override fun availableBytes(destination: String) = Files.getFileStore(root(destination)).usableSpace
    override fun createNew(destination: String,name: String): Pair<Artifact,RandomAccessStore> {
        checkDestination(destination); requireIo(name.matches(Regex("cscroll-[a-z]+-[A-Za-z0-9-]+\\.cscroll")))
        val p = root(destination).resolve(name)
        val store = FileChannelStore.open(p,createNew=true,syntheticCreateIdentity=true)
        val artifact = Artifact(p.toUri().toString(),store.identity); created.add(artifact)
        return artifact to store
    }
    override fun open(artifact: Artifact): RandomAccessStore {
        checkIdentity(artifact)
        return FileChannelStore.open(path(artifact),syntheticCreateIdentity=true)
    }
    override fun checkIdentity(artifact: Artifact) {
        try { refuse(FileChannelStore.identityOf(path(artifact)) == artifact.identity,MaintenanceCode.Conflict) }
        catch(_: java.nio.file.NoSuchFileException) { throw StoreException(IoCode.AccessLost) }
        catch(_: java.nio.file.AccessDeniedException) { throw StoreException(IoCode.AccessLost) }
    }
    override fun deleteOwned(artifact: Artifact) {
        requireIo(artifact in created && artifact.identity != source.identity,IoCode.AccessLost)
        checkIdentity(artifact); Files.delete(path(artifact)); created.remove(artifact)
    }
}
