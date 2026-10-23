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
public final class VerifyP6b2RuntimeResultsTest {
    private static int checks;
    private static Path root,reports,manifest;
    public static void main(String[] args) throws Exception {
        VerifyRuntimeResults.configure("p6b2"); root=Files.createTempDirectory("p6b2-result-tests-");
        Throwable primary=null;
        try {
            String completed="OK (1 test)\nINSTRUMENTATION_CODE: -1\n";
            runner(completed,true);
            runner("OK (1 test)\n",false);
            runner("INSTRUMENTATION_CODE: -1\n",false);
            runner(completed+"INSTRUMENTATION_CODE: -1\n",false);
            runner(completed+"INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n",false);
            runner(completed+"INSTRUMENTATION_FAILED\n",false);
            reset(); write(suite(12,"")); pass();
            reset(); write("<testsuites tests=\"12\"><testsuite>"+suite(12,"")+"</testsuite></testsuites>"); pass();
            reset(); VerifyRuntimeResults.saveManifest(manifest,Map.of("honor","Honor - 15","emulator","AVD - 17")); provenance();
            proof(2); write("<testsuites tests=\"24\">"+suite(12,"")+suite(12,"").replace("device=\"honor\"","device=\"emulator\"")+"</testsuites>"); pass();
            reset(); write(suite(12,"").replace(VerifyRuntimeResults.EXPECTED.iterator().next(),"foreignMethod")); reject();
            reset(); write(suite(12,"").replace("failures=\"0\"","failures=\"1\"")); reject();
            var artifacts=new Properties();
            for(String field:java.util.List.of("artifactBundleId","targetSha256","testSha256","targetMapId","testMapId","targetMappingSha256","testMappingSha256")) artifacts.setProperty(field,"synthetic");
            VerifyRuntimeResults.verifyArtifactFields(artifacts,artifacts); checks++;
            for(String field:artifacts.stringPropertyNames()) {
                var changed=new Properties(); changed.putAll(artifacts); changed.setProperty(field,"changed");
                try { VerifyRuntimeResults.verifyArtifactFields(artifacts,changed); throw new AssertionError("Changed artifact accepted"); } catch(IllegalStateException expected) { checks++; }
            }
            reset(); write(suite(12,""));
            String[] methods=VerifyRuntimeResults.EXPECTED.toArray(new String[0]);
            write(suite(12,"").replace(methods[0],methods[1])); reject();
            reset(); write(suite(12,"")); Files.write(reports.resolve("test-result.pb"),new byte[]{-1}); reject();
            reset(); write(suite(12,"")); proof(0); reject();
            for(var status:new TestStatus[]{TestStatus.FAILED,TestStatus.SKIPPED}) {
                reset(); write(suite(12,""));
                var pbf=TestSuiteResult.newBuilder().setTestStatus(status);
                for(String method:VerifyRuntimeResults.EXPECTED) pbf.addTestResult(TestResult.newBuilder().setTestStatus(status)
                    .setTestCase(TestCase.newBuilder().setTestPackage("com.fantadrom.copperscroll.storage.runtime").setTestClass("P6b2PrivacyRuntimeTest").setTestMethod(method)));
                Files.write(reports.resolve("test-result.pb"),pbf.build().toByteArray()); reject();
            }
            for(String field:java.util.List.of("device.0.api","device.0.build")) {
                reset(); write(suite(12,"")); Properties missing=new Properties(); try(var in=Files.newInputStream(manifest)) { missing.load(in); }
                missing.remove(field); try(var out=Files.newOutputStream(manifest)) { missing.store(out,"Missing device metadata fixture"); } reject();
            }
            checkProbeFixtures();
            reset(); write(suite(0,"")); reject();
            reset(); write(suite(11,"")); reject();
            for(String marker:new String[]{"<failure/>","<error/>","<skipped/>"}) { reset(); write(suite(12,marker)); reject(); }
            reset(); reject();
            reset(); write(suite(12,"")); Files.delete(reports.resolve("test-result.pb")); reject();
            reset(); write(suite(12,"")); Files.writeString(reports.resolve("test-result-exit-code.txt"),"1"); reject();
            reset(); write(suite(12,"")); Files.setLastModifiedTime(reports.resolve("TEST-p6b2.xml"),java.nio.file.attribute.FileTime.fromMillis(1)); reject();
            reset(); write(suite(12,"")); Files.setLastModifiedTime(reports.resolve("test-result.pb"),java.nio.file.attribute.FileTime.fromMillis(1)); reject();
            reset(); write(suite(12,"").replace(VerifyRuntimeResults.SUITE,"com.fantadrom.copperscroll.storage.runtime.P3TransactionRuntimeTest")); reject();
            reset(); write(suite(12,"").replace("honor","unknown-device")); reject();
            reset(); write(suite(12,"")+suite(12,"")); reject();
            reset(); write(suite(12,"")); Properties p=new Properties(); try(var in=Files.newInputStream(manifest)) { p.load(in); }
            p.remove("gradleVersion"); try(var out=Files.newOutputStream(manifest)) { p.store(out,"Missing provenance fixture"); } reject();
            reset(); write(suite(12,"")); p=new Properties(); try(var in=Files.newInputStream(manifest)) { p.load(in); }
            p.remove("sourceIdentitySha256"); try(var out=Files.newOutputStream(manifest)) { p.store(out,"Missing provenance hash fixture"); } reject();
        } catch(Exception|Error failure) { primary=failure; throw failure; }
        finally {
            try {
                Path[] owned;
                try(var paths=Files.walk(root)) { owned=paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new); }
                for(Path p:owned) Files.delete(p);
            } catch(Exception|Error cleanup) { if(primary!=null) primary.addSuppressed(cleanup); else throw cleanup; }
        }
        System.out.println("P6b2 result gate: "+checks+" host checks PASS; Android NOT RUN");
    }
    private static void checkProbeFixtures() throws Exception {
        Properties before=new Properties(),after=new Properties();
        before.setProperty("checkpoint","PRIMARY_PREPARED"); before.setProperty("wasAuthenticated","true"); before.setProperty("pickerPending","false"); before.setProperty("preparedState","open"); before.setProperty("capabilityPresent","true"); before.setProperty("plaintextPresented","true"); before.setProperty("pid","123"); before.setProperty("processSession","11111111-1111-4111-8111-111111111111");
        after.setProperty("checkpoint","FRESH_PRIMARY_LOCKED"); after.setProperty("attemptState","open"); after.setProperty("pid","456"); after.setProperty("processSession","22222222-2222-4222-8222-222222222222");
        for(String field:java.util.List.of("capabilityPresent","pickerPending","plaintextRestored")) after.setProperty(field,"false");
        PrivacyProbeController.verifyPrimaryPair(before,after); checks++;
        for(String field:after.stringPropertyNames()) {
            Properties changed=new Properties(); changed.putAll(after);
            changed.setProperty(field,field.equals("pid")?"123":field.equals("processSession")?before.getProperty(field):"true");
            try { PrivacyProbeController.verifyPrimaryPair(before,changed); throw new AssertionError("Bad restart pair accepted"); } catch(IllegalArgumentException|IllegalStateException expected) { checks++; }
        }
        for(String missing:before.stringPropertyNames()) {
            Properties changed=new Properties(); changed.putAll(before); changed.remove(missing);
            try { PrivacyProbeController.verifyPrimaryPair(changed,after); throw new AssertionError("Incomplete preparation accepted"); } catch(IllegalArgumentException|IllegalStateException expected) { checks++; }
        }
        var pickerBefore=new Properties(); pickerBefore.putAll(before); pickerBefore.setProperty("preparedState","picker"); pickerBefore.setProperty("capabilityPresent","false"); pickerBefore.setProperty("plaintextPresented","false"); pickerBefore.setProperty("pickerPending","true");
        var pickerAfter=new Properties(); pickerAfter.putAll(after); pickerAfter.setProperty("attemptState","picker");
        PrivacyProbeController.verifyPrimaryPair(pickerBefore,pickerAfter); checks++;
        Properties scope=new Properties(); scope.setProperty("runId","run"); scope.setProperty("deviceRunId","device");
        PrivacyProbeController.verifyScope(scope,"run","device"); checks++;
        for(String field:scope.stringPropertyNames()) {
            Properties changed=new Properties(); changed.putAll(scope); changed.setProperty(field,"foreign");
            try { PrivacyProbeController.verifyScope(changed,"run","device"); throw new AssertionError("Foreign probe accepted"); } catch(IllegalStateException expected) { checks++; }
        }
        String secure="  Window #0 Window{aaa com.fantadrom.copperscroll/MainActivity}:\n    mAttrs={ fl=SECURE }";
        if(!PrivacyProbeController.secureWindows(secure)) throw new AssertionError("Secure window rejected"); checks++;
        for(String bad:new String[]{"",secure.replace("SECURE",""),secure+"\n  Window #1 Window{bbb com.fantadrom.copperscroll/Dialog}:\n mAttrs={ fl=0 }"}) {
            if(PrivacyProbeController.secureWindows(bad)) throw new AssertionError("Insecure/absent window accepted"); checks++;
        }
        String encoded=java.util.Base64.getEncoder().encodeToString("checkpoint=FRESH_PRIMARY_LOCKED\npid=123\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String receipt=PrivacyProbeController.PREFIX+encoded+"\n";
        if(!"123".equals(PrivacyProbeController.receipt(receipt,"FRESH_PRIMARY_LOCKED").getProperty("pid"))) throw new AssertionError("Receipt mismatch"); checks++;
        for(String bad:new String[]{"",receipt+receipt,receipt.replace("Base64=","Wrong=")}) {
            try { PrivacyProbeController.receipt(bad,"FRESH_PRIMARY_LOCKED"); throw new AssertionError("Missing/duplicate receipt accepted"); } catch(IllegalStateException expected) { checks++; }
        }
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
        for(int i=0;i<Integer.parseInt(p.getProperty("count"));i++) { p.setProperty("device."+i+".api","37"); p.setProperty("device."+i+".build","synthetic-host-fixture"); }
        try(OutputStream out=Files.newOutputStream(manifest)) { p.store(out,"Host fixtures only, NOT a device receipt"); }
    }
    private static void proof(int devices) throws Exception {
        var proof=TestSuiteResult.newBuilder().setTestStatus(TestStatus.PASSED);
        for(int i=0;i<devices;i++) for(String method:VerifyRuntimeResults.EXPECTED) proof.addTestResult(TestResult.newBuilder().setTestStatus(TestStatus.PASSED)
            .setTestCase(TestCase.newBuilder().setTestPackage("com.fantadrom.copperscroll.storage.runtime").setTestClass("P6b2PrivacyRuntimeTest").setTestMethod(method)));
        Files.write(reports.resolve("test-result.pb"),proof.build().toByteArray()); Files.writeString(reports.resolve("test-result-exit-code.txt"),"0");
    }
    private static String suite(int count,String marker) {
        var b=new StringBuilder("<testsuite tests=\"").append(count).append("\" failures=\"0\" errors=\"0\" skipped=\"0\" device=\"honor\">");
        int i=0; for(String method:VerifyRuntimeResults.EXPECTED) {
            if(i++>=count) break; b.append("<testcase classname=\"").append(VerifyRuntimeResults.SUITE).append("\" name=\"").append(method).append("\">").append(marker).append("</testcase>");
        }
        return b.append("</testsuite>").toString();
    }
    private static void write(String s) throws Exception { Files.writeString(reports.resolve("TEST-p6b2.xml"),s); }
    private static void pass() throws Exception { VerifyRuntimeResults.verify(reports,manifest); checks++; }
    private static void reject() throws Exception {
        try { VerifyRuntimeResults.verify(reports,manifest); } catch(Exception expected) { checks++; return; }
        throw new AssertionError("Invalid P6b2 reports accepted");
    }
}
