package org.json;
import java.util.*;
public class JSONArray {
    final List<Object> values=new ArrayList<>();public JSONArray(){}public JSONArray(double[] array){for(double n:array)values.add(n);}
    public JSONArray put(Object value){values.add(value);return this;}public JSONArray put(int index,Object value){while(values.size()<=index)values.add(null);values.set(index,value);return this;}
    public int length(){return values.size();}public double getDouble(int index){return ((Number)values.get(index)).doubleValue();}public JSONObject getJSONObject(int index){return (JSONObject)values.get(index);}
}
