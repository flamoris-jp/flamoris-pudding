package net.flamoris.pudding;

import java.util.*;

/** Output-only encoder for fixture reports. No HTTP/MCP input parser. */
public final class Json {
    private Json(){}
    public static String write(Object value){
        if(value==null)return "null";
        if(value instanceof String s){
            var out=new StringBuilder("\"");
            for(int i=0;i<s.length();i++){
                char c=s.charAt(i);
                if(Character.isHighSurrogate(c)){
                    if(i+1>=s.length()||!Character.isLowSurrogate(s.charAt(i+1)))throw new IllegalArgumentException("Unicode");
                    out.append(c).append(s.charAt(++i));continue;
                }
                if(Character.isLowSurrogate(c))throw new IllegalArgumentException("Unicode");
                switch(c){case '"'->out.append("\\\"");case '\\'->out.append("\\\\");case '\b'->out.append("\\b");case '\f'->out.append("\\f");case '\n'->out.append("\\n");case '\r'->out.append("\\r");case '\t'->out.append("\\t");default->{if(c<32)out.append(String.format("\\u%04x",(int)c));else out.append(c);}}
            }return out.append('"').toString();
        }
        if(value instanceof Boolean||value instanceof Integer||value instanceof Long||value instanceof java.math.BigInteger)return value.toString();
        if(value instanceof Map<?,?> m){var parts=new ArrayList<String>();for(var e:m.entrySet()){if(!(e.getKey() instanceof String))throw new IllegalArgumentException("JSON key");parts.add(write(e.getKey())+":"+write(e.getValue()));}return "{"+String.join(",",parts)+"}";}
        if(value instanceof List<?> l)return "["+String.join(",",l.stream().map(Json::write).toList())+"]";
        throw new IllegalArgumentException("unsupported JSON output");
    }
}
