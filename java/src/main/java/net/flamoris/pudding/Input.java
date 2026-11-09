package net.flamoris.pudding;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Strict bounded inputs shared by the sandbox adapters. */
public final class Input {
    private Input(){}
    @SuppressWarnings("unchecked") public static Map<String,Object> object(Object value) {
        if(!(value instanceof Map<?,?> m)||m.keySet().stream().anyMatch(k->!(k instanceof String)))throw new IllegalArgumentException("object");
        return new LinkedHashMap<>((Map<String,Object>)m);
    }
    public static Map<String,Object> parse(String json){return object(JsonInput.parse(json.getBytes(StandardCharsets.UTF_8)));}
    public static void fields(Map<String,Object> m,String required,String optional) {
        var needed=words(required);var allowed=new HashSet<>(needed);allowed.addAll(words(optional));
        if(!m.keySet().containsAll(needed)||!allowed.containsAll(m.keySet()))throw new IllegalArgumentException("fields");
    }
    private static Set<String> words(String s){return s.isBlank()?Set.of():Set.of(s.split(" "));}
    public static String text(Map<String,Object> m,String key,int max) {
        if(!(m.get(key) instanceof String s)||s.isBlank()||s.length()>max||s.chars().anyMatch(c->c<32))throw new IllegalArgumentException(key);return s;
    }
    public static String id(Map<String,Object> m,String key){String s=text(m,key,80);if(!s.matches("[A-Za-z0-9_.-]{1,80}"))throw new IllegalArgumentException(key);return s;}
    public static UUID uuid(Map<String,Object> m,String key){String s=text(m,key,36);var u=UUID.fromString(s);if(!s.equals(u.toString()))throw new IllegalArgumentException(key);return u;}
    public static long number(Map<String,Object> m,String key,long min,long max) {
        Object v=m.get(key);long n;
        if(v instanceof BigInteger b)n=b.longValueExact();else if(v instanceof Integer i)n=i;else if(v instanceof Long l)n=l;else throw new IllegalArgumentException(key);
        if(n<min||n>max)throw new IllegalArgumentException(key);return n;
    }
    public static int integer(Map<String,Object> m,String key,int min,int max){return Math.toIntExact(number(m,key,min,max));}
    public static String digest(Object value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(value).getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static Map<String,Object> ok(String key,Object value){var m=new LinkedHashMap<String,Object>();m.put("ok",true);m.put(key,value);return m;}
    public static Map<String,Object> error(String code){return Map.of("ok",false,"error",code,"food_effect","none");}
}
