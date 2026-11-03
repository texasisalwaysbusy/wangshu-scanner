package org.wangshu.scan;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.wangshu.scan.core.Geometry;
import java.util.ArrayList;
import java.util.UUID;

final class Models {
    static final class Page {
        String id=UUID.randomUUID().toString(), file="",sha256="",mode="COLOR",ocrText="",ocrKey="",ocrLanguage="",ocrOrigin="",cropReview="";
        double[] quad=Geometry.full();
        int rotation=0,brightness=0,bias=8;
        boolean shadows=false,removed=false;
        private String cropIdentity(){StringBuilder s=new StringBuilder("quad-v2|");for(String v:new String[]{id,file,sha256})s.append(v.length()).append(':').append(v);s.append('|').append(rotation);for(double v:quad)s.append('|').append(Double.toHexString(v));return s.toString();}
        boolean cropConfirmed(){return Geometry.valid(quad)&&cropReview.equals(cropIdentity());}
        void confirmCrop(){cropReview=Geometry.valid(quad)?cropIdentity():"";}
        Page copy() throws JSONException { return from(json()); }
        JSONObject json() throws JSONException {
            JSONObject o=new JSONObject();o.put("id",id);o.put("file",file);o.put("sha256",sha256);o.put("mode",mode);
            o.put("quad",new JSONArray(quad));o.put("rotation",rotation);o.put("cropReview",cropReview);o.put("brightness",brightness);o.put("bias",bias);o.put("shadows",shadows);o.put("removed",removed);o.put("ocrText",ocrText);o.put("ocrKey",ocrKey);o.put("ocrLanguage",ocrLanguage);if(!ocrOrigin.isEmpty())o.put("ocrOrigin",ocrOrigin);return o;
        }
        static Page from(JSONObject o) throws JSONException {
            Page p=new Page();p.id=o.getString("id");p.file=o.getString("file");p.sha256=o.optString("sha256");p.mode=o.optString("mode","COLOR");
            JSONArray q=o.optJSONArray("quad");boolean validQuad=q!=null&&q.length()==8;if(validQuad)for(int i=0;i<8;i++)p.quad[i]=q.getDouble(i);
            validQuad=validQuad&&Geometry.valid(p.quad);if(!validQuad)p.quad=Geometry.full();
            p.rotation=((o.optInt("rotation")%4)+4)%4;p.brightness=Math.max(-40,Math.min(40,o.optInt("brightness")));p.bias=Math.max(1,Math.min(35,o.optInt("bias",8)));
            if(!p.mode.equals("COLOR")&&!p.mode.equals("GRAY")&&!p.mode.equals("BW"))p.mode="COLOR";
            p.cropReview=validQuad?o.optString("cropReview"):"";p.shadows=o.optBoolean("shadows");p.removed=o.optBoolean("removed");p.ocrText=o.optString("ocrText");p.ocrKey=o.optString("ocrKey");p.ocrLanguage=o.optString("ocrLanguage");String origin=o.optString("ocrOrigin");p.ocrOrigin=origin.equals("machine")||origin.equals("manual")?origin:"";return p;
        }
    }
    static final class Document {
        String id=UUID.randomUUID().toString(),title="扫描文稿";
        long created=System.currentTimeMillis(),updated=created;
        boolean archived=false;
        // In-memory concurrency baseline, never serialized in the manifest.
        String savedSnapshot="";
        volatile boolean manifestUncertain=false; // in-memory only; never serialized
        ArrayList<Page> pages=new ArrayList<>();
        ArrayList<Page> active() {ArrayList<Page> r=new ArrayList<>();for(Page p:pages)if(!p.removed)r.add(p);return r;}
        JSONObject json() throws JSONException {
            JSONObject o=new JSONObject();o.put("schemaVersion",1);o.put("id",id);o.put("title",title);o.put("created",created);o.put("updated",updated);o.put("archived",archived);
            JSONArray a=new JSONArray();for(Page p:pages)a.put(p.json());o.put("pages",a);return o;
        }
        static Document from(JSONObject o) throws JSONException {
            if(o.getInt("schemaVersion")!=1)throw new JSONException("Unsupported document version");
            Document d=new Document();d.id=o.getString("id");d.title=o.getString("title");d.created=o.getLong("created");d.updated=o.getLong("updated");d.archived=o.optBoolean("archived");
            JSONArray a=o.getJSONArray("pages");for(int i=0;i<a.length();i++)d.pages.add(Page.from(a.getJSONObject(i)));return d;
        }
    }
}
