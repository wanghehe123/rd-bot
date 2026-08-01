package com.wish.rd.rag.project.agent.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/** Canonical JSON and stable content-hash helpers for audit manifests. */
public final class AgentManifestCanonicalJson {

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

  private AgentManifestCanonicalJson() {
  }

  public static String canonicalJson(Object value) {
    try {
      JsonNode node = MAPPER.valueToTree(value);
      return MAPPER.writeValueAsString(sortNode(node));
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("manifest value is not serializable", exception);
    }
  }

  public static String contentHash(String content) {
    return sha256Prefix(content == null ? "" : content);
  }

  public static String manifestHash(Object manifest) {
    return sha256Prefix(canonicalJson(manifest));
  }

  private static JsonNode sortNode(JsonNode node) {
    if (node == null || node.isNull()) {
      return MAPPER.nullNode();
    }
    if (node.isObject()) {
      ObjectNode sorted = MAPPER.createObjectNode();
      TreeMap<String, JsonNode> fields = new TreeMap<>();
      Iterator<Map.Entry<String, JsonNode>> iterator = node.fields();
      while (iterator.hasNext()) {
        Map.Entry<String, JsonNode> entry = iterator.next();
        fields.put(entry.getKey(), sortNode(entry.getValue()));
      }
      fields.forEach(sorted::set);
      return sorted;
    }
    if (node.isArray()) {
      ArrayNode array = MAPPER.createArrayNode();
      node.forEach(child -> array.add(sortNode(child)));
      return array;
    }
    return node;
  }

  private static String sha256Prefix(String content) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(content.getBytes(StandardCharsets.UTF_8));
      return "sha256:" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
