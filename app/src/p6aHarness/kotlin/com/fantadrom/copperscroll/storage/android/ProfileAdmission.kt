package com.fantadrom.copperscroll.storage.android

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.fantadrom.copperscroll.p6aharness.HarnessFaults
import com.fantadrom.copperscroll.storage.io.*

/** Compiled only in the explicitly opted-in p1Release test target. Never in ordinary release. */
internal object ProfileAdmission {
    private fun own(context: Context,uri: Uri)=uri.authority==context.packageName+".p6a.documents"
    fun resolve(context: Context,uri: Uri): ContractIdentity? {
        if(!own(context,uri)) return null
        val b=context.contentResolver.call(uri,"p6a.identity",uri.toString(),null) ?: throw StoreException(IoCode.AccessLost)
        return ContractIdentity(b.getString("root")!!,b.getString("volume")!!,b.getString("backend")!!,
            b.getString("document")!!,if(b.containsKey("device")) b.getLong("device") else null,
            if(b.containsKey("inode")) b.getLong("inode") else null,"controlled-process-and-fault-model;NOT-power-loss")
    }
    fun contract(context: Context,uri: Uri,environment: StorageEnvironment): QualifiedContract? {
        if(!own(context,uri) || environment.providerPackage!=context.packageName || environment.backend!="p6a-controlled-v1" || environment.root !in setOf("a","b","c")) return null
        return QualifiedContract(environment,"P6A-HARNESS-ONLY",StorageGuarantee.entries.toSet(),true)
    }
    fun localContract(context: Context,environment: StorageEnvironment): QualifiedContract? =
        if(context.packageName.endsWith(".p1runtime")) QualifiedContract(environment,"P6A-LOCAL-HARNESS-ONLY",setOf(StorageGuarantee.DurableLocalState),false) else null
    fun calls(context: Context,uri: Uri): PositionalCalls=object : PositionalCalls {
        override fun read(fd: java.io.FileDescriptor,b: ByteArray,s: Int,n: Int,p: Long)=HarnessFaults.read(fd,b,s,n,p)
        override fun write(fd: java.io.FileDescriptor,b: ByteArray,s: Int,n: Int,p: Long)=HarnessFaults.write(fd,b,s,n,p)
        override fun sync(fd: java.io.FileDescriptor) {
            HarnessFaults.sync(fd)
            if(own(context,uri)) context.contentResolver.call(uri,"p6a.dataForce",uri.toString(),null)
        }
    }
    fun localPoint(point: String,moment: String)=HarnessFaults.localPoint(point,moment)
    fun forceIdentity(context: Context,uri: Uri) {
        requireIo(own(context,uri),IoCode.UnsupportedDurability)
        val result=context.contentResolver.call(uri,"p6a.forceIdentity",uri.toString(),null)
        requireIo(result?.getBoolean("published")==true,IoCode.Eio)
    }
}
