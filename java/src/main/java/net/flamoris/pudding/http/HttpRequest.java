package net.flamoris.pudding.http;

import java.util.Map;
import java.util.Objects;

/** HTTP transport value adapted with permission; application security is separate. */
public record HttpRequest(HttpMethod method, String target, String version,
                          Map<String, String> headers, byte[] body) {
    public HttpRequest {
        Objects.requireNonNull(method);
        Objects.requireNonNull(target);
        Objects.requireNonNull(version);
        headers = Map.copyOf(headers);
        body = Objects.requireNonNull(body).clone();
        if (body.length > 65536) throw new IllegalArgumentException("body size");
    }
    public String path() {
        int query = target.indexOf('?');
        return query >= 0 ? target.substring(0, query) : target;
    }
    @Override public byte[] body() { return body.clone(); }
    @Override public String toString() { return "HttpRequest[method=" + method + ",redacted]"; }
}
