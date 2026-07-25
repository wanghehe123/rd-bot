package com.wish.rd.bootstrap.executor.impl;

import com.wish.rd.exec.repair.execution.model.RepairInputAttachment;
import com.wish.rd.rag.ingestion.impl.InMemoryObjectStorageService;
import com.wish.rd.rag.ingestion.model.StoredIngestionFile;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RoleHandoffAttachmentResolverTest {

    @Test
    void shouldMaterializeOnlyThePrivateHandoffForTheCurrentDownstreamRole() throws Exception {
        InMemoryObjectStorageService storage = new InMemoryObjectStorageService();
        byte[] body = "# Plan\n\nUpdate serializer and run tests.\n".getBytes(StandardCharsets.UTF_8);
        StoredIngestionFile stored = storage.upload(
                "rd-role-handoffs",
                new ByteArrayInputStream(body),
                body.length,
                "next.md",
                "text/markdown"
        );
        RoleHandoffProperties properties = new RoleHandoffProperties();
        properties.setMaxTokens(128);
        RoleHandoffAttachmentResolver resolver = new RoleHandoffAttachmentResolver(storage, properties);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        String upstream = """
                {
                  "stages": [
                    {"role":"REQUIREMENT_REVIEWER","handoff":{"targetRole":"SOLUTION_ARCHITECT","artifactUri":"s3://other/ignore.md","sha256":"%s","bytes":%d}},
                    {"role":"SOLUTION_ARCHITECT","handoff":{"sourceRole":"SOLUTION_ARCHITECT","targetRole":"CODING_AGENT","artifactName":"handoff/next.md","artifactUri":"%s","sha256":"%s","bytes":%d,"summary":"Implement this plan"}}
                  ]
                }
                """.formatted(sha256, body.length, stored.url(), sha256, body.length);

        List<RepairInputAttachment> attachments = resolver.resolve("CODING_AGENT", upstream);

        assertEquals(1, attachments.size());
        assertEquals("handoff-solution_architect.md", attachments.getFirst().filename());
        assertEquals("text/markdown", attachments.getFirst().mimeType());
        assertArrayEquals(body, attachments.getFirst().content());
    }

    @Test
    void shouldMaterializeVerifiedCandidatePatchOnlyForLocalQa() throws Exception {
        InMemoryObjectStorageService storage = new InMemoryObjectStorageService();
        byte[] patch = """
                diff --git a/src/order.py b/src/order.py
                index 1111111..2222222 100644
                --- a/src/order.py
                +++ b/src/order.py
                @@ -1 +1 @@
                -return old_order
                +return new_order
                """.getBytes(StandardCharsets.UTF_8);
        StoredIngestionFile stored = storage.upload(
                "rd-role-handoffs",
                new ByteArrayInputStream(patch),
                patch.length,
                "patch.diff",
                "text/x-diff"
        );
        RoleHandoffProperties properties = new RoleHandoffProperties();
        RoleHandoffAttachmentResolver resolver = new RoleHandoffAttachmentResolver(storage, properties);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(patch));
        String upstream = """
                {
                  "stages": [
                    {
                      "role": "CODING_AGENT",
                      "candidatePatch": {
                        "sourceRole": "CODING_AGENT",
                        "targetRole": "QA_AGENT",
                        "artifactName": "patch.diff",
                        "artifactUri": "%s",
                        "sha256": "%s",
                        "bytes": %d
                      }
                    }
                  ]
                }
                """.formatted(stored.url(), sha256, patch.length);

        List<RepairInputAttachment> qaAttachments = resolver.resolve("QA_AGENT", upstream);
        List<RepairInputAttachment> codingAttachments = resolver.resolve("CODING_AGENT", upstream);

        assertEquals(1, qaAttachments.size());
        assertEquals("candidate-patch.diff", qaAttachments.getFirst().filename());
        assertEquals("text/x-diff", qaAttachments.getFirst().mimeType());
        assertArrayEquals(patch, qaAttachments.getFirst().content());
        assertEquals(0, codingAttachments.size());
    }
}
