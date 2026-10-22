import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.zip.*;
import com.google.testing.platform.proto.api.core.TestCaseProto.TestCase;
import com.google.testing.platform.proto.api.core.TestResultProto.TestResult;
import com.google.testing.platform.proto.api.core.TestStatusProto.TestStatus;
import com.google.testing.platform.proto.api.core.TestSuiteResultProto.TestSuiteResult;

/** Host archive/failure regressions, not Android receipts. Uses the shared P1 fixture profile. */
public final class RuntimeDiagnosticsTest {
    private static Path root,build,report,results;
    private static int checks;
    public static void main(String[] args) throws Exception {
        root=Files.createTempDirectory("p6a-diagnostics-test-"); Throwable primary=null;
        try {
            reset(); xml("");
            Files.writeString(VerifyRuntimeResults.summary(build),VerifyRuntimeResults.verify(results,VerifyRuntimeResults.manifest(build)));
            RuntimeDiagnostics.collect(build);
            check(Files.readString(report.resolve("runtime-diagnostics.txt")).contains("acceptance=PASS\nevidence=PASS"));
            check(entry("runtime-results/TEST-runtime.xml").contains("<testcase"));
            check(entry("target-mapping.txt").equals("synthetic target mapping"));
            check(entry("test-mapping.txt").equals("synthetic test mapping"));
            check(entry("run.properties").contains("artifactBundleId="));
            reset(); xml("<failure>StoreException: AccessLost</failure>");
            // connected failed: final gate never ran, but finalizer must preserve actual XML/logs.
            Files.writeString(results.resolve("logcat-test.txt"),"synthetic AccessLost at inspect");
            RuntimeDiagnostics.collect(build);
            check(entry("runtime-results/TEST-runtime.xml").contains("AccessLost"));
            check(entry("runtime-results/logcat-test.txt").contains("at inspect"));
            check(entry("runtime-diagnostics.txt").contains("evidence=FAIL"));
            check(!Files.exists(VerifyRuntimeResults.summary(build)));
            reset(); RuntimeDiagnostics.collect(build);
            check(entry("runtime-diagnostics.txt").contains("Missing runtime XML reports"));
            reset(); xml(""); Files.setLastModifiedTime(results.resolve("TEST-runtime.xml"),FileTime.fromMillis(1));
            RuntimeDiagnostics.collect(build);
            check(entry("runtime-diagnostics.txt").contains("excludedStale=runtime-results/TEST-runtime.xml"));
            try(var z=new ZipFile(report.resolve("p1-diagnostics.zip").toFile())) { check(z.getEntry("runtime-results/TEST-runtime.xml")==null); }
            reset(); xml(""); Files.delete(VerifyRuntimeResults.manifest(build)); RuntimeDiagnostics.collect(build);
            check(entry("runtime-diagnostics.txt").contains("preparation failed; runtime NOT RUN"));
            check(entry("runtime-diagnostics.txt").contains("runtimeResults=ABSENT"));
            VerifyRuntimeResults.recordFailure(report.resolve("preparation-failure.txt"),new IllegalStateException("synthetic adb failure"));
            RuntimeDiagnostics.collect(build);
            check(entry("preparation-failure.txt").contains("synthetic adb failure"));
            reset(); xml("");
            Files.writeString(report.resolve("attempt.properties"),"attemptId=new-synthetic-attempt\n");
            RuntimeDiagnostics.collect(build);
            check(entry("runtime-diagnostics.txt").contains("preparation failed; runtime NOT RUN"));
            reset(); xml("<skipped/>"); Exception caught=null;
            try { VerifyRuntimeResults.verifyRun(build); } catch(Exception expected) { caught=expected; }
            check(caught!=null && Files.readString(VerifyRuntimeResults.summary(build)).startsWith("runtime=FAIL"));
            Path impossible=report.resolve("not-a-directory"); Files.writeString(impossible,"owned fixture");
            Exception original=new IllegalStateException("original synthetic failure");
            VerifyRuntimeResults.recordFailure(impossible.resolve("result"),original);
            check(original.getSuppressed().length==1 && original.getMessage().equals("original synthetic failure"));
            reset(); xml(""); Files.writeString(VerifyRuntimeResults.summary(build),"runtime=PASS\nold receipt");
            VerifyRuntimeResults.clearPreviousResults(build); RuntimeDiagnostics.collect(build);
            check(!Files.exists(VerifyRuntimeResults.summary(build)));
            check(!entry("runtime-diagnostics.txt").contains("acceptance=PASS"));
        } catch(Exception|Error failure) { primary=failure; throw failure; }
        finally {
            try {
                Path[] paths; try(var walk=Files.walk(root)) { paths=walk.sorted(Comparator.reverseOrder()).toArray(Path[]::new); }
                for(Path p:paths) Files.delete(p);
            } catch(Exception|Error cleanup) { if(primary!=null) primary.addSuppressed(cleanup); else throw cleanup; }
        }
        System.out.println("P6a diagnostic archive: "+checks+" host checks PASS; Android NOT RUN");
    }
    private static void reset() throws Exception {
        build=Files.createTempDirectory(root,"build-"); report=RuntimeDiagnostics.report(build);
        RuntimeDiagnostics.begin(build);
        VerifyRuntimeResults.saveManifest(VerifyRuntimeResults.manifest(build),Map.of("synthetic","Synthetic device"));
        String bundle="a".repeat(64);
        String fields="artifactBundleId="+bundle+"\nmappingMarkers=PASS\n";
        for(String key:List.of("targetSha256","testSha256","targetMapId","testMapId","targetMappingSha256","testMappingSha256")) fields+=key+"=synthetic-host-fixture\n";
        Files.writeString(report.resolve("artifact-manifest.properties"),fields);
        VerifyRuntimeResults.bindArtifacts(build);
        Path base=report.resolve("artifacts/"+bundle+"/p1-diagnostics.zip"); Files.createDirectories(base.getParent());
        try(var zip=new ZipOutputStream(Files.newOutputStream(base))) {
            for(String n:List.of("target-mapping.txt","test-mapping.txt","artifact-manifest.properties")) {
                zip.putNextEntry(new ZipEntry(n));
                zip.write((n.equals("artifact-manifest.properties")?fields:"synthetic "+n.replace("-mapping.txt"," mapping")).getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        results=VerifyRuntimeResults.results(build); Files.createDirectories(results);
        var proof=TestSuiteResult.newBuilder().setTestStatus(TestStatus.PASSED);
        for(String method:VerifyRuntimeResults.EXPECTED) proof.addTestResult(TestResult.newBuilder().setTestStatus(TestStatus.PASSED)
            .setTestCase(TestCase.newBuilder().setTestPackage("com.fantadrom.copperscroll.storage.runtime").setTestClass("P1BackendRuntimeTest").setTestMethod(method)));
        Files.write(results.resolve("test-result.pb"),proof.build().toByteArray()); Files.writeString(results.resolve("test-result-exit-code.txt"),"0");
    }
    private static void xml(String marker) throws Exception {
        StringBuilder b=new StringBuilder("<testsuite tests=\"7\" device=\"synthetic\">");
        for(String method:VerifyRuntimeResults.EXPECTED) b.append("<testcase classname=\"").append(VerifyRuntimeResults.SUITE).append("\" name=\"").append(method).append("\">").append(marker).append("</testcase>");
        Files.writeString(results.resolve("TEST-runtime.xml"),b.append("</testsuite>").toString());
    }
    private static String entry(String name) throws Exception {
        try(var zip=new ZipFile(report.resolve("p1-diagnostics.zip").toFile()); var in=zip.getInputStream(zip.getEntry(name))) {
            return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    private static void check(boolean condition) { if(!condition) throw new AssertionError("Diagnostic regression "+checks); checks++; }
}
