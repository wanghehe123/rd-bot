package com.wish.rd.bootstrap.oracle;

import com.wish.rd.bootstrap.executor.DockerExecutorProperties;
import com.wish.rd.bootstrap.executor.PiAgentExecutorProperties;
import com.wish.rd.bootstrap.oracle.impl.BrowserDomAssertionRunner;
import com.wish.rd.bootstrap.oracle.impl.CleanHostVerifierWorkspaceFactory;
import com.wish.rd.bootstrap.oracle.impl.ContainerHostBrowserProbe;
import com.wish.rd.bootstrap.oracle.impl.HttpJsonPathAssertionRunner;
import com.wish.rd.bootstrap.oracle.impl.HttpStatusAssertionRunner;
import com.wish.rd.bootstrap.oracle.impl.JdbcSqlExistsProbe;
import com.wish.rd.bootstrap.oracle.impl.LogPatternAssertionRunner;
import com.wish.rd.bootstrap.oracle.impl.SqlRowExistsAssertionRunner;
import com.wish.rd.bootstrap.oracle.impl.SqlSemanticAssertionRunner;
import com.wish.rd.engine.oracle.AssertionRunnerPort;
import com.wish.rd.engine.oracle.AssertionSpecCompiler;
import com.wish.rd.engine.oracle.HostAssertionBundleStore;
import com.wish.rd.engine.oracle.HostAssertionOracle;
import com.wish.rd.engine.oracle.impl.FileAssertionRunner;
import com.wish.rd.engine.oracle.impl.InMemoryHostAssertionBundleStore;
import com.wish.rd.engine.oracle.model.AssertionType;
import com.wish.rd.exec.repair.docker.RepairWorkspaceFactory;
import com.wish.rd.exec.repair.docker.RepairWorkspaceRepositoryPort;
import com.wish.rd.exec.repair.docker.ContainerRunnerPort;
import com.wish.rd.exec.repair.oracle.HostVerifierWorkspaceFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * Wires Host-owned assertion runners into a shared {@link HostAssertionOracle}.
 * SQL probes require a {@link DataSource}; without one, SQL assertions fail closed at probe time.
 */
@Configuration(proxyBeanMethods = false)
public class HostAssertionOracleConfiguration {

    @Bean
    @ConditionalOnMissingBean(FileAssertionRunner.class)
    FileAssertionRunner fileAssertionRunner() {
        return new FileAssertionRunner();
    }

    @Bean
    @ConditionalOnMissingBean(HttpStatusAssertionRunner.class)
    HttpStatusAssertionRunner httpStatusAssertionRunner() {
        return new HttpStatusAssertionRunner();
    }

    @Bean
    @ConditionalOnMissingBean(HttpJsonPathAssertionRunner.class)
    HttpJsonPathAssertionRunner httpJsonPathAssertionRunner() {
        return new HttpJsonPathAssertionRunner();
    }

    @Bean
    @ConditionalOnMissingBean(SqlRowExistsAssertionRunner.class)
    SqlRowExistsAssertionRunner sqlRowExistsAssertionRunner(ObjectProvider<DataSource> dataSources) {
        DataSource dataSource = dataSources.getIfAvailable();
        if (dataSource == null) {
            return new SqlRowExistsAssertionRunner((sql, timeout) -> {
                throw new IllegalStateException("SQL_ROW_EXISTS requires a DataSource bean");
            });
        }
        return new SqlRowExistsAssertionRunner(new JdbcSqlExistsProbe(dataSource));
    }

    @Bean
    @ConditionalOnMissingBean(SqlSemanticAssertionRunner.class)
    SqlSemanticAssertionRunner sqlSemanticAssertionRunner(ObjectProvider<DataSource> dataSources) {
        DataSource dataSource = dataSources.getIfAvailable();
        if (dataSource == null) {
            return new SqlSemanticAssertionRunner((sql, timeout) -> {
                throw new IllegalStateException("semantic SQL assertions require a DataSource bean");
            });
        }
        return new SqlSemanticAssertionRunner(new JdbcSqlExistsProbe(dataSource));
    }

    @Bean
    @ConditionalOnMissingBean(LogPatternAssertionRunner.class)
    LogPatternAssertionRunner logPatternAssertionRunner() {
        return new LogPatternAssertionRunner();
    }

    @Bean
    @ConditionalOnMissingBean(HostBrowserProbe.class)
    HostBrowserProbe hostBrowserProbe(
            ObjectProvider<ContainerRunnerPort> containerRunnerProvider,
            ObjectProvider<PiAgentExecutorProperties> piPropertiesProvider,
            ObjectProvider<DockerExecutorProperties> dockerPropertiesProvider
    ) {
        ContainerRunnerPort containerRunner = containerRunnerProvider.getIfUnique();
        if (containerRunner == null) {
            return HostBrowserProbe.unavailable();
        }
        PiAgentExecutorProperties piProperties = piPropertiesProvider.getIfAvailable();
        DockerExecutorProperties dockerProperties = dockerPropertiesProvider.getIfAvailable();
        String image = piProperties == null
                ? PiAgentExecutorProperties.DEFAULT_QA_IMAGE
                : piProperties.getQaImage();
        String networkMode = piProperties == null
                ? PiAgentExecutorProperties.DEFAULT_NETWORK_MODE
                : piProperties.getNetworkMode();
        Path outputRoot = dockerProperties == null
                ? Path.of(System.getProperty("java.io.tmpdir"), "rd-bot", "host-browser-probe")
                : dockerProperties.getWorkspaceRoot().resolve("host-browser-probe");
        return new ContainerHostBrowserProbe(
                containerRunner,
                ContainerHostBrowserProbe.Configuration.defaults(image, networkMode, outputRoot)
        );
    }

    @Bean
    @ConditionalOnMissingBean(BrowserDomAssertionRunner.class)
    BrowserDomAssertionRunner browserDomAssertionRunner(HostBrowserProbe hostBrowserProbe) {
        return new BrowserDomAssertionRunner(hostBrowserProbe);
    }

    @Bean
    @ConditionalOnMissingBean(AssertionSpecCompiler.class)
    AssertionSpecCompiler assertionSpecCompiler() {
        return new AssertionSpecCompiler();
    }

    @Bean
    @ConditionalOnMissingBean(HostAssertionBundleStore.class)
    HostAssertionBundleStore hostAssertionBundleStore() {
        // PostgreSQL supplies its own HostAssertionBundleStore in production. Memory is an
        // explicit fallback only when no persisted implementation is present.
        return new InMemoryHostAssertionBundleStore();
    }

    @Bean
    @ConditionalOnMissingBean(HostVerifierWorkspaceFactory.class)
    HostVerifierWorkspaceFactory hostVerifierWorkspaceFactory(
            ObjectProvider<RepairWorkspaceFactory> workspaceFactoryProvider,
            ObjectProvider<RepairWorkspaceRepositoryPort> workspaceRepositoryProvider
    ) {
        RepairWorkspaceFactory workspaceFactory = workspaceFactoryProvider.getIfAvailable();
        RepairWorkspaceRepositoryPort repository = workspaceRepositoryProvider.getIfAvailable();
        if (workspaceFactory == null || repository == null) {
            return HostVerifierWorkspaceFactory.unavailable();
        }
        return new CleanHostVerifierWorkspaceFactory(workspaceFactory, repository);
    }

    @Bean
    @ConditionalOnMissingBean(HostAssertionOracle.class)
    HostAssertionOracle hostAssertionOracle(
            FileAssertionRunner fileAssertionRunner,
            HttpStatusAssertionRunner httpStatusAssertionRunner,
            HttpJsonPathAssertionRunner httpJsonPathAssertionRunner,
            SqlRowExistsAssertionRunner sqlRowExistsAssertionRunner,
            SqlSemanticAssertionRunner sqlSemanticAssertionRunner,
            LogPatternAssertionRunner logPatternAssertionRunner,
            BrowserDomAssertionRunner browserDomAssertionRunner
    ) {
        Map<AssertionType, AssertionRunnerPort> runners = new EnumMap<>(AssertionType.class);
        runners.put(AssertionType.FILE_EXISTS, fileAssertionRunner);
        runners.put(AssertionType.FILE_FORBIDDEN, fileAssertionRunner);
        runners.put(AssertionType.HTTP_STATUS, httpStatusAssertionRunner);
        runners.put(AssertionType.HTTP_JSONPATH, httpJsonPathAssertionRunner);
        runners.put(AssertionType.SQL_ROW_EXISTS, sqlRowExistsAssertionRunner);
        runners.put(AssertionType.SQL_FIELD_VALUE, sqlSemanticAssertionRunner);
        runners.put(AssertionType.SQL_ROW_COUNT, sqlSemanticAssertionRunner);
        runners.put(AssertionType.SQL_INVARIANT, sqlSemanticAssertionRunner);
        runners.put(AssertionType.LOG_MUST_MATCH, logPatternAssertionRunner);
        runners.put(AssertionType.LOG_MUST_NOT_MATCH, logPatternAssertionRunner);
        runners.put(AssertionType.LOG_SECRET_SCAN, logPatternAssertionRunner);
        runners.put(AssertionType.BROWSER_DOM, browserDomAssertionRunner);
        runners.put(AssertionType.BROWSER_ARIA, browserDomAssertionRunner);
        runners.put(AssertionType.BROWSER_VISIBLE, browserDomAssertionRunner);
        runners.put(AssertionType.BROWSER_ROUTE, browserDomAssertionRunner);
        return new HostAssertionOracle(runners);
    }

    @Bean
    @ConditionalOnMissingBean(HostOwnedAssertionGate.class)
    HostOwnedAssertionGate hostOwnedAssertionGate(
            HostAssertionOracle hostAssertionOracle,
            HostAssertionBundleStore hostAssertionBundleStore,
            HostVerifierWorkspaceFactory hostVerifierWorkspaceFactory,
            AssertionSpecCompiler assertionSpecCompiler
    ) {
        return new HostOwnedAssertionGate(
                hostAssertionOracle,
                hostAssertionBundleStore,
                hostVerifierWorkspaceFactory,
                assertionSpecCompiler
        );
    }
}
