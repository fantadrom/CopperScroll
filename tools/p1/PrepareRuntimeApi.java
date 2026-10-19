import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.DiagnosticsLevel;
import com.android.tools.r8.diagnostic.MissingDefinitionsDiagnostic;
import com.android.tools.r8.tracereferences.TraceReferences;
import com.android.tools.r8.tracereferences.TraceReferencesCommand;
import com.android.tools.r8.tracereferences.TraceReferencesKeepRules;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Generates only the target ABI referenced by the actual pre-R8 instrumentation inputs. Host only. */
public final class PrepareRuntimeApi {
    private static final Set<String> COMPILER_METADATA = Set.of(
        "com.google.errorprone.annotations.CanIgnoreReturnValue", "com.google.errorprone.annotations.MustBeClosed");

    public static void main(String[] args) throws Exception {
        Path rules = Path.of(args[0]), prepared = Path.of(args[1]);
        Map<String, List<Path>> groups = new TreeMap<>(); String group = null;
        for (int i = 2; i < args.length; i++) {
            if (args[i].startsWith("--")) { group = args[i]; groups.put(group, new ArrayList<>()); }
            else groups.get(group).add(Path.of(args[i]));
        }
        Map<String, byte[]> app = classes(groups.get("--app")), tests = classes(groups.get("--test"));
        int common = 0;
        for (String name : new ArrayList<>(tests.keySet())) {
            if (!app.containsKey(name)) continue;
            require(Arrays.equals(tests.get(name), app.get(name)), "Different app/test bytecode for shared dependency: " + name);
            tests.remove(name); common++;
        }
        require(app.containsKey("androidx/tracing/Trace.class"), "Resolved target is missing tracing dependency");
        require(tests.containsKey("androidx/test/runner/AndroidJUnitRunner.class"), "Missing runner input");
        Files.createDirectories(rules); Files.createDirectories(prepared);
        Path appJar = prepared.resolve("target-input.jar"), testJar = prepared.resolve("test-input.jar");
        writeJar(appJar, app); writeJar(testJar, tests);
        // Preserve exactly the shared class/member surface used by the test code. Mapping can still rename it.
        var command = TraceReferencesCommand.builder(new DiagnosticsHandler() {
            @Override public DiagnosticsLevel modifyDiagnosticsLevel(DiagnosticsLevel level, Diagnostic diagnostic) {
                if (diagnostic instanceof MissingDefinitionsDiagnostic) {
                    var missing = ((MissingDefinitionsDiagnostic) diagnostic).getMissingDefinitions();
                    if (missing.stream().allMatch(info -> info.isMissingClass() &&
                        COMPILER_METADATA.contains(info.asMissingClass().getClassReference().getTypeName())))
                        return DiagnosticsLevel.NONE;
                }
                return level;
            }
        }).addLibraryFiles(groups.get("--lib")).addTargetFiles(appJar).addSourceFiles(testJar)
            .setConsumer(TraceReferencesKeepRules.builder().setAllowObfuscation(true)
                .setOutputPath(rules.resolve("instrumentation-api.keep")).build());
        TraceReferences.run(command.build());
        Files.writeString(prepared.resolve("inputs.txt"), "targetClasses=" + app.size() + "\ntestOnlyClasses=" + tests.size() +
            "\nsharedClassesExcludedFromTest=" + common + "\ntargetInputSha256=" + hash(appJar) +
            "\ntestInputSha256=" + hash(testJar) + "\nruntime=NOT_RUN\n");
        System.out.println("P1 instrumentation ABI prepared: target=" + app.size() + ", testOnly=" + tests.size() +
            ", shared=" + common + "; runtime NOT RUN");
    }

    private static Map<String, byte[]> classes(List<Path> inputs) throws Exception {
        Map<String, byte[]> classes = new TreeMap<>();
        for (Path path : inputs) {
            if (Files.isDirectory(path)) {
                try (var files = Files.walk(path)) {
                    for (Path file : files.filter(Files::isRegularFile).sorted().toArray(Path[]::new)) {
                        String name = path.relativize(file).toString().replace('\\', '/');
                        if (name.endsWith(".class")) put(classes, name, Files.readAllBytes(file));
                    }
                }
            } else {
                try (var in = new ZipInputStream(Files.newInputStream(path))) { readArchive(in, classes, path.toString().endsWith(".aar")); }
            }
        }
        return classes;
    }
    private static void readArchive(ZipInputStream in, Map<String, byte[]> classes, boolean aar) throws Exception {
        ZipEntry entry;
        while ((entry = in.getNextEntry()) != null) {
            String name = entry.getName();
            if (entry.isDirectory() || name.startsWith("META-INF/")) continue;
            if (!aar && name.endsWith(".class")) {
                byte[] bytes = in.readNBytes(4 * 1024 * 1024 + 1);
                require(bytes.length <= 4 * 1024 * 1024, "Class input oversized"); put(classes, name, bytes);
            } else if (aar && (name.equals("classes.jar") || name.startsWith("libs/") && name.endsWith(".jar"))) {
                byte[] bytes = in.readNBytes(128 * 1024 * 1024 + 1);
                require(bytes.length <= 128 * 1024 * 1024, "AAR classes archive oversized");
                try (var nested = new ZipInputStream(new ByteArrayInputStream(bytes))) { readArchive(nested, classes, false); }
            }
        }
    }
    private static void put(Map<String, byte[]> classes, String name, byte[] bytes) {
        if (name.equals("module-info.class")) return;
        byte[] previous = classes.putIfAbsent(name, bytes);
        require(previous == null || Arrays.equals(previous, bytes), "Conflicting class inputs: " + name);
    }
    private static void writeJar(Path path, Map<String, byte[]> classes) throws Exception {
        try (var out = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : classes.entrySet()) {
                ZipEntry e = new ZipEntry(entry.getKey()); e.setTime(0); out.putNextEntry(e);
                out.write(entry.getValue()); out.closeEntry();
            }
        }
    }
    private static String hash(Path path) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        StringBuilder result = new StringBuilder(); for (byte b : digest) result.append(String.format("%02x", b & 255));
        return result.toString();
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
