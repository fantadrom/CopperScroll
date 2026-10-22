package com.fantadrom.copperscroll.storage.android

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import com.fantadrom.copperscroll.storage.io.*

/** Locked-only in-memory epoch. No keys, lifecycle persistence or maintenance continuation. */
class SafPickerPort {
    class Token internal constructor(internal val issuer: Any,internal val epoch: Long,val operation: SafOperation)
    private val issuer=Any(); private var epoch=0L; private var active: Token?=null
    @Synchronized fun beginLocked(operation: SafOperation): Token = Token(issuer,++epoch,operation).also { active=it }
    @Synchronized fun invalidate() { epoch++; active=null }
    /** Consume BEFORE calling resolver. A grant failure cannot replay a stale result. */
    @Synchronized fun accept(token: Token,resultCode: Int,intent: Intent?,resolver: ContentResolver): Uri? {
        requireIo(token.issuer===issuer && active===token && token.epoch==epoch,IoCode.AccessLost)
        active=null; epoch++
        if(resultCode!=Activity.RESULT_OK || intent?.data==null) return null
        val uri=intent.data!!
        val needed=Intent.FLAG_GRANT_READ_URI_PERMISSION or if(token.operation==SafOperation.OpenOriginal) 0 else Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val returned=intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        requireIo(returned and needed==needed,IoCode.AccessLost)
        requireIo(intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION!=0,IoCode.AccessLost)
        requireIo(uri.scheme=="content",IoCode.AccessLost)
        if(returned==Intent.FLAG_GRANT_READ_URI_PERMISSION)
            resolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        else if(returned==(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            resolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        else throw StoreException(IoCode.AccessLost)
        return uri
    }
}
