import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import com.google.testing.platform.proto.api.core.TestStatusProto.TestStatus;
import com.google.testing.platform.proto.api.core.TestSuiteResultProto.TestSuiteResult;

/** Host-only gate. Uses the existing AGP runner's result schema; no Android/production code. */
public final class VerifyRuntimeResults {
    static String SUITE = "com.fantadrom.copperscroll.storage.runtime.P1BackendRuntimeTest";
    static Set<String> EXPECTED = Set.of(
        "targetApkLoadsPinnedUnicode16Data", "unicode16CompositionAndEquivalentSecrets",
        "pinLeadingZerosAndRejectedRepertoire", "unsupportedBackendDoesNotFallBackToSystemNfc",
        "cborUnknownEnumsAndIndependentGolden", "rejectsNoncanonicalGolden", "all21SyntheticS1GoldenVectors");
    static String reportDirectory = "p1-runtime";
    static void configure(String profile) {
        require(profile.equals("p2") || profile.equals("p2-benchmark") || profile.equals("p3") || profile.equals("p4"), "Unknown runtime profile");
        if(profile.equals("p4")) {
            reportDirectory="p4-runtime"; SUITE="com.fantadrom.copperscroll.storage.runtime.P4VfsCardRuntimeTest";
            EXPECTED=Set.of("vfsBatchDepthTrash","noteRichTextRevision","cardFieldRoundTripAndGolden","cardAtomicSaveAndDraft",
                "masksDatesAndConstraints","templatesAndGlobalMessagePolicy","mediaStreamingAndCow","faultCommitUnknownAndRevoke",
                "compatibilityRecoveryAndBudgets","sortingSettingsAndZoneIsolation"); return;
        }
        if(profile.equals("p3")) {
            reportDirectory = "p3-runtime";
            SUITE = "com.fantadrom.copperscroll.storage.runtime.P3TransactionRuntimeTest";
            EXPECTED = Set.of("createAddMutateReopen", "commitUnknownReconciliationAndRevision",
                "recoveryIsolationAndNoPasswordFallback", "lazyVerificationAndUnsupportedSchema",
                "ownerRevokeAndClosingSave", "namespaceAndLocalStateDurability", "catalogueSplitsAndLimits",
                "boundedStoreAndConflict");
            return;
        }
        reportDirectory = profile.equals("p2") ? "p2-runtime" : "p2-benchmark";
        SUITE = "com.fantadrom.copperscroll.storage.runtime." +
            (profile.equals("p2") ? "P2CryptoRuntimeTest" : "P2KdfBenchmark");
        EXPECTED = profile.equals("p2") ? Set.of("primitiveKnownAnswers", "independentFormatFixture",
            "independentEnvelopesAndExpectedZone", "ambiguousCredentialAndUnchangedSecret",
            "kdfLimitsAndCancellation", "recordTamperingAndNoPlaintext", "freshRandomnessAndRngFailure",
            "prepareNewKeyAndCleanup") : Set.of("measureSelectedProfile");
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 3 || args.length == 4, "Expected prepare|verify, app build directory, SDK adb executable, optional P2 profile");
        if (args.length == 4) configure(args[3]);
        Path build = Path.of(args[1]);
        if (args[0].equals("prepare")) prepare(build, args[2]);
        else if (args[0].equals("verify")) {
            Path report = summary(build); Files.deleteIfExists(report);
            verifyArtifactBinding(build);
            String result = verify(results(build), manifest(build));
            Files.createDirectories(report.getParent()); Files.writeString(report, result);
            System.out.print(result);
        } else throw new IllegalArgumentException("Unknown mode");
    }

    static Path results(Path build) { return build.resolve("outputs/androidTest-results/connected/p1Release"); }
    static Path manifest(Path build) { return build.resolve("reports/" + reportDirectory + "/run.properties"); }
    static Path summary(Path build) { return build.resolve("reports/" + reportDirectory + "/runtime-result.txt"); }

    static void prepare(Path build, String adb) throws Exception {
        // This task is unconditionally executed BEFORE connected, never a cached input/output check.
        clearPreviousResults(build);
        if(reportDirectory.equals("p4-runtime")) RuntimeSourceIdentity.verify(build.getParent().getParent(),sourceIdentity(build));
        Map<String, String> devices = snapshot(adb, System.getenv("ANDROID_SERIAL"));
        saveManifest(manifest(build), devices);
        bindArtifacts(build);
        System.out.println(reportDirectory + " fresh run prepared: " + devices.size() + " device(s); previous runtime results removed");
    }

    private static final List<String> ARTIFACT_FIELDS = List.of("artifactBundleId", "targetSha256", "testSha256",
        "targetMapId", "testMapId", "targetMappingSha256", "testMappingSha256");
    private static Path sourceIdentity(Path build) { return build.resolve("reports/p4-runtime/build-identity.properties"); }
    private static String sourceIdentityHash(Path build) throws Exception {
        byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sourceIdentity(build)));
        StringBuilder b=new StringBuilder(); for(byte x:hash) b.append(String.format("%02x",x&255)); return b.toString();
    }
    private static Properties readProperties(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(path)) { properties.load(in); }
        return properties;
    }
    static void bindArtifacts(Path build) throws Exception {
        Properties artifacts = readProperties(build.resolve("reports/" + reportDirectory + "/artifact-manifest.properties"));
        require(artifacts.getProperty("mappingMarkers", "").equals("PASS"), "Missing matching APK/mapping diagnostic snapshot");
        Properties run = readProperties(manifest(build));
        for (String field : ARTIFACT_FIELDS) {
            String value = artifacts.getProperty(field, ""); require(!value.isBlank(), "Missing artifact identity: " + field);
            run.setProperty(field, value);
        }
        if(reportDirectory.equals("p4-runtime")) {
            Properties identity=RuntimeSourceIdentity.read(sourceIdentity(build));
            for(String field:RuntimeSourceIdentity.FIELDS) run.setProperty(field,identity.getProperty(field));
            run.setProperty("sourceIdentitySha256",sourceIdentityHash(build));
        }
        try (OutputStream out = Files.newOutputStream(manifest(build))) { run.store(out, "Fresh run bound to both processed APKs and mappings"); }
    }
    static void verifyArtifactBinding(Path build) throws Exception {
        Properties artifacts = readProperties(build.resolve("reports/" + reportDirectory + "/artifact-manifest.properties"));
        Properties run = readProperties(manifest(build));
        require(artifacts.getProperty("mappingMarkers", "").equals("PASS"), "APK/mapping diagnostic mismatch");
        for (String field : ARTIFACT_FIELDS) require(!run.getProperty(field, "").isBlank() &&
            run.getProperty(field).equals(artifacts.getProperty(field)), "Artifacts changed during runtime run: " + field);
        if(reportDirectory.equals("p4-runtime")) {
            RuntimeSourceIdentity.verify(build.getParent().getParent(),sourceIdentity(build));
            Properties identity=RuntimeSourceIdentity.read(sourceIdentity(build));
            for(String field:RuntimeSourceIdentity.FIELDS) require(identity.getProperty(field).equals(run.getProperty(field)),"Source identity changed: "+field);
            require(sourceIdentityHash(build).equals(run.getProperty("sourceIdentitySha256")),"Source provenance changed during runtime");
        }
    }

    static void clearPreviousResults(Path build) throws IOException {
        Files.deleteIfExists(summary(build)); Files.deleteIfExists(manifest(build));
        for (String relative : List.of("outputs/androidTest-results/connected/p1Release",
                "reports/androidTests/connected/p1Release",
                "intermediates/android_test_results/p1Release/connectedP1ReleaseAndroidTest",
                "outputs/connected_android_test_additional_output/p1ReleaseAndroidTest/connected")) {
            Path dir = build.resolve(relative);
            if (Files.exists(dir)) try (var paths = Files.walk(dir)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    static Map<String, String> snapshot(String adb, String selected) throws Exception {
        Map<String, String> devices = new LinkedHashMap<>();
        require(selected == null || !selected.contains(","), "Use a single ANDROID_SERIAL, or unset it for all connected devices");
        for (String line : command(adb, "devices", "-l").split("\\R")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("List of devices") || line.startsWith("*")) continue;
            String[] fields = line.split("\\s+");
            require(fields.length >= 2, "Unrecognized adb device listing");
            String serial = fields[0];
            if (selected != null && !selected.isBlank() && !selected.equals(serial)) continue;
            require(fields[1].equals("device"), "Device offline/unauthorized: " + serial);
            int sdk = Integer.parseInt(command(adb, "-s", serial, "shell", "getprop", "ro.build.version.sdk").trim());
            require(sdk >= 31, "P1 requires API31+ on every selected device: " + serial);
            String name = command(adb, "-s", serial, "shell", "getprop", "ro.product.model").trim();
            if (serial.startsWith("emulator-")) {
                String avd = command(adb, "-s", serial, "emu", "avd", "name").lines()
                    .filter(s -> !s.isBlank() && !s.equals("OK")).findFirst().orElse("");
                name = avd.isEmpty() ? serial : avd + "(AVD)";
            }
            if (name.isEmpty()) name = serial;
            String version = command(adb, "-s", serial, "shell", "getprop", "ro.build.version.release").trim();
            if (!version.isEmpty()) name += " - " + version;
            // AGP appends ambiguous-name suffixes. Require sequential runs rather than guess their identity.
            require(!devices.containsValue(name), "Ambiguous device names; connect these devices one at a time");
            devices.put(serial, name);
        }
        require(!devices.isEmpty(), "No selected online API31+ Android device; runtime NOT RUN");
        return devices;
    }

    private static String command(String... command) throws Exception {
        Path output = Files.createTempFile("p1-adb-", ".txt");
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly(); throw new IllegalStateException("adb command timed out");
            }
            require(Files.size(output) <= 65536, "adb command output oversized");
            String result = Files.readString(output);
            require(process.exitValue() == 0, "adb command failed (exit " + process.exitValue() + "): " +
                result.substring(0, Math.min(result.length(), 2048)));
            return result;
        } finally { Files.deleteIfExists(output); }
    }

    static void saveManifest(Path path, Map<String, String> devices) throws IOException {
        Properties p = new Properties(); p.setProperty("runId", UUID.randomUUID().toString());
        p.setProperty("count", Integer.toString(devices.size())); int i = 0;
        for (var device : devices.entrySet()) {
            p.setProperty("device." + i + ".serial", device.getKey());
            p.setProperty("device." + i++ + ".name", device.getValue());
        }
        Files.createDirectories(path.getParent());
        try (OutputStream out = Files.newOutputStream(path)) { p.store(out, "Fresh P1 runtime invocation; generated, do not reuse"); }
    }

    static String verify(Path directory, Path manifest) throws Exception {
        require(Files.isRegularFile(manifest), "Missing fresh-run manifest");
        Properties run = new Properties();
        try (InputStream in = Files.newInputStream(manifest)) { run.load(in); }
        require(run.getProperty("runId", "").length() > 0, "Missing run identity");
        int count = Integer.parseInt(run.getProperty("count", "0"));
        require(count > 0, "No expected devices");
        Map<String, Set<String>> found = new LinkedHashMap<>();
        Map<String, String> aliases = new HashMap<>();
        for (int i = 0; i < count; i++) {
            String serial = run.getProperty("device." + i + ".serial");
            String name = run.getProperty("device." + i + ".name");
            require(serial != null && name != null && !found.containsKey(serial), "Invalid device manifest");
            found.put(serial, new HashSet<>()); aliases.put(serial, serial);
            require(aliases.put(name, serial) == null || name.equals(serial), "Ambiguous expected devices");
        }
        require(Files.isDirectory(directory), "Missing runtime reports");
        List<Path> reports;
        try (var files = Files.walk(directory)) {
            reports = files.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".xml"))
                .sorted().collect(java.util.stream.Collectors.toList());
        }
        require(!reports.isEmpty(), "Missing runtime XML reports");
        for (Path report : reports) {
            require(Files.getLastModifiedTime(report).compareTo(Files.getLastModifiedTime(manifest)) >= 0,
                "Stale runtime XML report: " + report.getFileName());
            require(Files.size(report) <= 8 * 1024 * 1024, "Runtime XML report oversized");
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
                @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            });
            Element root = builder.parse(report.toFile()).getDocumentElement();
            require(root.getTagName().equals("testsuites") || root.getTagName().equals("testsuite"), "Unexpected XML root");
            checkTree(root);
            NodeList cases = root.getElementsByTagName("testcase");
            require(cases.getLength() > 0, "Zero tests in runtime XML: " + report.getFileName());
            // Traverse testcase elements once; parent/child suite summary counts are never added together.
            for (int i = 0; i < cases.getLength(); i++) {
                Element test = (Element) cases.item(i);
                require(SUITE.equals(test.getAttribute("classname")), "Unexpected runtime test class");
                String name = test.getAttribute("name");
                require(EXPECTED.contains(name), "Unexpected runtime test: " + name);
                String serial = deviceFor(test, aliases);
                require(serial != null, "Missing/unknown device identity in runtime XML");
                require(found.get(serial).add(name), "Duplicate runtime test on device " + serial + ": " + name);
            }
        }
        verifyRunnerProof(directory, manifest, count);
        StringBuilder summary = new StringBuilder("runtime=PASS\nvariant=p1Release\nsuite=" + SUITE + "\nrunId=")
            .append(run.getProperty("runId")).append("\n");
        for (String field : ARTIFACT_FIELDS) if (run.containsKey(field))
            summary.append(field).append('=').append(run.getProperty(field)).append('\n');
        if(reportDirectory.equals("p4-runtime")) {
            for(String field:RuntimeSourceIdentity.FIELDS) {
                require(!run.getProperty(field,"").isBlank(),"Missing build/source provenance: "+field);
                summary.append(field).append('=').append(run.getProperty(field)).append('\n');
            }
            require(run.getProperty("sourceIdentitySha256","").matches("[0-9a-f]{64}"),"Missing source provenance hash");
            summary.append("sourceIdentitySha256=").append(run.getProperty("sourceIdentitySha256")).append('\n');
        }
        for (var device : found.entrySet()) {
            require(device.getValue().equals(EXPECTED), "Incomplete runtime suite on " + device.getKey() +
                ": expected " + EXPECTED.size() + ", found " + device.getValue().size());
            summary.append("device=").append(device.getKey()).append(" tests=").append(EXPECTED.size())
                .append(" failures=0 errors=0 skipped=0\n");
        }
        return summary.toString();
    }

    private static void verifyRunnerProof(Path directory, Path manifest, int devices) throws Exception {
        // AGP32.4.1's XmlTestRunListener ignores testRunFailed and hardcodes skipped=0.
        // Its merged protobuf contains COMPLETED results, including aborted/skipped states.
        // Require both representations plus the runner exit status, never XML alone.
        Path protobuf = directory.resolve("test-result.pb"), exit = directory.resolve("test-result-exit-code.txt");
        for (Path path : List.of(protobuf, exit)) {
            require(Files.isRegularFile(path), "Missing runner machine result: " + path.getFileName());
            require(Files.getLastModifiedTime(path).compareTo(Files.getLastModifiedTime(manifest)) >= 0,
                "Stale runner machine result");
            require(Files.size(path) <= 8 * 1024 * 1024, "Runner machine result oversized");
        }
        require(Files.readString(exit).trim().equals("0"), "Android runner failed");
        TestSuiteResult suite = TestSuiteResult.parseFrom(Files.readAllBytes(protobuf));
        require(suite.getTestStatus() == TestStatus.PASSED && !suite.hasPlatformError(), "Nonpassing/crashed runner suite");
        require(suite.getTestResultCount() == EXPECTED.size() * devices, "Incomplete runner completed-test set");
        Map<String, Integer> counts = new HashMap<>();
        for (var result : suite.getTestResultList()) {
            require(result.getTestStatus() == TestStatus.PASSED && !result.hasError(), "Runner test did not complete with PASS");
            var test = result.getTestCase();
            require((test.getTestPackage() + "." + test.getTestClass()).equals(SUITE) && EXPECTED.contains(test.getTestMethod()),
                "Unexpected runner completed test");
            counts.merge(test.getTestMethod(), 1, Integer::sum);
        }
        require(counts.keySet().equals(EXPECTED) && counts.values().stream().allMatch(n -> n == devices),
            "Runner completed tests disagree with per-device XML");
    }

    private static String deviceFor(Element test, Map<String, String> aliases) {
        // AGP32.4.1 emits a properties/property name="device" at the suite. Accept serial properties too.
        for (Node node = test; node instanceof Element; node = node.getParentNode()) {
            Element element = (Element) node;
            Set<String> identities = new HashSet<>();
            for (String key : List.of("device", "serial", "deviceSerial")) {
                if (element.hasAttribute(key)) identities.add(element.getAttribute(key));
            }
            for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (!(child instanceof Element) || !child.getNodeName().equals("properties")) continue;
                NodeList properties = ((Element) child).getElementsByTagName("property");
                for (int i = 0; i < properties.getLength(); i++) {
                    Element p = (Element) properties.item(i);
                    if (Set.of("device", "serial", "deviceSerial").contains(p.getAttribute("name")))
                        identities.add(p.getAttribute("value"));
                }
            }
            if (!identities.isEmpty()) {
                Set<String> serials = new HashSet<>();
                for (String identity : identities) {
                    require(aliases.containsKey(identity), "Unknown device in runtime XML: " + identity);
                    serials.add(aliases.get(identity));
                }
                require(serials.size() == 1, "Conflicting runtime device identities");
                return serials.iterator().next();
            }
        }
        return null;
    }

    private static void checkTree(Element element) {
        String tag = element.getTagName();
        require(!Set.of("failure", "error", "skipped").contains(tag), "Failed/error/skipped runtime test");
        for (String attribute : List.of("failures", "failed", "errors", "skipped", "disabled"))
            if (element.hasAttribute(attribute))
                require(Integer.parseInt(element.getAttribute(attribute)) == 0, "Nonzero runtime " + attribute);
        if (tag.equals("testcase")) {
            require(!element.hasAttribute("status") || Set.of("passed", "run").contains(element.getAttribute("status")),
                "Nonpassing runtime test status");
            require(!element.hasAttribute("result") || Set.of("passed", "completed").contains(element.getAttribute("result")),
                "Nonpassing runtime test result");
        }
        if ((tag.equals("testsuite") || tag.equals("testsuites")) && element.hasAttribute("tests"))
            require(Integer.parseInt(element.getAttribute("tests")) == element.getElementsByTagName("testcase").getLength(),
                "XML test count disagrees with testcase elements");
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling())
            if (child instanceof Element) checkTree((Element) child);
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
