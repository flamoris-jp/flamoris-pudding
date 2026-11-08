package net.flamoris.pudding.http;

import java.net.URI;
import java.net.URISyntaxException;

/** Shared syntax check; never decodes or normalizes the routing path. */
final class HttpTarget {
    private HttpTarget() { }

    static boolean isOriginForm(String target) {
        if (!target.startsWith("/") || target.startsWith("//")) return false;
        if (target.chars().anyMatch(c -> c <= 0x20 || c >= 0x7f)) return false;
        try {
            URI uri = new URI(target);
            return !uri.isAbsolute() && uri.getRawAuthority() == null && uri.getRawFragment() == null;
        } catch (URISyntaxException ex) {
            return false;
        }
    }
}
