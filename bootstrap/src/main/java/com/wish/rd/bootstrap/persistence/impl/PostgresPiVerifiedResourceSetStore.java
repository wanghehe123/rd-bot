package com.wish.rd.bootstrap.persistence.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wish.rd.bootstrap.executor.PiAgentResourceProperties;
import com.wish.rd.bootstrap.persistence.entity.AgentExtensionSetMemberRow;
import com.wish.rd.bootstrap.persistence.entity.AgentExtensionSetRow;
import com.wish.rd.bootstrap.persistence.entity.AgentExtensionVersionRow;
import com.wish.rd.bootstrap.persistence.mapper.AgentExtensionSetMapper;
import com.wish.rd.bootstrap.persistence.mapper.AgentExtensionSetMemberMapper;
import com.wish.rd.bootstrap.persistence.mapper.AgentExtensionVersionMapper;
import com.wish.rd.exec.repair.pi.PiVerifiedResourceSetStore;
import com.wish.rd.exec.repair.pi.model.VerifiedPiResource;
import com.wish.rd.exec.repair.pi.model.VerifiedPiResourceSet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Resolves only enabled, verified extension sets from the control plane.
 * The cache path is derived from a host-configured root and the verified
 * artifact digest; it is never accepted from a request or database row.
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
@ConditionalOnProperty(name = "rd.executor.pi.resources.enabled", havingValue = "true")
public final class PostgresPiVerifiedResourceSetStore implements PiVerifiedResourceSetStore {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private final AgentExtensionSetMapper setMapper;
    private final AgentExtensionSetMemberMapper memberMapper;
    private final AgentExtensionVersionMapper versionMapper;
    private final Path approvedCacheRoot;

    public PostgresPiVerifiedResourceSetStore(
            AgentExtensionSetMapper setMapper,
            AgentExtensionSetMemberMapper memberMapper,
            AgentExtensionVersionMapper versionMapper,
            PiAgentResourceProperties properties
    ) {
        this.setMapper = setMapper;
        this.memberMapper = memberMapper;
        this.versionMapper = versionMapper;
        String root = properties.getApprovedCacheRoot();
        if (root == null || root.isBlank()) {
            throw new IllegalStateException(
                    "Pi H1 resources are enabled but approved cache root is blank");
        }
        this.approvedCacheRoot = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public Optional<VerifiedPiResourceSet> find(String setId, long version) {
        if (setId == null || setId.isBlank() || version <= 0L) {
            return Optional.empty();
        }
        AgentExtensionSetRow set = setMapper.selectOne(new QueryWrapper<AgentExtensionSetRow>()
                .eq("extension_set_id", setId.strip())
                .eq("version", version)
                .eq("enabled", true));
        if (set == null) {
            return Optional.empty();
        }
        List<AgentExtensionSetMemberRow> members = memberMapper.selectList(
                new QueryWrapper<AgentExtensionSetMemberRow>()
                        .eq("extension_set_id", set.extensionSetId)
                        .eq("set_version", set.version)
                        .eq("enabled", true)
                        .orderByAsc("extension_id"));
        List<VerifiedPiResource> resources = new ArrayList<>(members.size());
        for (AgentExtensionSetMemberRow member : members) {
            VerifiedPiResource resource = verifiedResource(member);
            if (resource == null) {
                return Optional.empty();
            }
            resources.add(resource);
        }
        return Optional.of(new VerifiedPiResourceSet(set.extensionSetId, set.version, resources));
    }

    private VerifiedPiResource verifiedResource(AgentExtensionSetMemberRow member) {
        AgentExtensionVersionRow version = versionMapper.selectOne(
                new QueryWrapper<AgentExtensionVersionRow>()
                        .eq("extension_id", member.extensionId)
                        .eq("version", member.extensionVersion)
                        .eq("status", "VERIFIED"));
        if (version == null) {
            return null;
        }
        String digest = normalizeDigest(version.artifactSha256);
        if (!SHA256.matcher(digest).matches()) {
            return null;
        }
        Path cachePath = approvedCacheRoot.resolve(digest).normalize();
        if (!cachePath.startsWith(approvedCacheRoot) || cachePath.equals(approvedCacheRoot)) {
            return null;
        }
        return new VerifiedPiResource(member.extensionId, member.extensionVersion, cachePath, digest);
    }

    private static String normalizeDigest(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return normalized.startsWith("sha256:") ? normalized.substring("sha256:".length()) : normalized;
    }
}
