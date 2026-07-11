package com.wish.rd.engine.admin.rewrite;

import com.wish.rd.rag.rewrite.model.ManagedQueryTermMapping;
import com.wish.rd.rag.rewrite.model.QueryRewritePreview;
import com.wish.rd.rag.rewrite.model.QueryTermMappingCommand;
import com.wish.rd.rag.rewrite.model.QueryTermMappingScope;
import com.wish.rd.rag.rewrite.QueryTermMappingRegistry;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 查询术语映射管理业务编排引擎。
 *
 * <p>委托 {@link QueryTermMappingRegistry} 完成映射的增删改查；当未注入 registry 时
 * 回退到内存实现。供 {@code QueryTermMappingController} 调用。
 */
@Service
public final class QueryTermMappingAdminEngine {

    private final QueryTermMappingRegistry registry;

    public QueryTermMappingAdminEngine(QueryTermMappingRegistry registry) {
        this.registry = registry == null ? QueryTermMappingRegistry.inMemory() : registry;
    }

    public ManagedQueryTermMapping create(QueryTermMappingCommand command) {
        return registry.create(command);
    }

    public ManagedQueryTermMapping update(String id, QueryTermMappingCommand command) {
        return registry.update(id, command);
    }

    public ManagedQueryTermMapping get(String id) {
        return registry.get(id);
    }

    public List<ManagedQueryTermMapping> list() {
        return registry.list();
    }

    public List<ManagedQueryTermMapping> list(
            String projectId,
            QueryTermMappingScope scope,
            Boolean enabled,
            String keyword
    ) {
        return registry.list(projectId, scope, enabled, keyword);
    }

    public QueryRewritePreview preview(String projectId, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        return registry.preview(projectId, text);
    }

    public void delete(String id) {
        registry.delete(id);
    }
}
