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
package org.apache.syncope.core.provisioning.java.data;

import java.util.stream.Collectors;
import org.apache.syncope.core.provisioning.api.data.PolicyDataBinder;
import org.apache.syncope.common.lib.policy.PolicyTO;
import org.apache.syncope.common.lib.policy.AccountPolicyTO;
import org.apache.syncope.common.lib.policy.PasswordPolicyTO;
import org.apache.syncope.common.lib.policy.PullPolicyTO;
import org.apache.syncope.common.lib.policy.PushPolicyTO;
import org.apache.syncope.core.persistence.api.dao.AnyTypeDAO;
import org.apache.syncope.core.persistence.api.dao.ExternalResourceDAO;
import org.apache.syncope.core.persistence.api.dao.ImplementationDAO;
import org.apache.syncope.core.persistence.api.dao.NotFoundException;
import org.apache.syncope.core.persistence.api.dao.RealmDAO;
import org.apache.syncope.core.persistence.api.entity.AnyType;
import org.apache.syncope.core.persistence.api.entity.Entity;
import org.apache.syncope.core.persistence.api.entity.policy.AccountPolicy;
import org.apache.syncope.core.persistence.api.entity.EntityFactory;
import org.apache.syncope.core.persistence.api.entity.Implementation;
import org.apache.syncope.core.persistence.api.entity.resource.ExternalResource;
import org.apache.syncope.core.persistence.api.entity.policy.PasswordPolicy;
import org.apache.syncope.core.persistence.api.entity.Realm;
import org.apache.syncope.core.persistence.api.entity.policy.Policy;
import org.apache.syncope.core.persistence.api.entity.policy.PullCorrelationRuleEntity;
import org.apache.syncope.core.persistence.api.entity.policy.PullPolicy;
import org.apache.syncope.core.persistence.api.entity.policy.PushCorrelationRuleEntity;
import org.apache.syncope.core.persistence.api.entity.policy.PushPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PolicyDataBinderImpl implements PolicyDataBinder {

    private static final Logger LOG = LoggerFactory.getLogger(PolicyDataBinder.class);

    @Autowired
    private ExternalResourceDAO resourceDAO;

    @Autowired
    private RealmDAO realmDAO;

    @Autowired
    private AnyTypeDAO anyTypeDAO;

    @Autowired
    private ImplementationDAO implementationDAO;

    @Autowired
    private EntityFactory entityFactory;

    @SuppressWarnings("unchecked")
    private <T extends Policy> T getPolicy(final T policy, final PolicyTO policyTO) {
        T result = policy;

        if (policyTO instanceof PasswordPolicyTO) {
            if (result == null) {
                result = (T) entityFactory.newEntity(PasswordPolicy.class);
            }

            PasswordPolicy passwordPolicy = PasswordPolicy.class.cast(result);
            PasswordPolicyTO passwordPolicyTO = PasswordPolicyTO.class.cast(policyTO);

            passwordPolicy.setAllowNullPassword(passwordPolicyTO.isAllowNullPassword());
            passwordPolicy.setHistoryLength(passwordPolicyTO.getHistoryLength());

            passwordPolicyTO.getRules().forEach(ruleKey -> {
                Implementation rule = implementationDAO.find(ruleKey);
                if (rule == null) {
                    LOG.debug("Invalid " + Implementation.class.getSimpleName() + " {}, ignoring...", ruleKey);
                } else {
                    passwordPolicy.add(rule);
                }
            });
            // remove all implementations not contained in the TO
            passwordPolicy.getRules().
                    removeIf(implementation -> !passwordPolicyTO.getRules().contains(implementation.getKey()));
        } else if (policyTO instanceof AccountPolicyTO) {
            if (result == null) {
                result = (T) entityFactory.newEntity(AccountPolicy.class);
            }

            AccountPolicy accountPolicy = AccountPolicy.class.cast(result);
            AccountPolicyTO accountPolicyTO = AccountPolicyTO.class.cast(policyTO);

            accountPolicy.setMaxAuthenticationAttempts(accountPolicyTO.getMaxAuthenticationAttempts());
            accountPolicy.setPropagateSuspension(accountPolicyTO.isPropagateSuspension());

            accountPolicyTO.getRules().forEach(ruleKey -> {
                Implementation rule = implementationDAO.find(ruleKey);
                if (rule == null) {
                    LOG.debug("Invalid " + Implementation.class.getSimpleName() + " {}, ignoring...", ruleKey);
                } else {
                    accountPolicy.add(rule);
                }
            });
            // remove all implementations not contained in the TO
            accountPolicy.getRules().
                    removeIf(implementation -> !accountPolicyTO.getRules().contains(implementation.getKey()));

            accountPolicy.getResources().clear();
            accountPolicyTO.getPassthroughResources().forEach(resourceName -> {
                ExternalResource resource = resourceDAO.find(resourceName);
                if (resource == null) {
                    LOG.debug("Ignoring invalid resource {} ", resourceName);
                } else {
                    accountPolicy.add(resource);
                }
            });
        } else if (policyTO instanceof PullPolicyTO) {
            if (result == null) {
                result = (T) entityFactory.newEntity(PullPolicy.class);
            }

            PullPolicy pullPolicy = PullPolicy.class.cast(result);
            PullPolicyTO pullPolicyTO = PullPolicyTO.class.cast(policyTO);

            pullPolicy.setConflictResolutionAction(pullPolicyTO.getConflictResolutionAction());

            pullPolicyTO.getCorrelationRules().forEach((type, impl) -> {
                AnyType anyType = anyTypeDAO.find(type);
                if (anyType == null) {
                    LOG.debug("Invalid AnyType {} specified, ignoring...", type);
                } else {
                    PullCorrelationRuleEntity correlationRule = pullPolicy.getCorrelationRule(anyType).orElse(null);
                    if (correlationRule == null) {
                        correlationRule = entityFactory.newEntity(PullCorrelationRuleEntity.class);
                        correlationRule.setAnyType(anyType);
                        correlationRule.setPullPolicy(pullPolicy);
                        pullPolicy.add(correlationRule);
                    }

                    Implementation rule = implementationDAO.find(impl);
                    if (rule == null) {
                        throw new NotFoundException("Implementation " + type + " " + impl);
                    }
                    correlationRule.setImplementation(rule);
                }
            });
            // remove all rules not contained in the TO
            pullPolicy.getCorrelationRules().removeIf(anyFilter
                    -> !pullPolicyTO.getCorrelationRules().containsKey(anyFilter.getAnyType().getKey()));
        } else if (policyTO instanceof PushPolicyTO) {
            if (result == null) {
                result = (T) entityFactory.newEntity(PushPolicy.class);
            }

            PushPolicy pushPolicy = PushPolicy.class.cast(result);
            PushPolicyTO pushPolicyTO = PushPolicyTO.class.cast(policyTO);

            pushPolicy.setConflictResolutionAction(pushPolicyTO.getConflictResolutionAction());

            pushPolicyTO.getCorrelationRules().forEach((type, impl) -> {
                AnyType anyType = anyTypeDAO.find(type);
                if (anyType == null) {
                    LOG.debug("Invalid AnyType {} specified, ignoring...", type);
                } else {
                    PushCorrelationRuleEntity correlationRule = pushPolicy.getCorrelationRule(anyType).orElse(null);
                    if (correlationRule == null) {
                        correlationRule = entityFactory.newEntity(PushCorrelationRuleEntity.class);
                        correlationRule.setAnyType(anyType);
                        correlationRule.setPushPolicy(pushPolicy);
                        pushPolicy.add(correlationRule);
                    }

                    Implementation rule = implementationDAO.find(impl);
                    if (rule == null) {
                        throw new NotFoundException("Implementation " + type + " " + impl);
                    }
                    correlationRule.setImplementation(rule);
                }
            });
            // remove all rules not contained in the TO
            pushPolicy.getCorrelationRules().removeIf(anyFilter
                    -> !pushPolicyTO.getCorrelationRules().containsKey(anyFilter.getAnyType().getKey()));
        }

        if (result != null) {
            result.setDescription(policyTO.getDescription());
        }

        return result;
    }

    @Override
    public <T extends Policy> T create(final PolicyTO policyTO) {
        return getPolicy(null, policyTO);
    }

    @Override
    public <T extends Policy> T update(final T policy, final PolicyTO policyTO) {
        return getPolicy(policy, policyTO);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends PolicyTO> T getPolicyTO(final Policy policy) {
        T policyTO = null;

        if (policy instanceof PasswordPolicy) {
            PasswordPolicy passwordPolicy = PasswordPolicy.class.cast(policy);
            PasswordPolicyTO passwordPolicyTO = new PasswordPolicyTO();
            policyTO = (T) passwordPolicyTO;

            passwordPolicyTO.setAllowNullPassword(passwordPolicy.isAllowNullPassword());
            passwordPolicyTO.setHistoryLength(passwordPolicy.getHistoryLength());

            passwordPolicyTO.getRules().addAll(
                    passwordPolicy.getRules().stream().map(Entity::getKey).collect(Collectors.toList()));
        } else if (policy instanceof AccountPolicy) {
            AccountPolicy accountPolicy = AccountPolicy.class.cast(policy);
            AccountPolicyTO accountPolicyTO = new AccountPolicyTO();
            policyTO = (T) accountPolicyTO;

            accountPolicyTO.setMaxAuthenticationAttempts(accountPolicy.getMaxAuthenticationAttempts());
            accountPolicyTO.setPropagateSuspension(accountPolicy.isPropagateSuspension());

            accountPolicyTO.getRules().addAll(
                    accountPolicy.getRules().stream().map(Entity::getKey).collect(Collectors.toList()));

            accountPolicyTO.getPassthroughResources().addAll(
                    accountPolicy.getResources().stream().map(Entity::getKey).collect(Collectors.toList()));
        } else if (policy instanceof PullPolicy) {
            PullPolicy pullPolicy = PullPolicy.class.cast(policy);
            PullPolicyTO pullPolicyTO = new PullPolicyTO();
            policyTO = (T) pullPolicyTO;

            pullPolicyTO.setConflictResolutionAction(((PullPolicy) policy).getConflictResolutionAction());
            pullPolicy.getCorrelationRules().
                    forEach(rule -> pullPolicyTO.getCorrelationRules().
                    put(rule.getAnyType().getKey(), rule.getImplementation().getKey()));
        } else if (policy instanceof PushPolicy) {
            PushPolicy pushPolicy = PushPolicy.class.cast(policy);
            PushPolicyTO pushPolicyTO = new PushPolicyTO();
            policyTO = (T) pushPolicyTO;

            pushPolicyTO.setConflictResolutionAction(((PushPolicy) policy).getConflictResolutionAction());
            pushPolicy.getCorrelationRules().
                    forEach(rule -> pushPolicyTO.getCorrelationRules().
                    put(rule.getAnyType().getKey(), rule.getImplementation().getKey()));
        }

        if (policyTO != null) {
            policyTO.setKey(policy.getKey());
            policyTO.setDescription(policy.getDescription());

            for (ExternalResource resource : resourceDAO.findByPolicy(policy)) {
                policyTO.getUsedByResources().add(resource.getKey());
            }
            for (Realm realm : realmDAO.findByPolicy(policy)) {
                policyTO.getUsedByRealms().add(realm.getFullPath());
            }
        }

        return policyTO;
    }
}
