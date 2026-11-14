package dev.aiauto.displayd;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 功能用途：定义有界线协议的纯逻辑入口，避免单测依赖 Android 的 JSON 桩。 */
final class Protocol {
    record Request(int id, String method, int width, int height, int dpi, int displayId) {}

    static final class Failure extends Exception {
        final String code;
        Failure(String code, String message) { super(message); this.code = code; }
    }

    static Request read(InputStream input) throws IOException, Failure {
        var bytes = new ByteArrayOutputStream();
        int b;
        while ((b = input.read()) != '\n') {
            if (b == -1) throw invalid();
            if (bytes.size() == Policy.MAX_MESSAGE_BYTES)
                throw new Failure("MESSAGE_TOO_LARGE", "JSON line exceeds 16384 bytes");
            bytes.write(b);
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException e) { throw invalid(); }
        Map<String, Object> fields = new Parser(text).object();
        int id = integer(fields, "id");
        Object methodValue = fields.get("method");
        if (!(methodValue instanceof String)) throw invalid();
        String method = (String) methodValue;
        Set<String> expected;
        switch (method) {
            case "hello", "display.list", "status", "shutdown" -> expected = Set.of("id", "method");
            case "display.create" -> expected = Set.of("id", "method", "width", "height", "dpi");
            case "display.close", "display.capture" -> expected = Set.of("id", "method", "displayId");
            default -> throw new Failure("UNKNOWN_METHOD", "method is not supported");
        }
        if (!fields.keySet().equals(expected)) throw invalid();
        int width = 0, height = 0, dpi = 0, displayId = -1;
        if (method.equals("display.create")) {
            width = integer(fields, "width"); height = integer(fields, "height"); dpi = integer(fields, "dpi");
            Policy.dimensions(width, height, dpi);
        }
        if (fields.containsKey("displayId")) displayId = integer(fields, "displayId");
        return new Request(id, method, width, height, dpi, displayId);
    }

    private static int integer(Map<String, Object> fields, String key) throws Failure {
        if (!(fields.get(key) instanceof Integer)) throw invalid();
        return (Integer) fields.get(key);
    }

    private static Failure invalid() { return new Failure("INVALID_REQUEST", "invalid request schema or JSON"); }

    static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }

    static byte[] line(Object value) throws Failure {
        byte[] bytes = json(value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Policy.MAX_MESSAGE_BYTES)
            throw new Failure("MESSAGE_TOO_LARGE", "response exceeds JSON budget");
        byte[] line = java.util.Arrays.copyOf(bytes, bytes.length + 1);
        line[bytes.length] = '\n';
        return line;
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String) {
            StringBuilder out = new StringBuilder("\"");
            for (char c : ((String) value).toCharArray()) {
                if (c == '"' || c == '\\') out.append('\\').append(c);
                else if (c < 32) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                else out.append(c);
            }
            return out.append('"').toString();
        }
        if (value instanceof Map) {
            var parts = new java.util.ArrayList<String>();
            for (var entry : ((Map<?, ?>) value).entrySet())
                parts.add(json(entry.getKey()) + ":" + json(entry.getValue()));
            return "{" + String.join(",", parts) + "}";
        }
        if (value instanceof Iterable) {
            var parts = new java.util.ArrayList<String>();
            for (Object item : (Iterable<?>) value) parts.add(json(item));
            return "[" + String.join(",", parts) + "]";
        }
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        throw new IllegalArgumentException("unsupported JSON value");
    }

    // 请求只允许一层对象、字符串与非负整数字面量；没有递归容器，避免深度和数值歧义。
    private static final class Parser {
        private final String text;
        private int at;
        Parser(String text) { this.text = text; }
        Map<String, Object> object() throws Failure {
            Map<String, Object> result = new LinkedHashMap<>();
            take('{');
            if (!peek('}')) {
                do {
                    String key = string(); take(':');
                    Object value = peek('"') ? string() : number();
                    if (result.put(key, value) != null) throw invalid();
                    if (!peek(',')) break;
                    take(',');
                } while (true);
            }
            take('}'); whitespace();
            if (at != text.length()) throw invalid();
            return result;
        }
        void whitespace() {
            while (at < text.length() && " \t\r".indexOf(text.charAt(at)) >= 0) at++;
        }
        boolean peek(char c) { whitespace(); return at < text.length() && text.charAt(at) == c; }
        void take(char c) throws Failure { if (!peek(c)) throw invalid(); at++; }
        Integer number() throws Failure {
            whitespace(); int start = at;
            while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') at++;
            if (start == at || (at - start > 1 && text.charAt(start) == '0')) throw invalid();
            try { return Integer.valueOf(text.substring(start, at)); }
            catch (NumberFormatException e) { throw invalid(); }
        }
        String string() throws Failure {
            take('"'); StringBuilder out = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') return out.toString();
                if (c < 32) throw invalid();
                if (c == '\\') {
                    if (at == text.length()) throw invalid();
                    char escape = text.charAt(at++);
                    switch (escape) {
                        case '"', '\\', '/' -> c = escape;
                        case 'b' -> c = '\b'; case 'f' -> c = '\f'; case 'n' -> c = '\n';
                        case 'r' -> c = '\r'; case 't' -> c = '\t';
                        case 'u' -> {
                            if (at + 4 > text.length()) throw invalid();
                            int code = 0;
                            for (int i = 0; i < 4; i++) {
                                char hex = text.charAt(at++);
                                int digit = "0123456789abcdef".indexOf(Character.toLowerCase(hex));
                                if (digit < 0) throw invalid();
                                code = code * 16 + digit;
                            }
                            c = (char) code;
                        }
                        default -> throw invalid();
                    }
                }
                out.append(c);
            }
            throw invalid();
        }
    }
}
