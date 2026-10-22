import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Properties;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class RuntimeSourceIdentityTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        if(args.length==1 && args[0].equals("--wait-child")) {
            new CountDownLatch(1).await(); // Owned subprocess used only by the cleanup regression below.
            return;
        }
        if(args.length!=0) throw new IllegalArgumentException("Unexpected fixture arguments");
        for(String autocrlf:List.of("false","true","input")) {
            try(TemporaryRepository fixture=new TemporaryRepository()) { provenance(fixture,autocrlf); }
        }
        gitWarningsAndEolBytes(); readOnlyCleanup(); scopedCleanup(); exceptionPreservation(); processCleanup();
        // No PASS can precede repository/process cleanup or the cleanup regressions themselves.
        System.out.println("P4 source provenance: "+checks+" host checks PASS; Android NOT RUN");
    }
    private static void provenance(TemporaryRepository fixture,String autocrlf) throws Exception {
            Path root=fixture.root;
            Files.createDirectories(root.resolve("app/src/main/java")); Files.createDirectories(root.resolve("gradle/wrapper"));
            Files.writeString(root.resolve("app/src/main/java/Synthetic.java"),"class Synthetic {}\n");
            Files.writeString(root.resolve("gradle/wrapper/gradle-wrapper.properties"),"distributionUrl=synthetic-9.6.0\n");
            Files.write(root.resolve("gradle/wrapper/gradle-wrapper.jar"),new byte[]{1,2,3});
            Files.writeString(root.resolve(".gitignore"),"work/\napp/src/main/java/Ignored.java\ntools/p6a/Ignored.java\ntools/p6a2/Ignored.java\n");
            // This synthetic repository requires exact bytes, independent of the user's Git EOL policy.
            // Never add these attributes/settings to the real project or normalize its fingerprint.
            Files.writeString(root.resolve(".gitattributes"),"* -text\n");
            fixture.git("init"); fixture.git("config","core.autocrlf",autocrlf);
            fixture.git("config","core.eol","crlf");
            fixture.git("add","."); fixture.git("commit","-m","Synthetic source identity fixture");
            Properties clean=RuntimeSourceIdentity.capture(root,"9.6.0","synthetic-jvm-A","synthetic-vendor");
            check(clean.getProperty("sourceDirty").equals("false") && clean.getProperty("sourceCodeDirty").equals("false"));
            check(clean.getProperty("sourceRevision").matches("[0-9a-f]{40}") && clean.getProperty("sourceFingerprint").matches("[0-9a-f]{64}"));
            Path identity=root.resolve("work/identity.properties"); Files.createDirectories(identity.getParent());
            try(var out=Files.newOutputStream(identity)) { clean.store(out,"Fixture"); }
            RuntimeSourceIdentity.verify(root,identity); checks++;
            Files.writeString(root.resolve("gradle/wrapper/gradle-wrapper.properties"),"distributionUrl=synthetic-9.8.0\n");
            Properties wrapper=RuntimeSourceIdentity.capture(root,"9.8.0","synthetic-jvm-B","synthetic-vendor");
            check(wrapper.getProperty("wrapperModified").equals("true") && wrapper.getProperty("sourceDirty").equals("true") && wrapper.getProperty("sourceCodeDirty").equals("false"));
            check(wrapper.getProperty("gradleVersion").equals("9.8.0") && wrapper.getProperty("gradleJvmRuntime").equals("synthetic-jvm-B"));
            check(!wrapper.getProperty("wrapperPropertiesSha256").equals(clean.getProperty("wrapperPropertiesSha256")));
            reject(()->RuntimeSourceIdentity.verify(root,identity));
            fixture.git("checkout","--","gradle/wrapper/gradle-wrapper.properties");
            Files.writeString(root.resolve("app/src/main/java/Synthetic.java"),"class Synthetic { int changed; }\n");
            Properties code=RuntimeSourceIdentity.capture(root,"9.6.0","synthetic-jvm-A","synthetic-vendor");
            check(code.getProperty("sourceCodeDirty").equals("true") && code.getProperty("wrapperModified").equals("false"));
            check(!code.getProperty("sourceFingerprint").equals(clean.getProperty("sourceFingerprint")));
            fixture.git("checkout","--","app/src/main/java/Synthetic.java");
            Files.writeString(root.resolve("app/src/main/java/Untracked.java"),"class Untracked {}\n");
            check(RuntimeSourceIdentity.capture(root,"9.6.0","synthetic-jvm-A","synthetic-vendor").getProperty("sourceCodeDirty").equals("true"));
            Files.delete(root.resolve("app/src/main/java/Untracked.java"));
            Files.writeString(root.resolve("app/src/main/java/Ignored.java"),"class Ignored {}\n");
            Properties ignored=RuntimeSourceIdentity.capture(root,"9.6.0","synthetic-jvm-A","synthetic-vendor");
            check(ignored.getProperty("sourceCodeDirty").equals("true") && ignored.getProperty("workingTreeDirty").equals("false"));
            reject(()->RuntimeSourceIdentity.verify(root,identity));
            Files.delete(root.resolve("app/src/main/java/Ignored.java"));
            for(String input:List.of("tools/p6a/Ignored.java","tools/p6a2/Ignored.java","app/src/p6aHarness/kotlin/Provider.kt",
                "app/src/p6aAndroidTest/assets/synthetic.txt","app/src/productionPolicy/kotlin/Policy.kt")) {
                Path added=root.resolve(input); Files.createDirectories(added.getParent()); Files.writeString(added,"synthetic input\n");
                check(!RuntimeSourceIdentity.capture(root,"9.6.0","synthetic-jvm-A","synthetic-vendor").getProperty("sourceFingerprint").equals(clean.getProperty("sourceFingerprint")));
                reject(()->RuntimeSourceIdentity.verify(root,identity)); Files.delete(added);
            }
            Files.writeString(root.resolve("work/unrelated.txt"),"synthetic unrelated data\n");
            check(RuntimeSourceIdentity.capture(root,"9.6.0","synthetic-jvm-A","synthetic-vendor").getProperty("sourceFingerprint").equals(clean.getProperty("sourceFingerprint")));
            RuntimeSourceIdentity.verify(root,identity); checks++;
            check(RuntimeSourceIdentity.FIELDS.containsAll(List.of("gradleVersion","gradleJvmRuntime","sourceRevision","sourceDirty","sourceCodeDirty","wrapperModified")));
            // Even an EOL-only change must still be rejected by the real byte fingerprint.
            Files.writeString(root.resolve("app/src/main/java/Synthetic.java"),"class Synthetic {}\r\n");
            reject(()->RuntimeSourceIdentity.verify(root,identity));
    }
    private static void check(boolean value) { if(!value) throw new AssertionError("Source identity check failed"); checks++; }
    private static void reject(Checked work) throws Exception { try { work.run(); } catch(IllegalStateException expected) { checks++; return; } throw new AssertionError("Changed source accepted"); }
    private interface Checked { void run() throws Exception; }

    private static void gitWarningsAndEolBytes() throws Exception {
        // Deliberately use CRLF checkout here, independently of core.autocrlf or the host OS.
        // Unlike the stable fixture matrix, this regression MUST observe a changed byte fingerprint.
        try(TemporaryRepository fixture=new TemporaryRepository()) {
            Path root=fixture.root, source=root.resolve("app/src/main/java/Synthetic.java");
            Path wrapper=root.resolve("gradle/wrapper"); Files.createDirectories(source.getParent()); Files.createDirectories(wrapper);
            Files.writeString(source,"class Synthetic {}\n");
            Files.writeString(wrapper.resolve("gradle-wrapper.properties"),"distributionUrl=synthetic\n");
            Files.write(wrapper.resolve("gradle-wrapper.jar"),new byte[]{1,2,3});
            Files.writeString(root.resolve(".gitignore"),"work/\n");
            Files.writeString(root.resolve(".gitattributes"),"* text eol=crlf\n*.jar -text\n");
            fixture.git("init"); fixture.git("add","."); fixture.git("commit","-m","Synthetic EOL warning fixture");
            Properties clean=RuntimeSourceIdentity.capture(root,"synthetic-gradle","synthetic-jvm","synthetic-vendor");
            check(clean.getProperty("sourceCodeDirty").equals("false"));
            Path identity=root.resolve("work/identity.properties"); Files.createDirectories(identity.getParent());
            try(var out=Files.newOutputStream(identity)) { clean.store(out,"Synthetic EOL regression"); }
            Files.writeString(source,"class Synthetic { int changed; }\n");
            Properties edited=RuntimeSourceIdentity.capture(root,"synthetic-gradle","synthetic-jvm","synthetic-vendor");
            check(edited.getProperty("sourceCodeDirty").equals("true") &&
                !edited.getProperty("sourceFingerprint").equals(clean.getProperty("sourceFingerprint")));
            fixture.git("checkout","--","app/src/main/java/Synthetic.java");
            Properties checkout=RuntimeSourceIdentity.capture(root,"synthetic-gradle","synthetic-jvm","synthetic-vendor");
            check(Files.readString(source).equals("class Synthetic {}\r\n") && checkout.getProperty("sourceDirty").equals("false") &&
                !checkout.getProperty("sourceFingerprint").equals(clean.getProperty("sourceFingerprint")));
            try { RuntimeSourceIdentity.verify(root,identity); throw new AssertionError("EOL-only source change accepted"); }
            catch(IllegalStateException expected) { check(expected.getMessage().endsWith(": sourceFingerprint")); }
        }
    }

    private static void readOnlyCleanup() throws Exception {
        TemporaryRepository fixture=new TemporaryRepository(); Path root=fixture.root;
        try(fixture) {
            Path file=root.resolve(".git/objects/ab/synthetic"); Files.createDirectories(file.getParent());
            Files.writeString(file,"synthetic read-only Git object");
            if(Files.getFileStore(file).supportsFileAttributeView(DosFileAttributeView.class)) {
                var dos=Files.getFileAttributeView(file,DosFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
                dos.setReadOnly(true); check(dos.readAttributes().isReadOnly());
            } else {
                Files.setPosixFilePermissions(file,Set.of(PosixFilePermission.OWNER_READ));
                check(!Files.getPosixFilePermissions(file).contains(PosixFilePermission.OWNER_WRITE));
            }
        }
        check(!Files.exists(root,LinkOption.NOFOLLOW_LINKS));
    }
    private static void scopedCleanup() throws Exception {
        try(TemporaryRepository outside=new TemporaryRepository()) {
            Path sentinel=outside.root.resolve("sentinel"); Files.writeString(sentinel,"outside fixture");
            TemporaryRepository inside=new TemporaryRepository();
            try(inside) {
                try { inside.clearAndDelete(sentinel); throw new AssertionError("External deletion accepted"); }
                catch(IOException expected) { checks++; }
            }
            check(Files.readString(sentinel).equals("outside fixture") && !Files.exists(inside.root));
        }
    }
    private static void exceptionPreservation() throws Exception {
        AssertionError primary=new AssertionError("Synthetic original check failure");
        IOException secondary=new IOException("Synthetic cleanup failure");
        TemporaryRepository fixture=new TemporaryRepository(f -> { f.deleteOwnedTree(); throw secondary; });
        try(fixture) { throw primary; }
        catch(AssertionError actual) {
            check(actual==primary && actual.getSuppressed().length==1 && actual.getSuppressed()[0]==secondary);
        }
        check(!Files.exists(fixture.root));
        IOException cleanupOnly=new IOException("Synthetic cleanup-only failure");
        TemporaryRepository other=new TemporaryRepository(f -> { f.deleteOwnedTree(); throw cleanupOnly; });
        boolean threw=false;
        try(other) { Files.writeString(other.root.resolve("synthetic"),"fixture"); }
        catch(IOException actual) { threw=true; check(actual==cleanupOnly && actual.getSuppressed().length==0); }
        check(threw && !Files.exists(other.root));
    }
    private static void processCleanup() throws Exception {
        TemporaryRepository fixture=new TemporaryRepository(); Process process;
        String java=Path.of(System.getProperty("java.home"),"bin",File.separatorChar=='\\' ? "java.exe" : "java").toString();
        String classpath=List.of(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator))).stream()
            .map(p->Path.of(p).toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
        try(fixture) {
            process=fixture.start(List.of(java,"-cp",classpath,"RuntimeSourceIdentityTest","--wait-child")).process;
            check(process.isAlive()); // Intentionally leave it running: the repository owns termination.
        }
        check(!process.isAlive() && !Files.exists(fixture.root));
    }

    private interface Cleanup { void run(TemporaryRepository fixture) throws Exception; }
    /** Owns only its newly created directory and processes; try-with-resources preserves the body error. */
    private static final class TemporaryRepository implements AutoCloseable {
        private final Path root=Files.createTempDirectory("p4-source-tests-").toAbsolutePath().normalize();
        private final List<Process> processes=new ArrayList<>();
        private final Cleanup cleanup;
        TemporaryRepository() throws IOException { this(TemporaryRepository::deleteOwnedTree); }
        TemporaryRepository(Cleanup cleanup) throws IOException { this.cleanup=cleanup; }
        void git(String... args) throws Exception {
            List<String> command=new ArrayList<>(List.of("git","-C",root.toString(),"-c","user.name=P4 Test","-c","user.email=p4@example.invalid",
                "-c","commit.gpgsign=false","-c","core.hooksPath="+root.resolve("no-hooks"),
                "-c","gc.auto=0","-c","maintenance.auto=false","-c","core.fsmonitor=false"));
            command.addAll(List.of(args));
            try(Child child=start(command)) {
                try {
                    if(!child.process.waitFor(10,TimeUnit.SECONDS)) throw new IllegalStateException("Fixture git timeout");
                } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
                if(child.process.exitValue()!=0) throw new IllegalStateException("Fixture git failure");
            }
        }
        Child start(List<String> command) throws IOException {
            Process process=new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            processes.add(process); return new Child(process);
        }
        private final class Child implements AutoCloseable {
            final Process process;
            Child(Process process) { this.process=process; }
            @Override public void close() throws Exception { stopAndClose(process); processes.remove(process); }
        }
        @Override public void close() throws Exception {
            Exception failure=null;
            for(Process process:new ArrayList<>(processes)) {
                try { stopAndClose(process); processes.remove(process); }
                catch(Exception e) { if(failure==null) failure=e; else failure.addSuppressed(e); }
            }
            // Never delete a repository while a child might still be accessing it.
            if(processes.stream().noneMatch(Process::isAlive)) {
                try { cleanup.run(this); }
                catch(Exception e) { if(failure==null) failure=e; else failure.addSuppressed(e); }
            }
            if(failure!=null) throw failure;
        }
        private void deleteOwnedTree() throws IOException {
            if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS)) return;
            if(Files.isSymbolicLink(root)) throw new IOException("Fixture root must remain a directory");
            List<Path> entries;
            // Close ALL directory streams before Windows deletion, and never follow symbolic links.
            try(var paths=Files.walk(root)) { entries=paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList()); }
            for(Path path:entries) clearAndDelete(path);
        }
        private void clearAndDelete(Path path) throws IOException {
            Path owned=path.toAbsolutePath().normalize();
            if(!owned.startsWith(root)) throw new IOException("Cleanup path outside fixture");
            for(Path parent=owned.getParent();parent!=null && parent.startsWith(root);parent=parent.getParent())
                if(Files.isSymbolicLink(parent)) throw new IOException("Cleanup parent must remain a directory");
            if(!Files.isSymbolicLink(owned)) {
                var dos=Files.getFileAttributeView(owned,DosFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
                if(dos!=null && dos.readAttributes().isReadOnly()) dos.setReadOnly(false);
            }
            Files.delete(owned);
        }
    }
    private static void stopAndClose(Process process) throws Exception {
        boolean[] interrupted={Thread.interrupted()};
        try(var input=process.getOutputStream();var output=process.getInputStream();var error=process.getErrorStream()) {
            if(process.isAlive()) {
                process.destroy();
                if(!awaitExit(process,interrupted)) {
                    process.destroyForcibly();
                    if(!awaitExit(process,interrupted)) throw new IOException("Fixture process did not terminate");
                }
            }
        } finally { if(interrupted[0]) Thread.currentThread().interrupt(); }
    }
    private static boolean awaitExit(Process process,boolean[] interrupted) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(process.isAlive()) {
            long remaining=deadline-System.nanoTime(); if(remaining<=0) return false;
            try { return process.waitFor(remaining,TimeUnit.NANOSECONDS); }
            catch(InterruptedException e) { interrupted[0]=true; }
        }
        return true;
    }
}
