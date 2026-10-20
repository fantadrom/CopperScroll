package com.fantadrom.copperscroll.storage;

import java.nio.file.Path;
import com.fantadrom.copperscroll.storage.io.*;

/** Independent cooperating JVM process, no Android emulator or production data. */
public final class P3LockProbe {
    public static void main(String[] args) throws Exception {
        try (FileChannelStore store = FileChannelStore.Companion.open(Path.of(args[0]), false, false)) {
            try (AutoCloseable lock = store.acquireExclusive()) { System.out.println("ACQUIRED"); }
            catch (StoreException error) {
                if (error.getCode() != IoCode.Busy) throw error;
                System.out.println("BUSY"); System.exit(23);
            }
        }
    }
}
