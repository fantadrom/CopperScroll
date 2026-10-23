import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Comparator;
import java.util.Properties;
import java.io.OutputStream;
import com.google.testing.platform.proto.api.core.TestCaseProto.TestCase;
import com.google.testing.platform.proto.api.core.TestResultProto.TestResult;
import com.google.testing.platform.proto.api.core.TestStatusProto.TestStatus;
import com.google.testing.platform.proto.api.core.TestSuiteResultProto.TestSuiteResult;

/** Synthetic XML/protobuf/provenance fixtures, never an Android execution receipt. */
public final class VerifyP6b1RuntimeResultsTest {
    private static int checks;
    private static Path root,reports,manifest;
    public static void main(String[] args) throws Exception {
        VerifyRuntimeResults.configure("p6b1"); root=Files.createTempDirectory("p6b1-result-tests-");
        Throwable primary=null;
        try {
            String completed="OK (1 test)\nINSTRUMENTATION_CODE: -1\n";
            runner(completed,true);
            runner("OK (1 test)\n",false);
            runner("INSTRUMENTATION_CODE: -1\n",false);
            runner(completed+"INSTRUMENTATION_CODE: -1\n",false);
            runner(completed+"INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n",false);
            runner(completed+"INSTRUMENTATION_FAILED\n",false);
            reset(); write(suite(8,"")); pass();
            reset(); write("<testsuites tests=\"8\"><testsuite>"+suite(8,"")+"</testsuite></testsuites>"); pass();
            reset(); VerifyRuntimeResults.saveManifest(manifest,Map.of("honor","Honor - 15","emulator","AVD - 17")); provenance();
            proof(2); write("<testsuites tests=\"16\">"+suite(8,"")+suite(8,"").replace("device=\"honor\"","device=\"emulator\"")+"</testsuites>"); pass();
            reset(); write(suite(0,"")); reject();
            reset(); write(suite(7,"")); reject();
            for(String marker:new String[]{"<failure/>","<error/>","<skipped/>"}) { reset(); write(suite(8,marker)); reject(); }
            reset(); reject();
            reset(); write(suite(8,"")); Files.delete(reports.resolve("test-result.pb")); reject();
            reset(); write(suite(8,"")); Files.writeString(reports.resolve("test-result-exit-code.txt"),"1"); reject();
            reset(); write(suite(8,"")); Files.setLastModifiedTime(reports.resolve("TEST-p6b1.xml"),java.nio.file.attribute.FileTime.fromMillis(1)); reject();
            reset(); write(suite(8,"")); Files.setLastModifiedTime(reports.resolve("test-result.pb"),java.nio.file.attribute.FileTime.fromMillis(1)); reject();
            reset(); write(suite(8,"").replace(VerifyRuntimeResults.SUITE,"com.fantadrom.copperscroll.storage.runtime.P3TransactionRuntimeTest")); reject();
            reset(); write(suite(8,"").replace("honor","unknown-device")); reject();
            reset(); write(suite(8,"")+suite(8,"")); reject();
            reset(); write(suite(8,"")); Properties p=new Properties(); try(var in=Files.newInputStream(manifest)) { p.load(in); }
            p.remove("gradleVersion"); try(var out=Files.newOutputStream(manifest)) { p.store(out,"Missing provenance fixture"); } reject();
            reset(); write(suite(8,"")); p=new Properties(); try(var in=Files.newInputStream(manifest)) { p.load(in); }
            p.remove("sourceIdentitySha256"); try(var out=Files.newOutputStream(manifest)) { p.store(out,"Missing provenance hash fixture"); } reject();
        } catch(Exception|Error failure) { primary=failure; throw failure; }
        finally {
            try {
                Path[] owned;
                try(var paths=Files.walk(root)) { owned=paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new); }
                for(Path p:owned) Files.delete(p);
            } catch(Exception|Error cleanup) { if(primary!=null) primary.addSuppressed(cleanup); else throw cleanup; }
        }
        System.out.println("P6b1 result gate: "+checks+" host checks PASS; Android NOT RUN");
    }
    private static void runner(String text,boolean expected) {
        if(LifecycleProbeController.completedRunner(text)!=expected) throw new AssertionError("Incorrect manual runner gate");
        checks++;
    }
    private static void reset() throws Exception {
        reports=root.resolve("reports"); manifest=root.resolve("run.properties");
        if(Files.exists(reports)) try(var paths=Files.walk(reports)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(p); }
        Files.createDirectories(reports); VerifyRuntimeResults.saveManifest(manifest,Map.of("honor","Honor - 15")); provenance(); proof(1);
    }
    private static void provenance() throws Exception {
        Properties p=new Properties(); try(var in=Files.newInputStream(manifest)) { p.load(in); }
        for(String key:RuntimeSourceIdentity.FIELDS) p.setProperty(key,"synthetic-host-fixture");
        p.setProperty("sourceIdentitySha256","a".repeat(64));
        try(OutputStream out=Files.newOutputStream(manifest)) { p.store(out,"Host fixtures only, NOT a device receipt"); }
    }
    private static void proof(int devices) throws Exception {
        var proof=TestSuiteResult.newBuilder().setTestStatus(TestStatus.PASSED);
        for(int i=0;i<devices;i++) for(String method:VerifyRuntimeResults.EXPECTED) proof.addTestResult(TestResult.newBuilder().setTestStatus(TestStatus.PASSED)
            .setTestCase(TestCase.newBuilder().setTestPackage("com.fantadrom.copperscroll.storage.runtime").setTestClass("P6b1SessionRuntimeTest").setTestMethod(method)));
        Files.write(reports.resolve("test-result.pb"),proof.build().toByteArray()); Files.writeString(reports.resolve("test-result-exit-code.txt"),"0");
    }
    private static String suite(int count,String marker) {
        var b=new StringBuilder("<testsuite tests=\"").append(count).append("\" failures=\"0\" errors=\"0\" skipped=\"0\" device=\"honor\">");
        int i=0; for(String method:VerifyRuntimeResults.EXPECTED) {
            if(i++>=count) break; b.append("<testcase classname=\"").append(VerifyRuntimeResults.SUITE).append("\" name=\"").append(method).append("\">").append(marker).append("</testcase>");
        }
        return b.append("</testsuite>").toString();
    }
    private static void write(String s) throws Exception { Files.writeString(reports.resolve("TEST-p6b1.xml"),s); }
    private static void pass() throws Exception { VerifyRuntimeResults.verify(reports,manifest); checks++; }
    private static void reject() throws Exception {
        try { VerifyRuntimeResults.verify(reports,manifest); } catch(Exception expected) { checks++; return; }
        throw new AssertionError("Invalid P6b1 reports accepted");
    }
}
