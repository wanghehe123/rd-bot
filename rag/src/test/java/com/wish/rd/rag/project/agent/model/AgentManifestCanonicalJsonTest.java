package com.wish.rd.rag.project.agent.model;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentManifestCanonicalJsonTest {

  @Test
  void shouldProduceStableCanonicalHashRegardlessOfMapInsertionOrder() {
    Map<String, Object> left = new LinkedHashMap<>();
    left.put("z", 1);
    left.put("a", Map.of("nested", List.of("b", "a")));
    left.put("m", "value");

    Map<String, Object> right = new TreeMap<>();
    right.put("a", Map.of("nested", List.of("b", "a")));
    right.put("m", "value");
    right.put("z", 1);

    assertEquals(
        AgentManifestCanonicalJson.manifestHash(left),
        AgentManifestCanonicalJson.manifestHash(right)
    );
    assertEquals(
        AgentManifestCanonicalJson.canonicalJson(left),
        AgentManifestCanonicalJson.canonicalJson(right)
    );
  }

  @Test
  void shouldHashContentIndependentlyOfWrapperType() {
    String content = "{\"attemptNo\":1,\"mode\":\"LEGACY_OBSERVE_ONLY\"}";
    assertEquals(
        AgentManifestCanonicalJson.contentHash(content),
        AgentManifestCanonicalJson.manifestHash(Map.of(
            "attemptNo", 1,
            "mode", "LEGACY_OBSERVE_ONLY"
        ))
    );
  }
}
