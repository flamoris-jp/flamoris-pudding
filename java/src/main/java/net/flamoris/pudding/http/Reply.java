package net.flamoris.pudding.http;

import java.nio.charset.StandardCharsets;
import java.util.*;
import net.flamoris.pudding.Json;

public record Reply(int status, Map<String,String> headers, byte[] body) {
    public Reply {
        if (!Set.of(200,202,204,400,401,403,404,405,406,411,413,415,417,429,431,500,503,505).contains(status)) throw new IllegalArgumentException("status");
        for (var field : headers.entrySet()) {
            if (!Set.of("Content-Type","Mcp-Session-Id","Allow","WWW-Authenticate").contains(field.getKey()) ||
                field.getValue().length() > 1024 || field.getValue().chars().anyMatch(c->c<32||c>126)) throw new IllegalArgumentException("response header");
        }
        headers = Map.copyOf(headers); body = body.clone();
        if (body.length > 1048576 || ((status==202||status==204) && body.length!=0)) throw new IllegalArgumentException("response size");
    }
    @Override public byte[] body() { return body.clone(); }
    public static Reply json(int status, Object value) { return new Reply(status,Map.of("Content-Type","application/json; charset=utf-8"),Json.write(value).getBytes(StandardCharsets.UTF_8)); }
    public static Reply empty(int status) { return new Reply(status,Map.of(),new byte[0]); }
    public Reply header(String name,String value) {var next=new HashMap<>(headers);next.put(name,value);return new Reply(status,next,body);}
    public static Reply refused(int status) {return json(status,Map.of("error","REQUEST_REJECTED"));}
    @Override public String toString() {return "Reply[status="+status+",redacted]";}
}
