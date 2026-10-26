package cn.xm1221.MieLib.installer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny JSON reader, just enough for the installer manifest.
 *
 * <p>Objects become {@link LinkedHashMap}, arrays {@link ArrayList}, integral numbers
 * {@link Long}, other numbers {@link Double}, plus {@link String}, {@link Boolean} and
 * {@code null}. Keeping this in-tree avoids shipping a JSON library in the installer jar.
 */
final class Json {
    private Json() {
    }

    static Object parse(String text) {
        // Tolerate a UTF-8 BOM (hand-edited manifests on Windows).
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        Parser parser = new Parser(text);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("unexpected trailing characters");
        }
        return value;
    }

    static Map<String, Object> asObject(Object value, String what) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) map;
            return cast;
        }
        throw new InstallerException(what + " must be a JSON object");
    }

    static List<Object> asArray(Object value, String what) {
        if (value instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<Object> cast = (List<Object>) list;
            return cast;
        }
        throw new InstallerException(what + " must be a JSON array");
    }

    static String asString(Object value, String what) {
        if (value instanceof String s) {
            return s;
        }
        throw new InstallerException(what + " must be a string");
    }

    static long asLong(Object value, String what) {
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) {
            return d.longValue();
        }
        throw new InstallerException(what + " must be an integer");
    }

    /** Required string field of {@code object}; rejects missing, empty and non-string values. */
    static String requiredString(Map<String, Object> object, String key, String what) {
        Object value = object.get(key);
        if (value == null) {
            throw new InstallerException(what + " is missing \"" + key + "\"");
        }
        String s = asString(value, what + "." + key);
        if (s.isBlank()) {
            throw new InstallerException(what + "." + key + " must not be empty");
        }
        return s;
    }

    /** Optional string field; returns {@code null} when absent or JSON {@code null}. */
    static String optionalString(Map<String, Object> object, String key, String what) {
        Object value = object.get(key);
        if (value == null) {
            return null;
        }
        String s = asString(value, what + "." + key);
        return s.isBlank() ? null : s;
    }

    /** Optional list of strings; returns an empty list when absent. */
    static List<String> optionalStringList(Map<String, Object> object, String key, String what) {
        Object value = object.get(key);
        if (value == null) {
            return List.of();
        }
        List<Object> raw = asArray(value, what + "." + key);
        List<String> out = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            String s = asString(raw.get(i), what + "." + key + "[" + i + "]");
            if (!s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        InstallerException error(String message) {
            return new InstallerException("bad JSON at offset " + pos + ": " + message);
        }

        Object readValue() {
            skipWhitespace();
            if (atEnd()) {
                throw error("unexpected end of input");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield readNumber();
                    }
                    throw error("unexpected character '" + c + "'");
                }
            };
        }

        private Map<String, Object> readObject() {
            Map<String, Object> out = new LinkedHashMap<>();
            pos++; // '{'
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return out;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("expected a string key");
                }
                String key = readString();
                skipWhitespace();
                expect(':');
                Object value = readValue();
                out.put(key, value);
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return out;
                } else {
                    throw error("expected ',' or '}'");
                }
            }
        }

        private List<Object> readArray() {
            List<Object> out = new ArrayList<>();
            pos++; // '['
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return out;
            }
            while (true) {
                out.add(readValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return out;
                } else {
                    throw error("expected ',' or ']'");
                }
            }
        }

        private String readString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw error("unterminated string");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (atEnd()) {
                        throw error("unterminated escape");
                    }
                    char esc = text.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > text.length()) {
                                throw error("truncated \\u escape");
                            }
                            String hex = text.substring(pos, pos + 4);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException e) {
                                throw error("bad \\u escape \"" + hex + "\"");
                            }
                            pos += 4;
                        }
                        default -> throw error("bad escape \\" + esc);
                    }
                } else if (c < 0x20) {
                    throw error("unescaped control character in string");
                } else {
                    sb.append(c);
                }
            }
        }

        private Object readNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (!atEnd() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            boolean integral = true;
            if (!atEnd() && text.charAt(pos) == '.') {
                integral = false;
                pos++;
                while (!atEnd() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            if (!atEnd() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                integral = false;
                pos++;
                if (!atEnd() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                    pos++;
                }
                while (!atEnd() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            String number = text.substring(start, pos);
            try {
                if (integral) {
                    return Long.parseLong(number);
                }
                return Double.parseDouble(number);
            } catch (NumberFormatException e) {
                throw error("bad number \"" + number + "\"");
            }
        }

        private Object readLiteral(String literal, Object value) {
            if (!text.startsWith(literal, pos)) {
                throw error("expected " + literal);
            }
            pos += literal.length();
            return value;
        }

        private char peek() {
            if (atEnd()) {
                throw error("unexpected end of input");
            }
            return text.charAt(pos);
        }

        private void expect(char expected) {
            if (peek() != expected) {
                throw error("expected '" + expected + "'");
            }
            pos++;
        }
    }
}
