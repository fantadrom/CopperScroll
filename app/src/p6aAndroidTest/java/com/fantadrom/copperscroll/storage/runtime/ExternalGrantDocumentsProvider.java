package com.fantadrom.copperscroll.storage.runtime;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
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
}
