package com.fantadrom.copperscroll.storage.android

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import com.fantadrom.copperscroll.storage.io.*
import com.fantadrom.copperscroll.storage.session.SessionCoordinator

/** Locked-only in-memory epoch. No keys, lifecycle persistence or maintenance continuation. */
class SafPickerPort {
    enum class Purpose { OpenOriginal, Create, ImportCopy, RestoreCopy, BackupTree, CandidateTree }
    class Token internal constructor(internal val issuer: Any,internal val epoch: Long,val operation: SafOperation,internal val session: SessionCoordinator.Picker?=null)
    private val issuer=Any(); private var epoch=0L; private var active: Token?=null
    @Synchronized internal fun beginLocked(operation: SafOperation): Token {
        requireIo(epoch<Long.MAX_VALUE,IoCode.AccessLost); return Token(issuer,++epoch,operation).also { active=it }
    }
    fun beginLocked(coordinator: SessionCoordinator,operation: SafOperation,purpose: Purpose=Purpose.valueOf(operation.name)): Token {
        requireIo(purpose.name==operation.name || purpose in setOf(Purpose.BackupTree,Purpose.CandidateTree) && operation==SafOperation.Create,IoCode.InvalidRange)
        val bound=coordinator.beginPicker(purpose.name)
        return synchronized(this) { requireIo(epoch<Long.MAX_VALUE,IoCode.AccessLost); Token(issuer,++epoch,operation,bound).also { active=it } }
    }
    fun acceptBound(coordinator: SessionCoordinator,window: SessionCoordinator.Window,token: Token,resultCode: Int,intent: Intent?,resolver: ContentResolver): Uri? {
        val bound=token.session ?: throw StoreException(IoCode.AccessLost)
        requireIo(coordinator.consumePicker(window, bound,bound.purpose,bound.document),IoCode.AccessLost)
        return consume(token,resultCode,intent,resolver)
    }
    @Synchronized fun invalidate() { active=null; if(epoch<Long.MAX_VALUE) epoch++ }
    /** Consume BEFORE calling resolver. A grant failure cannot replay a stale result. */
    internal fun accept(token: Token,resultCode: Int,intent: Intent?,resolver: ContentResolver): Uri? {
        requireIo(token.session==null,IoCode.AccessLost); return consume(token,resultCode,intent,resolver)
    }
    private fun consume(token: Token,resultCode: Int,intent: Intent?,resolver: ContentResolver): Uri? {
        synchronized(this) {
            requireIo(token.issuer===issuer && active===token && token.epoch==epoch && epoch<Long.MAX_VALUE,IoCode.AccessLost)
            active=null; epoch++
        }
        if(resultCode!=Activity.RESULT_OK || intent?.data==null) return null
        val uri=intent.data!!
        val needed=Intent.FLAG_GRANT_READ_URI_PERMISSION or if(token.operation==SafOperation.OpenOriginal) 0 else Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val returned=intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        requireIo(returned and needed==needed,IoCode.AccessLost)
        requireIo(intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION!=0,IoCode.AccessLost)
        requireIo(uri.scheme=="content",IoCode.AccessLost)
        if(token.session?.purpose in setOf("BackupTree","CandidateTree")) requireIo(android.provider.DocumentsContract.isTreeUri(uri),IoCode.AccessLost)
        if(returned==Intent.FLAG_GRANT_READ_URI_PERMISSION)
            resolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        else if(returned==(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            resolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        else throw StoreException(IoCode.AccessLost)
        return uri
    }
}
