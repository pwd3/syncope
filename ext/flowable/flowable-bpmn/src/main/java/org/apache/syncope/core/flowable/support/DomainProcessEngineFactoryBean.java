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
package org.apache.syncope.core.flowable.support;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.syncope.core.persistence.api.DomainHolder;
import org.apache.syncope.core.persistence.api.SyncopeCoreLoader;
import org.flowable.engine.ProcessEngine;
import org.flowable.common.engine.impl.cfg.SpringBeanFactoryProxyMap;
import org.flowable.common.engine.impl.interceptor.EngineConfigurationConstants;
import org.flowable.engine.impl.util.EngineServiceUtil;
import org.flowable.idm.spring.SpringIdmEngineConfiguration;
import org.flowable.spring.SpringExpressionManager;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring factory for {@link DomainProcessEngine} which takes the provided {@link SpringProcessEngineConfiguration} as
 * template for each of the configured Syncope domains.
 */
@Component
public class DomainProcessEngineFactoryBean
        implements FactoryBean<DomainProcessEngine>, DisposableBean, ApplicationContextAware, SyncopeCoreLoader {

    private static final Logger LOG = LoggerFactory.getLogger(DomainProcessEngineFactoryBean.class);

    private ApplicationContext ctx;

    private DomainProcessEngine engine;

    @Override
    public int getOrder() {
        return 300;
    }

    @Override
    public void setApplicationContext(final ApplicationContext ctx) throws BeansException {
        this.ctx = ctx;
    }

    private ProcessEngine build(final String domain, final DataSource datasource) {
        PlatformTransactionManager transactionManager = ctx.getBean(
                domain + "TransactionManager", PlatformTransactionManager.class);
        Object entityManagerFactory = ctx.getBean(domain + "EntityManagerFactory");

        DomainProcessEngineConfiguration conf = ctx.getBean(DomainProcessEngineConfiguration.class);
        conf.setDataSource(datasource);
        conf.setTransactionManager(transactionManager);
        conf.setTransactionsExternallyManaged(true);
        conf.setJpaEntityManagerFactory(entityManagerFactory);
        if (conf.getBeans() == null) {
            conf.setBeans(new SpringBeanFactoryProxyMap(ctx));
        }
        if (conf.getExpressionManager() == null) {
            conf.setExpressionManager(new SpringExpressionManager(ctx, conf.getBeans()));
        }
        if (EngineServiceUtil.getIdmEngineConfiguration(conf) == null) {
            conf.addEngineConfiguration(
                    EngineConfigurationConstants.KEY_IDM_ENGINE_CONFIG,
                    ctx.getBean(SpringIdmEngineConfiguration.class));
        }
        conf.setEnableSafeBpmnXml(true);
        conf.setCustomFormTypes(List.of(new DropdownFormType(null)));

        return conf.buildProcessEngine();
    }

    @Override
    public void load(final String domain, final DataSource datasource) {
        try {
            getObject().getEngines().put(domain, build(domain, datasource));
        } catch (Exception e) {
            LOG.error("Could not setup Flowable for {}", domain, e);
        }
    }

    @Override
    public DomainProcessEngine getObject() throws Exception {
        if (engine == null) {
            Map<String, ProcessEngine> engines = new HashMap<>();

            ctx.getBean(DomainHolder.class).getDomains().forEach(
                    (domain, datasource) -> engines.put(domain, build(domain, datasource)));

            engine = new DomainProcessEngine(engines);
        }

        return engine;
    }

    @Override
    public Class<DomainProcessEngine> getObjectType() {
        return DomainProcessEngine.class;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    @Override
    public void destroy() throws Exception {
        if (engine != null) {
            engine.close();
        }
    }
}
