package com.fantadrom.copperscroll.storage.runtime

import android.content.*
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Bind the signature-protected owner channel before trying to acquire the external provider. */
internal class ExternalGrantControl(private val context: Context,testPackage: String) : AutoCloseable {
    private val ready=CountDownLatch(1)
    @Volatile private var binder: IBinder?=null
    private val ownerUid: Int
    private val connection=object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName,service: IBinder) { binder=service; ready.countDown() }
        override fun onServiceDisconnected(name: ComponentName) { binder=null; ready.countDown() }
        override fun onNullBinding(name: ComponentName) { ready.countDown() }
        override fun onBindingDied(name: ComponentName) { binder=null; ready.countDown() }
    }
    init {
        val component=ComponentName(testPackage,"com.fantadrom.copperscroll.storage.runtime.ExternalGrantOwnerService")
        val info=context.packageManager.getServiceInfo(component,0)
        ownerUid=info.applicationInfo.uid
        val permission=context.packageName+".p6a.GRANT_CONTROL"
        check(ownerUid!=Process.myUid() && info.exported && info.permission==permission)
        check(context.packageManager.getPermissionInfo(permission,0).protectionLevel==android.content.pm.PermissionInfo.PROTECTION_SIGNATURE)
        check(context.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED)
        var registered=false
        try {
            registered=context.bindService(Intent().setComponent(component),connection,Context.BIND_AUTO_CREATE)
            check(registered && ready.await(15,TimeUnit.SECONDS) && binder!=null) { "Grant owner bind failed" }
        } catch(e: Throwable) {
            if(registered) try { context.unbindService(connection) } catch(clean: Throwable) { e.addSuppressed(clean) }
            throw e
        }
    }
    fun setReadGrant(enabled: Boolean) {
        val request=Parcel.obtain(); val response=Parcel.obtain()
        try {
            request.writeInterfaceToken("com.fantadrom.copperscroll.p6a.GRANT_CONTROL")
            request.writeInt(if(enabled) 1 else 0)
            check(checkNotNull(binder).transact(IBinder.FIRST_CALL_TRANSACTION,request,response,0))
            response.readException(); check(response.readInt()==ownerUid)
        } finally { response.recycle(); request.recycle() }
    }
    override fun close() {
        var primary: Throwable?=null
        try { setReadGrant(false) } catch(e: Throwable) { primary=e }
        try { context.unbindService(connection) } catch(clean: Throwable) { if(primary!=null) primary.addSuppressed(clean) else primary=clean }
        binder=null; primary?.let { throw it }
    }
}
