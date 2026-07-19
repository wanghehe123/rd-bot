package com.wish.rd.engine.retrieval;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.retrieval.model.RetrievalScope;
import com.wish.rd.engine.retrieval.model.SearchResult;
import com.wish.rd.rag.runtime.model.RdRequirementTask;

/** Project-scoped knowledge search boundary implemented by bootstrap infrastructure adapters. */
public interface RequirementKnowledgeSearchPort {

    RetrievalScope resolveScope(RdRequirementTask task);

    SearchResult search(
            RdRequirementTask task,
            AgentRole role,
            String query,
            RetrievalScope scope,
            int topK
    );

    static RequirementKnowledgeSearchPort noop() {
        return new RequirementKnowledgeSearchPort() {
            @Override
            public RetrievalScope resolveScope(RdRequirementTask task) {
                boolean scopedProject = task != null && !task.projectId().isBlank();
                return new RetrievalScope(
                        java.util.List.of(), repositoryFingerprint(task), scopedProject,
                        scopedProject ? "project has no available knowledge-base binding" : ""
                );
            }

            @Override
            public SearchResult search(
                    RdRequirementTask task,
                    AgentRole role,
                    String query,
                    RetrievalScope scope,
                    int topK
            ) {
                return SearchResult.empty();
            }
        };
    }

    private static String repositoryFingerprint(RdRequirementTask task) {
        if (task == null) {
            return "";
        }
        if (!task.repoOwner().isBlank() && !task.repoName().isBlank()) {
            return (task.repoOwner() + "/" + task.repoName()).toLowerCase(java.util.Locale.ROOT);
        }
        return task.repositoryUrl().toLowerCase(java.util.Locale.ROOT);
    }
}
