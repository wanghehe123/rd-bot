package com.wish.rd.engine.ticket;

import java.util.List;
import java.util.Optional;
import com.wish.rd.engine.ticket.model.RepairQueueDeadLetter;
import com.wish.rd.engine.ticket.model.RepairTicketMessage;

/**
 * 修复队列死信仓储端口，用于人工恢复和运维视图。
 */
public interface RepairQueueDeadLetterRepository {

    /**
     * 保存死信记录。
     *
     * @param message 原始队列消息
     * @param reason  死信原因
     * @return 死信记录
     */
    RepairQueueDeadLetter save(RepairTicketMessage message, String reason);

    /**
     * 查询所有死信记录。
     *
     * @return 死信列表
     */
    List<RepairQueueDeadLetter> list();

    /**
     * 按 ID 查询死信记录。
     *
     * @param id 死信 ID
     * @return 死信记录
     */
    Optional<RepairQueueDeadLetter> findById(String id);

    /**
     * 标记死信已重投。
     *
     * @param id 死信 ID
     * @return 更新后记录
     */
    RepairQueueDeadLetter markReplayed(String id);

    /**
     * 返回 no-op 实现。
     *
     * @return no-op 仓储
     */
    static RepairQueueDeadLetterRepository noop() {
        return Noop.INSTANCE;
    }

    final class Noop implements RepairQueueDeadLetterRepository {
        private static final Noop INSTANCE = new Noop();

        private Noop() {
        }

        @Override
        public RepairQueueDeadLetter save(RepairTicketMessage message, String reason) {
            return new RepairQueueDeadLetter(
                    "",
                    message == null ? "" : message.ticketId(),
                    message == null ? "" : message.traceId(),
                    message == null ? "" : message.source(),
                    message == null ? "" : message.eventId(),
                    message == null ? "" : message.eventType(),
                    message == null ? 0 : message.attempt(),
                    reason,
                    java.util.Map.of(),
                    false,
                    System.currentTimeMillis(),
                    0L
            );
        }

        @Override
        public List<RepairQueueDeadLetter> list() {
            return List.of();
        }

        @Override
        public Optional<RepairQueueDeadLetter> findById(String id) {
            return Optional.empty();
        }

        @Override
        public RepairQueueDeadLetter markReplayed(String id) {
            throw new java.util.NoSuchElementException("dead letter not found: " + id);
        }
    }
}
