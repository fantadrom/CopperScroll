package com.fantadrom.copperscroll.storage.runtime;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.FileNotFoundException;

/** Test APK only. Platform/Java dependencies only: this process has no target class loader. */
public final class ExternalGrantDocumentsProvider extends DocumentsProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Cursor queryRoots(String[] projection) {
        return new MatrixCursor(projection!=null ? projection : new String[]{DocumentsContract.Root.COLUMN_ROOT_ID});
    }
    @Override public Cursor queryDocument(String documentId,String[] projection) {
        if(!documentId.equals("synthetic")) throw new IllegalArgumentException();
        String[] columns=projection!=null ? projection : new String[]{DocumentsContract.Document.COLUMN_FLAGS};
        Object[] row=new Object[columns.length];
        for(int i=0;i<columns.length;i++) if(columns[i].equals(DocumentsContract.Document.COLUMN_FLAGS))
            row[i]=DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
        MatrixCursor cursor=new MatrixCursor(columns); cursor.addRow(row); return cursor;
    }
    @Override public Cursor queryChildDocuments(String parentId,String[] projection,String sortOrder) throws FileNotFoundException {
        throw new FileNotFoundException();
    }
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal) throws FileNotFoundException {
        throw new FileNotFoundException(); // No files/backend; tests stop at production inspect.
    }
    @Override public Bundle call(String method,String arg,Bundle extras) {
        if(!method.equals("p6a.grant")) return super.call(method,arg,extras);
        Context ctx=getContext(); String own=ctx.getPackageName();
        if(!own.endsWith(".test")) throw new SecurityException("Test package required");
        String target=own.substring(0,own.length()-5);
        final int uid;
        try { uid=ctx.getPackageManager().getApplicationInfo(target,0).uid; }
        catch(PackageManager.NameNotFoundException e) { throw new SecurityException(e); }
        if(uid==Process.myUid() || Binder.getCallingUid()!=uid ||
            ctx.getPackageManager().checkSignatures(uid,Process.myUid())!=PackageManager.SIGNATURE_MATCH)
            throw new SecurityException("Signed target UID required");
        Uri uri=DocumentsContract.buildDocumentUri(own.concat(".p6a.external.documents"),"synthetic");
        // ContentProvider.call has no implicit URI permission check. Authenticate the target
        // above, then issue/revoke only this synthetic URI from the actual provider process.
        long token=Binder.clearCallingIdentity();
        try {
            ctx.revokeUriPermission(target,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if("read".equals(arg)) ctx.grantUriPermission(target,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            else if(!"none".equals(arg)) throw new IllegalArgumentException();
        } finally { Binder.restoreCallingIdentity(token); }
        return new Bundle();
    }
}
