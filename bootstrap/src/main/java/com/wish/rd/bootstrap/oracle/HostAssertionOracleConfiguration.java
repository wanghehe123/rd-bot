package com.wish.rd.bootstrap.oracle;

import com.wish.rd.engine.oracle.AssertionRunnerPort;
import com.wish.rd.engine.oracle.FileAssertionRunner;
import com.wish.rd.engine.oracle.HostAssertionOracle;
import com.wish.rd.engine.oracle.model.AssertionType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
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
    @ConditionalOnMissingBean(HostAssertionOracle.class)
    HostAssertionOracle hostAssertionOracle(
            FileAssertionRunner fileAssertionRunner,
            HttpStatusAssertionRunner httpStatusAssertionRunner,
            HttpJsonPathAssertionRunner httpJsonPathAssertionRunner,
            SqlRowExistsAssertionRunner sqlRowExistsAssertionRunner
    ) {
        Map<AssertionType, AssertionRunnerPort> runners = new EnumMap<>(AssertionType.class);
        runners.put(AssertionType.FILE_EXISTS, fileAssertionRunner);
        runners.put(AssertionType.FILE_FORBIDDEN, fileAssertionRunner);
        runners.put(AssertionType.HTTP_STATUS, httpStatusAssertionRunner);
        runners.put(AssertionType.HTTP_JSONPATH, httpJsonPathAssertionRunner);
        runners.put(AssertionType.SQL_ROW_EXISTS, sqlRowExistsAssertionRunner);
        return new HostAssertionOracle(runners);
    }

    @Bean
    @ConditionalOnMissingBean(HostOwnedAssertionGate.class)
    HostOwnedAssertionGate hostOwnedAssertionGate(HostAssertionOracle hostAssertionOracle) {
        return new HostOwnedAssertionGate(hostAssertionOracle);
    }
}
