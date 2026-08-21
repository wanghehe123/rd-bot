package com.wish.rd.bootstrap.persistence.mapper;

import com.wish.rd.bootstrap.persistence.entity.ModelProviderCredentialRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** PostgreSQL mapper for provider secrets. Never selected by GET admin DTOs. */
@Mapper
public interface ModelProviderCredentialMapper {

    @Insert("""
            INSERT INTO rd_model_provider_credentials (
              provider_id, secret, updated_at
            ) VALUES (
              #{providerId}, #{secret}, #{updatedAt}
            ) ON CONFLICT (provider_id) DO UPDATE SET
              secret=EXCLUDED.secret,
              updated_at=EXCLUDED.updated_at
            """)
    int upsert(ModelProviderCredentialRow row);

    @Select("""
            SELECT provider_id AS providerId,
                   secret,
                   updated_at AS updatedAt
              FROM rd_model_provider_credentials
             WHERE provider_id=#{providerId}
            """)
    ModelProviderCredentialRow find(@Param("providerId") String providerId);

    @Delete("""
            DELETE FROM rd_model_provider_credentials
             WHERE provider_id=#{providerId}
            """)
    int delete(@Param("providerId") String providerId);
}
