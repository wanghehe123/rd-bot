package com.wish.rd.bootstrap.persistence.mapper;

import com.wish.rd.bootstrap.persistence.entity.ModelProviderProfileRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** PostgreSQL mapper for provider metadata; no credential value is stored. */
@Mapper
public interface ModelProviderProfileMapper {

    @Insert("""
            INSERT INTO rd_model_provider_profiles (
              provider_id, display_name, protocol, base_url, model_id,
              credential_environment_variable, auth_header, enabled, version
            ) VALUES (
              #{providerId}, #{displayName}, #{protocol}, #{baseUrl}, #{modelId},
              #{credentialEnvironmentVariable}, #{authHeader}, #{enabled}, #{version}
            ) ON CONFLICT (provider_id) DO UPDATE SET
              display_name=EXCLUDED.display_name,
              protocol=EXCLUDED.protocol,
              base_url=EXCLUDED.base_url,
              model_id=EXCLUDED.model_id,
              credential_environment_variable=EXCLUDED.credential_environment_variable,
              auth_header=EXCLUDED.auth_header,
              enabled=EXCLUDED.enabled,
              version=EXCLUDED.version,
              updated_at=now()
            """)
    int upsert(ModelProviderProfileRow row);

    @Select("""
            SELECT provider_id, display_name, protocol, base_url, model_id,
                   credential_environment_variable, auth_header, enabled, version
            FROM rd_model_provider_profiles
            WHERE provider_id=#{providerId}
            """)
    ModelProviderProfileRow find(@Param("providerId") String providerId);

    @Select("""
            SELECT provider_id, display_name, protocol, base_url, model_id,
                   credential_environment_variable, auth_header, enabled, version
            FROM rd_model_provider_profiles
            ORDER BY provider_id
            """)
    List<ModelProviderProfileRow> list();
}
