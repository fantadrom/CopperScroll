import java.util.*;
import java.util.zip.ZipFile;

/** Compiled AXML and actual DEX definitions. No inference from source XML/string pools. */
final class P6b1ManifestAudit {
    private static void require(boolean b,String message) { if(!b) throw new IllegalStateException(message); }
    static void check(ZipFile apk,boolean host) throws Exception {
        var elements=P6aManifestAudit.read(apk);
        var app=elements.stream().filter(e->e.tag.equals("application")).findFirst().orElseThrow();
        require("false".equals(app.attributes.get("allowBackup")),"allowBackup must be false");
        for(String key:List.of("fullBackupContent","dataExtractionRules"))
            require(app.attributes.containsKey(key) && !Set.of("0","false").contains(app.attributes.get(key)),"Missing backup resource: "+key);
        if(elements.stream().anyMatch(e->e.tag.equals("activity") && e.attributes.getOrDefault("name","").endsWith(".MainActivity"))) {
            require(!"true".equals(app.attributes.get("debuggable")),"Release must not be debuggable");
            require(app.attributes.getOrDefault("backupAgent","").endsWith(".backup.RefuseBackupAgent"),"Refusal backup agent missing from manifest");
        }
        String dump=resources(apk);
        backupResource(apk,app,"fullBackupContent","backup_rules",Set.of("full-backup-content"),dump);
        backupResource(apk,app,"dataExtractionRules","data_extraction_rules",Set.of("cloud-backup","device-transfer"),dump);
        var controls=elements.stream().filter(e->e.attributes.getOrDefault("name","").startsWith("com.fantadrom.copperscroll.p6b1harness.")).toArray(P6aManifestAudit.Element[]::new);
        require(controls.length==(host?2:0),"Lifecycle host manifest isolation/count");
        var privacy=elements.stream().filter(e->e.attributes.getOrDefault("name","").startsWith("com.fantadrom.copperscroll.p6b2harness.")).toArray(P6aManifestAudit.Element[]::new);
        require(privacy.length<2 && (host || privacy.length==0),"P6b2 manifest isolation");
        for(var e:privacy) require(e.tag.equals("activity") && "false".equals(e.attributes.get("exported")),"P6b2 host must be nonexported Activity");
        for(var e:controls) require(e.tag.equals("activity") && "application".equals(e.parent) && "false".equals(e.attributes.get("exported")),"Lifecycle host must be nonexported Activity");
    }
    private static void backupResource(ZipFile apk,P6aManifestAudit.Element app,String attribute,String resourceName,Set<String> parents,String dump) throws Exception {
        var matcher=java.util.regex.Pattern.compile("(?m)^\\s*resource (0x[0-9a-fA-F]+) xml/"+resourceName+"\\s*$").matcher(dump);
        require(matcher.find(),"Backup resource absent from actual table: "+resourceName);
        long id=Long.parseLong(matcher.group(1).substring(2),16);
        require(Integer.toUnsignedLong(Integer.parseInt(app.attributes.get(attribute)))==id,"Manifest backup reference points to wrong resource: "+attribute);
        int start=matcher.end();
        var next=java.util.regex.Pattern.compile("(?m)^\\s*resource ").matcher(dump);
        int end=next.find(start)?next.start():dump.length();
        String block=dump.substring(start,end);
        var files=java.util.regex.Pattern.compile("(?m)^\\s*\\([^\\r\\n]*\\) \\(file\\) (res/[^\\s]+) type=XML\\s*$").matcher(block);
        int count=0;
        while(files.find()) {
            String entry=files.group(1); require(++count<=32 && !entry.contains("..") && entry.endsWith(".xml"),"Invalid backup resource path");
            checkRules(P6aManifestAudit.read(apk,entry),parents);
        }
        require(count>0,"Backup resource is not a compiled XML file");
        // These baseline XMLs must have file values only: no aliases or alternate value types.
        long values=block.lines().filter(s->s.stripLeading().startsWith("(")).count();
        require(values==count,"Unsupported backup resource value/configuration");
    }
    private static String resources(ZipFile apk) throws Exception {
        String configured=System.getProperty("p6b1.aapt2");
        if(configured==null) {
            String sdk=System.getenv("ANDROID_HOME"); require(sdk!=null,"AAPT2 path must be supplied by Gradle or ANDROID_HOME");
            configured=java.nio.file.Path.of(sdk,"build-tools","37.0.0",System.getProperty("os.name").startsWith("Windows")?"aapt2.exe":"aapt2").toString();
        }
        require(java.nio.file.Files.isRegularFile(java.nio.file.Path.of(configured)),"Configured SDK AAPT2 is missing");
        var output=java.nio.file.Files.createTempFile("p6b1-aapt-resources-",".txt");
        Process process=null; Throwable primary=null;
        try {
            process=new ProcessBuilder(configured,"dump","resources",apk.getName()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            require(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS),"AAPT2 resource audit timed out");
            require(process.exitValue()==0 && java.nio.file.Files.size(output)<=16*1024*1024,"AAPT2 resource audit failed/oversized");
            return java.nio.file.Files.readString(output);
        } catch(Exception|Error e) { primary=e; throw e; }
        finally {
            try {
                if(process!=null && process.isAlive()) { process.destroyForcibly(); require(process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS),"AAPT2 child still alive"); }
                java.nio.file.Files.deleteIfExists(output);
            } catch(Exception|Error clean) { if(primary!=null) primary.addSuppressed(clean); else throw clean; }
        }
    }

    static void checkRules(List<P6aManifestAudit.Element> elements,Set<String> parents) {
        Set<String> domains=Set.of("root","file","database","sharedpref","external","device_root","device_file","device_database","device_sharedpref");
        Map<String,Set<String>> seen=new HashMap<>(); for(String parent:parents) seen.put(parent,new HashSet<>());
        for(var e:elements) {
            require(!e.tag.equals("include"),"Backup includes are forbidden");
            if(!e.tag.equals("exclude")) continue;
            String domain=e.attributes.get("domain");
            require(seen.containsKey(e.parent) && domains.contains(domain) && ".".equals(e.attributes.get("path")) && seen.get(e.parent).add(domain),"Invalid/duplicate backup exclusion");
        }
        require(seen.values().stream().allMatch(domains::equals),"Incomplete backup domain exclusions");
    }
    static void classes(Set<String> classes,boolean host) {
        for(String type:List.of("storage/session/SessionCoordinator","storage/session/SessionAuthentication","session/android/AndroidSessionSignals","session/android/SecureSessionPresentation","session/ui/UiAccess","session/android/ProtectedEditText","session/android/SessionWindowPolicy"))
            require(classes.contains("Lcom/fantadrom/copperscroll/"+type+";"),"Production session class missing: "+type);
        require(host || classes.stream().noneMatch(c->c.startsWith("Lcom/fantadrom/copperscroll/p6b2harness/") || c.startsWith("Lcom/fantadrom/copperscroll/privacytest/")),"P6b2 controls leaked into release");
        if(classes.contains("Lcom/fantadrom/copperscroll/MainActivity;")) require(classes.contains("Lcom/fantadrom/copperscroll/backup/RefuseBackupAgent;"),"Refusal backup agent missing");
        boolean synthetic=classes.stream().anyMatch(c->c.startsWith("Lcom/fantadrom/copperscroll/sessiontest/") || c.startsWith("Lcom/fantadrom/copperscroll/p6b1harness/"));
        require(synthetic==host,"Synthetic lifecycle bootstrap isolation");
        if(host) for(String type:List.of("P6b1SessionActivity","P6b1PickerActivity","P6b1HostModel"))
            require(classes.contains("Lcom/fantadrom/copperscroll/p6b1harness/"+type+";"),"Lifecycle host class missing: "+type);
    }
}
