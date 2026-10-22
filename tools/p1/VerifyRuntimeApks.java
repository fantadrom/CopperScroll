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
        "Lcom/fantadrom/copperscroll/storage/android/SafUriAccess;",
        "Lcom/fantadrom/copperscroll/storage/android/AndroidSafStore;",
        "Lcom/fantadrom/copperscroll/storage/android/SafStoreFactory;",
        "Lcom/fantadrom/copperscroll/storage/android/AppPrivateLocalState;",
        "Lcom/fantadrom/copperscroll/storage/access/SecretEncoding;",
        "Lcom/fantadrom/copperscroll/storage/access/CanonicalSecret;",
        "Lcom/fantadrom/copperscroll/storage/access/CredentialManager;",
        "Lcom/fantadrom/copperscroll/storage/crypto/ZoneKey;",
        "Lcom/fantadrom/copperscroll/storage/crypto/RecordCrypto;",
        "Lcom/fantadrom/copperscroll/storage/transaction/TransactionCore;",
        "Lcom/fantadrom/copperscroll/storage/io/ResourceOwner;", "Lcom/fantadrom/copperscroll/storage/io/FileChannelStore;",
        "Lcom/fantadrom/copperscroll/storage/io/FaultStore;", "Lcom/fantadrom/copperscroll/storage/io/LocalStateCodec;",
        "Lcom/fantadrom/copperscroll/storage/format/ContainerReader;", "Lcom/fantadrom/copperscroll/storage/format/CowCatalogue;",
        "Lcom/fantadrom/copperscroll/storage/vfs/ZoneRepository;", "Lcom/fantadrom/copperscroll/storage/vfs/CowIndex;",
        "Lcom/fantadrom/copperscroll/storage/vfs/NoteStorageAdapter;", "Lcom/fantadrom/copperscroll/storage/vfs/MediaStreams;",
        "Lcom/fantadrom/copperscroll/storage/cards/CardCodec;", "Lcom/fantadrom/copperscroll/storage/cards/CardBody;",
        "Lcom/fantadrom/copperscroll/storage/cards/DigitMask;", "Lcom/fantadrom/copperscroll/storage/cards/CardDraftSession;",
        "Lorg/bouncycastle/crypto/generators/Argon2BytesGenerator;",
        "Lorg/bouncycastle/crypto/generators/Argon2BytesGenerator$BlockPool;",
        "Lorg/bouncycastle/crypto/generators/HKDFBytesGenerator;", "Lorg/bouncycastle/crypto/macs/HMac;",
        "Lcom/fantadrom/copperscroll/storage/format/SettingsCodec;",
        "Lcom/fantadrom/copperscroll/storage/format/SettingsData;",
        "Lcom/ibm/icu/lang/UCharacter;", "Lcom/ibm/icu/text/Normalizer2;",
        "Lco/nstant/in/cbor/CborDecoder;", "Lco/nstant/in/cbor/CborEncoder;");

    public static void main(String[] args) throws Exception {
        if(args.length == 3 && args[0].equals("--p6a-isolation")) {
            try(ZipFile release=new ZipFile(args[1]); ZipFile harness=new ZipFile(args[2])) {
                var real=definitions(release); var test=definitions(harness);
                for(String type:List.of("AndroidSafStore","SafStoreFactory","AppPrivateLocalState"))
                    require(real.containsKey("Lcom/fantadrom/copperscroll/storage/android/"+type+";"),"Android adapter missing from ordinary release: "+type);
                require(real.keySet().stream().noneMatch(n->n.startsWith("Lcom/fantadrom/copperscroll/p6aharness/")),"Test controls/provider/helper leaked into ordinary release");
                require(!real.containsKey("Lcom/fantadrom/copperscroll/storage/runtime/ExternalGrantDocumentsProvider;"),"External test provider leaked into ordinary release");
                require(real.keySet().stream().noneMatch(n->n.startsWith("Lcom/fantadrom/copperscroll/storage/runtime/ExternalGrantOwnerService")),"Grant owner service leaked into ordinary release");
                byte[] manifest=release.getInputStream(release.getEntry("AndroidManifest.xml")).readAllBytes();
                require(!new String(manifest,StandardCharsets.UTF_8).contains("p6aharness") && !new String(manifest,StandardCharsets.UTF_16LE).contains("p6aharness"),"Harness manifest leaked into ordinary release");
                for(String type:List.of("ControlledDocumentsProvider","P6aHelperService","P6aProviderProbeActivity","HarnessFaults","ProviderRestartFaults","QualificationSession","QualificationDiagnostics","QualificationIdentity"))
                    require(test.containsKey("Lcom/fantadrom/copperscroll/p6aharness/"+type+";"),"P6a test component missing: "+type);
            }
            System.out.println("P6a ordinary release isolation PASS; runtime=NOT_RUN"); return;
        }
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
            require(appClasses.stream().noneMatch(name -> name.startsWith("Lcom/fantadrom/copperscroll/storage/runtimechecks/")),
                "Synthetic crypto test helpers must not ship in production target");
            require(app.getEntry("assets/p2/crypto.properties") == null, "Synthetic key fixture must not ship in production target");
            var zdk = appIndex.classes.get("Lcom/fantadrom/copperscroll/storage/crypto/ZoneKey;");
            require((zdk.fields.getOrDefault("key:[B", 0) & 2) != 0, "ZDK storage must remain private");
            require(zdk.methods.entrySet().stream().noneMatch(e -> (e.getValue() & 1) != 0 && e.getKey().endsWith(")[B")),
                "ZoneKey must not expose a public raw-byte exporter");
            require(!appClasses.contains(TEST) && testClasses.contains(TEST), "Test class belongs in instrumentation APK only");
            String p2Suite = "Lcom/fantadrom/copperscroll/storage/runtime/P2CryptoRuntimeTest;";
            require(testClasses.contains(p2Suite) && !appClasses.contains(p2Suite), "P2 suite must belong to test APK only");
            for (String method : List.of("primitiveKnownAnswers", "independentFormatFixture", "independentEnvelopesAndExpectedZone",
                    "ambiguousCredentialAndUnchangedSecret", "kdfLimitsAndCancellation", "recordTamperingAndNoPlaintext",
                    "freshRandomnessAndRngFailure", "prepareNewKeyAndCleanup"))
                require((testIndex.classes.get(p2Suite).methods.getOrDefault(method + "()V", 0) & 1) != 0,
                    "P2 expected runtime method missing from processed test APK: " + method);
            boolean benchmark = Path.of(args[6]).getFileName().toString().equals("p2-benchmark");
            String p3Suite = "Lcom/fantadrom/copperscroll/storage/runtime/P3TransactionRuntimeTest;";
            require(testClasses.contains(p3Suite) && !appClasses.contains(p3Suite), "P3 suite must belong to test APK only");
            for(String method : List.of("createAddMutateReopen", "commitUnknownReconciliationAndRevision",
                    "recoveryIsolationAndNoPasswordFallback", "lazyVerificationAndUnsupportedSchema", "ownerRevokeAndClosingSave",
                    "namespaceAndLocalStateDurability", "catalogueSplitsAndLimits", "boundedStoreAndConflict"))
                require((testIndex.classes.get(p3Suite).methods.getOrDefault(method+"()V",0) & 1) != 0,
                    "P3 expected runtime method missing: " + method);
            boolean p6a=Path.of(args[6]).getFileName().toString().equals("p6a-runtime");
            String p6aSuite="Lcom/fantadrom/copperscroll/storage/runtime/P6aSafRuntimeTest;";
            require(!appClasses.contains(p6aSuite),"P6a suite duplicated in target");
            if(p6a) {
                require(testClasses.contains(p6aSuite),"P6a suite missing from test APK");
                String external="Lcom/fantadrom/copperscroll/storage/runtime/ExternalGrantDocumentsProvider;";
                require(testClasses.contains(external) && !appClasses.contains(external),"External grant provider must belong to the distinct test APK only");
                String owner="Lcom/fantadrom/copperscroll/storage/runtime/ExternalGrantOwnerService;";
                require(testClasses.contains(owner) && appClasses.stream().noneMatch(n->n.startsWith(owner.substring(0,owner.length()-1))),"Grant owner must belong to the distinct test APK only");
                // A separately launched test-UID provider cannot use the target's shared Kotlin
                // runtime. Audit its real post-R8 code, not just the combined instrumentation loader.
                Set<String> standalone=new HashSet<>(List.of(external,owner));
                List<String> pending=new ArrayList<>(standalone);
                for(int p=0;p<pending.size();p++) for(var edge:testIndex.edges) if(edge.caller.equals(pending.get(p))) {
                    List<String> types=new ArrayList<>();
                    if(edge.type!=null) types.add(edge.type);
                    if(edge.member!=null) { types.add(edge.member.owner); types.addAll(edge.member.types); }
                    for(String t:types) {
                        while(t.startsWith("[")) t=t.substring(1);
                        if(!t.startsWith("L") || t.startsWith("Landroid/") || t.startsWith("Ljava/")) continue;
                        require(testClasses.contains(t),"External grant owner/provider requires class outside its standalone loader: "+t);
                        if(standalone.add(t)) pending.add(t);
                    }
                }
                P6aManifestAudit.check(app,tests);
                for(String method:List.of("explicitModesAndNegativeAdmission","createTransactionCopyCompactRekey","readOnlyPipeVirtualAndStaleMetadata","grantLossReplacementAndProfileMismatch","positionalShortInterruptedAndErrorIo","dataForceDoesNotPublishNamespaceAndUnknownCreate","noBackupPublicationFaultsAndWholeRecords","aliasInterprocessLocksAndContinuousReopen","blockingIoRevokeRetainsOwnerUntilQuiescence","processRestartLocalLockAndBoundGenerationProgress","pickerCancellationStaleAndRequiredFlags","corruptLostAndVersionedLocalStateNeverReset","switchRevocationLostAckAndDistinctRecoveryCredentials"))
                    require((testIndex.classes.get(p6aSuite).methods.getOrDefault(method+"()V",0)&1)!=0,"P6a runtime method missing: "+method);
                String qualificationSuite="Lcom/fantadrom/copperscroll/storage/runtime/P6aQualificationController;";
                require(testClasses.contains(qualificationSuite) && !appClasses.contains(qualificationSuite),"Qualification controller must live only in test APK");
                for(String method:List.of("prepareWorker","recoverWorker","prepareReboot","recoverReboot"))
                    require((testIndex.classes.get(qualificationSuite).methods.getOrDefault(method+"()V",0)&1)!=0,"Qualification method missing: "+method);
                for(String type:List.of("ControlledDocumentsProvider","P6aHelperService","P6aProviderProbeActivity","HarnessFaults","ProviderRestartFaults","QualificationSession","QualificationDiagnostics","QualificationIdentity"))
                    require(appClasses.contains("Lcom/fantadrom/copperscroll/p6aharness/"+type+";"),"P6a target harness missing: "+type);
            } else require(appClasses.stream().noneMatch(n->n.startsWith("Lcom/fantadrom/copperscroll/p6aharness/")),"P6a harness present without opt-in");
            String p5bSuite="Lcom/fantadrom/copperscroll/storage/runtime/P5bChangeSecretRuntimeTest;";
            require(testClasses.contains(p5bSuite) && !appClasses.contains(p5bSuite),"P5b suite must belong to test APK only");
            for(String method:List.of("mainOnlyAndCompatibility","mainRekeyPreservesDecoy","decoyRekeyPreservesMain",
                "credentialsAndNoArtifacts","oldKeyCannotReadRekeyOrFutureTransactions","revokeSwitchAndLease",
                "restartWithDistinctCredentialContexts","preferredInputAndRepeatedChange"))
                require((testIndex.classes.get(p5bSuite).methods.getOrDefault(method+"()V",0)&1)!=0,"P5b expected runtime method missing: "+method);
            require(appClasses.contains("Lcom/fantadrom/copperscroll/storage/maintenance/RekeyBuilder;"),"Rekey builder missing from target");
            String p5aSuite="Lcom/fantadrom/copperscroll/storage/runtime/P5aMaintenanceRuntimeTest;";
            require(testClasses.contains(p5aSuite) && !appClasses.contains(p5aSuite),"P5a suite must belong to test APK only");
            for(String method:List.of("selectiveZonesAndIndependentReaders","authorizationCredentialsAndNoEffects",
                "committedLengthCopyAndCoverage","restartAndLostAcknowledgement","revokeSwitchAndExclusiveLease",
                "faultCopyBuildAndJournal","retentionSizesAndFailures","largeMediaAndMainOnlyCompatibility"))
                require((testIndex.classes.get(p5aSuite).methods.getOrDefault(method+"()V",0)&1)!=0,"P5a expected runtime method missing: "+method);
            for(String type:List.of("SnapshotCopier","MaintenanceCoordinator","MaintenanceRecovery","SelectiveCompactor")) {
                String descriptor="Lcom/fantadrom/copperscroll/storage/maintenance/"+type+";";
                require(appClasses.contains(descriptor),"P5a backend missing from target: "+type);
            }
            String p4Suite="Lcom/fantadrom/copperscroll/storage/runtime/P4VfsCardRuntimeTest;";
            require(testClasses.contains(p4Suite) && !appClasses.contains(p4Suite),"P4 suite must belong to test APK only");
            for(String method:List.of("vfsBatchDepthTrash","noteRichTextRevision","cardFieldRoundTripAndGolden","cardAtomicSaveAndDraft",
                    "masksDatesAndConstraints","templatesAndGlobalMessagePolicy","mediaStreamingAndCow","faultCommitUnknownAndRevoke",
                    "compatibilityRecoveryAndBudgets","sortingSettingsAndZoneIsolation"))
                require((testIndex.classes.get(p4Suite).methods.getOrDefault(method+"()V",0)&1)!=0,"P4 expected runtime method missing: "+method);
            require(testClasses.contains("Lcom/fantadrom/copperscroll/storage/runtime/P2KdfBenchmark;") == benchmark,
                "KDF benchmark must be compiled only for the explicit benchmark profile");
            for (String name : testClasses) {
                require(!name.startsWith("Lcom/fantadrom/copperscroll/storage/android/") &&
                    !name.startsWith("Lcom/fantadrom/copperscroll/storage/access/") &&
                    !name.startsWith("Lcom/fantadrom/copperscroll/storage/format/") &&
                    !name.startsWith("Lcom/fantadrom/copperscroll/storage/io/") &&
                    !name.startsWith("Lcom/fantadrom/copperscroll/storage/transaction/") &&
                    !name.startsWith("Lcom/fantadrom/copperscroll/storage/vfs/") &&
                    !name.startsWith("Lcom/fantadrom/copperscroll/storage/cards/") &&
                    !name.startsWith("Lcom/fantadrom/copperscroll/storage/maintenance/") &&
                    !name.startsWith("Lcom/fantadrom/copperscroll/storage/crypto/") && !name.startsWith("Lorg/bouncycastle/") &&
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
            require(MessageDigest.isEqual(read(tests, "assets/p2/crypto.properties", 16384),
                Files.readAllBytes(golden.getParent().getParent().resolve("p2/crypto.properties"))),
                "Android P2 fixture differs from independent frozen JVM fixture");
            for(String fixture:List.of("golden.tsv","negative.tsv")) {
                require(app.getEntry("assets/p4/"+fixture)==null,"P4 synthetic fixtures must not ship in target");
                require(MessageDigest.isEqual(read(tests,"assets/p4/"+fixture,65536),
                    Files.readAllBytes(golden.getParent().getParent().resolve("p4/"+fixture))),"P4 independent fixture mismatch");
            }
            for (String name : List.of("ICU-76.1.txt", "CBOR-0.9.txt", "BC-1.86.txt", "NOTICE.txt"))
                require(app.getEntry("assets/third-party/" + name) != null, "License/NOTICE missing");
            String summary = "artifactAudit=PASS\nvariant=p1Release\nruntime=NOT_RUN\n" + identity + linkage +
                "targetApk=" + target.getFileName() + "\ntestApk=" + test.getFileName() + "\n" +
                "targetSha256=" + hash(Files.newInputStream(target)) + "\n" +
                "testSha256=" + hash(Files.newInputStream(test)) + "\n" +
                "targetBackendDefinitions=present\ntestBackendDefinitions=absent\n" +
                "cryptoBackendAndBouncyCastle=target-only\nzdkPublicRawExporter=absent\n" +
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
