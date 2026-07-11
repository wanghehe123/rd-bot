package com.wish.rd.bootstrap.persistence.mapper;

import com.wish.rd.bootstrap.persistence.entity.RdAlertDeliveryRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** PostgreSQL mapper for alert delivery audit. */
@Mapper
public interface RdAlertDeliveryMapper {

    @Insert("""
            INSERT INTO rd_alert_deliveries (
                id, task_id, project_id, alert_type, recipient_type, recipient_id,
                status, provider_message_id, failure_code, failure_message,
                idempotency_key, created_at
            ) VALUES (
                #{id}, #{taskId}, #{projectId}, #{alertType}, #{recipientType}, #{recipientId},
                #{status}, #{providerMessageId}, #{failureCode}, #{failureMessage},
                #{idempotencyKey}, #{createdAt}
            ) ON CONFLICT (idempotency_key) DO NOTHING
            """)
    int insertIfAbsent(RdAlertDeliveryRow row);

    @Update("""
            UPDATE rd_alert_deliveries
            SET status = #{status},
                provider_message_id = #{providerMessageId},
                failure_code = #{failureCode},
                failure_message = #{failureMessage}
            WHERE idempotency_key = #{idempotencyKey}
            """)
    int updateOutcome(RdAlertDeliveryRow row);

    @Select("""
            SELECT * FROM rd_alert_deliveries WHERE idempotency_key = #{idempotencyKey}
            """)
    RdAlertDeliveryRow findByIdempotencyKey(String idempotencyKey);

    @Select("""
            SELECT * FROM rd_alert_deliveries WHERE task_id = #{taskId} ORDER BY created_at, id
            """)
    List<RdAlertDeliveryRow> listByTask(long taskId);
}
