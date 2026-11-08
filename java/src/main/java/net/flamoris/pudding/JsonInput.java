package net.flamoris.pudding;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Narrow independent RFC 8259 parser. Bounds apply before any domain action. */
public final class JsonInput {
    private JsonInput() {}
    public static Object parse(byte[] bytes) {
        if (bytes.length > 65536) throw new IllegalArgumentException("JSON size");
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
            var parser = new Parser(text);
            Object result = parser.value(0);
            parser.space();
            if (parser.at != text.length()) throw new IllegalArgumentException("trailing JSON");
            return result;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("JSON encoding");
        }
    }

    private static final class Parser {
        final String text;
        int at, nodes;
        Parser(String text) { this.text = text; }
        IllegalArgumentException invalid() { return new IllegalArgumentException("invalid JSON"); }
        void space() { while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++; }
        boolean take(char c) {
            if (at < text.length() && text.charAt(at) == c) { at++; return true; }
            return false;
        }
        Object value(int depth) {
            if (depth > 32 || ++nodes > 10000) throw invalid();
            space();
            if (at == text.length()) throw invalid();
            char c = text.charAt(at);
            if (c == '"') return string();
            if (c == '{') {
                at++; space();
                var result = new LinkedHashMap<String, Object>();
                if (take('}')) return result;
                do {
                    space(); String key = string(); space();
                    if (!take(':') || result.containsKey(key)) throw invalid();
                    result.put(key, value(depth + 1)); space();
                    if (take('}')) return result;
                } while (take(','));
                throw invalid();
            }
            if (c == '[') {
                at++; space(); var result = new ArrayList<Object>();
                if (take(']')) return result;
                do {
                    result.add(value(depth + 1)); space();
                    if (take(']')) return result;
                } while (take(','));
                throw invalid();
            }
            for (String literal : List.of("true", "false", "null")) {
                if (text.startsWith(literal, at)) {
                    at += literal.length();
                    return literal.equals("null") ? null : Boolean.valueOf(literal);
                }
            }
            return number();
        }
        String string() {
            if (!take('"')) throw invalid();
            var out = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') {
                    // Reject lone surrogate escapes and raw malformed scalar values.
                    for (int i = 0; i < out.length(); i++) {
                        char x = out.charAt(i);
                        if (Character.isHighSurrogate(x)) {
                            if (++i >= out.length() || !Character.isLowSurrogate(out.charAt(i))) throw invalid();
                        } else if (Character.isLowSurrogate(x)) throw invalid();
                    }
                    return out.toString();
                }
                if (c < 32) throw invalid();
                if (c == '\\') {
                    if (at == text.length()) throw invalid();
                    c = text.charAt(at++);
                    switch (c) {
                        case '"', '\\', '/' -> out.append(c);
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            if (at + 4 > text.length()) throw invalid();
                            int value = 0;
                            for (int i = 0; i < 4; i++) {
                                char h = text.charAt(at++);
                                int digit = h <= 127 ? Character.digit(h, 16) : -1;
                                if (digit < 0) throw invalid();
                                value = value * 16 + digit;
                            }
                            out.append((char) value);
                        }
                        default -> throw invalid();
                    }
                } else out.append(c);
            }
            throw invalid();
        }
        Object number() {
            int start = at;
            take('-');
            if (!take('0')) {
                if (at >= text.length() || text.charAt(at) < '1' || text.charAt(at) > '9') throw invalid();
                digits();
            }
            boolean decimal = false;
            if (take('.')) { decimal = true; int before = at; digits(); if (before == at) throw invalid(); }
            if (take('e') || take('E')) {
                decimal = true; if (!take('+')) take('-');
                int before = at; digits(); if (before == at) throw invalid();
            }
            if (at - start > 256) throw invalid();
            try { return decimal ? new BigDecimal(text.substring(start, at)) : new BigInteger(text.substring(start, at)); }
            catch (NumberFormatException e) { throw invalid(); }
        }
        void digits() {
            while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') at++;
        }
    }
}
