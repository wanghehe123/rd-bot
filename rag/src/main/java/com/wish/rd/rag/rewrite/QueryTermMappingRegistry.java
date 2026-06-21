package com.wish.rd.rag.rewrite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 查询术语映射注册表：内存中管理"源术语→目标术语"的映射 CRUD。
 *
 * <p>这是 /mappings 系列接口的后端存储，也是 /rag/v3/chat 改写逻辑的数据源。
 * 所有写操作加 synchronized 保证并发安全；{@link #rewriteService()} 每次返回基于当前快照的新实例。
 */
public final class QueryTermMappingRegistry {

    /** 自增 ID 序列，作为映射主键。 */
    private final AtomicLong sequence = new AtomicLong(0);
    /** 映射存储，LinkedHashMap 保持插入顺序以便稳定分页。 */
    private final LinkedHashMap<String, ManagedQueryTermMapping> mappings = new LinkedHashMap<>();

    /** 空注册表工厂方法。 */
    public static QueryTermMappingRegistry inMemory() {
        return new QueryTermMappingRegistry();
    }

    /**
     * 内置默认映射的工厂方法：预置支付域两条映射（下单→接口、金额→字段），
     * 让 /rag/v3/chat 在零配置下也能演示改写效果。
     */
    public static QueryTermMappingRegistry withDefaults() {
        QueryTermMappingRegistry registry = new QueryTermMappingRegistry();
        registry.create(new QueryTermMappingCommand("下单", "POST /api/orders", 10, true, "default payment api"));
        registry.create(new QueryTermMappingCommand("金额", "orders.amount", 9, true, "default payment amount field"));
        return registry;
    }

    /**
     * 新建映射：校验后分配自增 ID 并存入。
     *
     * @return 含 ID 的受管映射对象
     */
    public synchronized ManagedQueryTermMapping create(QueryTermMappingCommand command) {
        validate(command);
        String id = Long.toString(sequence.incrementAndGet());
        ManagedQueryTermMapping mapping = new ManagedQueryTermMapping(
                id,
                command.sourceTerm(),
                command.targetTerm(),
                command.priority(),
                command.enabled(),
                command.remark()
        );
        mappings.put(id, mapping);
        return mapping;
    }

    /**
     * 更新映射：要求 ID 存在且新内容合法，整体替换。
     */
    public synchronized ManagedQueryTermMapping update(String id, QueryTermMappingCommand command) {
        require(id);
        validate(command);
        ManagedQueryTermMapping mapping = new ManagedQueryTermMapping(
                id,
                command.sourceTerm(),
                command.targetTerm(),
                command.priority(),
                command.enabled(),
                command.remark()
        );
        mappings.put(id, mapping);
        return mapping;
    }

    /** 按 ID 查询单条映射，不存在抛异常。 */
    public synchronized ManagedQueryTermMapping get(String id) {
        return require(id);
    }

    /** 返回全部映射的不可变快照。 */
    public synchronized List<ManagedQueryTermMapping> list() {
        return List.copyOf(mappings.values());
    }

    /** 删除映射，不存在抛异常。 */
    public synchronized void delete(String id) {
        require(id);
        mappings.remove(id);
    }

    /**
     * 基于当前映射快照构建一个 {@link RuleBasedQueryRewriteService}。
     *
     * <p>改写服务的排序与过滤逻辑在构造时固化，因此每次调用都基于最新快照新建实例，
     * 保证管理员对映射的增删改能即时生效。
     */
    public synchronized QueryRewriteService rewriteService() {
        List<QueryTermMapping> snapshot = new ArrayList<>();
        for (ManagedQueryTermMapping mapping : mappings.values()) {
            snapshot.add(mapping.toRewriteMapping());
        }
        return new RuleBasedQueryRewriteService(snapshot);
    }

    /** 校验映射存在，不存在抛 NoSuchElementException。 */
    private ManagedQueryTermMapping require(String id) {
        ManagedQueryTermMapping mapping = mappings.get(id);
        if (mapping == null) {
            throw new NoSuchElementException("mapping not found: " + id);
        }
        return mapping;
    }

    /** 校验映射命令：非空且源/目标术语均非空白。 */
    private void validate(QueryTermMappingCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("mapping command must not be null");
        }
        if (command.sourceTerm().isBlank()) {
            throw new IllegalArgumentException("sourceTerm must not be blank");
        }
        if (command.targetTerm().isBlank()) {
            throw new IllegalArgumentException("targetTerm must not be blank");
        }
    }
}
