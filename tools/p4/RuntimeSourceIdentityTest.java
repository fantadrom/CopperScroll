import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

public final class RuntimeSourceIdentityTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("p4-source-tests-");
        try {
            Files.createDirectories(root.resolve("app/src/main/java")); Files.createDirectories(root.resolve("gradle/wrapper"));
            Files.writeString(root.resolve("app/src/main/java/Synthetic.java"),"class Synthetic {}\n");
            Files.writeString(root.resolve("gradle/wrapper/gradle-wrapper.properties"),"distributionUrl=synthetic-9.6.0\n");
            Files.write(root.resolve("gradle/wrapper/gradle-wrapper.jar"),new byte[]{1,2,3});
            Files.writeString(root.resolve(".gitignore"),"work/\napp/src/main/java/Ignored.java\n");
            git(root,"init"); git(root,"add","."); git(root,"commit","-m","Synthetic source identity fixture");
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
            git(root,"checkout","--","gradle/wrapper/gradle-wrapper.properties");
            Files.writeString(root.resolve("app/src/main/java/Synthetic.java"),"class Synthetic { int changed; }\n");
            Properties code=RuntimeSourceIdentity.capture(root,"9.6.0","synthetic-jvm-A","synthetic-vendor");
            check(code.getProperty("sourceCodeDirty").equals("true") && code.getProperty("wrapperModified").equals("false"));
            check(!code.getProperty("sourceFingerprint").equals(clean.getProperty("sourceFingerprint")));
            git(root,"checkout","--","app/src/main/java/Synthetic.java");
            Files.writeString(root.resolve("app/src/main/java/Untracked.java"),"class Untracked {}\n");
            check(RuntimeSourceIdentity.capture(root,"9.6.0","synthetic-jvm-A","synthetic-vendor").getProperty("sourceCodeDirty").equals("true"));
            Files.delete(root.resolve("app/src/main/java/Untracked.java"));
            Files.writeString(root.resolve("app/src/main/java/Ignored.java"),"class Ignored {}\n");
            Properties ignored=RuntimeSourceIdentity.capture(root,"9.6.0","synthetic-jvm-A","synthetic-vendor");
            check(ignored.getProperty("sourceCodeDirty").equals("true") && ignored.getProperty("workingTreeDirty").equals("false"));
            reject(()->RuntimeSourceIdentity.verify(root,identity));
            Files.delete(root.resolve("app/src/main/java/Ignored.java"));
            Files.writeString(root.resolve("work/unrelated.txt"),"synthetic unrelated data\n");
            check(RuntimeSourceIdentity.capture(root,"9.6.0","synthetic-jvm-A","synthetic-vendor").getProperty("sourceFingerprint").equals(clean.getProperty("sourceFingerprint")));
            RuntimeSourceIdentity.verify(root,identity); checks++;
            check(RuntimeSourceIdentity.FIELDS.containsAll(List.of("gradleVersion","gradleJvmRuntime","sourceRevision","sourceDirty","sourceCodeDirty","wrapperModified")));
            System.out.println("P4 source provenance: "+checks+" host checks PASS; Android NOT RUN");
        } finally { try(var paths=Files.walk(root)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(p); } }
    }
    private static void check(boolean value) { if(!value) throw new AssertionError("Source identity check failed"); checks++; }
    private static void reject(Checked work) throws Exception { try { work.run(); } catch(IllegalStateException expected) { checks++; return; } throw new AssertionError("Changed source accepted"); }
    private interface Checked { void run() throws Exception; }
    private static void git(Path root,String... args) throws Exception {
        List<String> command=new ArrayList<>(List.of("git","-C",root.toString(),"-c","user.name=P4 Test","-c","user.email=p4@example.invalid",
            "-c","commit.gpgsign=false","-c","core.hooksPath="+root.resolve("no-hooks")));
        command.addAll(List.of(args)); Process process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if(!process.waitFor(10,TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("Fixture git timeout"); }
        if(process.exitValue()!=0) throw new IllegalStateException("Fixture git failure");
    }
}
