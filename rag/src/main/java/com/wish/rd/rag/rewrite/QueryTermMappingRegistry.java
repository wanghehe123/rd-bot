package com.wish.rd.rag.rewrite;

import com.wish.rd.rag.rewrite.impl.InMemoryQueryTermMappingStore;
import com.wish.rd.rag.rewrite.impl.RuleBasedQueryRewriteService;
import com.wish.rd.rag.rewrite.model.ManagedQueryTermMapping;
import com.wish.rd.rag.rewrite.model.QueryRewriteMatch;
import com.wish.rd.rag.rewrite.model.QueryRewritePreview;
import com.wish.rd.rag.rewrite.model.QueryTermMappingCommand;
import com.wish.rd.rag.rewrite.model.QueryTermMappingScope;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Project-aware query rewrite registry.
 *
 * <p>The registry owns validation and rule selection, while the configured store owns persistence.
 * A project can read only its own rules plus global rules; a task without a project can read global
 * rules only.
 */
public final class QueryTermMappingRegistry {

    private static final Comparator<ManagedQueryTermMapping> EFFECTIVE_ORDER = Comparator
            .comparingInt((ManagedQueryTermMapping mapping) -> mapping.scope() == QueryTermMappingScope.PROJECT ? 0 : 1)
            .thenComparing(ManagedQueryTermMapping::priority, Comparator.reverseOrder())
            .thenComparing(mapping -> mapping.sourceTerm().length(), Comparator.reverseOrder())
            .thenComparing(ManagedQueryTermMapping::id);

    private final QueryTermMappingStore store;
    private final Supplier<String> idSupplier;

    public QueryTermMappingRegistry(QueryTermMappingStore store, Supplier<String> idSupplier) {
        this.store = store == null ? new InMemoryQueryTermMappingStore() : store;
        this.idSupplier = idSupplier == null ? sequenceSupplier(this.store) : idSupplier;
    }

    /** Empty in-memory registry for focused unit tests. */
    public static QueryTermMappingRegistry inMemory() {
        InMemoryQueryTermMappingStore store = new InMemoryQueryTermMappingStore();
        return new QueryTermMappingRegistry(store, sequenceSupplier(store));
    }

    /** Compatibility registry containing the two historical global defaults. */
    public static QueryTermMappingRegistry withDefaults() {
        QueryTermMappingRegistry registry = inMemory();
        registry.seedDefaults();
        return registry;
    }

    public ManagedQueryTermMapping create(QueryTermMappingCommand command) {
        validate(command);
        long now = System.currentTimeMillis();
        return store.save(new ManagedQueryTermMapping(
                idSupplier.get(),
                command.projectId(),
                command.scope(),
                command.sourceTerm(),
                command.targetTerm(),
                command.priority(),
                command.enabled(),
                command.remark(),
                now,
                now
        ));
    }

    public ManagedQueryTermMapping update(String id, QueryTermMappingCommand command) {
        ManagedQueryTermMapping existing = require(id);
        validate(command);
        return store.save(new ManagedQueryTermMapping(
                existing.id(),
                command.projectId(),
                command.scope(),
                command.sourceTerm(),
                command.targetTerm(),
                command.priority(),
                command.enabled(),
                command.remark(),
                existing.createdAtEpochMillis(),
                System.currentTimeMillis()
        ));
    }

    public ManagedQueryTermMapping get(String id) {
        return require(id);
    }

    public List<ManagedQueryTermMapping> list() {
        return store.list();
    }

    /**
     * Lists rules visible in the requested management scope. A concrete project sees its project
     * rules and global rules; the all-project view may inspect every stored project rule.
     */
    public List<ManagedQueryTermMapping> list(
            String projectId,
            QueryTermMappingScope scope,
            Boolean enabled,
            String keyword
    ) {
        String safeProjectId = normalizeProjectId(projectId);
        String safeKeyword = keyword == null ? "" : keyword.strip().toLowerCase();
        return store.list().stream()
                .filter(mapping -> isVisible(mapping, safeProjectId))
                .filter(mapping -> scope == null || mapping.scope() == scope)
                .filter(mapping -> enabled == null || mapping.enabled() == enabled)
                .filter(mapping -> safeKeyword.isBlank() || containsKeyword(mapping, safeKeyword))
                .sorted(EFFECTIVE_ORDER)
                .toList();
    }

    public void delete(String id) {
        require(id);
        store.delete(id);
    }

    /** Existing callers retain global-only behavior unless they pass an explicit task project. */
    public QueryRewriteService rewriteService() {
        return rewriteService(null);
    }

    public QueryRewriteService rewriteService(String projectId) {
        return RuleBasedQueryRewriteService.inOrder(effectiveMappings(projectId).stream()
                .map(ManagedQueryTermMapping::toRewriteMapping)
                .toList());
    }

    /** Applies only effective enabled rules and records the rule order that changed the input. */
    public QueryRewritePreview preview(String projectId, String text) {
        String original = text == null ? "" : text;
        String rewritten = original;
        List<QueryRewriteMatch> matches = new java.util.ArrayList<>();
        for (ManagedQueryTermMapping mapping : effectiveMappings(projectId)) {
            String next = QueryTermMappingUtil.applyMapping(rewritten, mapping.sourceTerm(), mapping.targetTerm());
            if (!next.equals(rewritten)) {
                matches.add(new QueryRewriteMatch(
                        mapping.id(), mapping.sourceTerm(), mapping.targetTerm(), mapping.scope()));
                rewritten = next;
            }
        }
        return new QueryRewritePreview(original, rewritten, matches);
    }

    private List<ManagedQueryTermMapping> effectiveMappings(String projectId) {
        String safeProjectId = normalizeProjectId(projectId);
        return store.list().stream()
                .filter(ManagedQueryTermMapping::enabled)
                .filter(mapping -> mapping.scope() == QueryTermMappingScope.GLOBAL
                        || (!safeProjectId.isBlank() && safeProjectId.equals(mapping.projectId())))
                .sorted(EFFECTIVE_ORDER)
                .toList();
    }

    private ManagedQueryTermMapping require(String id) {
        String safeId = id == null ? "" : id.strip();
        return store.findById(safeId)
                .orElseThrow(() -> new NoSuchElementException("mapping not found: " + safeId));
    }

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
        if (command.scope() == QueryTermMappingScope.PROJECT && command.projectId().isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank for PROJECT scope");
        }
        if (command.scope() == QueryTermMappingScope.GLOBAL && !command.projectId().isBlank()) {
            throw new IllegalArgumentException("projectId must be blank for GLOBAL scope");
        }
    }

    private static boolean isVisible(ManagedQueryTermMapping mapping, String projectId) {
        return projectId.isBlank()
                || mapping.scope() == QueryTermMappingScope.GLOBAL
                || projectId.equals(mapping.projectId());
    }

    private static boolean containsKeyword(ManagedQueryTermMapping mapping, String keyword) {
        return mapping.sourceTerm().toLowerCase().contains(keyword)
                || mapping.targetTerm().toLowerCase().contains(keyword)
                || mapping.remark().toLowerCase().contains(keyword);
    }

    private static String normalizeProjectId(String projectId) {
        return projectId == null ? "" : projectId.strip();
    }

    private static Supplier<String> sequenceSupplier(QueryTermMappingStore store) {
        long initial = store.list().stream()
                .map(ManagedQueryTermMapping::id)
                .mapToLong(QueryTermMappingRegistry::numericIdOrZero)
                .max()
                .orElse(0L);
        AtomicLong sequence = new AtomicLong(initial);
        return () -> Long.toString(sequence.incrementAndGet());
    }

    private static long numericIdOrZero(String id) {
        try {
            return Long.parseLong(id);
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private void seedDefaults() {
        create(new QueryTermMappingCommand("下单", "POST /api/orders", 10, true, "default payment api"));
        create(new QueryTermMappingCommand("金额", "orders.amount", 9, true, "default payment amount field"));
    }
}
