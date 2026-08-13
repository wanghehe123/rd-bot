package com.wish.rd.bootstrap.openviking;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeDocumentMapper;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeExternalIndexBindingMapper;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeVectorMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * 评测用只读 MyBatis-Plus 会话。不启动 Spring，避免投影 Worker 抢写同一套账本。
 */
final class ShadowRetrievalEvaluationPersistence {

    private ShadowRetrievalEvaluationPersistence() {
    }

    static DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(System.getProperty(
                "rd.openviking.eval.jdbc-url",
                "jdbc:postgresql://127.0.0.1:5432/ragent?client_encoding=UTF8"));
        dataSource.setUsername(System.getProperty("rd.openviking.eval.jdbc-user", "postgres"));
        dataSource.setPassword(System.getProperty("rd.openviking.eval.jdbc-password", "postgres"));
        return dataSource;
    }

    static SqlSessionFactory sqlSessionFactory(DataSource dataSource) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setCacheEnabled(false);
        configuration.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        globalConfig.setBanner(false);
        GlobalConfig.DbConfig dbConfig = new GlobalConfig.DbConfig();
        dbConfig.setIdType(IdType.INPUT);
        globalConfig.setDbConfig(dbConfig);
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
        configuration.setEnvironment(new Environment("shadow-eval", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(KnowledgeVectorMapper.class);
        configuration.addMapper(KnowledgeDocumentMapper.class);
        configuration.addMapper(KnowledgeExternalIndexBindingMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }
}
