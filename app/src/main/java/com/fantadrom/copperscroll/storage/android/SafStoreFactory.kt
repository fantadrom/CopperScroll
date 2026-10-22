package com.fantadrom.copperscroll.storage.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import android.os.Process
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import com.fantadrom.copperscroll.storage.io.*
import com.fantadrom.copperscroll.storage.maintenance.*
import com.fantadrom.copperscroll.storage.crypto.ZoneKey
import com.fantadrom.copperscroll.storage.format.*
import java.util.UUID

internal data class ContractIdentity(val root: String, val volume: String, val backend: String,
    val document: String, val device: Long?, val inode: Long?, val failureModel: String)
enum class SafOperation { OpenOriginal, Create, ImportCopy, RestoreCopy }
class OpenedSafDocument internal constructor(val store: AndroidSafStore, val writable: Boolean, val decision: GateDecision)
internal data class SafInspection(val uri: Uri, val environment: StorageEnvironment,
    val contractIdentity: ContractIdentity?, val decision: GateDecision, val flags: Int)

/** No path guesses, staging, broad storage permission, or implicit create on missing. */
class SafStoreFactory(context: Context) {
    internal val context=context.applicationContext
    private val resolver=this.context.contentResolver
    internal fun inspect(uri: Uri, write: Boolean = false): SafInspection = AndroidIo.call {
        requireIo(uri.scheme=="content" && !uri.authority.isNullOrBlank() && uri.userInfo==null && uri.fragment==null,IoCode.AccessLost)
        requireIo(DocumentsContract.isDocumentUri(context,uri) || DocumentsContract.isTreeUri(uri),IoCode.AccessLost)
        val flags=Intent.FLAG_GRANT_READ_URI_PERMISSION or if(write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        requireIo(context.checkUriPermission(uri,Process.myPid(),Process.myUid(),flags)==PackageManager.PERMISSION_GRANTED,IoCode.AccessLost)
        val provider=context.packageManager.resolveContentProvider(uri.authority!!,0) ?: throw StoreException(IoCode.AccessLost)
        val pkg=context.packageManager.getPackageInfo(provider.packageName,0)
        val identity=ProfileAdmission.resolve(context,uri)
        val environment=StorageEnvironment(uri.authority!!,provider.packageName,pkg.longVersionCode.toString(),
            "${pkg.versionName}:${pkg.lastUpdateTime}",Build.VERSION.SDK_INT,Build.FINGERPRINT,
            identity?.root ?: if(DocumentsContract.isTreeUri(uri)) DocumentsContract.getTreeDocumentId(uri) else null,
            identity?.volume,identity?.backend,identity?.failureModel)
        val document=if(DocumentsContract.isDocumentUri(context,uri)) uri else DocumentsContract.buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri))
        val columns=arrayOf(DocumentsContract.Document.COLUMN_FLAGS)
        val documentFlags=resolver.query(document,columns,null,null,null)?.use { c ->
            requireIo(c.moveToFirst(),IoCode.AccessLost); c.getInt(0)
        } ?: throw StoreException(IoCode.AccessLost)
        requireIo(documentFlags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT==0,IoCode.UnsupportedDurability)
        if(write && DocumentsContract.isDocumentUri(context,uri)) requireIo(documentFlags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0,IoCode.AccessLost)
        val decision=AdmissionMatcher.decide(environment,ProfileAdmission.contract(context,uri,environment))
        SafInspection(uri,environment,identity,decision,documentFlags)
    }
    fun gate(uri: Uri): GateDecision = inspect(uri).decision
    fun openOriginal(uri: Uri, requestWrite: Boolean = false): OpenedSafDocument {
        val observed=inspect(uri)
        val writable=requestWrite && observed.decision.permits(StorageGuarantee.ExistingWriteDurable)
        val actual=if(writable) inspect(uri,true) else observed
        val store=AndroidSafStore.open(this,actual,writable)
        return OpenedSafDocument(store,writable,actual.decision)
    }
    internal fun openRequired(uri: Uri): AndroidSafStore {
        val i=inspect(uri,true); i.decision.require(StorageGuarantee.ExistingWriteDurable)
        return AndroidSafStore.open(this,i,true)
    }
    /** Gate before createDocument; actual returned URI/identity/name are authoritative. */
    fun create(root: Uri): Pair<Artifact,RandomAccessStore> =
        createNamed(root,"cscroll-create-${UUID.randomUUID()}.cscroll")
    internal fun createNamed(root: Uri, name: String): Pair<Artifact,RandomAccessStore> = AndroidIo.call {
        requireIo(DocumentsContract.isTreeUri(root),IoCode.AccessLost)
        val checked=inspect(root,true)
        checked.decision.require(StorageGuarantee.ExistingWriteDurable,StorageGuarantee.DurableCreateIdentity)
        requireIo(checked.flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE!=0,IoCode.AccessLost)
        requireIo(name.matches(Regex("cscroll-[a-z]+-[A-Za-z0-9-]+\\.cscroll")))
        val parent=DocumentsContract.buildDocumentUriUsingTree(root,DocumentsContract.getTreeDocumentId(root))
        // An exception/null may have created an unknown document. NEVER delete by requested name.
        val uri=try { DocumentsContract.createDocument(resolver,parent,"application/octet-stream",name) ?: throw StoreException(IoCode.Eio) }
            catch(e: Throwable) { throw MaintenanceRefusal(MaintenanceCode.CopyUnknown).also { it.addSuppressed(e) } }
        val store=try { openRequired(uri) }
            catch(e: Throwable) { throw MaintenanceRefusal(MaintenanceCode.CopyUnknown).also { it.addSuppressed(e) } }
        try {
            requireIo(store.size==0L,IoCode.InvalidRange)
            val artifact=Artifact(uri.toString(),store.identity)
            owned[artifact.uri]=artifact.identity
            artifact to store
        } catch(e: Throwable) { try { store.close() } catch(clean: Throwable) { e.addSuppressed(clean) }; throw e }
    }
    private val owned=HashMap<String,StoreIdentity>()
    internal fun deleteOwned(artifact: Artifact) {
        requireIo(owned[artifact.uri]==artifact.identity,IoCode.AccessLost)
        checkIdentity(artifact)
        AndroidIo.call { requireIo(DocumentsContract.deleteDocument(resolver,artifact.uri.toUri()),IoCode.Eio) }
        owned.remove(artifact.uri)
    }
    internal fun checkIdentity(a: Artifact) {
        val i=inspect(a.uri.toUri(),true); i.decision.require(StorageGuarantee.ExistingWriteDurable)
        val known=i.contractIdentity ?: throw StoreException(IoCode.UnsupportedDurability)
        requireIo(AndroidSafStore.identity(known.device ?: throw StoreException(IoCode.UnsupportedDurability),
            known.inode ?: throw StoreException(IoCode.UnsupportedDurability))==a.identity,IoCode.AccessLost)
    }
}

class SafMaintenanceDocuments(private val factory: SafStoreFactory, private val source: Artifact,
    backupRoot: Uri, candidateRoot: Uri) : MaintenanceDocuments {
    override val profile=MaintenanceProfile.AndroidGated
    private val roots=setOf(backupRoot.toString(),candidateRoot.toString())
    override fun checkSource(source: Artifact) { requireIo(source.identity==this.source.identity && source.uri==this.source.uri,IoCode.AccessLost); factory.checkIdentity(source) }
    override fun checkDestination(destination: String) {
        requireIo(destination in roots,IoCode.AccessLost)
        val endpoint=factory.inspect(destination.toUri(),true)
        endpoint.decision.require(StorageGuarantee.ExistingWriteDurable,StorageGuarantee.DurableCreateIdentity)
        requireIo(endpoint.flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE!=0,IoCode.AccessLost)
    }
    override fun availableBytes(destination: String): Long? { checkDestination(destination); return null }
    override fun createNew(destination: String,name: String): Pair<Artifact,RandomAccessStore> { checkDestination(destination); return factory.createNamed(destination.toUri(),name) }
    override fun open(artifact: Artifact): RandomAccessStore {
        checkIdentity(artifact); val store=factory.openRequired(artifact.uri.toUri())
        try { requireIo(store.identity==artifact.identity,IoCode.AccessLost); return store }
        catch(e: Throwable) { try { store.close() } catch(c: Throwable) { e.addSuppressed(c) }; throw e }
    }
    override fun checkIdentity(artifact: Artifact) {
        val actual=factory.inspect(artifact.uri.toUri(),true)
        val permittedRoots=roots.map { DocumentsContract.getTreeDocumentId(it.toUri()) }.toSet()
        requireIo(artifact.uri==source.uri || actual.environment.root in permittedRoots,IoCode.AccessLost)
        factory.checkIdentity(artifact)
    }
    override fun deleteOwned(artifact: Artifact) { requireIo(artifact.identity!=source.identity,IoCode.AccessLost); factory.deleteOwned(artifact) }
}

/** An unverified copy is never a healthy result or an authority to delete by name. */
class SafCopyFailure internal constructor(val code: MaintenanceCode,val unverifiedArtifact: Artifact?,primary: Throwable) :
    java.io.IOException(code.name) { init { addSuppressed(primary) } }

/** Explicit copies use P5's frozen snapshot/whole verification. They never publish a binding. */
class SafCopyOperations(private val documents: SafMaintenanceDocuments) {
    fun importCopy(owner: ResourceOwner, permit: WritePermit, snapshot: ReadSnapshot, source: Artifact,
        destination: Uri, keys: Map<Bytes,ZoneKey>): VerifiedCopy = copy(owner,permit,snapshot,source,destination,keys)
    fun restoreCopy(owner: ResourceOwner, permit: WritePermit, snapshot: ReadSnapshot, source: Artifact,
        destination: Uri, keys: Map<Bytes,ZoneKey>): VerifiedCopy = copy(owner,permit,snapshot,source,destination,keys)
    private fun copy(owner: ResourceOwner,permit: WritePermit,snapshot: ReadSnapshot,source: Artifact,destination: Uri,keys: Map<Bytes,ZoneKey>): VerifiedCopy {
        requireIo(owner.store.guarantees.existingWriteDurable,IoCode.UnsupportedDurability)
        var created: Artifact?=null
        try { return SnapshotCopier().copy(owner,permit,snapshot,source,documents,destination.toString(),keys) { created=it } }
        catch(e: Throwable) {
            if(created!=null || e is MaintenanceRefusal && e.code==MaintenanceCode.CopyUnknown)
                throw SafCopyFailure(MaintenanceCode.CopyUnknown,created,e)
            throw e
        }
    }
}
