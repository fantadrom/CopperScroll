import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipFile;

/** Read actual compiled AXML attributes; string presence alone cannot establish component ACLs. */
final class P6aManifestAudit {
    private static final String ANDROID="http://schemas.android.com/apk/res/android";
    private static final String OWNER="com.fantadrom.copperscroll.storage.runtime.ExternalGrantOwnerService";
    private static final String PROVIDER="com.fantadrom.copperscroll.storage.runtime.ExternalGrantDocumentsProvider";
    static void check(ZipFile target,ZipFile test) throws Exception {
        List<Element> app=read(target),instrumentation=read(test);
        String pkg=find(app,"manifest",null,null).attributes.get("package");
        String testPkg=find(instrumentation,"manifest",null,null).attributes.get("package");
        require((pkg+".test").equals(testPkg),"P6a owner/test package mismatch");
        String permission=pkg+".p6a.GRANT_CONTROL";
        Element definition=find(app,"permission","name",permission);
        require(definition.parent.equals("manifest") && "2".equals(definition.attributes.get("protectionLevel")),"Grant owner permission must be signature-only");
        find(app,"uses-permission","name",permission);
        Element service=find(instrumentation,"service","name",OWNER);
        require(service.parent.equals("application") && "true".equals(service.attributes.get("exported")) &&
            permission.equals(service.attributes.get("permission")) && ":p6aGrantOwner".equals(service.attributes.get("process")),"Grant owner channel missing or unprotected");
        Element provider=find(instrumentation,"provider","name",PROVIDER);
        require(provider.parent.equals("application") && (testPkg+".p6a.external.documents").equals(provider.attributes.get("authorities")) &&
            "true".equals(provider.attributes.get("exported")) && "true".equals(provider.attributes.get("grantUriPermissions")) &&
            "android.permission.MANAGE_DOCUMENTS".equals(provider.attributes.get("permission")) &&
            ":p6aExternalProvider".equals(provider.attributes.get("process")),"External grant provider manifest/authority missing");
    }
    private static Element find(List<Element> elements,String tag,String key,String value) {
        List<Element> found=new ArrayList<>();
        for(Element e:elements) if(e.tag.equals(tag) && (key==null || value.equals(e.attributes.get(key)))) found.add(e);
        require(found.size()==1,"Missing/duplicate P6a manifest element: "+tag+" "+value);
        return found.get(0);
    }
    private static final class Element {
        final String tag,parent; final Map<String,String> attributes=new HashMap<>();
        Element(String tag,String parent) { this.tag=tag; this.parent=parent; }
    }
    private static List<Element> read(ZipFile apk) throws Exception {
        byte[] bytes;
        try(var in=apk.getInputStream(apk.getEntry("AndroidManifest.xml"))) { bytes=in.readNBytes(1024*1024+1); }
        require(bytes.length<=1024*1024,"Manifest oversized");
        ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        require(u16(b,0)==3 && b.getInt(4)==bytes.length,"Invalid AXML header");
        List<String> strings=new ArrayList<>(); List<Element> result=new ArrayList<>(); Deque<String> parents=new ArrayDeque<>();
        for(int offset=u16(b,2);offset<bytes.length;) {
            int type=u16(b,offset),header=u16(b,offset+2),size=b.getInt(offset+4);
            require(header>=8 && size>=header && size<=bytes.length-offset,"Invalid AXML chunk");
            if(type==1) {
                require(strings.isEmpty() && header>=28,"Invalid AXML string pool");
                int count=b.getInt(offset+8),flags=b.getInt(offset+16),start=b.getInt(offset+20);
                require(count>=0 && count<65536 && header+4L*count<=size && start>=header && start<size,"Invalid string table");
                for(int i=0;i<count;i++) {
                    int position=offset+start+b.getInt(offset+header+4*i); int[] cursor={position};
                    require(position>=offset+start && position<offset+size,"Invalid string offset");
                    int length=length(b,cursor,(flags&256)!=0);
                    if((flags&256)!=0) length=length(b,cursor,true);
                    else length=Math.multiplyExact(length,2);
                    require(length>=0 && cursor[0]+(long)length<=offset+size,"Invalid manifest string");
                    strings.add(new String(bytes,cursor[0],length,(flags&256)!=0?StandardCharsets.UTF_8:StandardCharsets.UTF_16LE));
                }
            } else if(type==0x102) {
                require(header==16 && size>=36,"Invalid start element");
                String tag=str(strings,b.getInt(offset+20)); Element e=new Element(tag,parents.isEmpty()?"":parents.peek());
                int start=u16(b,offset+24),stride=u16(b,offset+26),count=u16(b,offset+28);
                require(stride>=20 && start>=20 && 16+start+(long)stride*count<=size,"Invalid attribute table");
                for(int i=0;i<count;i++) {
                    int p=offset+16+start+i*stride,ns=b.getInt(p),kind=Byte.toUnsignedInt(b.get(p+15)),data=b.getInt(p+16);
                    if(ns!=-1 && !ANDROID.equals(str(strings,ns))) continue;
                    String value=kind==3?str(strings,data):kind==18?(data==0?"false":"true"):Integer.toString(data);
                    require(e.attributes.put(str(strings,b.getInt(p+4)),value)==null,"Duplicate manifest attribute");
                }
                result.add(e); parents.push(tag);
            } else if(type==0x103) {
                require(!parents.isEmpty() && parents.pop().equals(str(strings,b.getInt(offset+20))),"Unbalanced AXML");
            }
            offset+=size;
        }
        require(parents.isEmpty(),"Unclosed AXML"); return result;
    }
    private static int length(ByteBuffer b,int[] cursor,boolean utf8) {
        int first=utf8?Byte.toUnsignedInt(b.get(cursor[0]++)):u16(b,cursor[0]);
        if(!utf8) cursor[0]+=2;
        int mask=utf8?128:32768;
        if((first&mask)==0) return first;
        int second=utf8?Byte.toUnsignedInt(b.get(cursor[0]++)):u16(b,cursor[0]);
        if(!utf8) cursor[0]+=2;
        return ((first&(mask-1))<<(utf8?8:16))|second;
    }
    private static int u16(ByteBuffer b,int p) { return Short.toUnsignedInt(b.getShort(p)); }
    private static String str(List<String> pool,int i) { require(i>=0 && i<pool.size(),"Invalid manifest string index"); return pool.get(i); }
    private static void require(boolean condition,String message) { if(!condition) throw new IllegalStateException(message); }
}
