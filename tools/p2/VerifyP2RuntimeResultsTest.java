import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Comparator;
import com.google.testing.platform.proto.api.core.TestCaseProto.TestCase;
import com.google.testing.platform.proto.api.core.TestResultProto.TestResult;
import com.google.testing.platform.proto.api.core.TestStatusProto.TestStatus;
import com.google.testing.platform.proto.api.core.TestSuiteResultProto.TestSuiteResult;

/** Exercises the SAME result parser/protobuf/freshness gate with the distinct P2 expected set. */
public final class VerifyP2RuntimeResultsTest {
    private static int checks;
    private static Path root, reports, manifest;
    public static void main(String[] args) throws Exception {
        VerifyRuntimeResults.configure("p2");
        root=Files.createTempDirectory("p2-result-tests-");
        try {
            reset(); write(suite(8,"")); pass();
            reset(); write("<testsuites tests=\"8\"><testsuite>"+suite(8,"")+"</testsuite></testsuites>"); pass();
            reset(); VerifyRuntimeResults.saveManifest(manifest,Map.of("honor","Honor - 15","emulator","AVD - 17"));
            proof(2); write("<testsuites tests=\"16\">"+suite(8,"")+
                suite(8,"").replace("device=\"honor\"","device=\"emulator\"")+"</testsuites>"); pass();
            reset(); write(suite(0,"")); reject();
            reset(); write(suite(7,"")); reject();
            for(String marker:new String[]{"<failure/>","<error/>","<skipped/>"}) { reset(); write(suite(8,marker)); reject(); }
            reset(); reject();
            reset(); write(suite(8,"")); Files.delete(reports.resolve("test-result.pb")); reject();
            reset(); write(suite(8,"")); Files.writeString(reports.resolve("test-result-exit-code.txt"),"1"); reject();
            reset(); write(suite(8,"")); Files.setLastModifiedTime(reports.resolve("TEST-p2.xml"),java.nio.file.attribute.FileTime.fromMillis(1)); reject();
            reset(); write(suite(8,"").replace(VerifyRuntimeResults.SUITE,"com.fantadrom.copperscroll.storage.runtime.P1BackendRuntimeTest")); reject();
            VerifyRuntimeResults.configure("p2-benchmark"); reset(); write(suite(1,"")); pass();
            reset(); write(suite(0,"")); reject();
            System.out.println("P2 distinct result/benchmark profiles: "+checks+" checks PASS; Android NOT RUN");
        } finally { try(var paths=Files.walk(root)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(p); } }
    }
    private static void reset() throws Exception {
        reports=root.resolve("reports"); manifest=root.resolve("run.properties");
        if(Files.exists(reports)) try(var paths=Files.walk(reports)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(p); }
        Files.createDirectories(reports); VerifyRuntimeResults.saveManifest(manifest,Map.of("honor","Honor - 15"));
        proof(1);
    }
    private static void proof(int devices) throws Exception {
        var proof=TestSuiteResult.newBuilder().setTestStatus(TestStatus.PASSED);
        for(int i=0;i<devices;i++) for(String method:VerifyRuntimeResults.EXPECTED) proof.addTestResult(TestResult.newBuilder().setTestStatus(TestStatus.PASSED)
            .setTestCase(TestCase.newBuilder().setTestPackage("com.fantadrom.copperscroll.storage.runtime")
                .setTestClass(VerifyRuntimeResults.SUITE.substring(VerifyRuntimeResults.SUITE.lastIndexOf('.')+1)).setTestMethod(method)));
        Files.write(reports.resolve("test-result.pb"),proof.build().toByteArray());
        Files.writeString(reports.resolve("test-result-exit-code.txt"),"0");
    }
    private static String suite(int count,String marker) {
        var b=new StringBuilder("<testsuite tests=\"").append(count).append("\" failures=\"0\" errors=\"0\" skipped=\"0\" device=\"honor\">");
        int i=0; for(String method:VerifyRuntimeResults.EXPECTED) {
            if(i++>=count) break;
            b.append("<testcase classname=\"").append(VerifyRuntimeResults.SUITE).append("\" name=\"").append(method).append("\">").append(marker).append("</testcase>");
        }
        return b.append("</testsuite>").toString();
    }
    private static void write(String s) throws Exception { Files.writeString(reports.resolve("TEST-p2.xml"),s); }
    private static void pass() throws Exception { VerifyRuntimeResults.verify(reports,manifest); checks++; }
    private static void reject() throws Exception {
        try { VerifyRuntimeResults.verify(reports,manifest); } catch(Exception expected) { checks++; return; }
        throw new AssertionError("Invalid P2 reports accepted");
    }
}
