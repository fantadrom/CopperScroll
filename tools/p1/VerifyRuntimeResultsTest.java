import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.Map;
import com.google.testing.platform.proto.api.core.TestCaseProto.TestCase;
import com.google.testing.platform.proto.api.core.TestResultProto.TestResult;
import com.google.testing.platform.proto.api.core.TestStatusProto.TestStatus;
import com.google.testing.platform.proto.api.core.TestSuiteResultProto.TestSuiteResult;
import com.google.testing.platform.proto.api.core.PlatformErrorProto.PlatformError;

/** Small executable regression suite; Gradle supplies its existing runner protocol, also on Windows. */
public final class VerifyRuntimeResultsTest {
    private static int checks;
    private static Path root, reports, manifest;

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("p1-result-tests-");
        try {
            reset(); write(suite("honor", 7, "")); pass("full PASS");
            reset(); write("<testsuites tests=\"7\"><testsuite tests=\"7\">" +
                suite("honor", 7, "") + "</testsuite></testsuites>"); pass("nested counts are not added");
            reset(); VerifyRuntimeResults.saveManifest(manifest, Map.of("honor", "Honor - 15", "emulator", "AVD - 17"));
            proof(2, 7, TestStatus.PASSED);
            write("<testsuites tests=\"14\">" + suite("Honor - 15", 7, "") +
                suite("AVD - 17", 7, "") + "</testsuites>"); pass("seven on each of two devices");
            reset(); write(suite("honor", 0, "")); reject("zero tests");
            reset(); write(suite("honor", 6, "")); reject("incomplete suite");
            reset(); write(suite("honor", 7, "<failure/>")); reject("failure child");
            reset(); write(suite("honor", 7, "<error/>")); reject("error child");
            reset(); write(suite("honor", 7, "<skipped/>")); reject("skipped child");
            reset(); write(suite("honor", 7, "").replace("failures=\"0\"", "failures=\"1\"")); reject("failure summary");
            reset(); reject("missing report");
            reset(); write(suite("honor", 7, ""));
            Files.setLastModifiedTime(reports.resolve("TEST-runtime.xml"), FileTime.fromMillis(1)); reject("stale report");
            reset(); write(suite("honor", 7, "").replace("tests=\"7\"", "tests=\"0\"")); reject("false zero summary");
            reset(); write(suite("honor", 7, "").replace("pinLeadingZerosAndRejectedRepertoire", "unexpected")); reject("wrong method set");
            reset(); write(suite("honor", 7, "").replace(VerifyRuntimeResults.SUITE, "other.Test")); reject("wrong test class");
            reset(); write(suite("unknown", 7, "")); reject("unknown device");
            reset(); VerifyRuntimeResults.saveManifest(manifest, Map.of("honor", "Honor - 15", "emulator", "AVD - 17"));
            write(suite("honor", 7, "")); reject("missing device results");
            reset(); write(suite("honor", 7, ""));
            Files.writeString(reports.resolve("TEST-duplicate.xml"), suite("honor", 7, "")); reject("duplicate report");
            reset(); write(suite("honor", 7, "").replace("<testcase ", "<testcase status=\"notrun\" ")); reject("not executed status");
            reset(); write("<!DOCTYPE testsuite [<!ENTITY ext SYSTEM 'file:///nonexistent'>]>" + suite("honor", 7, ""));
            reject("external entity XML");
            reset(); Files.delete(manifest); write(suite("honor", 7, "")); reject("missing fresh-run manifest");
            reset(); write(suite("honor", 7, "")); Files.writeString(reports.resolve("test-result-exit-code.txt"), "1");
            reject("runner crash despite seven clean XML cases");
            reset(); write(suite("honor", 7, "")); proof(1, 7, TestStatus.SKIPPED); reject("XML hides runner skipped state");
            reset(); write(suite("honor", 7, "")); proof(1, 7, TestStatus.STARTED); reject("XML hides unfinished test");
            reset(); write(suite("honor", 7, "")); Files.delete(reports.resolve("test-result.pb")); reject("missing machine results");
            reset(); write(suite("honor", 7, "")); proof(1, 6, TestStatus.PASSED); reject("incomplete completed-test proof");
            reset(); write(suite("honor", 7, ""));
            TestSuiteResult p = TestSuiteResult.parseFrom(Files.readAllBytes(reports.resolve("test-result.pb")));
            Files.write(reports.resolve("test-result.pb"), p.toBuilder().setPlatformError(PlatformError.getDefaultInstance()).build().toByteArray());
            reject("runner platform error");
            reset(); write(suite("honor", 7, ""));
            Files.setLastModifiedTime(reports.resolve("test-result.pb"), FileTime.fromMillis(1)); reject("stale machine result");
            reset(); write(suite("honor", 7, ""));
            p = TestSuiteResult.parseFrom(Files.readAllBytes(reports.resolve("test-result.pb")));
            Files.write(reports.resolve("test-result.pb"), p.toBuilder().setTestStatus(TestStatus.FAILED).build().toByteArray());
            reject("nonpassing machine suite");
            reset(); write(suite("honor", 7, "")); Files.write(reports.resolve("test-result.pb"), new byte[]{-1});
            reject("corrupt machine result");
            Path build = Files.createTempDirectory(root, "build-");
            Path oldXml = VerifyRuntimeResults.results(build).resolve("TEST-old.xml");
            Files.createDirectories(oldXml.getParent()); Files.writeString(oldXml, suite("honor", 7, ""));
            VerifyRuntimeResults.saveManifest(VerifyRuntimeResults.manifest(build), Map.of("honor", "Honor - 15"));
            Files.writeString(VerifyRuntimeResults.summary(build), "runtime=PASS\n");
            VerifyRuntimeResults.clearPreviousResults(build);
            if (Files.exists(oldXml) || Files.exists(VerifyRuntimeResults.manifest(build)) ||
                Files.exists(VerifyRuntimeResults.summary(build))) throw new AssertionError("Previous PASS survived preparation");
            checks++;
            Path bound = Files.createTempDirectory(root, "bound-");
            VerifyRuntimeResults.saveManifest(VerifyRuntimeResults.manifest(bound), Map.of("honor", "Honor - 15"));
            Path identity = bound.resolve("reports/p1-runtime/artifact-manifest.properties");
            String metadata = "mappingMarkers=PASS\nartifactBundleId=bundle\ntargetSha256=target\ntestSha256=test\n" +
                "targetMapId=target-map\ntestMapId=test-map\ntargetMappingSha256=target-mapping\ntestMappingSha256=test-mapping\n";
            Files.writeString(identity, metadata); VerifyRuntimeResults.bindArtifacts(bound);
            VerifyRuntimeResults.verifyArtifactBinding(bound); checks++;
            Files.writeString(identity, metadata.replace("artifactBundleId=bundle", "artifactBundleId=other"));
            boolean rejected = false;
            try { VerifyRuntimeResults.verifyArtifactBinding(bound); } catch (IllegalStateException expected) { rejected = true; }
            if (!rejected) throw new AssertionError("Changed artifacts accepted"); checks++;
            Files.delete(identity); rejected = false;
            try { VerifyRuntimeResults.verifyArtifactBinding(bound); } catch (java.io.IOException expected) { rejected = true; }
            if (!rejected) throw new AssertionError("Missing artifact identity accepted"); checks++;
            System.out.println("P1 runtime result verifier: " + checks + " checks PASS (host fixtures only; Android NOT RUN)");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(p);
            }
        }
    }

    private static void reset() throws Exception {
        Path dir = Files.createTempDirectory(root, "case-");
        reports = Files.createDirectories(dir.resolve("reports")); manifest = dir.resolve("run.properties");
        VerifyRuntimeResults.saveManifest(manifest, Map.of("honor", "Honor - 15"));
        proof(1, 7, TestStatus.PASSED);
    }
    private static void proof(int devices, int count, TestStatus status) throws Exception {
        var suite = TestSuiteResult.newBuilder().setTestStatus(TestStatus.PASSED);
        for (int device = 0; device < devices; device++) {
            int i = 0;
            for (String name : VerifyRuntimeResults.EXPECTED.stream().sorted().toArray(String[]::new)) {
                if (i++ >= count) break;
                suite.addTestResult(TestResult.newBuilder().setTestStatus(status).setTestCase(TestCase.newBuilder()
                    .setTestPackage("com.fantadrom.copperscroll.storage.runtime").setTestClass("P1BackendRuntimeTest").setTestMethod(name)));
            }
        }
        Files.write(reports.resolve("test-result.pb"), suite.build().toByteArray());
        Files.writeString(reports.resolve("test-result-exit-code.txt"), "0");
    }
    private static String suite(String device, int count, String failure) {
        StringBuilder xml = new StringBuilder("<testsuite tests=\"").append(count).append("\" failures=\"0\" errors=\"0\" skipped=\"0\">")
            .append("<properties><property name=\"device\" value=\"").append(device).append("\"/></properties>");
        int i = 0;
        for (String name : VerifyRuntimeResults.EXPECTED.stream().sorted().toArray(String[]::new)) {
            if (i++ >= count) break;
            xml.append("<testcase classname=\"").append(VerifyRuntimeResults.SUITE).append("\" name=\"")
                .append(name).append("\">").append(failure).append("</testcase>");
        }
        return xml.append("</testsuite>").toString();
    }
    private static void write(String xml) throws Exception {
        Files.writeString(reports.resolve("TEST-runtime.xml"), xml);
    }
    private static void pass(String label) throws Exception {
        String result = VerifyRuntimeResults.verify(reports, manifest);
        if (!result.startsWith("runtime=PASS\n")) throw new AssertionError(label);
        checks++;
    }
    private static void reject(String label) throws Exception {
        try { VerifyRuntimeResults.verify(reports, manifest); }
        catch (Exception expected) { checks++; return; }
        throw new AssertionError("Accepted invalid result: " + label);
    }
}
