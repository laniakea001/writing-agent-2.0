package com.writingagent.v2.domain.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;

/** Deterministic document boundary: validate first, then derive canonical JSON/Markdown/hash. */
public final class ChapterDocumentCodec {
    public static final int VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> BLOCK = Set.of("paragraph", "heading", "bulletList", "orderedList",
            "blockquote", "codeBlock", "table", "horizontalRule");
    private static final Set<String> INLINE = Set.of("text", "hardBreak");
    private static final Set<String> MARKS = Set.of("bold", "italic", "strike", "link");

    public record EncodedDocument(String contentJson, String bodyMarkdown, String documentHash,
                                  int codecVersion, boolean hasText) {}

    public EncodedDocument encode(String contentJson, boolean requireText) {
        if (contentJson == null || contentJson.length() > 1_000_000) {
            throw invalid("DOCUMENT_SIZE_INVALID");
        }
        final JsonNode root;
        try {
            root = JSON.readTree(contentJson);
        } catch (Exception e) {
            throw invalid("DOCUMENT_JSON_INVALID");
        }
        if (root == null || !"doc".equals(root.path("type").asText())) {
            throw invalid("DOCUMENT_ROOT_REQUIRED");
        }
        validate(root, 0, new int[]{0});
        boolean hasText = hasText(root);
        if (requireText && !hasText) throw invalid("DOCUMENT_TEXT_REQUIRED");
        String canonical = canonical(root).toString();
        return new EncodedDocument(canonical, markdown(root).strip(), sha256(canonical), VERSION, hasText);
    }

    private static void validate(JsonNode node, int depth, int[] count) {
        if (!node.isObject() || depth > 64 || ++count[0] > 20_000) throw invalid("DOCUMENT_STRUCTURE_INVALID");
        String type = node.path("type").asText();
        Set<String> allowedChildren = switch (type) {
            case "doc", "blockquote", "listItem", "tableCell", "tableHeader" -> BLOCK;
            case "paragraph", "heading" -> INLINE;
            case "codeBlock" -> Set.of("text");
            case "bulletList", "orderedList" -> Set.of("listItem");
            case "table" -> Set.of("tableRow");
            case "tableRow" -> Set.of("tableCell", "tableHeader");
            case "text", "hardBreak", "horizontalRule" -> Set.of();
            default -> throw invalid("UNSUPPORTED_DOCUMENT_NODE");
        };
        node.fieldNames().forEachRemaining(key -> {
            if (!Set.of("type", "content", "attrs", "text", "marks").contains(key)) {
                throw invalid("DOCUMENT_FIELD_UNSUPPORTED");
            }
        });
        if (node.has("text") && !"text".equals(type)) throw invalid("DOCUMENT_STRUCTURE_INVALID");
        if ("text".equals(type) && (!node.path("text").isTextual() || node.path("text").asText().isEmpty())) {
            throw invalid("DOCUMENT_TEXT_INVALID");
        }
        validateAttrs(node, type);
        if (node.has("marks")) {
            if (!"text".equals(type) || !node.get("marks").isArray()) throw invalid("DOCUMENT_MARK_INVALID");
            Set<String> seen = new java.util.HashSet<>();
            for (JsonNode mark : node.get("marks")) {
                String m = mark.path("type").asText();
                if (!mark.isObject() || !MARKS.contains(m) || !seen.add(m)) throw invalid("DOCUMENT_MARK_INVALID");
                mark.fieldNames().forEachRemaining(key -> {
                    if (!Set.of("type", "attrs").contains(key)) throw invalid("DOCUMENT_MARK_INVALID");
                });
                validateAttrs(mark, m);
            }
        }
        if (node.has("content")) {
            if (!node.get("content").isArray() || allowedChildren.isEmpty()) throw invalid("DOCUMENT_STRUCTURE_INVALID");
            for (JsonNode child : node.get("content")) {
                if (!allowedChildren.contains(child.path("type").asText())) throw invalid("UNSUPPORTED_DOCUMENT_NODE");
                if ("codeBlock".equals(type) && !child.path("marks").isEmpty()) throw invalid("DOCUMENT_MARK_INVALID");
                validate(child, depth + 1, count);
            }
        }
        if (Set.of("bulletList", "orderedList", "listItem", "table", "tableRow", "tableCell", "tableHeader", "blockquote")
                .contains(type) && node.path("content").isEmpty()) throw invalid("DOCUMENT_STRUCTURE_INVALID");
        if ("listItem".equals(type) && !"paragraph".equals(node.path("content").path(0).path("type").asText())) {
            throw invalid("DOCUMENT_STRUCTURE_INVALID");
        }
    }

    private static void validateAttrs(JsonNode node, String type) {
        JsonNode attrs = node.get("attrs");
        if (attrs != null && !attrs.isNull() && !attrs.isObject()) throw invalid("DOCUMENT_ATTRIBUTE_INVALID");
        Set<String> allowed = switch (type) {
            case "heading" -> Set.of("level");
            case "orderedList" -> Set.of("start", "type");
            case "codeBlock" -> Set.of("language");
            case "link" -> Set.of("href", "target", "rel", "class");
            case "tableCell", "tableHeader" -> Set.of("colspan", "rowspan", "colwidth");
            default -> Set.of();
        };
        if (attrs != null && attrs.isObject()) attrs.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) throw invalid("DOCUMENT_ATTRIBUTE_UNSUPPORTED");
        });
        if ("heading".equals(type)) {
            JsonNode level = node.path("attrs").path("level");
            if (!level.isIntegralNumber() || level.asInt() < 2 || level.asInt() > 4) throw invalid("DOCUMENT_HEADING_INVALID");
        }
        if ("orderedList".equals(type) && attrs != null && attrs.has("start") &&
                (!attrs.get("start").isIntegralNumber() || attrs.get("start").asInt() < 1)) throw invalid("DOCUMENT_ATTRIBUTE_INVALID");
        if ("orderedList".equals(type) && attrs != null && attrs.hasNonNull("type") &&
                !"1".equals(attrs.get("type").asText())) throw invalid("DOCUMENT_ATTRIBUTE_INVALID");
        if ("link".equals(type)) {
            String href = node.path("attrs").path("href").asText();
            try {
                URI uri = URI.create(href);
                if (uri.getScheme() == null || !Set.of("http", "https", "mailto").contains(uri.getScheme().toLowerCase(java.util.Locale.ROOT))
                        || (("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) && uri.getHost() == null)) {
                    throw invalid("DOCUMENT_LINK_REJECTED");
                }
            } catch (IllegalArgumentException e) { throw invalid("DOCUMENT_LINK_REJECTED"); }
        }
        if ("tableCell".equals(type) || "tableHeader".equals(type)) {
            for (String key : Set.of("colspan", "rowspan")) {
                JsonNode v = node.path("attrs").path(key);
                if (!v.isMissingNode() && (!v.isIntegralNumber() || v.asInt() < 1 || v.asInt() > 100)) throw invalid("DOCUMENT_ATTRIBUTE_INVALID");
            }
            JsonNode widths = node.path("attrs").path("colwidth");
            if (!widths.isMissingNode() && !widths.isNull()) {
                if (!widths.isArray()) throw invalid("DOCUMENT_ATTRIBUTE_INVALID");
                for (JsonNode w : widths) if (!w.isIntegralNumber() || w.asInt() < 1) throw invalid("DOCUMENT_ATTRIBUTE_INVALID");
            }
        }
    }

    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            Set<String> keys = new TreeSet<>();
            node.fieldNames().forEachRemaining(keys::add);
            keys.forEach(k -> result.set(k, canonical(node.get(k))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JSON.createArrayNode();
            node.forEach(n -> result.add(canonical(n)));
            return result;
        }
        return node.deepCopy();
    }

    private static boolean hasText(JsonNode n) {
        if ("text".equals(n.path("type").asText()) && !n.path("text").asText().isBlank()) return true;
        for (JsonNode child : n.path("content")) if (hasText(child)) return true;
        return false;
    }

    private static String markdown(JsonNode node) {
        String type = node.path("type").asText();
        StringBuilder children = new StringBuilder();
        for (JsonNode child : node.path("content")) children.append(markdown(child));
        String text = children.toString();
        return switch (type) {
            case "text" -> marked(node);
            case "paragraph" -> text + "\n\n";
            case "heading" -> "#".repeat(node.path("attrs").path("level").asInt()) + " " + text + "\n\n";
            case "hardBreak" -> "  \n";
            case "horizontalRule" -> "---\n\n";
            case "blockquote" -> "> " + text.strip().replace("\n", "\n> ") + "\n\n";
            case "codeBlock" -> "````\n" + codeText(node) + "\n````\n\n";
            case "bulletList", "orderedList" -> listMarkdown(node, "orderedList".equals(type));
            case "table" -> tableMarkdown(node);
            default -> text;
        };
    }

    private static String marked(JsonNode node) {
        String value = node.path("text").asText().replace("\\", "\\\\");
        for (String ch : new String[]{"`", "*", "_", "[", "]", "<", ">", "~", "#"}) value = value.replace(ch, "\\" + ch);
        for (JsonNode mark : node.path("marks")) value = switch (mark.path("type").asText()) {
            case "bold" -> "**" + value + "**";
            case "italic" -> "*" + value + "*";
            case "strike" -> "~~" + value + "~~";
            case "link" -> "[" + value + "](" + mark.path("attrs").path("href").asText().replace("(", "%28").replace(")", "%29") + ")";
            default -> value;
        };
        return value;
    }

    private static String codeText(JsonNode node) {
        StringBuilder text = new StringBuilder();
        for (JsonNode child : node.path("content")) text.append(child.path("text").asText());
        return text.toString();
    }

    private static String listMarkdown(JsonNode node, boolean ordered) {
        StringBuilder text = new StringBuilder();
        int index = node.path("attrs").path("start").asInt(1);
        for (JsonNode child : node.path("content")) {
            text.append(ordered ? index++ + ". " : "- ").append(markdown(child).strip().replace("\n", "\n    ")).append("\n");
        }
        return text + "\n";
    }

    private static String tableMarkdown(JsonNode node) {
        StringBuilder result = new StringBuilder();
        boolean first = true;
        for (JsonNode row : node.path("content")) {
            result.append('|');
            for (JsonNode cell : row.path("content")) result.append(' ').append(markdown(cell).strip().replace("|", "\\|").replace("\n", " ")).append(" |");
            result.append('\n');
            if (first) { result.append("| --- ".repeat(row.path("content").size())).append("|\n"); first = false; }
        }
        return result + "\n";
    }

    private static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static IllegalArgumentException invalid(String code) { return new IllegalArgumentException(code); }
}
