package net.flamoris.pudding.http;

public enum HttpMethod {
    GET,
    HEAD,
    POST,
    PUT,
    PATCH,
    DELETE,
    OPTIONS;

    public boolean writes() { return this == POST || this == PUT || this == PATCH || this == DELETE; }
}
