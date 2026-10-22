package com.fantadrom.copperscroll.storage.runtime;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.provider.DocumentsContract;

/** Test APK owner UID, separate process. No provider acquisition, Kotlin, backend or UI. */
public final class ExternalGrantOwnerService extends Service {
    private final Binder control=new Binder() {
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
            if(code!=IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code,data,reply,flags);
            data.enforceInterface("com.fantadrom.copperscroll.p6a.GRANT_CONTROL");
            String own=getPackageName();
            if(!own.endsWith(".test")) throw new SecurityException("Test package required");
            String target=own.substring(0,own.length()-5);
            enforceCallingPermission(target.concat(".p6a.GRANT_CONTROL"),"Grant owner channel permission required");
            final int uid;
            try { uid=getPackageManager().getApplicationInfo(target,0).uid; }
            catch(PackageManager.NameNotFoundException e) { throw new SecurityException(e); }
            if(uid==Process.myUid() || Binder.getCallingUid()!=uid ||
                getPackageManager().checkSignatures(uid,Process.myUid())!=PackageManager.SIGNATURE_MATCH)
                throw new SecurityException("Signed target UID required");
            int mode=data.readInt();
            if((mode!=0 && mode!=1) || data.dataAvail()!=0 || reply==null)
                throw new SecurityException(); // Reject the request without a shared exception-outline dependency.
            Uri uri=DocumentsContract.buildDocumentUri(own.concat(".p6a.external.documents"),"synthetic");
            long token=Binder.clearCallingIdentity();
            try {
                revokeUriPermission(target,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if(mode==1) grantUriPermission(target,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } finally { Binder.restoreCallingIdentity(token); }
            reply.writeNoException(); reply.writeInt(Process.myUid()); return true;
        }
    };
    @Override public IBinder onBind(Intent intent) { return control; }
}
