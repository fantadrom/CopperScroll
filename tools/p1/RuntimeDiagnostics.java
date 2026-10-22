import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Host-only post-run evidence. Runs even when connected fails, never issues a runtime PASS. */
final class RuntimeDiagnostics {
    static Path report(Path build) { return VerifyRuntimeResults.summary(build).getParent(); }
    static void begin(Path build) throws Exception {
        Path report=report(build); Files.createDirectories(report);
        Files.writeString(report.resolve("attempt.properties"),"attemptId="+UUID.randomUUID()+"\n");
        Files.deleteIfExists(report.resolve("runtime-diagnostics.txt"));
        Files.deleteIfExists(report.resolve("preparation-failure.txt"));
    }
    static String failureText(Throwable failure) {
        StringWriter text=new StringWriter(); failure.printStackTrace(new PrintWriter(text));
        return text.toString();
    }
    static void collect(Path build) throws Exception {
        Path report=report(build),attempt=report.resolve("attempt.properties");
        if(!Files.isRegularFile(attempt)) throw new IllegalStateException("No current runtime attempt; no reports reused");
        var started=Files.getLastModifiedTime(attempt);
        Path manifest=VerifyRuntimeResults.manifest(build);
        boolean current=Files.isRegularFile(manifest) && Files.getLastModifiedTime(manifest).compareTo(started)>=0;
        if(current) {
            Properties a=new Properties(),r=new Properties();
            try(var in=Files.newInputStream(attempt)) { a.load(in); }
            try(var in=Files.newInputStream(manifest)) { r.load(in); }
            current=!a.getProperty("attemptId","").isEmpty() && a.getProperty("attemptId").equals(r.getProperty("attemptId"));
        }
        String evidence;
        String verified=null;
        try {
            if(!current) throw new IllegalStateException("No fresh-run manifest: preparation failed; runtime NOT RUN");
            VerifyRuntimeResults.verifyArtifactBinding(build);
            verified=VerifyRuntimeResults.verify(VerifyRuntimeResults.results(build),manifest);
            evidence="evidence=PASS\n";
        } catch(Exception failure) {
            evidence="evidence=FAIL\n"+failureText(failure);
        }
        // The gate, not the collector, owns runtime-result.txt. Its absence/failure is explicit.
        Path receipt=VerifyRuntimeResults.summary(build);
        String acceptance="acceptance=NOT_CONFIRMED\n";
        if(verified!=null && fresh(receipt,started) && Files.readString(receipt).equals(verified))
            acceptance="acceptance=PASS\n";
        StringBuilder note=new StringBuilder(acceptance).append(evidence);
        Map<String,Path> entries=new TreeMap<>();
        add(entries,"attempt.properties",attempt);
        if(current) add(entries,"run.properties",manifest);
        for(String name:List.of("runtime-result.txt","preparation-failure.txt","build-identity.properties")) {
            Path p=report.resolve(name);
            // Source capture precedes prepare, but is checked by verifyArtifactBinding.
            if(name.equals("build-identity.properties") || fresh(p,started)) add(entries,name,p);
        }
        Path results=VerifyRuntimeResults.results(build);
        if(current && Files.isDirectory(results)) {
            try(var paths=Files.walk(results)) {
                for(Path p:paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).sorted().toArray(Path[]::new)) {
                    String name="runtime-results/"+results.relativize(p).toString().replace('\\','/');
                    if(fresh(p,Files.getLastModifiedTime(manifest))) add(entries,name,p);
                    else note.append("excludedStale=").append(name).append('\n');
                }
            }
        } else note.append("runtimeResults=ABSENT (no old results used)\n");
        // Immutable per-artifact ZIP contains both exact mappings/configs and APK SHA256/map IDs.
        Properties artifacts=new Properties();
        try(var in=Files.newInputStream(report.resolve("artifact-manifest.properties"))) { artifacts.load(in); }
        String bundle=artifacts.getProperty("artifactBundleId","");
        if(!bundle.matches("[0-9a-f]{64}")) throw new IllegalStateException("Invalid artifact bundle identity");
        Path base=report.resolve("artifacts").resolve(bundle).resolve("p1-diagnostics.zip");
        Path temporary=Files.createTempFile(report,"runtime-diagnostics-",".zip");
        try {
            try(var out=new ZipOutputStream(Files.newOutputStream(temporary)); var zip=new ZipFile(base.toFile())) {
                for(ZipEntry e:Collections.list(zip.entries())) {
                    if(e.isDirectory()) continue;
                    out.putNextEntry(new ZipEntry(e.getName()));
                    try(var in=zip.getInputStream(e)) { in.transferTo(out); } out.closeEntry();
                }
                long total=0;
                for(var e:entries.entrySet()) {
                    long size=Files.size(e.getValue());
                    if(size>32L*1024*1024 || total+size>128L*1024*1024) {
                        note.append("excludedOversized=").append(e.getKey()).append('\n'); continue;
                    }
                    total+=size; out.putNextEntry(new ZipEntry(e.getKey())); Files.copy(e.getValue(),out); out.closeEntry();
                }
                out.putNextEntry(new ZipEntry("runtime-diagnostics.txt"));
                out.write(note.toString().getBytes(StandardCharsets.UTF_8)); out.closeEntry();
            }
            Files.move(temporary,report.resolve("p1-diagnostics.zip"),StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(report.resolve("runtime-diagnostics.txt"),note);
        } finally { Files.deleteIfExists(temporary); }
        System.out.println("Current runtime results/failure evidence archived: "+report.resolve("p1-diagnostics.zip"));
    }
    private static boolean fresh(Path p,java.nio.file.attribute.FileTime start) throws Exception {
        return Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS) && Files.getLastModifiedTime(p).compareTo(start)>=0;
    }
    private static void add(Map<String,Path> entries,String name,Path p) {
        if(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)) entries.put(name,p);
    }
}
