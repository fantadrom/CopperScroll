import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/** Explicit picker/process and two-invocation reboot controller. Never reboots a user's device. */
public final class QualificationController {
    static final String TARGET="com.fantadrom.copperscroll.p1runtime",TEST=TARGET+".test";
    static String hash(String s) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
    static String hex(byte[] b) { StringBuilder s=new StringBuilder(); for(byte v:b) s.append(String.format("%02x",v&255)); return s.toString(); }
    static final class CommandFailure extends IOException {
        final String output; CommandFailure(String message,String output) { super(message); this.output=output; }
    }
    static String command(int seconds,String... args) throws Exception {
        Path temp=Files.createTempFile("p6a2-adb-",".txt"); Process p=null; Throwable primary=null;
        try {
            p=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(temp.toFile()).start();
            boolean ended=p.waitFor(seconds,TimeUnit.SECONDS);
            QualificationLedger.require(Files.size(temp)<=2*1024*1024,"Diagnostic byte budget");
            String text=Files.readString(temp);
            if(!ended) throw new CommandFailure("Controller timeout; IO completion unknown",text);
            if(p.exitValue()!=0) throw new CommandFailure("adb failed (exit "+p.exitValue()+")",text);
            return text;
        } catch(Exception|Error e) { primary=e; throw e; }
        finally {
            try { if(p!=null && p.isAlive()) { try(var children=p.descendants()) { children.forEach(ProcessHandle::destroyForcibly); } p.destroyForcibly(); QualificationLedger.require(p.waitFor(10,TimeUnit.SECONDS),"adb still alive"); } }
            catch(Exception|Error e) { if(primary!=null) primary.addSuppressed(e); else throw e; }
            finally { try { Files.deleteIfExists(temp); } catch(Exception e) { if(primary!=null) primary.addSuppressed(e); else throw e; } }
        }
    }
    static String adb(String adb,String serial,String... tail) throws Exception {
        List<String> a=new ArrayList<>(List.of(adb,"-s",serial)); a.addAll(Arrays.asList(tail)); return command(45,a.toArray(new String[0]));
    }
    static void installed(String adb,String serial,String pkg,String expected) throws Exception {
        String text=adb(adb,serial,"shell","pm","path",pkg).trim();
        QualificationLedger.require(text.matches("package:/data/app/[a-zA-Z0-9_./+=~\\-]+/base\\.apk"),"Unexpected installed APK/splits");
        String path=text.substring("package:".length());
        String actual=adb(adb,serial,"shell","sha256sum",path).trim().split("\\s+")[0];
        QualificationLedger.require(actual.equals(expected),"Installed artifact differs from audited pair");
    }
    static Properties invoke(String adb,String serial,String method,String id,Path dir) throws Exception {
        String text;
        try { text=command(660,adb,"-s",serial,"shell","am","instrument","-w","-r","-e","class",QualificationLedger.SUITE+"#"+method,
            "-e","qualificationRunId",id,"-e","p1ExpectedVariant","p1Release",TEST+"/androidx.test.runner.AndroidJUnitRunner"); }
        catch(CommandFailure e) { Files.writeString(dir.resolve(method+"-instrumentation.txt"),e.output); throw e; }
        Files.writeString(dir.resolve(method+"-instrumentation.txt"),text);
        Properties p=QualificationLedger.terminal(text,method,id); QualificationLedger.write(dir.resolve(method+".properties"),p); return p;
    }
    static Properties base(Properties artifacts,Properties source,String id,String device,String mode) throws Exception {
        Properties p=new Properties();
        for(String k:artifacts.stringPropertyNames()) if(!k.toLowerCase(Locale.ROOT).contains("path") && !k.equals("runtime")) p.setProperty(k,artifacts.getProperty(k));
        for(String k:RuntimeSourceIdentity.FIELDS) p.setProperty(k,QualificationLedger.value(source,k));
        for(String line:List.of("schemaVersion=1","policyVersion=P6A2-RESEARCH-1","productionAdmission=REFUSED","productionApproved=false",
            "existingWriteDurable=UNKNOWN","durableCreateIdentity=UNKNOWN","durableLocalState=UNKNOWN","volume=UNKNOWN","backend=UNKNOWN",
            "physicalInterruption=NOT_RUN","controlledStorageInterruption=NOT_RUN","experimentOutcome=NOT_RUN","phase=NOT_STARTED","completedMethods=0")) {
            int eq=line.indexOf('='); p.setProperty(line.substring(0,eq),line.substring(eq+1));
        }
        p.setProperty("expected","SCRATCH_AND_WHOLE_LOCAL_RECORD_SURVIVE_INTERVENTION");
        p.setProperty("observed","NOT_RUN");
        p.setProperty("openEvidence","PREOPEN_IDENTITY,NAMESPACE_FORCE,BACKEND_CONTRACT,POWER_MODEL");
        for(String g:List.of("existingWriteDurable","durableCreateIdentity","durableLocalState")) {
            p.setProperty(g+".evidenceId","P6A2-RESEARCH-1-"+g);
            p.setProperty(g+".contract","CONDITIONAL_AOSP15_LINUX66_NOT_DEVICE_QUALIFIED");
            p.setProperty(g+".prerequisites",g.equals("durableLocalState")?"LOCAL_FS_MOUNT_DEVICE_POWER_EVIDENCE":"TRUSTED_IDENTITY_BACKEND_NAMESPACE_POWER_EVIDENCE");
        }
        p.setProperty("runId",id); p.setProperty("deviceHash",hash(device)); p.setProperty("intervention",mode);
        p.setProperty("contracts","AOSP15-4e43ad1;LINUX6.6;GAPS-OEM-BACKEND-NAMESPACE");
        return p;
    }
    static void summary(Path dir,Properties p) throws Exception {
        QualificationLedger.validate(p); QualificationLedger.write(dir.resolve("ledger.properties"),p);
        StringBuilder s=new StringBuilder();
        for(String k:List.of("schemaVersion","runId","intervention","phase","experimentOutcome","completedMethods","productionAdmission",
            "existingWriteDurable","durableCreateIdentity","durableLocalState","physicalInterruption","controlledStorageInterruption","artifactBundleId","sourceRevision","sourceFingerprint","cleanup","failureType"))
            if(p.containsKey(k)) s.append(k).append('=').append(p.getProperty(k)).append('\n');
        Files.writeString(dir.resolve("qualification-result.txt"),s);
    }
    static void archive(Path output) throws Exception {
        Path zip=output.resolve("qualification-diagnostics.zip"); long total=0;
        try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(zip)); var files=Files.walk(output)) {
            for(Path path:(Iterable<Path>)files.filter(Files::isRegularFile)::iterator) {
                if(path.equals(zip)) continue;
                long n=Files.size(path); QualificationLedger.require(n<=32L*1024*1024 && (total+=n)<=64L*1024*1024,"Archive byte budget");
                out.putNextEntry(new ZipEntry(output.relativize(path).toString().replace('\\','/'))); Files.copy(path,out); out.closeEntry();
            }
        }
    }
    public static void main(String[] args) throws Exception {
        QualificationLedger.require(args.length==4,"Expected mode build adb runId-or-new");
        String mode=args[0]; QualificationLedger.require(Set.of("process","rebootPrepare","rebootResume").contains(mode),"Unknown experiment");
        boolean resume=mode.equals("rebootResume"),reboot=!mode.equals("process");
        Path build=Path.of(args[1]), reports=build.resolve("reports/p6a-runtime"); String adb=args[2];
        RuntimeSourceIdentity.verify(build.getParent().getParent(),reports.resolve("build-identity.properties"));
        String id=resume?UUID.fromString(args[3]).toString():UUID.randomUUID().toString();
        Path output=build.resolve("reports/p6a2-qualification").resolve(id);
        if(resume) QualificationLedger.require(Files.isDirectory(output),"No prepared host run");
        else {
            Files.createDirectories(output.getParent());
            try(var history=Files.list(output.getParent())) { QualificationLedger.require(history.filter(Files::isDirectory).count()<32,"Host qualification history limit; preserve/move prior evidence before another run"); }
            Files.createDirectories(output); Files.copy(reports.resolve("artifact-manifest.properties"),output.resolve("artifact-manifest.properties"));
            Files.copy(reports.resolve("build-identity.properties"),output.resolve("build-identity.properties"));
            Files.copy(reports.resolve("p1-diagnostics.zip"),output.resolve("artifact-diagnostics.zip")); }
        List<String> failures=new ArrayList<>(); Throwable primary=null;
        try {
            Properties artifacts=QualificationLedger.read(output.resolve("artifact-manifest.properties")),source=QualificationLedger.read(output.resolve("build-identity.properties"));
            QualificationLedger.match(artifacts,QualificationLedger.read(reports.resolve("artifact-manifest.properties")),List.of("artifactBundleId","targetSha256","testSha256","targetMappingSha256","testMappingSha256"));
            QualificationLedger.match(source,QualificationLedger.read(reports.resolve("build-identity.properties")),RuntimeSourceIdentity.FIELDS);
            var devices=VerifyRuntimeResults.snapshot(adb,System.getenv("ANDROID_SERIAL"));
            String deviceSet=String.join(",",new TreeSet<>(devices.keySet().stream().map(k->{try {return hash(k);}catch(Exception e){throw new IllegalStateException(e);}}).collect(java.util.stream.Collectors.toList())));
            Path deviceFile=output.resolve("devices.txt");
            if(resume) QualificationLedger.require(Files.readString(deviceFile).equals(deviceSet),"Device set changed"); else Files.writeString(deviceFile,deviceSet);
            for(String serial:devices.keySet()) {
                Path dir=output.resolve(hash(serial)); Files.createDirectories(dir);
                // Device-specific run identity, saved before any Android action; never reused as a new attempt.
                Path state=dir.resolve("ledger.properties");
                String deviceId=resume?QualificationLedger.value(QualificationLedger.read(state),"runId"):UUID.randomUUID().toString();
                Properties ledger=base(artifacts,source,deviceId,serial,reboot?"ORDERLY_REBOOT":"WORKER_PROCESS");
                try {
                    if(resume) {
                        Properties old=QualificationLedger.read(state); QualificationLedger.validate(old);
                        QualificationLedger.require("PREPARED".equals(old.getProperty("phase")) && "NOT_RUN".equals(old.getProperty("experimentOutcome")),"Stale/consumed reboot attempt");
                    } else {
                        summary(dir,ledger);
                        for(String[] apk:new String[][]{{"p1Release/app-p1Release.apk","target"},{"androidTest/p1Release/app-p1Release-androidTest.apk","test"}})
                            Files.writeString(dir.resolve("install-"+apk[1]+".txt"),adb(adb,serial,"install","-r","-t",build.resolve("outputs/apk/"+apk[0]).toString()));
                    }
                    installed(adb,serial,TARGET,QualificationLedger.value(artifacts,"targetSha256")); installed(adb,serial,TEST,QualificationLedger.value(artifacts,"testSha256"));
                    Properties before;
                    if(resume) before=QualificationLedger.read(dir.resolve("prepareReboot.properties"));
                    else {
                        System.out.println("P6a.2: unlock selected device and choose a dedicated EMPTY scratch folder; production admission REFUSED.");
                        before=invoke(adb,serial,reboot?"prepareReboot":"prepareWorker",deviceId,dir); QualificationLedger.observation(before,false);
                    }
                    for(String k:before.stringPropertyNames()) if(!Set.of("status","method","runId","phase").contains(k)) ledger.setProperty(k,before.getProperty(k));
                    ledger.setProperty("observed","PREPARED_CHECKPOINT_ONLY"); ledger.setProperty("phase","PREPARED"); ledger.setProperty("completedMethods","1"); summary(dir,ledger);
                    if(!mode.equals("rebootPrepare")) {
                        Properties after=invoke(adb,serial,reboot?"recoverReboot":"recoverWorker",deviceId,dir);
                        QualificationLedger.recovered(before,after,reboot);
                        ledger.setProperty("observed","SCRATCH_AND_WHOLE_LOCAL_RECORD_SURVIVED"); ledger.setProperty("phase","RECOVERED"); ledger.setProperty("completedMethods","2"); ledger.setProperty("restartEvidence","CHECKED"); ledger.setProperty("experimentOutcome","PASS");
                    }
                } catch(Exception e) { ledger.setProperty("observed","FAILED_OR_INCOMPLETE"); ledger.setProperty("experimentOutcome","FAIL"); ledger.setProperty("failureType",e.getClass().getSimpleName()); failures.add(hash(serial)+": "+e.getMessage());
                    Files.writeString(dir.resolve("first-failure.txt"),e.toString());
                } finally { summary(dir,ledger); }
            }
            QualificationLedger.require(failures.isEmpty(),"Qualification experiment failed: "+failures+"; diagnostics="+output);
        } catch(Exception|Error e) {
            primary=e;
            try {
                Files.writeString(output.resolve("controller-failure.txt"),e.toString());
                Files.writeString(output.resolve("qualification-result.txt"),"schemaVersion=1\nhostRunId="+id+
                    "\nphase=CONTROLLER_FAILED\nexperimentOutcome="+(failures.isEmpty()?"NOT_RUN":"FAIL")+
                    "\nproductionAdmission=REFUSED\nmissingMandatoryExperiment=true\nfailureType="+e.getClass().getSimpleName()+"\n");
            } catch(Exception diagnostic) { e.addSuppressed(diagnostic); }
            throw e;
        } finally {
            try { archive(output); } catch(Exception diagnostic) { if(primary!=null) primary.addSuppressed(diagnostic); else throw diagnostic; }
        }
        if(mode.equals("rebootPrepare")) System.out.println("PREPARED; experimentOutcome=NOT_RUN. Restart selected devices normally, unlock, then resume with -Pp6a2RunId="+id);
        else System.out.println("experimentOutcome=PASS; exactly 2 completed methods per device; productionAdmission=REFUSED");
        System.out.println("P6a.2 receipts: "+output);
    }
}
