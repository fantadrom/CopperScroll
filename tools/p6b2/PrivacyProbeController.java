import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.io.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Host survives target death. Probes never masquerade as the automatic correctness suite.
 * Only synthetic opt-in target state; no keyboard/service changes, dumps of clipboard, or personal cloud. */
public final class PrivacyProbeController {
    static final String TARGET="com.fantadrom.copperscroll.p1runtime";
    static final String RUNNER=TARGET+".test/androidx.test.runner.AndroidJUnitRunner";
    static final String PREFIX="INSTRUMENTATION_STATUS: p6b2ReceiptBase64=";
    static Path build,output; static String adb,serial,buildTools,deviceRunId,debugKey;
    public static void main(String[] args) throws Exception {
        if(args.length!=5) throw new IllegalArgumentException("mode build adb build-tools local-debug-keystore");
        String mode=args[0]; build=Path.of(args[1]); adb=args[2]; buildTools=args[3]; debugKey=args[4];
        VerifyRuntimeResults.configure("p6b2");
        RuntimeSourceIdentity.verify(build.getParent().getParent(),build.resolve("reports/p6b2-runtime/build-identity.properties"));
        var devices=VerifyRuntimeResults.snapshot(adb,System.getenv("ANDROID_SERIAL"));
        output=build.resolve("reports/p6b2-"+mode).resolve(UUID.randomUUID().toString()); Files.createDirectories(output);
        Properties provenance=load(build.resolve("reports/p6b2-runtime/build-identity.properties"));
        provenance.putAll(load(build.resolve("reports/p6b2-runtime/artifact-manifest.properties")));
        provenance.remove("runtime"); // Artifact audit is NOT a runtime receipt.
        provenance.setProperty("runId",output.getFileName().toString()); provenance.setProperty("mode",mode);
        provenance.setProperty("variant",mode.equals("release")?"ordinaryReleaseResignedLocally":"p1Release");
        provenance.setProperty("optimizationScope","androidx.**,kotlin.**,kotlinx.**; storage/ICU/crypto outside scope");
        provenance.setProperty("signing",mode.equals("release")?"existing-local-debug-key; see certificate.txt":"local-debug-test-key");
        store(output.resolve("provenance.properties"),provenance);
        Files.copy(build.resolve("reports/p6b2-runtime/p1-diagnostics.zip"),output.resolve("artifact-diagnostics.zip"));
        require(provenance.getProperty("targetSha256").equals(sha(build.resolve("outputs/apk/p1Release/app-p1Release.apk"))),"TARGET_ARTIFACT_CHANGED");
        require(provenance.getProperty("testSha256").equals(sha(build.resolve("outputs/apk/androidTest/p1Release/app-p1Release-androidTest.apk"))),"TEST_ARTIFACT_CHANGED");
        List<String> failures=new ArrayList<>(); int index=0;
        System.out.println("P6b2 "+mode+" evidence: "+output);
        for(var device:devices.entrySet()) {
            serial=device.getKey(); deviceRunId=UUID.randomUUID().toString(); Path dir=output.resolve("device-"+index++); Files.createDirectories(dir);
            Properties result=new Properties(); result.putAll(provenance); result.setProperty("deviceRunId",deviceRunId); result.setProperty("device",device.getValue()); result.setProperty("serial",serial);
            result.setProperty("api",shell("getprop ro.build.version.sdk").trim()); result.setProperty("build",shell("getprop ro.build.fingerprint").trim());
            result.setProperty("runtime","SEE_INDEPENDENT_OUTCOME_NOT_AUTOMATIC_SUITE"); result.setProperty("checkpoint","HOST_STARTED");
            result.setProperty("productionAdmission","REFUSED"); result.setProperty("observationMethod","host-adb+explicit-instrumentation");
            try {
                { // External black-box observer also runs in the isolated opt-in package, never ordinary release.
                    command(60,adb,"-s",serial,"install","-r","-t",build.resolve("outputs/apk/p1Release/app-p1Release.apk").toString());
                    command(60,adb,"-s",serial,"install","-r","-t",build.resolve("outputs/apk/androidTest/p1Release/app-p1Release-androidTest.apk").toString());
                }
                switch(mode) {
                    case "privacy": probe(dir,"P6b2PrivacyProbe",null,null,900); result.setProperty("checkpoint","PRIVACY_OBSERVATIONS"); result.setProperty("outcome","USER_OBSERVATIONS_COLLECTED; NOT_LEAK_CERTIFICATION"); break;
                    case "process": process(dir,result); break;
                    case "handoff": handoff(dir,result); break;
                    case "backup": backup(dir,result); break;
                    case "release": release(dir,result); break;
                    default: throw new IllegalArgumentException("Unknown mode");
                }
            } catch(Exception|Error e) {
                result.setProperty("outcome","FAIL"); result.setProperty("failure",safeFailure(e)); failures.add(serial+":"+e.getClass().getSimpleName());
            } finally { store(dir.resolve("runtime-result.txt"),result); }
        }
        try { RuntimeSourceIdentity.verify(build.getParent().getParent(),build.resolve("reports/p6b2-runtime/build-identity.properties")); }
        catch(Exception e) { Files.writeString(output.resolve("source-failure.txt"),safeFailure(e)); failures.add("SOURCE_IDENTITY_CHANGED"); }
        zip(output);
        if(!failures.isEmpty()) throw new IllegalStateException("P6b2 "+mode+" FAIL: "+failures+"; "+output);
        System.out.println("P6b2 "+mode+" receipts written; inspect individual statuses; no automatic acceptance inferred: "+output);
    }
    static void process(Path dir,Properties result) throws Exception {
        for(String phase:List.of("open","picker")) {
            Path phaseDir=dir.resolve(phase); Files.createDirectories(phaseDir);
            processPhase(phaseDir,result,phase); result.setProperty(phase,"OBSERVED_PRIMARY_DEATH_FRESH_LOCKED");
        }
    }
    static void processPhase(Path dir,Properties result,String phase) throws Exception {
        Path preparing=dir.resolve("prepare-interrupted.txt");
        Process child=instrument(preparing,"P6b2ProcessProbe#preparePrimary","p6b2ProcessState",phase);
        Throwable primary=null;
        try {
            Properties before=awaitReceipt(preparing,"PRIMARY_PREPARED",child,70);
            store(dir.resolve("before-kill.properties"),before); // Persist before destructive event/gate.
            String actual=shell("pidof "+TARGET+" || true").trim();
            require(actual.equals(before.getProperty("pid")),"PREPARE_PID_MISMATCH");
            shell("am force-stop "+TARGET);
            waitUntil(20,()->shell("pidof "+TARGET+" || true").trim().isEmpty(),"PRIMARY_DEATH_NOT_OBSERVED");
            require(child.waitFor(20,TimeUnit.SECONDS),"INTERRUPTED_RUNNER_STILL_RUNNING");
            result.setProperty("interruptedRunnerExit",Integer.toString(child.exitValue())); result.setProperty("prepareRunner","INTENTIONALLY_INTERRUPTED_NOT_COMPLETED_PASS");
            Path fresh=probe(dir,"P6b2ProcessProbe#verifyFreshPrimary","p6b2ProcessState",phase,80);
            Properties after=receipt(Files.readString(fresh),"FRESH_PRIMARY_LOCKED"); store(dir.resolve("after-restart.properties"),after);
            verifyPrimaryPair(before,after);
            result.setProperty("checkpoint","FRESH_PRIMARY_LOCKED");
            result.setProperty("outcome","OBSERVED_PRIMARY_DEATH_FRESH_LOCKED"); result.setProperty("powerLoss","NOT_TESTED");
        } catch(Exception|Error e) { primary=e; throw e; }
        finally { stop(child,primary); }
    }
    static void handoff(Path dir,Properties result) throws Exception {
        for(String event:List.of("home","screenoff")) {
            Path log=dir.resolve(event+"-instrumentation.txt"); Process child=instrument(log,"P6b2HandoffProbe","p6b2Event",event);
            Throwable primary=null;
            try {
                Properties checkpoint=awaitReceipt(log,"HANDOFF_WAITING",child,70); store(dir.resolve(event+"-checkpoint.properties"),checkpoint);
                shell(event.equals("home")?"input keyevent KEYCODE_HOME":"input keyevent KEYCODE_SLEEP");
                Properties observed=awaitReceipt(log,"HANDOFF_EVENT_OBSERVED",child,45); store(dir.resolve(event+"-observed.properties"),observed);
                if(event.equals("screenoff")) {
                    shell("input keyevent KEYCODE_WAKEUP");
                    System.out.println("Unlock "+serial+" yourself now if needed; no PIN or keyguard settings are changed.");
                }
                require(child.waitFor(80,TimeUnit.SECONDS),"HANDOFF_TIMEOUT");
                require(child.exitValue()==0 && LifecycleProbeController.completedRunner(Files.readString(log)),"HANDOFF_RUNNER_FAILED");
                Properties done=receipt(Files.readString(log),"HANDOFF_RECREATED_LOCKED"); store(dir.resolve(event+"-result.properties"),done);
                verifyScope(done,output.getFileName().toString(),deviceRunId);
                require(event.equals(done.getProperty("event")) && "true".equals(done.getProperty("actualRecreation")) && "false".equals(done.getProperty("automaticReentry")),"HANDOFF_RESULT_INCOMPLETE");
                result.setProperty(event,"OBSERVED_ACTUAL_EVENT_AND_RECREATION_LOCKED");
            } catch(Exception|Error e) { primary=e; throw e; } finally {
                try { stop(child,primary); }
                finally { if(event.equals("screenoff")) try { shell("input keyevent KEYCODE_WAKEUP"); } catch(Exception cleanup) { if(primary!=null) primary.addSuppressed(cleanup); else throw cleanup; } }
            }
        }
        result.setProperty("checkpoint","HANDOFF_RECREATED_LOCKED");
        result.setProperty("outcome","OBSERVED_HANDOFF_BOUNDARIES; narrowBeginHandoffBeforeRecreate=true");
    }
    static void backup(Path dir,Properties result) throws Exception {
        result.setProperty("checkpoint","STATIC_EXCLUSIONS_CHECKED");
        result.setProperty("staticExclusions","PASS_ACTUAL_APK_AUDIT"); result.setProperty("restoreAttempt","NOT_RUN");
        result.setProperty("transportContents","NOT_RUN_NO_PRIVILEGED_TRANSPORT_READ"); result.setProperty("oemD2D","NOT_RUN_SEPARATE_DISPOSABLE_DEVICE_REQUIRED");
        result.setProperty("crossPlatformIos","NOT_RUN_NO_MATCHING_IOS_APP_OR_TRANSFER_RIG; refuse-agent defense not OEM evidence");
        if(!serial.startsWith("emulator-") || !shell("getprop ro.kernel.qemu").trim().equals("1")) {
            result.setProperty("outcome","NOT_RUN_PERSONAL_DEVICE_NO_BACKUP_REQUEST_OR_SETTINGS_CHANGE"); return;
        }
        // Only the ALREADY selected local transport, never user's cloud. Never enable/switch transport.
        String selected=shell("bmgr list transports").lines().filter(s->s.stripLeading().startsWith("*")).findFirst().orElse("");
        if(!selected.contains("com.android.localtransport") || !shell("bmgr enabled").contains("currently enabled")) {
            result.setProperty("outcome","NOT_RUN_LOCAL_TRANSPORT_NOT_ALREADY_SELECTED_AND_ENABLED"); return;
        }
        Throwable primary=null;
        try {
            probe(dir,"P6b2BackupProbe#seedCanaries",null,null,80);
            String response=shell("bmgr backupnow --non-incremental "+TARGET);
            Files.writeString(dir.resolve("backup-request-response.txt"),response);
            result.setProperty("systemBackupRequest","OBSERVED_RESPONSE; response is not proof of transport contents");
            String sets=shell("bmgr list sets");
            var token=java.util.regex.Pattern.compile("(?m)^\\s*([0-9a-fA-F]+)\\s+:").matcher(sets);
            if(token.find()) {
                String restore=shell("bmgr restore "+token.group(1)+" "+TARGET);
                Files.writeString(dir.resolve("restore-request-response.txt"),restore); result.setProperty("restoreAttempt","OBSERVED_LOCAL_TRANSPORT_REQUEST_NOT_OEM_CERTIFICATION");
            }
            result.setProperty("checkpoint","BACKUP_REQUEST_RESPONSE_RECORDED");
            result.setProperty("outcome","BACKUP_REQUEST_OBSERVED; contents/restore/OEM remain individually limited");
        } catch(Exception|Error e) { primary=e; throw e; }
        finally { try { probe(dir,"P6b2BackupProbe#removeOwnedCanaries",null,null,80); } catch(Exception|Error e) { if(primary!=null) primary.addSuppressed(e); else throw e; } }
    }
    static void release(Path dir,Properties result) throws Exception {
        Path unsigned=build.resolve("outputs/apk/release/app-release-unsigned.apk");
        Path signed=dir.resolve("ordinary-release-local-signed.apk"); Path key=Path.of(debugKey);
        require(Files.isRegularFile(key),"LOCAL_DEBUG_KEY_MISSING");
        String suffix=System.getProperty("os.name").startsWith("Windows")?".exe":"";
        String java=Path.of(System.getProperty("java.home"),"bin","java"+suffix).toString();
        String signer=Path.of(buildTools,"lib","apksigner.jar").toString();
        command(45,java,"-jar",signer,"sign","--ks",key.toString(),"--ks-key-alias","androiddebugkey","--ks-pass","pass:android","--out",signed.toString(),unsigned.toString());
        Files.writeString(dir.resolve("certificate.txt"),command(30,java,"-jar",signer,"verify","--print-certs",signed.toString()));
        result.setProperty("releaseUnsignedSha256",sha(unsigned)); result.setProperty("releaseInstalledSha256",sha(signed));
        Properties ordinary=load(build.resolve("reports/p6b2-runtime/ordinary-release-audit.properties"));
        require(sha(unsigned).equals(ordinary.getProperty("releaseSha256")),"ORDINARY_AUDIT_STALE"); result.putAll(ordinary); result.remove("runtime"); result.setProperty("variant","ordinaryRelease; local signature-only resign");
        result.setProperty("releaseMappingSha256",sha(build.resolve("outputs/mapping/release/mapping.txt")));
        Files.copy(build.resolve("outputs/mapping/release/mapping.txt"),dir.resolve("ordinary-release-mapping.txt"));
        // Signature-only change: actual ordinary release code/resources, no instrumentation ABI.
        try(var a=new java.util.zip.ZipFile(unsigned.toFile());var b=new java.util.zip.ZipFile(signed.toFile())) {
            for(var en= a.entries();en.hasMoreElements();) { var entry=en.nextElement(); if(!entry.getName().matches("META-INF/(MANIFEST[.]MF|[^/]+[.](SF|RSA|DSA|EC))")) require(Arrays.equals(a.getInputStream(entry).readAllBytes(),b.getInputStream(b.getEntry(entry.getName())).readAllBytes()),"RELEASE_CODE_CHANGED"); }
        }
        command(60,adb,"-s",serial,"install","-r",signed.toString()); // Signature mismatch FAIL; never uninstall/clear.
        Path publicUi=probe(dir,"P6b2OrdinaryReleaseProbe",null,null,140);
        Properties observed=receipt(Files.readString(publicUi),"ORDINARY_RELEASE_PUBLIC_UI");
        require("com.fantadrom.copperscroll".equals(observed.getProperty("observedPackage")) &&
            "true".equals(observed.getProperty("launcherObserved")) && "true".equals(observed.getProperty("homeReturnObserved")) &&
            "true".equals(observed.getProperty("noOrdinaryTargetAbi")),"ORDINARY_PUBLIC_UI_NOT_CONFIRMED");
        require(secureWindows(shell("dumpsys window windows")),"RELEASE_RETURN_WINDOW_NOT_SECURE");
        result.setProperty("nativeEditor",observed.getProperty("nativeEditor","NOT_CONFIRMED"));
        result.setProperty("observationMethod","external-p1runtime-UiAutomation-filtered-to-ordinary-package+host-window-flags; no raw hierarchy dumps");
        result.setProperty("checkpoint","ORDINARY_RELEASE_PUBLIC_UI"); result.setProperty("runtime","OBSERVED_PUBLIC_UI_ONLY");
        result.setProperty("outcome","OBSERVED_ORDINARY_RELEASE_LAUNCH_SECURE_WINDOWS_HOME_RETURN");
        result.setProperty("authenticatedEditor","NOT_APPLICABLE_NO_ENGINE_UI; P6c integration remains OPEN");
        result.setProperty("recentsImage","NOT_CONFIRMED_GUIDED_VISUAL_REQUIRED");
    }
    static void verifyScope(Properties p,String run,String deviceRun) {
        require(run.equals(p.getProperty("runId")) && deviceRun.equals(p.getProperty("deviceRunId")),"FOREIGN_PROBE_RECEIPT");
    }
    static void verifyPrimaryPair(Properties before,Properties after) {
        require("PRIMARY_PREPARED".equals(before.getProperty("checkpoint")) && "FRESH_PRIMARY_LOCKED".equals(after.getProperty("checkpoint")),"PRIMARY_CHECKPOINT_MISMATCH");
        require("true".equals(before.getProperty("wasAuthenticated")),"PRIMARY_NOT_PREPARED");
        String phase=before.getProperty("preparedState","");
        require(Set.of("open","picker").contains(phase) && phase.equals(after.getProperty("attemptState")),"PRIMARY_PHASE_MISMATCH");
        require(Boolean.toString(phase.equals("open")).equals(before.getProperty("capabilityPresent")) &&
            Boolean.toString(phase.equals("open")).equals(before.getProperty("plaintextPresented")) &&
            Boolean.toString(phase.equals("picker")).equals(before.getProperty("pickerPending")),"PRIMARY_STATE_MISMATCH");
        for(Properties p:List.of(before,after)) {
            require(p.getProperty("pid","").matches("[1-9][0-9]{0,8}"),"PRIMARY_PID_MISSING");
            UUID.fromString(p.getProperty("processSession",""));
        }
        require(!before.getProperty("pid").equals(after.getProperty("pid")),"PID_NOT_CHANGED");
        require(!before.getProperty("processSession").equals(after.getProperty("processSession")),"PROCESS_SESSION_NOT_CHANGED");
        require("false".equals(after.getProperty("capabilityPresent")) && "false".equals(after.getProperty("pickerPending")) && "false".equals(after.getProperty("plaintextRestored")),"PRIMARY_RESTORED_ACCESS");
    }
    static boolean secureWindows(String dump) {
        int count=0;
        for(String block:dump.split("(?m)(?=^  Window #)")) if(block.contains("com.fantadrom.copperscroll/") && block.contains("mAttrs=")) {
            count++; if(!block.contains(" SECURE") && !block.contains("|SECURE") && !block.contains("fl=SECURE")) return false;
        }
        return count>0;
    }
    static Path probe(Path dir,String clazz,String key,String value,int seconds) throws Exception {
        Path log=dir.resolve(clazz.replace('#','-')+"-instrumentation.txt"); Process p=instrument(log,clazz,key,value); Throwable primary=null;
        try {
            require(p.waitFor(seconds,TimeUnit.SECONDS),"PROBE_TIMEOUT"); String terminal=Files.readString(log);
            // Always save primary receipt BEFORE any strict runner gate.
            for(String line:terminal.split("\\R")) if(line.startsWith(PREFIX)) {
                String decoded=new String(Base64.getDecoder().decode(line.substring(PREFIX.length()).trim()),StandardCharsets.UTF_8);
                Files.writeString(dir.resolve(clazz.replace('#','-')+"-helper-result.txt"),decoded,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            }
            require(p.exitValue()==0 && LifecycleProbeController.completedRunner(terminal),"PROBE_RUNNER_FAILED");
            String checkpoint=clazz.startsWith("P6b2OrdinaryReleaseProbe")?"ORDINARY_RELEASE_PUBLIC_UI":clazz.startsWith("P6b2PrivacyProbe")?"PRIVACY_OBSERVATIONS":clazz.contains("seedCanaries")?"BACKUP_CANARIES_SEEDED":clazz.contains("removeOwnedCanaries")?"BACKUP_OWNED_CANARIES_REMOVED":"FRESH_PRIMARY_LOCKED";
            Properties received=receipt(terminal,checkpoint);
            verifyScope(received,output.getFileName().toString(),deviceRunId);
            if(checkpoint.equals("PRIVACY_OBSERVATIONS")) {
                for(String step:List.of("active_ime","clipboard_preview","accessibility","recents_screenshot","autofill_capture"))
                    require(Set.of("OBSERVED_OK","NOT_RUN","NOT_CONFIRMED").contains(received.getProperty(step,"MISSING")),"PRIVACY_STEP_FAILED_OR_MISSING");
                require("false".equals(received.getProperty("automatedLeakCertification")),"GUIDED_CERTIFICATION_FORBIDDEN");
            }
            return log;
        } catch(Exception|Error e) { primary=e; throw e; } finally { stop(p,primary); }
    }
    static Process instrument(Path log,String clazz,String key,String value) throws IOException {
        List<String> cmd=new ArrayList<>(List.of(adb,"-s",serial,"shell","am","instrument","-w","-r","-e","class","com.fantadrom.copperscroll.storage.runtime."+clazz));
        cmd.addAll(List.of("-e","p6b2RunId",output.getFileName().toString(),"-e","p6b2DeviceRunId",deviceRunId));
        if(key!=null) cmd.addAll(List.of("-e",key,value)); cmd.add(RUNNER);
        return new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    static Properties awaitReceipt(Path log,String checkpoint,Process child,int seconds) throws Exception {
        Properties[] result={null}; waitUntil(seconds,()-> {
            String text=Files.readString(log);
            for(String line:text.split("\\R")) if(line.startsWith(PREFIX)) {
                try { Properties p=parseReceipt(line.substring(PREFIX.length()).trim()); if(checkpoint.equals(p.getProperty("checkpoint"))) { verifyScope(p,output.getFileName().toString(),deviceRunId); result[0]=p; return true; } }
                catch(IllegalArgumentException partial) { /* still-writing base64 line; no acceptance */ }
            }
            require(child.isAlive(),"RUNNER_DIED_BEFORE_CHECKPOINT"); return false;
        },"CHECKPOINT_TIMEOUT"); return result[0];
    }
    static Properties receipt(String terminal,String checkpoint) throws Exception {
        Properties found=null;
        for(String line:terminal.split("\\R")) if(line.startsWith(PREFIX)) { Properties p=parseReceipt(line.substring(PREFIX.length()).trim()); if(checkpoint.equals(p.getProperty("checkpoint"))) { require(found==null,"DUPLICATE_CHECKPOINT"); found=p; } }
        require(found!=null,"MISSING_CHECKPOINT"); return found;
    }
    static Properties parseReceipt(String base64) throws IOException { Properties p=new Properties(); p.load(new StringReader(new String(Base64.getDecoder().decode(base64),StandardCharsets.UTF_8))); return p; }
    interface Condition { boolean get() throws Exception; }
    static void waitUntil(int seconds,Condition c,String code) throws Exception { long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds); while(!c.get()) { require(System.nanoTime()<end,code); Thread.sleep(50); } }
    static String shell(String cmd) throws Exception { return command(45,adb,"-s",serial,"shell",cmd); }
    static String command(int seconds,String... cmd) throws Exception {
        Path temp=Files.createTempFile("p6b2-command-",".txt"); Process p=null; Throwable primary=null;
        try { p=new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(temp.toFile()).start(); require(p.waitFor(seconds,TimeUnit.SECONDS),"COMMAND_TIMEOUT"); require(Files.size(temp)<=4*1024*1024,"OUTPUT_BUDGET"); String s=Files.readString(temp); if(p.exitValue()!=0) {
            var code=java.util.regex.Pattern.compile("INSTALL_FAILED_[A-Z_]+|SecurityException|Permission denied|Error type [0-9]+|Unknown command").matcher(s);
            throw new IllegalStateException("COMMAND_EXIT_"+p.exitValue()+"; "+(code.find()?code.group():"SEE_SAFE_CALLER_FRAMES"));
        } return s; }
        catch(Exception|Error e) { primary=e; throw e; } finally { stop(p,primary); Files.deleteIfExists(temp); }
    }
    static void stop(Process p,Throwable primary) throws Exception { if(p!=null && p.isAlive()) { p.destroyForcibly(); try { require(p.waitFor(10,TimeUnit.SECONDS),"CHILD_NOT_REAPED"); } catch(Exception e) { if(primary!=null) primary.addSuppressed(e); else throw e; } } }
    static void require(boolean value,String code) { if(!value) throw new IllegalStateException(code); }
    static Properties load(Path p) throws IOException { Properties v=new Properties(); try(var in=Files.newInputStream(p)) { v.load(in); } return v; }
    static void store(Path p,Properties v) throws IOException { try(var out=Files.newOutputStream(p)) { v.store(out,"P6b2 evidence; statuses are independent, no aggregate leak PASS"); } }
    static String sha(Path p) throws Exception { var digest=java.security.MessageDigest.getInstance("SHA-256"); try(var in=Files.newInputStream(p)) { byte[] b=new byte[65536]; for(int n;(n=in.read(b))>=0;) digest.update(b,0,n); } StringBuilder s=new StringBuilder(); for(byte b:digest.digest()) s.append(String.format("%02x",b&255)); return s.toString(); }
    static String safeFailure(Throwable e) {
        String code=String.valueOf(e.getMessage());
        if(!code.matches("[A-Z0-9_:; .-]{1,300}")) code="NON_CODE_MESSAGE_OMITTED";
        StringBuilder safe=new StringBuilder(e.getClass().getName()+":"+code);
        for(var frame:Arrays.stream(e.getStackTrace()).limit(16).toArray(StackTraceElement[]::new)) safe.append("; at ").append(frame);
        safe.append("; suppressed=").append(e.getSuppressed().length); return safe.toString();
    }

    static void zip(Path root) throws IOException { Path out=root.resolve("diagnostics.zip"); try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(out));var files=Files.walk(root)) { for(Path p:files.filter(Files::isRegularFile).filter(p->!p.equals(out) && !p.toString().endsWith(".apk")).sorted().toArray(Path[]::new)) { zip.putNextEntry(new java.util.zip.ZipEntry(root.relativize(p).toString().replace('\\','/'))); Files.copy(p,zip); zip.closeEntry(); } } }
}
