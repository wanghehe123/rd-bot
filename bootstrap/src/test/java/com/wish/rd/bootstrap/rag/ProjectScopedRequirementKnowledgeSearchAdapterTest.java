package com.wish.rd.bootstrap.rag;

import com.wish.rd.bootstrap.rag.impl.ProjectScopedRequirementKnowledgeSearchAdapter;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.WorkflowExperienceEntry;
import com.wish.rd.engine.agent.model.WorkflowExperienceType;
import com.wish.rd.engine.agent.WorkflowExperienceStore;
import com.wish.rd.engine.retrieval.model.RetrievalScope;
import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.rag.project.RdProjectService;
import com.wish.rd.rag.project.model.RdProject;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.vector.VectorStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectScopedRequirementKnowledgeSearchAdapterTest {

    @Test
    void emptyProjectScopeNeverFallsBackToGlobalVectorStoreSearch() {
        VectorStore vectorStore = mock(VectorStore.class);
        RdProjectService projectService = mock(RdProjectService.class);
        ProjectScopedRequirementKnowledgeSearchAdapter adapter =
                new ProjectScopedRequirementKnowledgeSearchAdapter(vectorStore, projectService);

        var result = adapter.search(task(), AgentRole.CODING_AGENT, "product save",
                new RetrievalScope(List.of(), "example/waimai", true, "missing binding"), 8);

        assertTrue(result.candidates().isEmpty());
        assertTrue(result.channels().getFirst().failed());
        verify(vectorStore, never()).vectorSearch(anyString(), anyCollection(), anyInt());
        verify(vectorStore, never()).keywordSearch(anyString(), anyCollection(), anyInt());
    }

    @Test
    void resolvesProjectKnowledgeBaseAndEmitsAuditableCodeEvidence() {
        VectorStore vectorStore = mock(VectorStore.class);
        RdProjectService projectService = mock(RdProjectService.class);
        when(projectService.getEnabled("project-1")).thenReturn(new RdProject(
                "project-1", "waimai", "Waimai", "", "https://github.com/example/waimai",
                "example", "waimai", "main", true, false, 1L, 1L, "kb-waimai"
        ));
        RetrievedChunk code = new RetrievedChunk(
                "chunk-1", "ProductController.save persists product", "kb-waimai", "code-snippet",
                "ProductController.java", 0.8d, Map.of("path", "src/ProductController.java")
        );
        when(vectorStore.vectorSearch(anyString(), anyCollection(), anyInt())).thenReturn(List.of(code));
        when(vectorStore.keywordSearch(anyString(), anyCollection(), anyInt())).thenReturn(List.of(code));
        ProjectScopedRequirementKnowledgeSearchAdapter adapter =
                new ProjectScopedRequirementKnowledgeSearchAdapter(vectorStore, projectService);

        RetrievalScope scope = adapter.resolveScope(task());
        var result = adapter.search(task(), AgentRole.CODING_AGENT, "product save", scope, 8);

        assertEquals(List.of("kb-waimai"), scope.knowledgeBaseIds());
        assertEquals(1, result.candidates().size());
        assertEquals("CODE_SYMBOL", result.candidates().getFirst().requiredEvidenceType());
        assertTrue(result.candidates().getFirst().sourceUri().contains("ProductController.java"));
        assertTrue(result.candidates().getFirst().contentHash().startsWith("sha256:"));
        assertEquals(2, result.channels().size());
    }

    @Test
    void addsOnlyRoleCompatibleSameProjectExperienceAsBoundedHintChannel() {
        VectorStore vectorStore = mock(VectorStore.class);
        RdProjectService projectService = mock(RdProjectService.class);
        WorkflowExperienceStore experienceStore = mock(WorkflowExperienceStore.class);
        RagStreamTaskRegistry taskRegistry = mock(RagStreamTaskRegistry.class);
        RetrievedChunk code = new RetrievedChunk(
                "chunk-1", "ProductController.save persists product", "kb-waimai", "code-snippet",
                "ProductController.java", 0.8d, Map.of("path", "src/ProductController.java")
        );
        when(vectorStore.vectorSearch(anyString(), anyCollection(), anyInt())).thenReturn(List.of(code));
        when(vectorStore.keywordSearch(anyString(), anyCollection(), anyInt())).thenReturn(List.of(code));
        WorkflowExperienceEntry sameProject = experience(
                "experience-same", "source-same", AgentRole.CODING_AGENT, WorkflowExperienceType.CODE_CHANGE
        );
        WorkflowExperienceEntry foreignProject = experience(
                "experience-foreign", "source-foreign", AgentRole.CODING_AGENT, WorkflowExperienceType.CODE_CHANGE
        );
        WorkflowExperienceEntry wrongRole = experience(
                "experience-qa", "source-same", AgentRole.QA_AGENT, WorkflowExperienceType.QA_REPORT
        );
        when(experienceStore.searchReusableScoped(
                anyString(), anyString(), anyString(), anyString(), any(AgentRole.class), anyInt()))
                .thenReturn(List.of(sameProject, foreignProject, wrongRole));
        when(taskRegistry.getRequirementTask("source-same")).thenReturn(sourceTask(
                "source-same", "project-1", "https://github.com/example/waimai"
        ));
        when(taskRegistry.getRequirementTask("source-foreign")).thenReturn(sourceTask(
                "source-foreign", "project-2", "https://github.com/example/other"
        ));
        ProjectScopedRequirementKnowledgeSearchAdapter adapter =
                new ProjectScopedRequirementKnowledgeSearchAdapter(
                        vectorStore, projectService, experienceStore, taskRegistry
                );

        var result = adapter.search(
                task(), AgentRole.CODING_AGENT, "product save",
                new RetrievalScope(List.of("kb-waimai"), "example/waimai", true, ""), 8
        );

        assertEquals(2, result.candidates().size());
        assertTrue(result.candidates().stream().anyMatch(item ->
                item.sourceUri().equals("rd-experience://experience-same")
                        && item.requiredEvidenceType().equals("EXPERIENCE_HINT")
        ));
        assertFalse(result.candidates().stream().anyMatch(item ->
                item.sourceUri().contains("experience-foreign") || item.sourceUri().contains("experience-qa")
        ));
        assertTrue(result.channels().stream().anyMatch(item ->
                item.channel().equals("WorkflowExperience") && item.candidateCount() == 1
        ));
    }

    private RdRequirementTask task() {
        return new RdRequirementTask(
                "task-1", "REQUIREMENT", "ADMIN", "", "", "P1", null,
                "Product management", "project-1", "waimai", "Waimai",
                "https://github.com/example/waimai", "example", "waimai", "main", "",
                "admin saves product", "[\"API returns 200\"]", "", "{}", "", "",
                1L, 1L, false
        );
    }

    private RdRequirementTask sourceTask(String taskId, String projectId, String repositoryUrl) {
        return new RdRequirementTask(
                taskId, "REQUIREMENT", "ADMIN", "", "", "P1", null,
                "Source task", projectId, projectId, projectId,
                repositoryUrl, "example", repositoryUrl.endsWith("/other") ? "other" : "waimai",
                "main", "", "source", "[]", "", "{}", "", "",
                1L, 1L, false
        );
    }

    private WorkflowExperienceEntry experience(
            String experienceId,
            String taskId,
            AgentRole role,
            WorkflowExperienceType type
    ) {
        return new WorkflowExperienceEntry(
                experienceId, taskId, "stage-" + taskId, "artifact-" + taskId,
                role, type, "Product save", "ProductController save lesson",
                "{\"summary\":\"product save\"}", true, false, true, 1L
        );
    }
}
