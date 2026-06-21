package com.wish.rd.engine.admin.intent;

import com.wish.rd.rag.intent.IntentNodeCommand;
import com.wish.rd.rag.intent.IntentTreeRegistry;
import com.wish.rd.rag.intent.ManagedIntentNode;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 意图树管理业务编排引擎。
 *
 * <p>委托 {@link IntentTreeRegistry} 完成意图树的查询、节点增删改与批量启停/删除；
 * 当未注入 registry 时回退到默认实现。供 {@code IntentTreeController} 调用。
 */
@Service
public final class IntentTreeAdminEngine {

    private final IntentTreeRegistry registry;

    public IntentTreeAdminEngine(IntentTreeRegistry registry) {
        this.registry = registry == null ? IntentTreeRegistry.withDefaults() : registry;
    }

    public List<ManagedIntentNode> tree() {
        return registry.tree();
    }

    public ManagedIntentNode create(IntentNodeCommand command) {
        return registry.create(command);
    }

    public ManagedIntentNode update(String id, IntentNodeCommand command) {
        return registry.update(id, command);
    }

    public void delete(String id) {
        registry.delete(id);
    }

    public void batchEnable(List<String> ids) {
        registry.batchEnable(ids);
    }

    public void batchDisable(List<String> ids) {
        registry.batchDisable(ids);
    }

    public void batchDelete(List<String> ids) {
        registry.batchDelete(ids);
    }
}
