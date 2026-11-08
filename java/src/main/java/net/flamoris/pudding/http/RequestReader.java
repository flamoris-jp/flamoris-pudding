package net.flamoris.pudding.http;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pudding's independent fixed-length HTTP/1.1 input policy. */
public final class RequestReader {
    private int bytes;
    private HttpMethod method;
    public HttpMethod parsedMethod(){return method;}
    private String line(InputStream in) throws IOException, HttpFailure {
        var out = new ByteArrayOutputStream();
        while (true) {
            int c = in.read();
            if (c < 0 || c == '\n' || c > 127 || c == 0) throw new HttpFailure(400);
            if (++bytes > 16384 || out.size() > 4096) throw new HttpFailure(431);
            if (c == '\r') {
                if (in.read() != '\n') throw new HttpFailure(400);
                if (++bytes > 16384) throw new HttpFailure(431);
                return out.toString(StandardCharsets.US_ASCII);
            }
            if ((c < 32 && c != '\t') || c == 127) throw new HttpFailure(400);
            out.write(c);
        }
    }
    public HttpRequest read(InputStream in) throws IOException, HttpFailure {
        bytes = 0;
        method = null;
        String[] start = line(in).split(" ", -1);
        if (start.length != 3 || !HttpTarget.isOriginForm(start[1])) throw new HttpFailure(400);
        if (!start[2].equals("HTTP/1.1")) throw new HttpFailure(505);
        try { method = HttpMethod.valueOf(start[0]); } catch (IllegalArgumentException e) { throw new HttpFailure(405); }
        var headers = new LinkedHashMap<String,String>();
        while (true) {
            String line = line(in);
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon < 1 || headers.size() >= 64) throw new HttpFailure(400);
            String name = line.substring(0,colon);
            if (!name.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+")) throw new HttpFailure(400);
            if (headers.putIfAbsent(name.toLowerCase(Locale.ROOT), line.substring(colon+1).strip()) != null) throw new HttpFailure(400);
        }
        String host = headers.get("host");
        if (host == null || host.isBlank() || host.chars().anyMatch(c -> c <= 32 || c >= 127)) throw new HttpFailure(400);
        if (headers.containsKey("transfer-encoding") || headers.containsKey("content-encoding") || headers.containsKey("upgrade")) throw new HttpFailure(400);
        if (headers.containsKey("expect")) throw new HttpFailure(417);
        String length = headers.get("content-length");
        int size = 0;
        if (length != null) {
            if (!length.matches("0|[1-9][0-9]{0,5}")) throw new HttpFailure(400);
            size = Integer.parseInt(length);
            if (size > 65536) throw new HttpFailure(413);
        }
        if (method == HttpMethod.POST) {
            if (length == null) throw new HttpFailure(411);
            String content = headers.getOrDefault("content-type", "").toLowerCase(Locale.ROOT);
            if (!content.matches("application/json(?:;\\s*charset=utf-8)?")) throw new HttpFailure(415);
        } else if (size != 0) throw new HttpFailure(400);
        byte[] body = in.readNBytes(size);
        if (body.length != size) throw new HttpFailure(400);
        return new HttpRequest(method, start[1], start[2], headers, body);
    }
}
