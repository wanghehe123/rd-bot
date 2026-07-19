package com.wish.rd.bootstrap.controller.admin.rdtask;

import com.wish.rd.engine.agent.impl.InMemoryAgentStageArtifactStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.rag.ingestion.impl.InMemoryObjectStorageService;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaEvidenceControllerTest {

    @Test
    void shouldListTaskScopedEvidenceWithoutLeakingObjectUriAndStreamOwnedContent() throws Exception {
        RagStreamTaskRegistry registry = RagStreamTaskRegistry.inMemory();
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "浏览器验收", "P1", "https://github.com/acme/web.git", "", "", "main",
                "页面可用", List.of("真实浏览器通过"), false
        ));
        byte[] bytes = "png-evidence".getBytes(StandardCharsets.UTF_8);
        InMemoryObjectStorageService objectStorage = new InMemoryObjectStorageService();
        String objectUri = objectStorage.upload(
                "rd-qa-evidence",
                new ByteArrayInputStream(bytes),
                bytes.length,
                "current.png",
                "image/png"
        ).url();
        InMemoryAgentStageArtifactStore artifactStore = new InMemoryAgentStageArtifactStore();
        artifactStore.save(new AgentStageArtifact(
                "9001",
                "8001",
                task.taskId(),
                AgentRole.QA_AGENT,
                "QA_SCREENSHOT",
                objectUri,
                "current viewport",
                "",
                "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "{\"artifactName\":\"qa-evidence/screenshots/current.png\",\"contentType\":\"image/png\",\"bytes\":\"12\",\"sha256\":\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\"}",
                1000L
        ));
        byte[] logBytes = "GET /api/orders 200".getBytes(StandardCharsets.UTF_8);
        String logUri = objectStorage.upload(
                "rd-qa-evidence",
                new ByteArrayInputStream(logBytes),
                logBytes.length,
                "network.log",
                "text/plain"
        ).url();
        artifactStore.save(new AgentStageArtifact(
                "9002",
                "8001",
                task.taskId(),
                AgentRole.QA_AGENT,
                "QA_NETWORK_LOG",
                logUri,
                "network requests",
                "",
                "sha256:1123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "{\"artifactName\":\"qa-evidence/network/current.log\",\"contentType\":\"text/plain\",\"bytes\":\"19\",\"sha256\":\"1123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\"}",
                1100L
        ));
        QaEvidenceController controller = new QaEvidenceController(registry, artifactStore, objectStorage);

        List<QaEvidenceController.QaEvidenceView> evidence = controller.list(task.taskId());
        ResponseEntity<InputStreamResource> content = controller.content(task.taskId(), "9001");
        ResponseEntity<InputStreamResource> logContent = controller.content(task.taskId(), "9002");

        assertEquals(2, evidence.size());
        QaEvidenceController.QaEvidenceView screenshot = evidence.stream()
                .filter(item -> item.artifactId().equals("9001"))
                .findFirst()
                .orElseThrow();
        QaEvidenceController.QaEvidenceView network = evidence.stream()
                .filter(item -> item.artifactId().equals("9002"))
                .findFirst()
                .orElseThrow();
        assertEquals("qa-evidence/screenshots/current.png", screenshot.name());
        assertTrue(screenshot.previewable());
        assertTrue(network.previewable());
        assertTrue(screenshot.contentUrl().endsWith("/9001/content"));
        assertFalse(evidence.toString().contains("s3://"));
        assertEquals("image/png", content.getHeaders().getContentType().toString());
        assertTrue(logContent.getHeaders().getFirst("Content-Disposition").startsWith("inline"));
        assertEquals("png-evidence", new String(
                content.getBody().getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }
}
