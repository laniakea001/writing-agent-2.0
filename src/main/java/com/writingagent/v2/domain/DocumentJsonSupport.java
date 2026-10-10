package com.writingagent.v2.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.TreeSet;

/** Canonical payloads retain array order; never use Map.toString for request identity. */
public final class DocumentJsonSupport {
    public static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private DocumentJsonSupport() {}
    public static JsonNode parse(String s) {
        try { return JSON.readTree(s); } catch(Exception e) { throw new IllegalArgumentException("PERSISTED_JSON_INVALID",e); }
    }
    public static String write(Object value) {
        try { return JSON.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("JSON_SERIALIZATION_FAILED",e); }
    }
    public static JsonNode canonical(JsonNode n) {
        if(n.isObject()) { ObjectNode out=JSON.createObjectNode(); var keys=new TreeSet<String>();n.fieldNames().forEachRemaining(keys::add);keys.forEach(k->out.set(k,canonical(n.get(k))));return out; }
        if(n.isArray()) { ArrayNode out=JSON.createArrayNode();n.forEach(v->out.add(canonical(v)));return out; }
        return n.deepCopy();
    }
}
