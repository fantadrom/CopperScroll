import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Build-output audit only. Reads actual class_defs, not mere DEX string references. No Android/production copy. */
public final class VerifyRuntimeApks {
    private static final String ICU = "com/ibm/icu/impl/data/";
    private static final String TEST = "Lcom/fantadrom/copperscroll/storage/runtime/P1BackendRuntimeTest;";
    private static final List<String> BACKEND = List.of(
        "Lcom/fantadrom/copperscroll/storage/access/SecretEncoding;",
        "Lcom/fantadrom/copperscroll/storage/access/CanonicalSecret;",
        "Lcom/fantadrom/copperscroll/storage/format/SettingsCodec;",
        "Lcom/fantadrom/copperscroll/storage/format/SettingsData;",
        "Lcom/ibm/icu/lang/UCharacter;", "Lcom/ibm/icu/text/Normalizer2;",
        "Lco/nstant/in/cbor/CborDecoder;", "Lco/nstant/in/cbor/CborEncoder;");

    public static void main(String[] args) throws Exception {
        if (args.length >= 4 && args[0].equals("--linkage")) {
            System.out.print(DexRuntimeLinkage.check(DexRuntimeLinkage.apk(Path.of(args[1]), false),
                DexRuntimeLinkage.apk(Path.of(args[2])), java.util.Arrays.stream(args).skip(3).map(Path::of).collect(java.util.stream.Collectors.toList())));
            System.out.println("runtime=NOT_RUN"); return;
        }
        if (args.length == 2 && args[0].equals("--hierarchy")) {
            try (ZipFile apk = new ZipFile(args[1])) { checkHierarchy(definitions(apk)); }
            System.out.println("classHierarchyPackageAccess=PASS; runtime=NOT_RUN"); return;
        }
        require(args.length >= 9, "Expected APKs, golden, report, both mappings, diagnostic directory, generated rules and platform libraries");
        Path target = Path.of(args[0]), test = Path.of(args[1]), golden = Path.of(args[2]), report = Path.of(args[3]);
        Files.createDirectories(report.getParent()); Files.deleteIfExists(report);
        Files.write(report.resolveSibling("audit-arguments.txt"), List.of(args), StandardCharsets.UTF_8);
        try (ZipFile app = new ZipFile(target.toFile()); ZipFile tests = new ZipFile(test.toFile())) {
            var appIndex = DexRuntimeLinkage.apk(target, false); var testIndex = DexRuntimeLinkage.apk(test);
            String identity = P1ArtifactDiagnostics.save(target, test, Path.of(args[4]), Path.of(args[5]), Path.of(args[7]), Path.of(args[6]), appIndex.mapIds, testIndex.mapIds);
            Map<String, Definition> appDefinitions = definitions(app), testDefinitions = definitions(tests);
            checkHierarchy(appDefinitions); checkHierarchy(testDefinitions);
            Set<String> appClasses = appDefinitions.keySet(), testClasses = testDefinitions.keySet();
            require(appClasses.containsAll(BACKEND), "Processed target is missing backend definitions");
            require(!appClasses.contains(TEST) && testClasses.contains(TEST), "Test class belongs in instrumentation APK only");
            for (String name : testClasses) {
                require(!name.startsWith("Lcom/fantadrom/copperscroll/storage/access/") &&
                    !name.startsWith("Lcom/fantadrom/copperscroll/storage/format/") &&
                    !name.startsWith("Lcom/ibm/icu/") && !name.startsWith("Lco/nstant/in/cbor/"),
                    "Instrumentation APK contains a second backend implementation");
            }
            Set<String> shared = new HashSet<>(appClasses); shared.retainAll(testClasses);
            require(shared.isEmpty(), "Duplicate target/test runtime class definitions: " + shared);
            String linkage = DexRuntimeLinkage.check(appIndex, testIndex,
                java.util.Arrays.stream(args).skip(8).map(Path::of).collect(java.util.stream.Collectors.toList()));
            long resources = app.stream().filter(e -> e.getName().startsWith(ICU) && !e.isDirectory()).count();
            require(resources == 3950, "Unexpected pinned ICU resource set");
            require(tests.stream().noneMatch(e -> e.getName().startsWith(ICU)), "ICU data duplicated in test APK");
            // Hashes of data from the pinned Maven Central ICU4J76.1 JAR, recorded independently of these APKs.
            checkHash(app, ICU + "icudata/nfc.nrm", "dd07de08e03f832754a356852a54480b6ad3acb404e242b5cc1e0f05eddfc278");
            checkHash(app, ICU + "icudata/uprops.icu", "fc9777f410cb3c6b499cc833f771d9367e05eed68ce0efb34307d615e1b8d64b");
            require(new String(read(app, "assets/p1-runtime-target.txt", 128), StandardCharsets.UTF_8).trim().equals("p1Release"),
                "APK is not the marked release-derived target");
            require(MessageDigest.isEqual(read(tests, "assets/p1/settings-default.hex", 1024), Files.readAllBytes(golden)),
                "Android golden differs from independently authored frozen JVM fixture");
            require(MessageDigest.isEqual(read(tests, "assets/p1/s1.tsv", 16384), Files.readAllBytes(golden.resolveSibling("s1.tsv"))),
                "Android S1 vectors differ from frozen independent fixtures");
            require(new String(read(tests, "assets/p1/noncanonical-settings.hex", 1024), StandardCharsets.US_ASCII).trim()
                .equals("980801f4f6f5f501f501"), "Noncanonical fixture changed");
            for (String name : List.of("ICU-76.1.txt", "CBOR-0.9.txt", "NOTICE.txt"))
                require(app.getEntry("assets/third-party/" + name) != null, "License/NOTICE missing");
            String summary = "artifactAudit=PASS\nvariant=p1Release\nruntime=NOT_RUN\n" + identity + linkage +
                "targetApk=" + target.getFileName() + "\ntestApk=" + test.getFileName() + "\n" +
                "targetSha256=" + hash(Files.newInputStream(target)) + "\n" +
                "testSha256=" + hash(Files.newInputStream(test)) + "\n" +
                "targetBackendDefinitions=present\ntestBackendDefinitions=absent\n" +
                "classHierarchyPackageAccess=PASS\nduplicateTargetTestDefinitions=0\n" +
                "icuResourceFiles=" + resources + "\npinnedNfcAndRepertoireHashes=PASS\nindependentFixtures=PASS\n";
            Files.createDirectories(report.getParent()); Files.writeString(report, summary);
            System.out.print(summary);
        }
    }

    private static void checkHash(ZipFile file, String name, String expected) throws Exception {
        ZipEntry entry = file.getEntry(name); require(entry != null, "Pinned ICU data missing");
        require(hash(file.getInputStream(entry)).equals(expected), "Pinned ICU data hash mismatch");
    }
    private static String hash(InputStream stream) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = stream) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format("%02x", b & 255));
        return hex.toString();
    }
    private static byte[] read(ZipFile file, String name, int max) throws IOException {
        ZipEntry entry = file.getEntry(name); require(entry != null && entry.getSize() <= max, "APK entry missing or oversized");
        try (InputStream in = file.getInputStream(entry)) {
            byte[] bytes = in.readNBytes(max + 1); require(bytes.length <= max, "APK entry oversized"); return bytes;
        }
    }
    private static Map<String, Definition> definitions(ZipFile file) throws IOException {
        Map<String, Definition> classes = new HashMap<>(); int dexCount = 0;
        var entries = file.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.getName().matches("classes[0-9]*\\.dex")) continue;
            require(++dexCount <= 256, "Too many DEX files");
            Dex dex = new Dex(read(file, entry.getName(), 64 * 1024 * 1024));
            int count = dex.u32(96), offset = dex.u32(100); dex.range(offset, (long) count * 32);
            for (int i = 0; i < count; i++) {
                int row = offset + i * 32;
                String name = dex.descriptor(dex.u32(row));
                List<String> parents = new ArrayList<>();
                int superclass = dex.b.getInt(row + 8);
                if (superclass != -1) parents.add(dex.descriptor(superclass));
                int interfaces = dex.u32(row + 12);
                if (interfaces != 0) {
                    int size = dex.u32(interfaces); dex.range(interfaces + 4, (long) size * 2);
                    for (int j = 0; j < size; j++)
                        parents.add(dex.descriptor(Short.toUnsignedInt(dex.b.getShort(interfaces + 4 + j * 2))));
                }
                require(classes.put(name, new Definition(dex.u32(row + 4), parents)) == null, "Duplicate DEX definition");
            }
        }
        require(dexCount > 0, "APK contains no DEX"); return classes;
    }
    private static final class Definition {
        final int flags; final List<String> parents;
        Definition(int flags, List<String> parents) { this.flags = flags; this.parents = parents; }
    }
    private static String packageName(String descriptor) { return descriptor.substring(0, descriptor.lastIndexOf('/') + 1); }
    private static void checkHierarchy(Map<String, Definition> classes) {
        classes.forEach((name, definition) -> {
            for (String parent : definition.parents) {
                Definition base = classes.get(parent);
                require(base == null || (base.flags & 1) != 0 || packageName(name).equals(packageName(parent)),
                    "Inaccessible nonpublic superclass/interface: " + name + " -> " + parent);
            }
        });
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    /** Minimal bounded reader of standard build-tools DEX header/type/string/class_defs tables. */
    static final class Dex {
        final byte[] data; final ByteBuffer b;
        Dex(byte[] data) {
            this.data = data; this.b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            range(0, 112); require(data[0] == 'd' && data[1] == 'e' && data[2] == 'x' && data[3] == '\n', "Not standard DEX");
            require(u32(32) == data.length && u32(40) == 0x12345678, "Bad DEX size/endianness");
            range(u32(60), (long) u32(56) * 4); range(u32(68), (long) u32(64) * 4);
        }
        void range(int offset, long count) { require(offset >= 0 && count >= 0 && count <= data.length - (long) offset, "DEX bounds"); }
        int u32(int offset) { range(offset, 4); int value = b.getInt(offset); require(value >= 0, "DEX index too large"); return value; }
        String descriptor(int type) {
            require(type >= 0 && type < u32(64), "DEX type bounds");
            String result = string(u32(u32(68) + type * 4));
            require(result.length() <= 4096, "DEX descriptor too long"); return result;
        }
        String string(int string) {
            require(string >= 0 && string < u32(56), "DEX string bounds");
            int start = u32(u32(60) + string * 4); int width = 0;
            do { range(start, 1); require(++width <= 5, "DEX ULEB128 too long"); } while ((data[start++] & 128) != 0);
            int end = start;
            while (true) { range(end, 1); if (data[end] == 0) break; require(end++ - start < 1024 * 1024, "DEX string too long"); }
            return new String(data, start, end - start, StandardCharsets.UTF_8);
        }
    }
}
