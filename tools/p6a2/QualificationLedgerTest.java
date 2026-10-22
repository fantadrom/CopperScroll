import java.nio.file.*;
import java.util.*;
import java.io.*;

public final class QualificationLedgerTest {
    static int checks;
    interface Action { void run() throws Exception; }
    static void reject(Action a) throws Exception { try { a.run(); } catch(Exception expected) { checks++; return; } throw new AssertionError("Expected rejection"); }
    static void check(boolean b) { if(!b) throw new AssertionError(); checks++; }
    static final String ID="22222222-2222-4222-8222-222222222222",HASH="a".repeat(64);
    static Properties observed(boolean after) {
        Properties p=new Properties();
        for(String k:List.of("rootHash","environmentHash","providerBuildHash","androidBuildHash")) p.setProperty(k,HASH);
        p.setProperty("runId",ID); p.setProperty("status",after?"RECOVERED":"PREPARED");
        p.setProperty("preparationVerified","true");
        p.setProperty("checkpointPhase",after?"RECOVERED":"PREPARED");
        p.setProperty("checkpoints",QualificationLedger.PHASES+(after?",RECOVERED":""));
        p.setProperty("authority","com.android.externalstorage.documents"); p.setProperty("providerPackage","com.android.externalstorage");
        p.setProperty("providerVersion","1"); p.setProperty("androidApi","35"); p.setProperty("pid",after?"202":"101");
        p.setProperty("bootCount","10"); p.setProperty("session",after?"33333333-3333-4333-8333-333333333333":ID);
        p.setProperty("grants","PERSISTED_RW_OBSERVED"); p.setProperty("identity","FSTAT_EQUAL_OBSERVED_NOT_QUALIFIED");
        p.setProperty("lock","INTERPROCESS_PROBE_OBSERVED"); p.setProperty("recovery","LOCAL_RECORD_HASH_AND_SCRATCH_BYTES");
        p.setProperty("cleanup","RETAINED_NO_QUALIFIED_DELETE_CONTRACT"); p.setProperty("method",after?"recoverWorker":"prepareWorker");
        return p;
    }
    static String terminal(Properties p) throws Exception {
        StringWriter w=new StringWriter(); p.store(w,null);
        return "INSTRUMENTATION_STATUS: p6a2ReceiptBase64="+Base64.getEncoder().encodeToString(w.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))+"\nINSTRUMENTATION_STATUS_CODE: 2\n"+
            "INSTRUMENTATION_STATUS: class="+QualificationLedger.SUITE+"\nINSTRUMENTATION_STATUS: test="+p.getProperty("method")+"\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n";
    }
    public static void main(String[] args) throws Exception {
        Properties before=observed(false),after=observed(true);
        QualificationLedger.recovered(before,after,false); checks++;
        after.setProperty("bootCount","11"); QualificationLedger.recovered(before,after,true); checks++;
        for(String key:List.of("runId","rootHash","environmentHash","authority","providerPackage","providerVersion","providerBuildHash","androidBuildHash","androidApi","status","preparationVerified","checkpointPhase","checkpoints","grants","identity","cleanup","lock","recovery")) {
            Properties wrong=observed(true); wrong.setProperty(key,"wrong"); reject(()->QualificationLedger.recovered(before,wrong,false));
        }
        Properties sameSession=observed(true); sameSession.setProperty("session",ID); reject(()->QualificationLedger.recovered(before,sameSession,false));
        Properties samePid=observed(true); samePid.setProperty("pid","101"); reject(()->QualificationLedger.recovered(before,samePid,false));
        reject(()->QualificationLedger.recovered(before,observed(true),true));
        String pass=terminal(before); QualificationLedger.terminal(pass,"prepareWorker",ID); checks++;
        for(String wrong:List.of("",pass.replace("OK (1 test)","OK (0 tests)"),pass.replace("_CODE: 0","_CODE: -3"),pass.replace("_CODE: 0","_CODE: -2"),pass+"INSTRUMENTATION_FAILED: crash",pass+pass,pass.replace("numtests=1","numtests=2"),pass.replace("INSTRUMENTATION_CODE: -1",""),pass.replace("INSTRUMENTATION_CODE: -1","INSTRUMENTATION_CODE: -10"),pass+"INSTRUMENTATION_CODE: -1\n"))
            reject(()->QualificationLedger.terminal(wrong,"prepareWorker",ID));
        reject(()->QualificationLedger.terminal(pass,"recoverWorker",ID));
        reject(()->QualificationLedger.terminal(pass,"prepareWorker",UUID.randomUUID().toString()));
        reject(()->QualificationLedger.parse("schemaVersion=1\nschemaVersion=2\n"));
        reject(()->QualificationLedger.parse("x="+"x".repeat(65537)));
        reject(()->QualificationLedger.read(Path.of(args[0],"work/no-such-qualification-report")));
        Properties source=new Properties(); for(String k:RuntimeSourceIdentity.FIELDS) source.setProperty(k,k);
        for(String k:List.of("sourceDirty","sourceCodeDirty","workingTreeDirty","wrapperModified")) source.setProperty(k,"false");
        source.setProperty("sourceRevision","a".repeat(40)); source.setProperty("sourceFingerprint",HASH);
        Properties artifacts=new Properties(); for(String k:List.of("artifactBundleId","targetSha256","testSha256","targetMapId","testMapId","targetMappingSha256","testMappingSha256","targetConfigurationSha256","testConfigurationSha256")) artifacts.setProperty(k,HASH);
        artifacts.setProperty("variant","p1Release"); artifacts.setProperty("mappingMarkers","PASS");
        QualificationLedger.match(artifacts,(Properties)artifacts.clone(),List.of("artifactBundleId","targetSha256","testSha256")); checks++;
        for(String k:List.of("artifactBundleId","targetSha256","testSha256")) {
            Properties wrong=(Properties)artifacts.clone(); wrong.setProperty(k,"b".repeat(64)); reject(()->QualificationLedger.match(artifacts,wrong,List.of(k)));
        }
        for(String k:RuntimeSourceIdentity.FIELDS) {
            Properties wrong=(Properties)source.clone(); wrong.setProperty(k,"foreign"); reject(()->QualificationLedger.match(source,wrong,List.of(k)));
        }
        Properties ledger=QualificationController.base(artifacts,source,ID,"synthetic-device","WORKER_PROCESS");
        QualificationLedger.validate(ledger); checks++;
        ledger.setProperty("experimentOutcome","PASS"); reject(()->QualificationLedger.validate(ledger));
        ledger.setProperty("phase","RECOVERED"); ledger.setProperty("completedMethods","2"); ledger.setProperty("restartEvidence","CHECKED");
        reject(()->QualificationLedger.validate(ledger));
        ledger.setProperty("preparationCompleted","true"); ledger.setProperty("preparationVerified","true"); ledger.setProperty("checkpointPhase","RECOVERED");
        QualificationLedger.validate(ledger); checks++;
        for(String k:List.of("productionApproved","productionAdmission","existingWriteDurable","durableCreateIdentity","durableLocalState","backend","volume","physicalInterruption","controlledStorageInterruption","policyVersion","schemaVersion")) {
            Properties wrong=(Properties)ledger.clone(); wrong.setProperty(k,"qualified"); reject(()->QualificationLedger.validate(wrong));
        }
        Properties privateData=(Properties)ledger.clone(); privateData.setProperty("selectedUri","content://synthetic"); reject(()->QualificationLedger.validate(privateData));
        // Failed helper response survives a negative terminal gate. Checkpoint != completion.
        Path temp=Files.createTempDirectory("p6a2-helper-diagnostics-");
        try {
            Properties failed=observed(false); failed.setProperty("status","error"); failed.setProperty("preparationVerified","false");
            failed.setProperty("failureCode","VERIFY_IDENTITY"); failed.setProperty("failureOrigin0","safe.Helper.verifyBytes(QualificationSession.kt:123)");
            String failure=terminal(failed).replace("_CODE: 0","_CODE: -2").replace("OK (1 test)","FAILURES!!!");
            Properties progress=QualificationController.base(artifacts,source,ID,"synthetic-device","WORKER_PROCESS");
            reject(()->QualificationController.acceptOutput(failure,"prepareWorker",ID,temp,progress));
            Properties saved=QualificationLedger.read(temp.resolve("prepareWorker-helper-response.properties"));
            check(saved.getProperty("failureCode").equals("VERIFY_IDENTITY"));
            check(saved.getProperty("failureOrigin0").contains("QualificationSession.kt:123"));
            check(progress.getProperty("checkpointPhase").equals("PREPARED"));
            check(progress.getProperty("completedMethods").equals("0") && progress.getProperty("preparationCompleted").equals("false"));
            check(progress.getProperty("preparationVerified").equals("false") && !Files.exists(temp.resolve("prepareWorker.properties")));
            progress.setProperty("phase","PREPARING"); QualificationController.failed(progress,new IllegalStateException("runner rejected"));
            QualificationController.summary(temp,progress);
            check(progress.getProperty("phase").equals("PREPARATION_FAILED") && progress.getProperty("experimentOutcome").equals("FAIL"));
            check(Files.readString(temp.resolve("qualification-result.txt")).contains("checkpointPhase=PREPARED"));
            QualificationLedger.validate(progress); checks++;
            // Lost acknowledgement and valid checkpoint alone never imply a completed method.
            failed.setProperty("preparationVerified","true");
            reject(()->QualificationController.acceptOutput(terminal(failed).replace("_CODE: 0","_CODE: -2"),"prepareWorker",ID,temp,progress));
            check(progress.getProperty("preparationCompleted").equals("false"));
            Properties foreign=(Properties)failed.clone(); foreign.setProperty("runId",UUID.randomUUID().toString());
            String oldCheckpoint=progress.getProperty("checkpointPhase"); foreign.setProperty("checkpointPhase","RECOVERED");
            reject(()->QualificationController.acceptOutput(terminal(foreign),"prepareWorker",ID,temp,progress));
            check(progress.getProperty("checkpointPhase").equals(oldCheckpoint));
            // Whitelist cannot modify source/policy/completion even in a bound response.
            failed.setProperty("productionAdmission","ALLOW"); failed.setProperty("sourceRevision","foreign"); failed.setProperty("completedMethods","2");
            QualificationLedger.observeResponse(progress,failed,"prepareWorker",ID);
            check(progress.getProperty("productionAdmission").equals("REFUSED") && progress.getProperty("sourceRevision").equals(source.getProperty("sourceRevision")) && progress.getProperty("completedMethods").equals("0"));
            QualificationLedger.observeResponse(progress,observed(true),"recoverWorker",ID);
            check(progress.getProperty("checkpointPhase").equals("RECOVERED") && progress.getProperty("pid").equals("202"));
            check(!progress.containsKey("failureCode") && progress.getProperty("helperMethod").equals("recoverWorker"));
            progress.setProperty("phase","RECOVERING"); progress.setProperty("completedMethods","1"); progress.setProperty("preparationCompleted","true");
            QualificationController.failed(progress,new IllegalStateException("lost acknowledgement"));
            check(progress.getProperty("phase").equals("RECOVERY_FAILED") && progress.getProperty("completedMethods").equals("1") && progress.getProperty("experimentOutcome").equals("FAIL"));
            reject(()->QualificationLedger.response(failure+failure));
            reject(()->QualificationController.acceptOutput("","prepareReboot",ID,temp,progress));
            check(Files.isRegularFile(temp.resolve("prepareReboot-helper-response-unavailable.txt")) && !Files.exists(temp.resolve("prepareReboot.properties")));
        } finally { try(var paths=Files.walk(temp)) { for(Path p:paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) Files.delete(p); } }
        Properties research=QualificationLedger.read(Path.of(args[0],"tools/p6a2/evidence-ledger.properties"));
        check(research.getProperty("reviewedProductionPolicy").equals("EMPTY") && research.getProperty("scopeCount").equals("4"));
        for(int n=0;n<4;n++) {
            String prefix="scope."+n+".";
            for(String k:List.of("existingWriteDurable","durableCreateIdentity","durableLocalState")) check(Set.of("UNKNOWN","NOT_APPLICABLE").contains(research.getProperty(prefix+k)));
            check(research.getProperty(prefix+"backend").equals("UNKNOWN") && research.getProperty(prefix+"volume").equals("UNKNOWN"));
            for(String k:List.of("id","endpoint","androidApi","providerBuild","androidBuild","failureModel","evidenceId","prerequisites")) check(!QualificationLedger.value(research,prefix+k).isBlank());
        }
        for(String name:List.of("README.md","android-qualification-windows.md")) {
            String doc=Files.readString(Path.of(args[0],"tools/p6a2",name));
            for(String task:List.of("p6a2ProcessQualification","p6a2RebootPrepare","p6a2RebootResume")) check(doc.contains(task));
        }
        for(String[] config:new String[][]{{"P6a2 Process Qualification","p6a2ProcessQualification"},{"P6a2 Reboot Prepare","p6a2RebootPrepare"},{"P6a2 Reboot Resume","p6a2RebootResume"}}) {
            String run=Files.readString(Path.of(args[0],".run",config[0]+".run.xml"));
            check(run.contains(":app:"+config[1]) && run.contains("-Pp6aReleaseRuntime=true"));
        }
        System.out.println("P6a.2 evidence/controller gate: "+checks+" host checks PASS; Android NOT_RUN; production admission REFUSED");
    }
}
