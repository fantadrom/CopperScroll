import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Immutable mapping snapshots tied to BOTH actual APK hashes and their embedded R8 map IDs. */
final class P1ArtifactDiagnostics {
    static String save(Path target, Path test, Path targetMapping, Path testMapping, Path rules,
            Path reportDirectory, Set<String> targetIds, Set<String> testIds) throws Exception {
        String targetSha = hash(target), testSha = hash(test), targetMapSha = hash(targetMapping), testMapSha = hash(testMapping);
        String targetId = mapId(targetMapping), testId = mapId(testMapping), rulesSha = hash(rules);
        Path targetConfig = targetMapping.resolveSibling("configuration.txt"), testConfig = testMapping.resolveSibling("configuration.txt");
        String targetConfigSha = hash(targetConfig), testConfigSha = hash(testConfig);
        boolean markersMatch = targetIds.contains(targetId) && testIds.contains(testId);
        String bundle = hex(MessageDigest.getInstance("SHA-256").digest(
            (targetSha + testSha + targetMapSha + testMapSha + rulesSha + targetConfigSha + testConfigSha).getBytes(StandardCharsets.US_ASCII)));
        Path directory = reportDirectory.resolve("artifacts").resolve(bundle); Files.createDirectories(directory);
        exact(directory.resolve("target-mapping.txt"), Files.readAllBytes(targetMapping));
        exact(directory.resolve("test-mapping.txt"), Files.readAllBytes(testMapping));
        exact(directory.resolve("instrumentation-api.keep"), Files.readAllBytes(rules));
        exact(directory.resolve("target-configuration.txt"), Files.readAllBytes(targetConfig));
        exact(directory.resolve("test-configuration.txt"), Files.readAllBytes(testConfig));
        String manifest = "schemaVersion=1\nvariant=p1Release\nartifactBundleId=" + bundle +
            "\ntargetSha256=" + targetSha + "\ntestSha256=" + testSha +
            "\ntargetMappingSha256=" + targetMapSha + "\ntestMappingSha256=" + testMapSha +
            "\ntargetMapId=" + targetId + "\ntestMapId=" + testId +
            "\ntargetConfigurationSha256=" + targetConfigSha + "\ntestConfigurationSha256=" + testConfigSha +
            "\ninstrumentationApiRulesSha256=" + rulesSha + "\nmappingMarkers=" + (markersMatch ? "PASS" : "FAIL") +
            "\nruntime=NOT_RUN\n";
        exact(directory.resolve("artifact-manifest.properties"), manifest.getBytes(StandardCharsets.US_ASCII));
        Path archive = directory.resolve("p1-diagnostics.zip");
        if (!Files.exists(archive)) {
            try (var out = new ZipOutputStream(Files.newOutputStream(archive))) {
                for (String name : List.of("artifact-manifest.properties", "target-mapping.txt", "test-mapping.txt",
                        "target-configuration.txt", "test-configuration.txt", "instrumentation-api.keep")) {
                    ZipEntry e = new ZipEntry(name); e.setTime(0); out.putNextEntry(e);
                    Files.copy(directory.resolve(name), out); out.closeEntry();
                }
            }
        }
        Files.copy(archive, reportDirectory.resolve("p1-diagnostics.zip"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(reportDirectory.resolve("artifact-manifest.properties"), manifest);
        if (!markersMatch) throw new IllegalStateException("APK R8 markers do not match supplied target/test mappings; diagnostic bundle marked FAIL");
        return "artifactBundleId=" + bundle + "\ntargetMapId=" + targetId + "\ntestMapId=" + testId + "\nmappingMarkers=PASS\n";
    }
    private static String mapId(Path mapping) throws Exception {
        try (var lines = Files.lines(mapping)) {
            String header = lines.limit(20).filter(line -> line.startsWith("# pg_map_id: ")).findFirst()
                .orElseThrow(() -> new IllegalStateException("Mapping has no R8 map ID"));
            String id = header.substring("# pg_map_id: ".length()).trim();
            if (!Pattern.matches("[0-9a-f]{6,64}", id)) throw new IllegalStateException("Invalid R8 map ID");
            return id;
        }
    }
    private static void exact(Path path, byte[] bytes) throws Exception {
        if (Files.exists(path)) {
            if (!MessageDigest.isEqual(Files.readAllBytes(path), bytes)) throw new IllegalStateException("Artifact snapshot collision");
        } else Files.write(path, bytes);
    }
    static String hash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192]; int n; while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        return hex(digest.digest());
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(); for (byte b : bytes) result.append(String.format("%02x", b & 255)); return result.toString();
    }
}
