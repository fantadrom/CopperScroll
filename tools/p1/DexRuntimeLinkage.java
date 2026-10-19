import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/** Static linkage of the processed instrumentation code, including members. Not an ART emulator. */
final class DexRuntimeLinkage {
    static final class Type {
        int flags; final List<String> parents = new ArrayList<>();
        final Map<String, Integer> methods = new HashMap<>(), fields = new HashMap<>();
    }
    static final class Member {
        final String owner, signature; final List<String> types;
        Member(String owner, String signature, List<String> types) { this.owner = owner; this.signature = signature; this.types = types; }
    }
    static final class Edge {
        final String caller; final Member member; final String type; final String kind; final Boolean isStatic;
        Edge(String caller, Member member, String type, String kind, Boolean isStatic) {
            this.caller = caller; this.member = member; this.type = type; this.kind = kind; this.isStatic = isStatic;
        }
    }
    static final class Index {
        final Map<String, Type> classes = new HashMap<>();
        final List<Member> methods = new ArrayList<>(), fields = new ArrayList<>();
        final List<Edge> edges = new ArrayList<>(); final Set<String> neededTypes = new HashSet<>(), mapIds = new HashSet<>();
    }

    static Index apk(Path path) throws Exception {
        return apk(path, true);
    }
    static Index apk(Path path, boolean readCode) throws Exception {
        Index result = new Index();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().matches("classes[0-9]*\\.dex")) continue;
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) { bytes = in.readNBytes(64 * 1024 * 1024 + 1); }
                require(bytes.length <= 64 * 1024 * 1024, "DEX oversized");
                readDex(new VerifyRuntimeApks.Dex(bytes), result, readCode);
            }
        }
        require(!result.classes.isEmpty(), "APK contains no definitions"); return result;
    }

    private static void readDex(VerifyRuntimeApks.Dex d, Index result, boolean readCode) {
        List<Member> methods = new ArrayList<>(), fields = new ArrayList<>();
        d.range(d.u32(92), (long) d.u32(88) * 8); d.range(d.u32(84), (long) d.u32(80) * 8);
        for (int i = 0; i < d.u32(88); i++) {
            int row = d.u32(92) + i * 8, proto = d.u32(76) + u16(d, row + 2) * 12;
            d.range(proto, 12); List<String> types = new ArrayList<>();
            String ret = d.descriptor(d.u32(proto + 4)); types.add(ret);
            StringBuilder signature = new StringBuilder(d.string(d.u32(row + 4))).append('(');
            int parameters = d.u32(proto + 8);
            if (parameters != 0) {
                int size = d.u32(parameters); d.range(parameters + 4, (long) size * 2);
                for (int j = 0; j < size; j++) {
                    String type = d.descriptor(u16(d, parameters + 4 + j * 2)); signature.append(type); types.add(type);
                }
            }
            methods.add(new Member(d.descriptor(u16(d, row)), signature.append(')').append(ret).toString(), types));
        }
        for (int i = 0; i < d.u32(80); i++) {
            int row = d.u32(84) + i * 8; String type = d.descriptor(u16(d, row + 2));
            fields.add(new Member(d.descriptor(u16(d, row)), d.string(d.u32(row + 4)) + ":" + type, List.of(type)));
        }
        for (Member member : methods) { result.neededTypes.add(member.owner); result.neededTypes.addAll(member.types); }
        for (Member member : fields) { result.neededTypes.add(member.owner); result.neededTypes.addAll(member.types); }
        result.methods.addAll(methods); result.fields.addAll(fields);
        int count = d.u32(96), offset = d.u32(100); d.range(offset, (long) count * 32);
        for (int i = 0; i < count; i++) {
            int row = offset + i * 32; String name = d.descriptor(d.u32(row)); Type type = new Type(); type.flags = d.u32(row + 4);
            int parent = d.b.getInt(row + 8); if (parent != -1) type.parents.add(d.descriptor(parent));
            int interfaces = d.u32(row + 12);
            if (interfaces != 0) {
                int size = d.u32(interfaces); d.range(interfaces + 4, (long) size * 2);
                for (int j = 0; j < size; j++) type.parents.add(d.descriptor(u16(d, interfaces + 4 + j * 2)));
            }
            result.neededTypes.addAll(type.parents);
            for (String p : type.parents) result.edges.add(new Edge(name, null, p, "type", null));
            require(result.classes.put(name, type) == null, "Duplicate DEX class");
            int data = d.u32(row + 24);
            if (data == 0) continue;
            int[] cursor = {data}; int[] sizes = {leb(d, cursor), leb(d, cursor), leb(d, cursor), leb(d, cursor)};
            for (int group = 0; group < 4; group++) {
                int index = 0;
                for (int j = 0; j < sizes[group]; j++) {
                    index = Math.addExact(index, leb(d, cursor)); int flags = leb(d, cursor);
                    List<Member> members = group < 2 ? fields : methods;
                    require(index < members.size(), "DEX member bounds"); Member member = members.get(index);
                    require(member.owner.equals(name), "DEX member owner mismatch");
                    require((group < 2 ? type.fields : type.methods).put(member.signature, flags) == null, "Duplicate DEX member");
                    if (group >= 2) { int code = leb(d, cursor); if (readCode && code != 0) code(d, code, name, methods, fields, result); }
                }
            }
        }
        for (int i = 0; i < d.u32(56); i++) {
            String s = d.string(i);
            if (s.startsWith("~~R8")) {
                var matcher = java.util.regex.Pattern.compile("\"pg-map-id\":\"([0-9a-f]+)\"").matcher(s);
                if (matcher.find()) result.mapIds.add(matcher.group(1));
            }
        }
    }

    private static void code(VerifyRuntimeApks.Dex d, int offset, String caller, List<Member> methods, List<Member> fields, Index result) {
        int count = d.u32(offset + 12), start = offset + 16; d.range(start, (long) count * 2);
        for (int pc = 0; pc < count;) {
            int instruction = u16(d, start + pc * 2), op = instruction & 255, width = width(op);
            if (op == 0 && instruction != 0) {
                if (instruction == 0x100) width = Math.addExact(4, Math.multiplyExact(2, u16(d, start + (pc + 1) * 2)));
                else if (instruction == 0x200) width = Math.addExact(2, Math.multiplyExact(4, u16(d, start + (pc + 1) * 2)));
                else if (instruction == 0x300) width = Math.addExact(4, Math.toIntExact(((long) u16(d, start + (pc + 1) * 2) *
                    d.u32(start + (pc + 2) * 2) + 1) / 2));
                else throw new IllegalStateException("Unknown DEX payload");
            }
            require(width > 0 && width <= count - pc, "Unsupported/truncated DEX opcode");
            int operand = width >= 2 ? u16(d, start + (pc + 1) * 2) : 0;
            if (Set.of(0x1c, 0x1f, 0x20, 0x22, 0x23, 0x24, 0x25).contains(op)) {
                String type = d.descriptor(operand); result.neededTypes.add(type);
                result.edges.add(new Edge(caller, null, type, "type", null));
            }
            if (op >= 0x52 && op <= 0x6d) {
                require(operand < fields.size(), "DEX field instruction bounds");
                result.edges.add(new Edge(caller, fields.get(operand), null, "field", op >= 0x60));
            }
            if (op >= 0x6e && op <= 0x72 || op >= 0x74 && op <= 0x78 || op == 0xfa || op == 0xfb) {
                require(operand < methods.size(), "DEX method instruction bounds");
                result.edges.add(new Edge(caller, methods.get(operand), null, "method", op == 0x71 || op == 0x77));
            }
            pc += width;
        }
    }
    private static int width(int op) {
        if (op == 0x18) return 5;
        if (op == 0xfa || op == 0xfb) return 4;
        if (Set.of(0x03,0x06,0x09,0x14,0x17,0x1b,0x24,0x25,0x26,0x2a,0x2b,0x2c,0xfc,0xfd).contains(op) ||
                op >= 0x6e && op <= 0x72 || op >= 0x74 && op <= 0x78) return 3;
        if (Set.of(0x02,0x05,0x08,0x13,0x15,0x16,0x19,0x1a,0x1c,0x1f,0x20,0x22,0x23,0x29,0xfe,0xff).contains(op) ||
                op >= 0x2d && op <= 0x3d || op >= 0x44 && op <= 0x6d || op >= 0x90 && op <= 0xaf ||
                op >= 0xd0 && op <= 0xe2) return 2;
        if (op <= 0x12 || op == 0x1d || op == 0x1e || op == 0x21 || op == 0x27 || op == 0x28 ||
                op >= 0x7b && op <= 0x8f || op >= 0xb0 && op <= 0xcf) return 1;
        return 0;
    }

    static String check(Index app, Index test, List<Path> platformJars) throws Exception {
        Map<String, Type> all = new HashMap<>();
        for (Path jar : platformJars) readPlatform(jar, all);
        all.putAll(app.classes); all.putAll(test.classes);
        Set<String> failures = new java.util.TreeSet<>();
        for (String type : test.neededTypes) collect(failures, () -> requireType(type, all));
        for (Member m : test.methods) collect(failures, () -> checkMember(m, "method", all));
        for (Member m : test.fields) collect(failures, () -> checkMember(m, "field", all));
        for (Edge edge : test.edges) {
            String owner = elementType(edge.member == null ? edge.type : edge.member.owner);
            if (!app.classes.containsKey(owner)) continue;
            collect(failures, () -> {
                Type target = app.classes.get(owner);
                require((target.flags & 1) != 0 || samePackage(owner, edge.caller), "Inaccessible target class: " + owner);
                if (edge.member == null) return;
                Integer flags = resolve(owner, edge.member.signature, edge.kind, all, new HashSet<>());
                require(flags != null, "Missing target member: " + owner + "->" + edge.member.signature);
                require((flags & 1) != 0 || (flags & 2) == 0 && samePackage(owner, edge.caller) ||
                    (flags & 4) != 0 && subtype(edge.caller, owner, all, new HashSet<>()),
                    "Inaccessible target member: " + owner + "->" + edge.member.signature);
                require(((flags & 8) != 0) == edge.isStatic, "Static/instance target member mismatch");
            });
        }
        require(failures.isEmpty(), "DEX linkage failed (" + failures.size() + " distinct problems):\n" +
            failures.stream().limit(30).collect(java.util.stream.Collectors.joining("\n")));
        return "instrumentationDexLinkage=PASS\ntestMethodReferences=" + test.methods.size() +
            "\ntestFieldReferences=" + test.fields.size() + "\ntestCodeEdges=" + test.edges.size() +
            "\nplatformHiddenBridge=AndroidX.ExposedInstrumentationApi.execStartActivity\n";
    }
    private static void requireType(String descriptor, Map<String, Type> all) {
        String type = elementType(descriptor);
        if (type.startsWith("L") && !platform(type)) require(all.containsKey(type), "Missing runtime class: " + type);
    }
    private static String elementType(String type) { while (type.startsWith("[")) type = type.substring(1); return type; }
    private static boolean platform(String type) {
        // javax.inject/annotation are dependency namespaces, not blanket framework exemptions.
        return List.of("Ljava/", "Ljavax/crypto/", "Ljavax/net/", "Ljavax/security/", "Ljavax/xml/",
                "Landroid/", "Ldalvik/", "Llibcore/", "Lsun/", "Lorg/xml/", "Lorg/w3c/")
            .stream().anyMatch(type::startsWith);
    }
    private static void checkMember(Member m, String kind, Map<String, Type> all) {
        if (platform(m.owner) || m.owner.startsWith("[")) return;
        requireType(m.owner, all);
        // Upstream AndroidX's explicit bridge calls hidden android.app.Instrumentation overloads.
        // They are deliberately absent from public SDK stubs; no app-owned reference is waived here.
        if (kind.equals("method") && m.owner.equals("Landroidx/test/internal/runner/hidden/ExposedInstrumentationApi;") &&
            m.signature.startsWith("execStartActivity(")) return;
        require(resolve(m.owner, m.signature, kind, all, new HashSet<>()) != null,
            "Missing runtime " + kind + ": " + m.owner + "->" + m.signature);
    }
    private static void collect(Set<String> failures, Runnable check) {
        try { check.run(); } catch (IllegalStateException failure) { failures.add(failure.getMessage()); }
    }
    private static boolean subtype(String caller, String base, Map<String, Type> all, Set<String> seen) {
        if (caller.equals(base)) return true;
        Type type = all.get(caller); if (type == null || !seen.add(caller)) return false;
        return type.parents.stream().anyMatch(parent -> subtype(parent, base, all, seen));
    }
    private static Integer resolve(String owner, String signature, String kind, Map<String, Type> all, Set<String> seen) {
        if (!seen.add(owner)) return null;
        Type type = all.get(owner); if (type == null) return null;
        Integer flags = (kind.equals("method") ? type.methods : type.fields).get(signature);
        if (flags != null || signature.startsWith("<init>(")) return flags;
        for (String parent : type.parents) { flags = resolve(parent, signature, kind, all, seen); if (flags != null) return flags; }
        if ((type.flags & 0x200) != 0) return resolve("Ljava/lang/Object;", signature, kind, all, seen);
        return null;
    }
    private static boolean samePackage(String a, String b) {
        return a.substring(0, a.lastIndexOf('/') + 1).equals(b.substring(0, b.lastIndexOf('/') + 1));
    }
    private static void readPlatform(Path path, Map<String, Type> all) throws Exception {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var e = entries.nextElement(); if (!e.getName().endsWith(".class") || e.getName().equals("module-info.class")) continue;
                try (DataInputStream in = new DataInputStream(zip.getInputStream(e))) {
                    require(in.readInt() == 0xcafebabe, "Invalid platform classfile"); skip(in, 4);
                    Object[] pool = new Object[in.readUnsignedShort()];
                    for (int i = 1; i < pool.length; i++) {
                        int tag = in.readUnsignedByte();
                        switch (tag) {
                            case 1: pool[i] = in.readUTF(); break;
                            case 7: pool[i] = in.readUnsignedShort(); break;
                            case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: skip(in, 4); break;
                            case 5: case 6: skip(in, 8); i++; break;
                            case 8: case 16: case 19: case 20: skip(in, 2); break;
                            case 15: skip(in, 3); break;
                            default: throw new IllegalStateException("Unsupported classfile constant");
                        }
                    }
                    Type type = new Type(); type.flags = in.readUnsignedShort();
                    String name = cfType(pool, in.readUnsignedShort()); int parent = in.readUnsignedShort();
                    if (parent != 0) type.parents.add(cfType(pool, parent));
                    int interfaces = in.readUnsignedShort();
                    for (int i = 0; i < interfaces; i++) type.parents.add(cfType(pool, in.readUnsignedShort()));
                    for (int group = 0; group < 2; group++) {
                        int count = in.readUnsignedShort();
                        for (int i = 0; i < count; i++) {
                            int flags = in.readUnsignedShort(); String member = (String) pool[in.readUnsignedShort()], descriptor = (String) pool[in.readUnsignedShort()];
                            (group == 0 ? type.fields : type.methods).put(member + (group == 0 ? ":" : "") + descriptor, flags);
                            int attributes = in.readUnsignedShort();
                            for (int j = 0; j < attributes; j++) { in.readUnsignedShort(); skip(in, Integer.toUnsignedLong(in.readInt())); }
                        }
                    }
                    all.put(name, type);
                }
            }
        }
    }
    private static void skip(InputStream in, long count) throws Exception {
        while (count > 0) {
            long skipped = in.skip(count);
            if (skipped == 0) { if (in.read() == -1) throw new java.io.EOFException(); skipped = 1; }
            count -= skipped;
        }
    }
    private static String cfType(Object[] pool, int index) { return "L" + pool[(Integer) pool[index]] + ";"; }
    private static int u16(VerifyRuntimeApks.Dex d, int offset) { d.range(offset, 2); return Short.toUnsignedInt(d.b.getShort(offset)); }
    private static int leb(VerifyRuntimeApks.Dex d, int[] cursor) {
        long value = 0;
        for (int i = 0; i < 5; i++) {
            d.range(cursor[0], 1); int b = d.data[cursor[0]++] & 255; value |= (long) (b & 127) << (i * 7);
            if ((b & 128) == 0) { require(value <= Integer.MAX_VALUE, "DEX ULEB overflow"); return (int) value; }
        }
        throw new IllegalStateException("DEX ULEB too long");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
