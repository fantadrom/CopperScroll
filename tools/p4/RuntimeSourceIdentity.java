import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/** Host-only, bounded source provenance. No credentials, source contents or paths in receipts. */
public final class RuntimeSourceIdentity {
    static final List<String> FIELDS=List.of("gradleVersion","gradleJvmRuntime","gradleJvmVendor","sourceRevision",
        "sourceFingerprint","sourceDirty","sourceCodeDirty","workingTreeDirty","wrapperModified",
        "wrapperPropertiesSha256","wrapperJarSha256");
    private static final Set<String> WRAPPER=Set.of("gradle/wrapper/gradle-wrapper.properties",
        "gradle/wrapper/gradle-wrapper.jar","gradlew","gradlew.bat");
    public static void main(String[] args) throws Exception {
        require(args.length>=3,"Expected capture|verify repository identity-file [Gradle JVM vendor]");
        Path root=Path.of(args[1]).toAbsolutePath().normalize(), target=Path.of(args[2]);
        if(args[0].equals("capture")) {
            require(args.length==6,"Missing actual Gradle/JVM versions");
            Properties p=capture(root,args[3],args[4],args[5]); Files.createDirectories(target.getParent());
            try(OutputStream out=Files.newOutputStream(target)) { p.store(out,"Actual P4 build invocation; no source contents"); }
            System.out.println("P4 build source identity captured; gradle="+p.getProperty("gradleVersion")+
                " jvm="+p.getProperty("gradleJvmRuntime")+" sourceDirty="+p.getProperty("sourceDirty")+
                " wrapperModified="+p.getProperty("wrapperModified"));
        } else if(args[0].equals("verify")) { verify(root,target); System.out.println("P4 source unchanged since pre-build capture: PASS; runtime NOT RUN"); }
        else throw new IllegalArgumentException("Unknown source-identity mode");
    }
    static boolean relevant(String name) {
        if(name.contains("/__pycache__/") || name.endsWith(".pyc")) return false;
        return name.startsWith("app/src/") || name.startsWith("tools/p1/") || name.startsWith("tools/p2/") ||
            name.startsWith("tools/p3/") || name.startsWith("tools/p4/") || name.startsWith("tools/p5a/") || name.startsWith("tools/p6a/") || name.startsWith("tools/p6a2/") || name.startsWith("tools/p6b2/") || name.startsWith("tools/p6b1/") || name.startsWith("tools/p5b/") || name.startsWith("gradle/") || WRAPPER.contains(name) ||
            Set.of("build.gradle.kts","app/build.gradle.kts","settings.gradle.kts","gradle.properties").contains(name);
    }
    static Properties capture(Path root,String gradle,String jvm,String vendor) throws Exception {
        require(!gradle.isBlank() && !jvm.isBlank() && !vendor.isBlank(),"Missing build version");
        Properties p=new Properties(); p.setProperty("gradleVersion",gradle); p.setProperty("gradleJvmRuntime",jvm); p.setProperty("gradleJvmVendor",vendor);
        String head=git(root,"rev-parse","HEAD").trim(); require(head.matches("[0-9a-f]{40}"),"Invalid source revision"); p.setProperty("sourceRevision",head);
        TreeSet<String> changed=new TreeSet<>(names(git(root,"diff","--name-only","-z","HEAD")));
        changed.addAll(names(git(root,"ls-files","--others","--exclude-standard","-z")));
        p.setProperty("workingTreeDirty",Boolean.toString(!changed.isEmpty()));
        TreeSet<String> tracked=new TreeSet<>(names(git(root,"ls-files","--cached","-z")));
        TreeSet<String> files=new TreeSet<>(names(git(root,"ls-files","--cached","--others","--exclude-standard","-z")));
        // Ignored sources/assets can still be compiled by AGP; Git cleanliness alone is insufficient.
        for(String directory:List.of("app/src","tools/p1","tools/p2","tools/p3","tools/p4","tools/p5a","tools/p5b","tools/p6a","tools/p6a2","tools/p6b1","tools/p6b2","gradle")) {
            Path dir=root.resolve(directory); if(!Files.isDirectory(dir)) continue;
            try(var paths=Files.walk(dir)) {
                var iterator=paths.iterator(); int count=0;
                while(iterator.hasNext()) {
                    Path path=iterator.next(); require(++count<=20000,"Source scan budget exceeded");
                    if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)) continue;
                    String name=root.relativize(path).toString().replace('\\','/');
                    if(relevant(name)) { files.add(name); if(!tracked.contains(name)) changed.add(name); }
                }
            }
        }
        p.setProperty("sourceDirty",Boolean.toString(changed.stream().anyMatch(RuntimeSourceIdentity::relevant)));
        p.setProperty("sourceCodeDirty",Boolean.toString(changed.stream().anyMatch(n->relevant(n) && !WRAPPER.contains(n))));
        p.setProperty("wrapperModified",Boolean.toString(changed.stream().anyMatch(WRAPPER::contains)));
        require(files.size()<=20000,"Source file-count budget exceeded");
        MessageDigest digest=MessageDigest.getInstance("SHA-256"); long total=0; byte[] buffer=new byte[8192];
        for(String name:files) {
            if(!relevant(name)) continue;
            Path path=root.resolve(name).normalize(); require(path.startsWith(root),"Source path escaped repository");
            digest.update(name.getBytes(StandardCharsets.UTF_8)); digest.update((byte)0);
            require(!Files.isSymbolicLink(path),"Symbolic source requires explicit provenance support");
            if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) {
                long size=Files.size(path); require(size<=64L*1024*1024 && (total+=size)<=256L*1024*1024,"Source byte budget exceeded");
                digest.update(Long.toString(size).getBytes(StandardCharsets.US_ASCII)); digest.update((byte)0);
                try(InputStream in=Files.newInputStream(path)) { int n; while((n=in.read(buffer))!=-1) { require(n>0,"Short source read"); digest.update(buffer,0,n); } }
            } else digest.update((byte)0xff); // Tracked deletion remains part of this working-tree identity.
            digest.update((byte)0);
        }
        p.setProperty("sourceFingerprint",hex(digest.digest()));
        for(String name:List.of("gradle-wrapper.properties","gradle-wrapper.jar")) {
            Path path=root.resolve("gradle/wrapper/"+name); require(Files.isRegularFile(path),"Missing wrapper artifact");
            p.setProperty(name.endsWith("properties")?"wrapperPropertiesSha256":"wrapperJarSha256",hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        }
        return p;
    }
    static Properties read(Path path) throws Exception {
        require(Files.isRegularFile(path) && Files.size(path)<16384,"Missing/oversized source identity");
        Properties p=new Properties(); try(InputStream in=Files.newInputStream(path)) { p.load(in); }
        for(String key:FIELDS) require(!p.getProperty(key,"").isBlank(),"Missing source identity field: "+key);
        return p;
    }
    static void verify(Path root,Path path) throws Exception {
        Properties old=read(path), now=capture(root,old.getProperty("gradleVersion"),old.getProperty("gradleJvmRuntime"),old.getProperty("gradleJvmVendor"));
        for(String key:FIELDS) require(old.getProperty(key).equals(now.getProperty(key)),"Source changed during P4 build/run: "+key);
    }
    private static List<String> names(String nul) { List<String> result=new ArrayList<>(); for(String s:nul.split("\u0000")) if(!s.isEmpty()) result.add(s); return result; }
    private static String git(Path root,String... args) throws Exception {
        List<String> command=new ArrayList<>(List.of("git","-C",root.toString())); command.addAll(Arrays.asList(args));
        Path temp=Files.createTempFile("p4-source-identity-",".log");
        try {
            // Git EOL warnings belong to stderr, never to the NUL-delimited filename protocol.
            Process process=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(temp.toFile()).start();
            if(!process.waitFor(30,TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5,TimeUnit.SECONDS); throw new IllegalStateException("Source git command timed out"); }
            require(process.exitValue()==0 && Files.size(temp)<=4*1024*1024,"Source git command failed/oversized");
            return Files.readString(temp,StandardCharsets.UTF_8);
        } finally { Files.deleteIfExists(temp); }
    }
    private static String hex(byte[] value) { StringBuilder b=new StringBuilder(); for(byte x:value) b.append(String.format("%02x",x&255)); return b.toString(); }
    private static void require(boolean value,String message) { if(!value) throw new IllegalStateException(message); }
}
