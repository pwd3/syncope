/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.syncope.core.persistence.jpa;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import javax.sql.DataSource;
import org.apache.syncope.core.persistence.jpa.spring.CommonEntityManagerFactoryConf;
import org.apache.syncope.core.persistence.jpa.spring.DomainEntityManagerFactoryBean;
import org.apache.syncope.core.spring.ResourceWithFallbackLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jndi.JndiObjectFactoryBean;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.vendor.OpenJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

@PropertySource("classpath:domains/Master.properties")
@PropertySource(value = "file:${conf.directory}/domains/Master.properties", ignoreResourceNotFound = true)
@Configuration
public class MasterDomain implements EnvironmentAware {

    private Environment env;

    @Autowired
    private CommonEntityManagerFactoryConf commonEMFConf;

    @Autowired
    private ConfigurableApplicationContext ctx;

    @Value("${Master.driverClassName}")
    private String driverClassName;

    @Value("${Master.url}")
    private String url;

    @Value("${Master.schema}")
    private String schema;

    @Value("${Master.username}")
    private String username;

    @Value("${Master.password}")
    private String password;

    @Value("${Master.pool.transactionIsolation:TRANSACTION_READ_COMMITTED}")
    private String transactionIsolation;

    @Value("${Master.pool.maxActive:10}")
    private int maximumPoolSize;

    @Value("${Master.pool.minIdle:2}")
    private int minimumIdle;

    @Value("classpath:/audit/${Master.audit.sql}")
    private Resource auditSql;

    @Value("${Master.orm}")
    private String orm;

    @Value("${Master.databasePlatform}")
    private String databasePlatform;

    @Value("${content.directory}")
    private String contentDirectory;

    @Override
    public void setEnvironment(final Environment env) {
        this.env = env;
    }

    public DataSource localMasterDataSource() {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setDriverClassName(driverClassName);
        hikariConfig.setJdbcUrl(url);
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        hikariConfig.setTransactionIsolation(transactionIsolation);
        hikariConfig.setMaximumPoolSize(maximumPoolSize);
        hikariConfig.setMinimumIdle(minimumIdle);
        return new HikariDataSource(hikariConfig);
    }

    @Bean("MasterDataSource")
    public JndiObjectFactoryBean masterDataSource() {
        JndiObjectFactoryBean masterDataSource = new JndiObjectFactoryBean();
        masterDataSource.setJndiName("java:comp/env/jdbc/syncopeMasterDataSource");
        masterDataSource.setDefaultObject(localMasterDataSource());
        return masterDataSource;
    }

    @Bean
    public ResourceDatabasePopulator masterResourceDatabasePopulator() {
        ResourceDatabasePopulator databasePopulator = new ResourceDatabasePopulator();
        databasePopulator.setContinueOnError(true);
        databasePopulator.setIgnoreFailedDrops(true);
        databasePopulator.setSqlScriptEncoding("UTF-8");
        databasePopulator.addScript(auditSql);
        return databasePopulator;
    }

    @Bean
    public DataSourceInitializer masterDataSourceInitializer() {
        DataSourceInitializer dataSourceInitializer = new DataSourceInitializer();
        dataSourceInitializer.setDataSource((DataSource) masterDataSource().getObject());
        dataSourceInitializer.setEnabled(true);
        dataSourceInitializer.setDatabasePopulator(masterResourceDatabasePopulator());
        return dataSourceInitializer;
    }

    @Bean("MasterEntityManagerFactory")
    @DependsOn("commonEMFConf")
    public DomainEntityManagerFactoryBean masterEntityManagerFactory() {
        OpenJpaVendorAdapter vendorAdapter = new OpenJpaVendorAdapter();
        vendorAdapter.setShowSql(false);
        vendorAdapter.setGenerateDdl(true);
        vendorAdapter.setDatabasePlatform(databasePlatform);
        DomainEntityManagerFactoryBean masterEntityManagerFactory = new DomainEntityManagerFactoryBean();
        masterEntityManagerFactory.setMappingResources(orm);
        masterEntityManagerFactory.setPersistenceUnitName("Master");
        masterEntityManagerFactory.setDataSource((DataSource) masterDataSource().getObject());
        masterEntityManagerFactory.setJpaVendorAdapter(vendorAdapter);
        masterEntityManagerFactory.setCommonEntityManagerFactoryConf(commonEMFConf);

        if (env.containsProperty("openjpaMetaDataFactory")) {
            masterEntityManagerFactory.setJpaPropertyMap(Map.of(
                    "openjpa.MetaDataFactory",
                    env.getProperty("openjpaMetaDataFactory").replace("##orm##", orm)));
        }

        return masterEntityManagerFactory;
    }

    @Bean("MasterTransactionManager")
    @Qualifier("Master")
    public PlatformTransactionManager transactionManager() {
        return new JpaTransactionManager(masterEntityManagerFactory().getObject());
    }

    @Bean("MasterProperties")
    public ResourceWithFallbackLoader masterProperties() {
        ResourceWithFallbackLoader masterProperties = new ResourceWithFallbackLoader();
        masterProperties.setPrimary("file:" + contentDirectory + "/domains/Master.properties");
        masterProperties.setFallback("classpath:domains/Master.properties");
        return masterProperties;
    }

    @Bean("MasterContentXML")
    public InputStream masterContentXML() throws IOException {
        ResourceWithFallbackLoader masterContentXML =
                ctx.getBeanFactory().createBean(ResourceWithFallbackLoader.class);
        masterContentXML.setPrimary("file:" + contentDirectory + "/domains/MasterContent.xml");
        masterContentXML.setFallback("classpath:domains/MasterContent.xml");
        return masterContentXML.getResource().getInputStream();
    }

    @Bean("MasterKeymasterConfParamsJSON")
    public InputStream masterKeymasterConfParamsJSON() throws IOException {
        ResourceWithFallbackLoader keymasterConfParamsJSON =
                ctx.getBeanFactory().createBean(ResourceWithFallbackLoader.class);
        keymasterConfParamsJSON.setPrimary("file:" + contentDirectory + "/domains/MasterKeymasterConfParams.json");
        keymasterConfParamsJSON.setFallback("classpath:domains/MasterKeymasterConfParams.json");
        return keymasterConfParamsJSON.getResource().getInputStream();
    }

    @Bean("MasterDatabaseSchema")
    public String masterDatabaseSchema() {
        return schema;
    }
}
