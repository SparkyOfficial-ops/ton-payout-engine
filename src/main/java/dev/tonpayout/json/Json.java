package dev.tonpayout.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {
    private Json() {
    }

    public static Object parse(String text) {
        Parser parser = new Parser(text);
        Object value = parser.parseValue();
        parser.skip();
        if (!parser.end()) {
            throw new IllegalArgumentException("trailing data in JSON");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("JSON object expected");
        }
        return (Map<String, Object>) value;
    }

    public static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            writeString(out, (String) value);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(out, String.valueOf(entry.getKey()));
                out.append(':');
                write(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(out, item);
            }
            out.append(']');
        } else {
            writeString(out, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static final class Parser {
        private final String text;
        private int i;

        private Parser(String text) {
            this.text = text == null ? "" : text;
        }

        private Object parseValue() {
            skip();
            if (end()) {
                throw new IllegalArgumentException("empty JSON");
            }
            char c = text.charAt(i);
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"') {
                return parseString();
            }
            if (c == 't' && text.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (c == 'f' && text.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (c == 'n' && text.startsWith("null", i)) {
                i += 4;
                return null;
            }
            if (c == '-' || (c >= '0' && c <= '9')) {
                return parseNumber();
            }
            throw new IllegalArgumentException("unexpected character at " + i);
        }

        private Map<String, Object> parseObject() {
            i++;
            Map<String, Object> object = new LinkedHashMap<>();
            skip();
            if (peek('}')) {
                i++;
                return object;
            }
            while (true) {
                skip();
                if (end() || text.charAt(i) != '"') {
                    throw new IllegalArgumentException("object key expected");
                }
                String key = parseString();
                skip();
                if (!peek(':')) {
                    throw new IllegalArgumentException("colon expected");
                }
                i++;
                object.put(key, parseValue());
                skip();
                if (peek('}')) {
                    i++;
                    return object;
                }
                if (!peek(',')) {
                    throw new IllegalArgumentException("comma expected");
                }
                i++;
            }
        }

        private List<Object> parseArray() {
            i++;
            List<Object> array = new ArrayList<>();
            skip();
            if (peek(']')) {
                i++;
                return array;
            }
            while (true) {
                array.add(parseValue());
                skip();
                if (peek(']')) {
                    i++;
                    return array;
                }
                if (!peek(',')) {
                    throw new IllegalArgumentException("comma expected");
                }
                i++;
            }
        }

        private String parseString() {
            i++;
            StringBuilder out = new StringBuilder();
            while (!end()) {
                char c = text.charAt(i++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    if (end()) {
                        break;
                    }
                    char esc = text.charAt(i++);
                    switch (esc) {
                        case '"', '\\', '/' -> out.append(esc);
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            if (i + 4 > text.length()) {
                                throw new IllegalArgumentException("bad unicode escape");
                            }
                            out.append((char) Integer.parseInt(text.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw new IllegalArgumentException("bad escape");
                    }
                } else {
                    out.append(c);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private Object parseNumber() {
            int start = i;
            if (peek('-')) {
                i++;
            }
            while (!end() && text.charAt(i) >= '0' && text.charAt(i) <= '9') {
                i++;
            }
            boolean fractional = false;
            if (!end() && (text.charAt(i) == '.' || text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
                fractional = true;
                i++;
                while (!end()) {
                    char c = text.charAt(i);
                    if ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == 'e' || c == 'E' || c == '.') {
                        i++;
                    } else {
                        break;
                    }
                }
            }
            String raw = text.substring(start, i);
            if (fractional) {
                return raw;
            }
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException e) {
                return raw;
            }
        }

        private void skip() {
            while (!end() && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
        }

        private boolean peek(char c) {
            return !end() && text.charAt(i) == c;
        }

        private boolean end() {
            return i >= text.length();
        }
    }
}
