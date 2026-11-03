package org.json;
import java.util.*;
/** Host-only typed JSON seam. NOT Android org.json; no API/platform claim. */
public class JSONObject {
    final LinkedHashMap<String,Object> values=new LinkedHashMap<>();
    public JSONObject(){}
    public JSONObject(String text)throws JSONException{Object o=new Parser(text).parse();if(!(o instanceof JSONObject))throw new JSONException("not an object");values.putAll(((JSONObject)o).values);}
    public JSONObject put(String key,Object value)throws JSONException{if(key==null)throw new JSONException("null key");values.put(key,value);return this;}
    Object get(String key)throws JSONException{if(!values.containsKey(key))throw new JSONException("missing "+key);return values.get(key);}
    public String getString(String key)throws JSONException{return (String)get(key);}
    public String optString(String key){return optString(key,"");}public String optString(String key,String fallback){Object o=values.get(key);return o==null?fallback:o.toString();}
    public int getInt(String key)throws JSONException{return ((Number)get(key)).intValue();}public long getLong(String key)throws JSONException{return ((Number)get(key)).longValue();}
    public int optInt(String key){return optInt(key,0);}public int optInt(String key,int fallback){Object o=values.get(key);return o instanceof Number?((Number)o).intValue():fallback;}
    public boolean optBoolean(String key){return Boolean.TRUE.equals(values.get(key));}
    public JSONArray getJSONArray(String key)throws JSONException{return (JSONArray)get(key);}public JSONArray optJSONArray(String key){Object o=values.get(key);return o instanceof JSONArray?(JSONArray)o:null;}
    public String toString(){return encode(this,0,0);}public String toString(int indent){return encode(this,indent,0);}
    static String quote(String text){StringBuilder b=new StringBuilder("\"");for(int i=0;i<text.length();i++){char c=text.charAt(i);switch(c){case '"':b.append("\\\"");break;case '\\':b.append("\\\\");break;case '\n':b.append("\\n");break;case '\r':b.append("\\r");break;case '\t':b.append("\\t");break;case '\b':b.append("\\b");break;case '\f':b.append("\\f");break;default:if(c<32)b.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else b.append(c);}}return b.append('"').toString();}
    static String encode(Object o,int step,int level){
        if(o==null)return "null";if(o instanceof String)return quote((String)o);
        if(o instanceof JSONObject){JSONObject object=(JSONObject)o;StringBuilder b=new StringBuilder("{");int index=0;for(var item:object.values.entrySet()){if(index++>0)b.append(',');if(step>0)b.append('\n').append(" ".repeat(level+step));b.append(quote(item.getKey())).append(step>0?": ":":").append(encode(item.getValue(),step,level+step));}if(step>0&&!object.values.isEmpty())b.append('\n').append(" ".repeat(level));return b.append('}').toString();}
        if(o instanceof JSONArray){JSONArray array=(JSONArray)o;StringBuilder b=new StringBuilder("[");for(int i=0;i<array.values.size();i++){if(i>0)b.append(',');if(step>0)b.append('\n').append(" ".repeat(level+step));b.append(encode(array.values.get(i),step,level+step));}if(step>0&&!array.values.isEmpty())b.append('\n').append(" ".repeat(level));return b.append(']').toString();}
        if(o instanceof Double){double n=(Double)o;if(n==(long)n)return Long.toString((long)n);}return o.toString();
    }
    static class Parser {
        final String text;int pos;Parser(String text){this.text=text;}void space(){while(pos<text.length()&&Character.isWhitespace(text.charAt(pos)))pos++;}
        Object parse()throws JSONException{Object o=value();space();if(pos!=text.length())throw new JSONException("trailing data");return o;}
        char take()throws JSONException{if(pos>=text.length())throw new JSONException("EOF");return text.charAt(pos++);}
        Object value()throws JSONException{space();char c=take();if(c=='"')return string();if(c=='{'){JSONObject o=new JSONObject();space();if(pos<text.length()&&text.charAt(pos)=='}'){pos++;return o;}while(true){space();if(take()!='"')throw new JSONException("key");String key=string();space();if(take()!=':')throw new JSONException("colon");o.put(key,value());space();char end=take();if(end=='}')return o;if(end!=',')throw new JSONException("comma");}}
            if(c=='['){JSONArray a=new JSONArray();space();if(pos<text.length()&&text.charAt(pos)==']'){pos++;return a;}while(true){a.put(value());space();char end=take();if(end==']')return a;if(end!=',')throw new JSONException("comma");}}
            int start=pos-1;while(pos<text.length()&&",]} \n\r\t".indexOf(text.charAt(pos))<0)pos++;String token=text.substring(start,pos);if(token.equals("true"))return true;if(token.equals("false"))return false;if(token.equals("null"))return null;try{return token.contains(".")||token.contains("e")||token.contains("E")?Double.valueOf(token):Long.valueOf(token);}catch(Exception ex){throw new JSONException("bad value");}}
        String string()throws JSONException{StringBuilder b=new StringBuilder();while(true){char c=take();if(c=='"')return b.toString();if(c!='\\'){b.append(c);continue;}c=take();switch(c){case 'n':b.append('\n');break;case 'r':b.append('\r');break;case 't':b.append('\t');break;case 'b':b.append('\b');break;case 'f':b.append('\f');break;case 'u':try{b.append((char)Integer.parseInt(text.substring(pos,pos+4),16));pos+=4;}catch(Exception ex){throw new JSONException("escape");}break;default:b.append(c);}}}
    }
}
