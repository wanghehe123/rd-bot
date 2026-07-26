package com.wish.rd.bootstrap.executor;

import com.wish.rd.exec.repair.pi.impl.FileSystemPiResourceManifestMaterializer;
import com.wish.rd.exec.repair.pi.PiResourceManifestMaterializerPort;
import com.wish.rd.exec.repair.pi.PiVerifiedResourceSetStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/** Wires H1 only when an operator explicitly enables the publisher-backed cache. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rd.executor.pi.resources", name = "enabled", havingValue = "true")
@ConditionalOnBean(PiVerifiedResourceSetStore.class)
public class PiAgentResourceConfiguration {

    @Bean
    @ConditionalOnMissingBean(PiResourceManifestMaterializerPort.class)
    public PiResourceManifestMaterializerPort piResourceManifestMaterializer(
            PiAgentResourceProperties properties,
            ObjectProvider<PiVerifiedResourceSetStore> resourceSetStoreProvider
    ) {
        String root = properties.getApprovedCacheRoot();
        if (root == null || root.isBlank()) {
            throw new IllegalStateException(
                    "Pi H1 resources are enabled but rd.executor.pi.resources.approved-cache-root is blank"
            );
        }
        PiVerifiedResourceSetStore store = resourceSetStoreProvider.getIfAvailable(PiVerifiedResourceSetStore::empty);
        return new FileSystemPiResourceManifestMaterializer(Path.of(root), store);
    }
}
