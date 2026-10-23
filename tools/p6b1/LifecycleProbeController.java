import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Host controller for a separate, explicit lifecycle observation probe on every selected device. */
public final class LifecycleProbeController {
    public static void main(String[] args) throws Exception {
        if(args.length!=2) throw new IllegalArgumentException("Expected app build directory and adb");
        Path build=Path.of(args[0]); String adb=args[1];
        RuntimeSourceIdentity.verify(build.getParent().getParent(),build.resolve("reports/p6b1-runtime/build-identity.properties"));
        var devices=VerifyRuntimeResults.snapshot(adb,System.getenv("ANDROID_SERIAL"));
        Properties artifacts=new Properties();
        try(var in=Files.newInputStream(build.resolve("reports/p6b1-runtime/artifact-manifest.properties"))) { artifacts.load(in); }
        Path output=build.resolve("reports/p6b1-manual").resolve(UUID.randomUUID().toString()); Files.createDirectories(output);
        System.out.println("hostRunId="+output.getFileName()+"; manualReceiptRoot="+output);
        Files.copy(build.resolve("reports/p6b1-runtime/artifact-manifest.properties"),output.resolve("artifact-manifest.properties"));
        Files.copy(build.resolve("reports/p6b1-runtime/build-identity.properties"),output.resolve("build-identity.properties"));
        Properties source=new Properties();
        try(var in=Files.newInputStream(output.resolve("build-identity.properties"))) { source.load(in); }
        List<String> failures=new ArrayList<>(); int deviceIndex=0;
        for(var device:devices.entrySet()) {
            Path dir=output.resolve("device-"+(deviceIndex++)); Files.createDirectories(dir);
            try {
                run(dir.resolve("install-target.txt"),45,adb,"-s",device.getKey(),"install","-r","-t",build.resolve("outputs/apk/p1Release/app-p1Release.apk").toString());
                run(dir.resolve("install-test.txt"),45,adb,"-s",device.getKey(),"install","-r","-t",build.resolve("outputs/apk/androidTest/p1Release/app-p1Release-androidTest.apk").toString());
                System.out.println("P6b.1 lifecycle probe on "+device.getValue()+": follow tools/p6b1/android-runtime-windows.md; explicit synthetic login after each revoke; finish all five steps.");
                Path log=dir.resolve("instrumentation.txt");
                run(log,960,adb,"-s",device.getKey(),"shell","am","instrument","-w","-r","-e","class",
                    "com.fantadrom.copperscroll.storage.runtime.P6b1LifecycleProbe",
                    "com.fantadrom.copperscroll.p1runtime.test/androidx.test.runner.AndroidJUnitRunner");
                String receipt=null; String terminal=Files.readString(log);
                for(String line:terminal.split("\\R")) {
                    String prefix="INSTRUMENTATION_STATUS: p6b1ReceiptBase64=";
                    if(line.startsWith(prefix)) { if(receipt!=null) throw new IllegalStateException("Duplicate receipt"); receipt=new String(Base64.getDecoder().decode(line.substring(prefix.length()).trim()),StandardCharsets.UTF_8); }
                }
                StringBuilder header=new StringBuilder("hostDevice="+device.getValue().replace('\n',' ').replace('\r',' ')+"\n");
                for(String key:List.of("artifactBundleId","targetSha256","testSha256","targetMapId","testMapId","targetMappingSha256","testMappingSha256"))
                    header.append(key).append('=').append(artifacts.getProperty(key,"unknown")).append('\n');
                for(String key:RuntimeSourceIdentity.FIELDS) header.append(key).append('=').append(source.getProperty(key,"unknown")).append('\n');
                if(receipt!=null) Files.writeString(dir.resolve("manual-result.txt"),header+receipt);
                Set<String> lines=receipt==null ? Set.of() : new HashSet<>(Arrays.asList(receipt.split("\\R")));
                if(receipt==null || !completedRunner(terminal) || !lines.containsAll(List.of("outcome=USER_OBSERVATIONS_COLLECTED","productionAdmission=REFUSED","automatedLeakCertification=false"))) {
                    throw new IllegalStateException("Probe missing/cancelled/failed; inspect current instrumentation output");
                }
                System.out.println("Manual observations saved: "+dir.resolve("manual-result.txt")+"; productionAdmission=REFUSED");
            } catch(Exception e) { Files.writeString(dir.resolve("failure.txt"),e.getClass().getName()+"\n"+e.getMessage()); failures.add(device.getKey()+": "+e.getMessage()); }
        }
        try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(output.resolve("lifecycle-diagnostics.zip")))) {
            try(var files=Files.walk(output)) {
                for(Path file:files.filter(Files::isRegularFile).filter(p->!p.getFileName().toString().equals("lifecycle-diagnostics.zip")).sorted().toArray(Path[]::new)) {
                    zip.putNextEntry(new java.util.zip.ZipEntry(output.relativize(file).toString().replace('\\','/'))); Files.copy(file,zip); zip.closeEntry();
                }
            }
        }
        if(!failures.isEmpty()) throw new IllegalStateException("P6b1 probe incomplete: "+failures+"; receipts/logs="+output);
        System.out.println("P6b1 user lifecycle observations collected on "+devices.size()+" device(s); production admission remains CLOSED; "+output);
    }
    /** adb may exit zero after a runner crash. Require one final instrumentation success. */
    static boolean completedRunner(String terminal) {
        int endings=0;
        for(String line:terminal.split("\\R")) if(line.startsWith("INSTRUMENTATION_CODE:")) {
            if(!line.substring(line.indexOf(':')+1).trim().equals("-1")) return false;
            endings++;
        }
        return endings==1 && terminal.contains("OK (1 test)") && !terminal.contains("INSTRUMENTATION_FAILED") &&
            !terminal.contains("INSTRUMENTATION_ABORTED") && !terminal.contains("shortMsg=") && !terminal.contains("FAILURES");
    }
    private static void run(Path output,int seconds,String... command) throws Exception {
        Process process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        Throwable primary=null;
        try {
            if(!process.waitFor(seconds,TimeUnit.SECONDS)) throw new IllegalStateException("Probe controller timeout; device syscall completion unknown");
            if(process.exitValue()!=0) throw new IllegalStateException("adb failed; "+output);
            if(Files.size(output)>2*1024*1024) throw new IllegalStateException("Diagnostic output oversized");
        } catch(Exception|Error e) { primary=e; throw e; }
        finally {
            if(process.isAlive()) {
                process.destroyForcibly();
                try { if(!process.waitFor(10,TimeUnit.SECONDS)) throw new IllegalStateException("adb child still alive"); }
                catch(Exception|Error e) { if(primary!=null) primary.addSuppressed(e); else throw e; }
            }
        }
    }
}
