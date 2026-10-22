package com.fantadrom.copperscroll.storage.android

import android.content.Context
import android.net.Uri

/** P6a.2 has not qualified any provider, backend or local filesystem. Fail closed in release. */
internal object ProfileAdmission {
    fun contract(context: Context, uri: Uri, environment: StorageEnvironment): QualifiedContract? = null
    fun resolve(context: Context, uri: Uri): ContractIdentity? = null
    fun calls(context: Context,uri: Uri): PositionalCalls = SystemCalls
    fun localPoint(point: String, moment: String) {}
    fun localContract(context: Context,environment: StorageEnvironment): QualifiedContract? = null
    fun forceIdentity(context: Context, uri: Uri) { throw com.fantadrom.copperscroll.storage.io.StoreException(com.fantadrom.copperscroll.storage.io.IoCode.UnsupportedDurability) }
}
