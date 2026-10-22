import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Bounded observation ledger v1. It has NO production authority; reviewed policy stays compiled.
 * Scope is exact evidence scope, not a proposed production registry keyed by a user's root hash.
 */
public final class QualificationLedger {
    static final String SUITE="com.fantadrom.copperscroll.storage.runtime.P6aQualificationController";
    static final String PHASES="BEFORE_CREATE,URI_RETURNED,BYTES_WRITTEN,FILE_FORCED,PREPARED";
    static void require(boolean b,String reason) { if(!b) throw new IllegalStateException(reason); }
    static Properties read(Path file) throws Exception {
        require(Files.isRegularFile(file) && Files.size(file)<=65536,"Missing/oversized ledger");
        return parse(Files.readString(file));
    }
    static Properties parse(String text) throws Exception {
        require(text.getBytes(StandardCharsets.UTF_8).length<=65536,"Ledger byte budget");
        Properties p=new Properties() {
            @Override public synchronized Object put(Object key,Object value) {
                require(!containsKey(key),"Duplicate ledger field"); return super.put(key,value);
            }
        };
        p.load(new StringReader(text)); require(p.size()<=160,"Ledger field budget");
        for(String k:p.stringPropertyNames()) require(k.matches("[a-zA-Z][a-zA-Z0-9.]{0,79}") && p.getProperty(k).length()<=4096,"Invalid ledger field");
        return p;
    }
    static void write(Path path,Properties p) throws Exception {
        Files.createDirectories(path.getParent());
        try(Writer w=Files.newBufferedWriter(path,StandardCharsets.UTF_8)) { p.store(w,"P6a.2 observations; no production authority"); }
    }
    static String value(Properties p,String key) { String v=p.getProperty(key); require(v!=null && !v.isBlank(),"Missing "+key); return v; }
    static void match(Properties a,Properties b,List<String> keys) {
        for(String k:keys) require(value(a,k).equals(value(b,k)),"Evidence mismatch: "+k);
    }
    /** Diagnostic decoding is independent of acceptance: failed JUnit blocks still carry evidence. */
    static Properties response(String terminal) throws Exception {
        require(terminal.length()<=2*1024*1024,"Runner log budget");
        String receipt=null, prefix="INSTRUMENTATION_STATUS: p6a2ReceiptBase64=";
        for(String line:terminal.split("\\R")) if(line.startsWith(prefix)) {
            require(receipt==null,"Duplicate receipt"); receipt=line.substring(prefix.length()).trim();
        }
        require(receipt!=null && receipt.length()<24000,"Missing/oversized receipt");
        return parse(new String(Base64.getDecoder().decode(receipt),StandardCharsets.UTF_8));
    }
    static final Set<String> OBSERVATION_FIELDS=Set.of("checkpointPhase","preparationVerified","pid","session","bootCount",
        "rootHash","environmentHash","authority","providerPackage","providerVersion","providerBuildHash","androidBuildHash","androidApi",
        "checkpoints","lock","productionCreate","namespacePublication","recovery","grants","identity","cleanup",
        "lastVerifiedCheck","observedWritable","observedGrantCount","observedExistingWriteDurable","expectedSize","observedSize",
        "contentHashMatches","identityMatches","identityDeviceMatches","identityInodeMatches","checkpointReadFailureType");
    static final Set<String> DIAGNOSTIC_FIELDS=Set.of("lastVerifiedCheck","observedWritable","observedGrantCount",
        "observedExistingWriteDurable","expectedSize","observedSize","contentHashMatches","identityMatches",
        "identityDeviceMatches","identityInodeMatches","checkpointReadFailureType");
    static boolean failureField(String key) { return key.matches("failure(Code|Truncated|Type[0-3]|Origin[0-3]|Frames[0-3]|Relation[0-3])"); }
    static void observeResponse(Properties ledger,Properties p,String method,String id) {
        require(method.equals(value(p,"method")) && id.equals(value(p,"runId")),"Foreign run/method");
        String status=value(p,"status");
        // Diagnostic values describe THIS response, never an earlier successful method.
        for(String k:new ArrayList<>(ledger.stringPropertyNames())) if(DIAGNOSTIC_FIELDS.contains(k) || failureField(k)) ledger.remove(k);
        ledger.setProperty("helperStatus",status); ledger.setProperty("helperMethod",method);
        for(String k:p.stringPropertyNames()) if(OBSERVATION_FIELDS.contains(k) ||
            failureField(k)) ledger.setProperty(k,p.getProperty(k));
    }
    static Properties terminal(String terminal,String method,String runId) throws Exception {
        require(terminal.length()<=2*1024*1024,"Runner log budget");
        int completed=0,runnerEnds=0; Properties block=new Properties();
        for(String line:terminal.split("\\R")) {
            if(line.startsWith("INSTRUMENTATION_CODE:")) {
                require(line.substring(line.indexOf(':')+1).trim().equals("-1"),"Runner exit mismatch"); runnerEnds++;
            }
            for(String k:List.of("class","test","numtests")) {
                String start="INSTRUMENTATION_STATUS: "+k+"=";
                if(line.startsWith(start)) block.setProperty(k,line.substring(start.length()).trim());
            }
            if(line.startsWith("INSTRUMENTATION_STATUS_CODE:")) {
                int code=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
                if(code==0) {
                    require(SUITE.equals(block.getProperty("class")) && method.equals(block.getProperty("test")) && "1".equals(block.getProperty("numtests")),"Wrong completed method"); completed++;
                } else require(code==1 || code==2,"Failed/skipped runner result");
                block.clear();
            }
        }
        require(completed==1 && runnerEnds==1 && terminal.contains("OK (1 test)") && terminal.contains("INSTRUMENTATION_CODE: -1") &&
            !terminal.contains("INSTRUMENTATION_FAILED") && !terminal.contains("shortMsg=") && !terminal.contains("FAILURES"),"Zero/partial/crashed runner");
        Properties p=response(terminal);
        require(method.equals(value(p,"method")) && runId.equals(value(p,"runId")),"Foreign run/method");
        return p;
    }
    static void observation(Properties p,boolean recovered) {
        require(value(p,"preparationVerified").equals("true"),"Preparation verification incomplete");
        require((recovered?"RECOVERED":"PREPARED").equals(value(p,"status")),"Incomplete experiment");
        require(value(p,"checkpointPhase").equals(recovered?"RECOVERED":"PREPARED"),"Incomplete checkpoint");
        require(value(p,"checkpoints").equals(PHASES+(recovered?",RECOVERED":"")),"Incomplete phase sequence");
        require(value(p,"authority").equals("com.android.externalstorage.documents"),"Wrong provider");
        for(String k:List.of("rootHash","environmentHash","providerBuildHash","androidBuildHash")) require(value(p,k).matches("[0-9a-f]{64}"),"Invalid scope hash");
        require(Integer.parseInt(value(p,"androidApi"))>=31 && Integer.parseInt(value(p,"pid"))>0 && Integer.parseInt(value(p,"bootCount"))>=0,"Unsupported process/boot evidence");
        UUID.fromString(value(p,"session"));
        require(value(p,"grants").equals("PERSISTED_RW_OBSERVED") && value(p,"identity").equals("FSTAT_EQUAL_OBSERVED_NOT_QUALIFIED"),"Missing grants/identity");
        require(value(p,"lock").equals("INTERPROCESS_PROBE_OBSERVED") && value(p,"recovery").equals("LOCAL_RECORD_HASH_AND_SCRATCH_BYTES"),"Missing lock/recovery observation");
        require(value(p,"cleanup").equals("RETAINED_NO_QUALIFIED_DELETE_CONTRACT"),"Unsafe cleanup");
    }
    static void recovered(Properties before,Properties after,boolean reboot) {
        observation(before,false); observation(after,true);
        match(before,after,List.of("runId","rootHash","environmentHash","authority","providerPackage","providerVersion","providerBuildHash","androidBuildHash","androidApi"));
        require(!value(before,"session").equals(value(after,"session")),"No fresh worker session");
        int a=Integer.parseInt(value(before,"bootCount")),b=Integer.parseInt(value(after,"bootCount"));
        if(reboot) require(b>a,"Reboot not observed");
        else require(a==b && !value(before,"pid").equals(value(after,"pid")),"Worker death not observed");
    }
    static void validate(Properties p) {
        require(value(p,"schemaVersion").equals("1"),"Unknown evidence schema");
        require(value(p,"policyVersion").equals("P6A2-RESEARCH-1"),"Unknown contract version");
        require(value(p,"productionAdmission").equals("REFUSED") && value(p,"productionApproved").equals("false"),"Observation cannot authorize production");
        for(String k:List.of("existingWriteDurable","durableCreateIdentity","durableLocalState")) require(value(p,k).equals("UNKNOWN"),"Unreviewed promotion");
        require(value(p,"volume").equals("UNKNOWN") && value(p,"backend").equals("UNKNOWN"),"Guessed backend");
        require(value(p,"physicalInterruption").equals("NOT_RUN") && value(p,"controlledStorageInterruption").equals("NOT_RUN"),"Unsupported power claim");
        require(Set.of("PASS","FAIL","NOT_RUN").contains(value(p,"experimentOutcome")),"Invalid experiment outcome");
        UUID.fromString(value(p,"runId"));
        for(String k:List.of("artifactBundleId","targetSha256","testSha256","targetMapId","testMapId","targetMappingSha256","testMappingSha256","targetConfigurationSha256","testConfigurationSha256","sourceFingerprint","deviceHash")) require(value(p,k).matches("[0-9a-f]{64}"),"Missing artifact/source/device identity");
        for(String k:RuntimeSourceIdentity.FIELDS) value(p,k);
        require(value(p,"variant").equals("p1Release") && value(p,"mappingMarkers").equals("PASS"),"Wrong or unaudited variant");
        value(p,"expected"); value(p,"observed"); value(p,"openEvidence");
        for(String g:List.of("existingWriteDurable","durableCreateIdentity","durableLocalState")) {
            require(value(p,g+".evidenceId").equals("P6A2-RESEARCH-1-"+g),"Unknown guarantee evidence");
            value(p,g+".contract"); value(p,g+".prerequisites");
        }
        require(value(p,"sourceRevision").matches("[0-9a-f]{40}"),"Missing revision");
        for(String k:List.of("sourceDirty","sourceCodeDirty","workingTreeDirty","wrapperModified")) require(Set.of("true","false").contains(value(p,k)),"Missing source state");
        if(value(p,"experimentOutcome").equals("PASS")) {
            require(value(p,"preparationCompleted").equals("true") && value(p,"preparationVerified").equals("true") && value(p,"checkpointPhase").equals("RECOVERED"),"Checkpoint alone cannot PASS");
            require(value(p,"phase").equals("RECOVERED") && value(p,"completedMethods").equals("2"),"Partial experiment cannot PASS");
            require(Set.of("WORKER_PROCESS","ORDERLY_REBOOT").contains(value(p,"intervention")),"Wrong intervention");
            require(value(p,"restartEvidence").equals("CHECKED"),"No restart proof");
        }
        // Arbitrary URI, local binding or external path fields are never accepted as public data.
        for(String k:p.stringPropertyNames()) require(!k.toLowerCase(Locale.ROOT).contains("uri") && !k.toLowerCase(Locale.ROOT).contains("path") && !k.toLowerCase(Locale.ROOT).contains("secret"),"Private field in public ledger");
    }
}
