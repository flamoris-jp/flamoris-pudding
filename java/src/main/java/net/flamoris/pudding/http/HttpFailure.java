package net.flamoris.pudding.http;

/** Contains only a fixed public status, never request text or credentials. */
public final class HttpFailure extends Exception {
    public final int status;
    public HttpFailure(int status) { super("HTTP request rejected"); this.status = status; }
}
