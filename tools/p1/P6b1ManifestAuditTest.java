import java.util.*;
public final class P6b1ManifestAuditTest {
    private static List<P6aManifestAudit.Element> rules() {
        var result=new ArrayList<P6aManifestAudit.Element>();
        for(String parent:List.of("cloud-backup","device-transfer")) for(String domain:List.of("root","file","database","sharedpref","external","device_root","device_file","device_database","device_sharedpref")) {
            var e=new P6aManifestAudit.Element("exclude",parent); e.attributes.put("domain",domain); e.attributes.put("path","."); result.add(e);
        }
        return result;
    }
    private static void reject(List<P6aManifestAudit.Element> elements) {
        try { P6b1ManifestAudit.checkRules(elements,Set.of("cloud-backup","device-transfer")); } catch(IllegalStateException expected) { return; }
        throw new AssertionError("Invalid backup rules accepted");
    }
    public static void main(String[] args) {
        P6b1ManifestAudit.checkRules(rules(),Set.of("cloud-backup","device-transfer"));
        var r=rules(); r.remove(0); reject(r);
        r=rules(); r.get(0).attributes.put("domain","device_external"); reject(r);
        r=rules(); r.get(0).attributes.put("path","subdir"); reject(r);
        r=rules(); r.add(r.get(0)); reject(r);
        r=rules(); r.add(new P6aManifestAudit.Element("include","cloud-backup")); reject(r);
        System.out.println("P6b1 backup rules: positive + 5 negatives PASS; Android NOT_RUN");
    }
}
