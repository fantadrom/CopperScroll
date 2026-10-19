import com.fantadrom.copperscroll.storage.format.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Read-only CLI. Path, hints and record data never enter diagnostics. */
public final class Inspector {
    public static void main(String[] args) {
        if (args.length != 1) { System.err.println("usage: inspect.sh SYNTHETIC_FILE"); System.exit(2); }
        System.out.println("Structural check only. No cryptographic verification, decryption, recovery or durability proof.");
        try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.READ)) {
            final long length = channel.size();
            ReadOnlySource source = new ReadOnlySource() {
                public long getSize() { return length; }
                public byte[] read(long offset, int count) {
                    ByteBuffer b = ByteBuffer.allocate(count);
                    try {
                        while (b.hasRemaining()) {
                            int n = channel.read(b, offset + b.position());
                            if (n <= 0) throw new IOException();
                        }
                        return b.array();
                    } catch (IOException e) { throw new ReadFailure(); }
                }
            };
            System.out.println(StructuralInspector.INSTANCE.inspect(source,
                new InspectionLimits(32L*1024*1024, 16777216, 1024L*1024*1024, 30000), () -> false));
        } catch (FormatException e) {
            System.err.println("structure=FAIL code=" + e.getFailure().name()); System.exit(1);
        } catch (IOException | ReadFailure | SecurityException e) {
            System.err.println("structure=FAIL code=ReadFailure"); System.exit(1);
        }
    }
    private static final class ReadFailure extends RuntimeException {}
}
