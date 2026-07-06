package com.wish.rd.rag.intent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import com.wish.rd.rag.intent.model.IntentLevel;
import com.wish.rd.rag.intent.model.IntentNode;
import com.wish.rd.rag.intent.model.IntentNodeCommand;
import com.wish.rd.rag.intent.model.ManagedIntentNode;

@Component
public final class IntentTreeRegistry {

    private final AtomicLong sequence = new AtomicLong(0);
    private final LinkedHashMap<String, ManagedIntentNode> nodes = new LinkedHashMap<>();

    public IntentTreeRegistry() {
        this(true);
    }

    private IntentTreeRegistry(boolean seedDefaults) {
        if (seedDefaults) {
            seedDefaults();
        }
    }

    public static IntentTreeRegistry inMemory() {
        return new IntentTreeRegistry(false);
    }

    public static IntentTreeRegistry withDefaults() {
        return new IntentTreeRegistry(true);
    }

    public synchronized ManagedIntentNode create(IntentNodeCommand command) {
        validateCreate(command);
        if (findByIntentCode(command.intentCode()) != null) {
            throw new IllegalArgumentException("intentCode already exists: " + command.intentCode());
        }
        String id = Long.toString(sequence.incrementAndGet());
        ManagedIntentNode node = fromCommand(id, command, null);
        nodes.put(id, node);
        return node;
    }

    public synchronized ManagedIntentNode update(String id, IntentNodeCommand command) {
        ManagedIntentNode existing = require(id);
        if (command == null) {
            throw new IllegalArgumentException("intent node command must not be null");
        }
        ManagedIntentNode updated = new ManagedIntentNode(
                existing.id(),
                existing.intentCode(),
                command.name() == null ? existing.name() : command.name(),
                command.level() == null ? existing.level() : command.level(),
                command.parentCode() == null ? existing.parentCode() : command.parentCode(),
                command.description() == null || command.description().isBlank() ? existing.description() : command.description(),
                command.kbId() == null ? existing.kbId() : command.kbId(),
                command.examples().isEmpty() ? existing.examples() : command.examples(),
                command.codeRepositoryIds().isEmpty() ? existing.codeRepositoryIds() : command.codeRepositoryIds(),
                command.enabled(),
                command.sortOrder(),
                List.of()
        );
        nodes.put(id, updated);
        return updated;
    }

    public synchronized ManagedIntentNode get(String id) {
        return require(id);
    }

    public synchronized List<ManagedIntentNode> tree() {
        return buildTree(false);
    }

    public synchronized IntentTree intentTree() {
        return new IntentTree(buildTree(true).stream()
                .map(this::toIntentNode)
                .toList());
    }

    public synchronized void delete(String id) {
        ManagedIntentNode node = require(id);
        List<String> toDelete = new ArrayList<>();
        collectDescendantIds(node.intentCode(), toDelete);
        toDelete.add(id);
        for (String deleteId : toDelete) {
            nodes.remove(deleteId);
        }
    }

    public synchronized void batchEnable(List<String> ids) {
        setEnabled(ids, 1);
    }

    public synchronized void batchDisable(List<String> ids) {
        setEnabled(ids, 0);
    }

    public synchronized void batchDelete(List<String> ids) {
        for (String id : safeIds(ids)) {
            delete(id);
        }
    }

    private ManagedIntentNode fromCommand(String id, IntentNodeCommand command, List<ManagedIntentNode> children) {
        return new ManagedIntentNode(
                id,
                command.intentCode(),
                command.name(),
                command.level(),
                command.parentCode(),
                command.description(),
                command.kbId(),
                command.examples(),
                command.codeRepositoryIds(),
                command.enabled(),
                command.sortOrder(),
                children
        );
    }

    private List<ManagedIntentNode> buildTree(boolean enabledOnly) {
        return orderedNodes().stream()
                .filter(node -> node.parentCode() == null)
                .filter(node -> !enabledOnly || enabled(node))
                .map(node -> withChildren(node, enabledOnly))
                .toList();
    }

    private ManagedIntentNode withChildren(ManagedIntentNode node, boolean enabledOnly) {
        List<ManagedIntentNode> children = orderedNodes().stream()
                .filter(candidate -> node.intentCode().equals(candidate.parentCode()))
                .filter(candidate -> !enabledOnly || enabled(candidate))
                .map(candidate -> withChildren(candidate, enabledOnly))
                .toList();
        return new ManagedIntentNode(
                node.id(),
                node.intentCode(),
                node.name(),
                node.level(),
                node.parentCode(),
                node.description(),
                node.kbId(),
                node.examples(),
                node.codeRepositoryIds(),
                node.enabled(),
                node.sortOrder(),
                children
        );
    }

    private IntentNode toIntentNode(ManagedIntentNode node) {
        return IntentNode.builder()
                .id(node.intentCode())
                .name(node.name())
                .description(node.description())
                .level(toLevel(node.level()))
                .systemId(node.intentCode())
                .knowledgeBaseIds(node.kbId() == null ? List.of(node.intentCode()) : List.of(node.kbId()))
                .codeRepositoryIds(node.codeRepositoryIds())
                .examples(node.examples())
                .children(node.children().stream().map(this::toIntentNode).toList())
                .build();
    }

    private List<ManagedIntentNode> orderedNodes() {
        return nodes.values().stream()
                .sorted(Comparator.comparingInt(ManagedIntentNode::sortOrder)
                        .thenComparing(ManagedIntentNode::id))
                .toList();
    }

    private void setEnabled(List<String> ids, int enabled) {
        for (String id : safeIds(ids)) {
            ManagedIntentNode node = require(id);
            nodes.put(id, new ManagedIntentNode(
                    node.id(),
                    node.intentCode(),
                    node.name(),
                    node.level(),
                    node.parentCode(),
                    node.description(),
                    node.kbId(),
                    node.examples(),
                    node.codeRepositoryIds(),
                    enabled,
                    node.sortOrder(),
                    List.of()
            ));
        }
    }

    private List<String> safeIds(List<String> ids) {
        return ids == null ? List.of() : ids.stream().filter(id -> id != null && !id.isBlank()).toList();
    }

    private void collectDescendantIds(String parentCode, List<String> target) {
        for (ManagedIntentNode node : nodes.values()) {
            if (parentCode.equals(node.parentCode())) {
                collectDescendantIds(node.intentCode(), target);
                target.add(node.id());
            }
        }
    }

    private ManagedIntentNode findByIntentCode(String intentCode) {
        return nodes.values().stream()
                .filter(node -> node.intentCode().equals(intentCode))
                .findFirst()
                .orElse(null);
    }

    private ManagedIntentNode require(String id) {
        ManagedIntentNode node = nodes.get(id);
        if (node == null) {
            throw new NoSuchElementException("intent node not found: " + id);
        }
        return node;
    }

    private void validateCreate(IntentNodeCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("intent node command must not be null");
        }
        if (command.intentCode() == null || command.intentCode().isBlank()) {
            throw new IllegalArgumentException("intentCode must not be blank");
        }
        if (command.name() == null || command.name().isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    private boolean enabled(ManagedIntentNode node) {
        return node.enabled() == null || node.enabled() != 0;
    }

    private IntentLevel toLevel(Integer level) {
        return switch (level == null ? 2 : level) {
            case 0 -> IntentLevel.SYSTEM;
            case 1 -> IntentLevel.DOMAIN;
            default -> IntentLevel.CAPABILITY;
        };
    }

    private void seedDefaults() {
        create(new IntentNodeCommand(
                "waimai",
                "外卖平台",
                0,
                null,
                "外卖、waimai、下单、订单、POST /api/orders、创建订单、收货地址、delivery_address、address、customer_name、"
                        + "支付、支付回调、PaymentCallbackService、PENDING_PAYMENT、PAID、订单状态、商家接单",
                "waimai",
                List.of(
                        "外卖下单接口返回 500",
                        "waimai 创建订单失败",
                        "POST /api/orders 创建订单失败",
                        "外卖订单支付成功后状态仍为待支付"
                ),
                List.of("waimai"),
                1,
                -10
        ));
        create(new IntentNodeCommand(
                "payment-system",
                "支付系统",
                0,
                null,
                "支付、下单、订单、金额、orders.amount",
                "payment-system",
                List.of("支付系统下单接口 500", "金额为空"),
                List.of("payment-service"),
                1,
                0
        ));
    }
}
