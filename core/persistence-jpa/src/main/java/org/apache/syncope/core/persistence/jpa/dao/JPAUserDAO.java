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
package org.apache.syncope.core.persistence.jpa.dao;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Resource;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceException;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.syncope.common.lib.SyncopeConstants;
import org.apache.syncope.common.lib.types.AnyTypeKind;
import org.apache.syncope.common.lib.types.EntityViolationType;
import org.apache.syncope.common.lib.types.IdRepoEntitlement;
import org.apache.syncope.core.persistence.api.entity.Relationship;
import org.apache.syncope.core.spring.security.AuthContextUtils;
import org.apache.syncope.core.spring.security.DelegatedAdministrationException;
import org.apache.syncope.core.persistence.api.attrvalue.validation.InvalidEntityException;
import org.apache.syncope.core.persistence.api.dao.AccessTokenDAO;
import org.apache.syncope.core.persistence.api.dao.AccountRule;
import org.apache.syncope.core.persistence.api.dao.GroupDAO;
import org.apache.syncope.core.persistence.api.dao.RealmDAO;
import org.apache.syncope.core.persistence.api.dao.RoleDAO;
import org.apache.syncope.core.persistence.api.dao.UserDAO;
import org.apache.syncope.core.persistence.api.entity.AccessToken;
import org.apache.syncope.core.persistence.api.entity.AnyUtils;
import org.apache.syncope.core.persistence.api.entity.Entity;
import org.apache.syncope.core.persistence.api.entity.Implementation;
import org.apache.syncope.core.persistence.api.entity.Realm;
import org.apache.syncope.core.persistence.api.entity.Role;
import org.apache.syncope.core.persistence.api.entity.group.Group;
import org.apache.syncope.core.persistence.api.entity.policy.AccountPolicy;
import org.apache.syncope.core.persistence.api.entity.policy.PasswordPolicy;
import org.apache.syncope.core.persistence.api.entity.resource.ExternalResource;
import org.apache.syncope.core.persistence.api.entity.user.SecurityQuestion;
import org.apache.syncope.core.persistence.api.entity.user.UMembership;
import org.apache.syncope.core.persistence.api.entity.user.User;
import org.apache.syncope.core.persistence.jpa.entity.user.JPAUMembership;
import org.apache.syncope.core.persistence.jpa.entity.user.JPAUser;
import org.apache.syncope.core.provisioning.api.event.AnyCreatedUpdatedEvent;
import org.apache.syncope.core.provisioning.api.event.AnyDeletedEvent;
import org.apache.syncope.core.spring.ImplementationManager;
import org.apache.syncope.core.spring.policy.AccountPolicyException;
import org.apache.syncope.core.spring.policy.PasswordPolicyException;
import org.apache.syncope.core.spring.security.Encryptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class JPAUserDAO extends AbstractAnyDAO<User> implements UserDAO {

    protected static final Pattern USERNAME_PATTERN =
            Pattern.compile("^" + SyncopeConstants.NAME_PATTERN, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    protected static final Encryptor ENCRYPTOR = Encryptor.getInstance();

    @Autowired
    protected RoleDAO roleDAO;

    @Autowired
    protected AccessTokenDAO accessTokenDAO;

    @Autowired
    protected RealmDAO realmDAO;

    @Autowired
    protected GroupDAO groupDAO;

    @Resource(name = "adminUser")
    protected String adminUser;

    @Resource(name = "anonymousUser")
    protected String anonymousUser;

    @Override
    protected AnyUtils init() {
        return anyUtilsFactory.getInstance(AnyTypeKind.USER);
    }

    @Transactional(readOnly = true)
    @Override
    public String findKey(final String username) {
        return findKey(username, JPAUser.TABLE);
    }

    @Transactional(readOnly = true)
    @Override
    public Date findLastChange(final String key) {
        return findLastChange(key, JPAUser.TABLE);
    }

    @Override
    public int count() {
        Query query = entityManager().createQuery(
                "SELECT COUNT(e) FROM  " + anyUtils().anyClass().getSimpleName() + " e");
        return ((Number) query.getSingleResult()).intValue();
    }

    @Override
    public Map<String, Integer> countByRealm() {
        Query query = entityManager().createQuery(
                "SELECT e.realm, COUNT(e) FROM  " + anyUtils().anyClass().getSimpleName() + " e GROUP BY e.realm");

        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();
        return results.stream().collect(Collectors.toMap(
                result -> ((Realm) result[0]).getFullPath(),
                result -> ((Number) result[1]).intValue()));
    }

    @Override
    public Map<String, Integer> countByStatus() {
        Query query = entityManager().createQuery(
                "SELECT e.status, COUNT(e) FROM  " + anyUtils().anyClass().getSimpleName() + " e GROUP BY e.status");

        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();
        return results.stream().collect(Collectors.toMap(
                result -> (String) result[0],
                result -> ((Number) result[1]).intValue()));
    }

    @Override
    protected void securityChecks(final User user) {
        // Allows anonymous (during self-registration) and self (during self-update) to read own user,
        // otherwise goes through security checks to see if required entitlements are owned
        if (!AuthContextUtils.getUsername().equals(anonymousUser)
                && !AuthContextUtils.getUsername().equals(user.getUsername())) {

            Map<String, Set<String>> authorizations = AuthContextUtils.getAuthorizations();
            Set<String> authRealms = authorizations.containsKey(IdRepoEntitlement.USER_READ)
                    ? authorizations.get(IdRepoEntitlement.USER_READ)
                    : Set.of();
            boolean authorized = authRealms.stream().
                    anyMatch(realm -> user.getRealm().getFullPath().startsWith(realm));
            if (!authorized) {
                authorized = findDynRealms(user.getKey()).stream().
                        filter(authRealms::contains).
                        count() > 0;
            }
            if (authRealms.isEmpty() || !authorized) {
                throw new DelegatedAdministrationException(
                        user.getRealm().getFullPath(), AnyTypeKind.USER.name(), user.getKey());
            }
        }
    }

    @Override
    public User findByUsername(final String username) {
        TypedQuery<User> query = entityManager().createQuery(
                "SELECT e FROM " + anyUtils().anyClass().getSimpleName()
                + " e WHERE e.username = :username", User.class);
        query.setParameter("username", username);

        User result = null;
        try {
            result = query.getSingleResult();
        } catch (NoResultException e) {
            LOG.debug("No user found with username {}", username, e);
        }

        return result;
    }

    @Override
    public User findByToken(final String token) {
        TypedQuery<User> query = entityManager().createQuery(
                "SELECT e FROM " + anyUtils().anyClass().getSimpleName()
                + " e WHERE e.token LIKE :token", User.class);
        query.setParameter("token", token);

        User result = null;
        try {
            result = query.getSingleResult();
        } catch (NoResultException e) {
            LOG.debug("No user found with token {}", token, e);
        }

        return result;
    }

    @Override
    public List<User> findBySecurityQuestion(final SecurityQuestion securityQuestion) {
        TypedQuery<User> query = entityManager().createQuery(
                "SELECT e FROM " + anyUtils().anyClass().getSimpleName()
                + " e WHERE e.securityQuestion = :securityQuestion", User.class);
        query.setParameter("securityQuestion", securityQuestion);

        return query.getResultList();
    }

    @Override
    public UMembership findMembership(final String key) {
        return entityManager().find(JPAUMembership.class, key);
    }

    protected List<PasswordPolicy> getPasswordPolicies(final User user) {
        List<PasswordPolicy> policies = new ArrayList<>();

        PasswordPolicy policy;

        // add resource policies
        for (ExternalResource resource : findAllResources(user)) {
            policy = resource.getPasswordPolicy();
            if (policy != null) {
                policies.add(policy);
            }
        }

        // add realm policies
        for (Realm realm : realmDAO.findAncestors(user.getRealm())) {
            policy = realm.getPasswordPolicy();
            if (policy != null) {
                policies.add(policy);
            }
        }

        return policies;
    }

    @Override
    public List<User> findAll(final int page, final int itemsPerPage) {
        TypedQuery<User> query = entityManager().createQuery(
                "SELECT e FROM  " + anyUtils().anyClass().getSimpleName() + " e ORDER BY e.id", User.class);
        query.setFirstResult(itemsPerPage * (page <= 0 ? 0 : page - 1));
        query.setMaxResults(itemsPerPage);

        return query.getResultList();
    }

    public List<String> findAllKeys(final int page, final int itemsPerPage) {
        return findAllKeys(JPAUser.TABLE, page, itemsPerPage);
    }

    protected List<AccountPolicy> getAccountPolicies(final User user) {
        List<AccountPolicy> policies = new ArrayList<>();

        // add resource policies
        findAllResources(user).stream().
                map(ExternalResource::getAccountPolicy).
                filter(Objects::nonNull).
                forEachOrdered(policies::add);

        // add realm policies
        realmDAO.findAncestors(user.getRealm()).stream().
                map(Realm::getAccountPolicy).
                filter(Objects::nonNull).
                forEachOrdered(policies::add);

        return policies;
    }

    @Transactional(readOnly = true)
    @Override
    public Pair<Boolean, Boolean> enforcePolicies(final User user) {
        // ------------------------------
        // Verify password policies
        // ------------------------------
        LOG.debug("Password Policy enforcement");

        try {
            int maxPPSpecHistory = 0;
            for (PasswordPolicy policy : getPasswordPolicies(user)) {
                if (user.getPassword() == null && !policy.isAllowNullPassword()) {
                    throw new PasswordPolicyException("Password mandatory");
                }

                for (Implementation impl : policy.getRules()) {
                    ImplementationManager.buildPasswordRule(impl).ifPresent(rule -> rule.enforce(user));
                }

                boolean matching = false;
                if (policy.getHistoryLength() > 0) {
                    List<String> pwdHistory = user.getPasswordHistory();
                    matching = pwdHistory.subList(policy.getHistoryLength() >= pwdHistory.size()
                            ? 0
                            : pwdHistory.size() - policy.getHistoryLength(), pwdHistory.size()).stream().
                            map(old -> ENCRYPTOR.verify(user.getClearPassword(), user.getCipherAlgorithm(), old)).
                            reduce(matching, (accumulator, item) -> accumulator | item);
                }
                if (matching) {
                    throw new PasswordPolicyException("Password value was used in the past: not allowed");
                }

                if (policy.getHistoryLength() > maxPPSpecHistory) {
                    maxPPSpecHistory = policy.getHistoryLength();
                }
            }

            // update user's password history with encrypted password
            if (maxPPSpecHistory > 0 && user.getPassword() != null
                    && !user.getPasswordHistory().contains(user.getPassword())) {

                user.getPasswordHistory().add(user.getPassword());
            }
            // keep only the last maxPPSpecHistory items in user's password history
            if (maxPPSpecHistory < user.getPasswordHistory().size()) {
                for (int i = 0; i < user.getPasswordHistory().size() - maxPPSpecHistory; i++) {
                    user.getPasswordHistory().remove(i);
                }
            }
        } catch (PersistenceException | InvalidEntityException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Invalid password for {}", user, e);
            throw new InvalidEntityException(User.class, EntityViolationType.InvalidPassword, e.getMessage());
        } finally {
            // password has been validated, let's remove its clear version
            user.removeClearPassword();
        }

        // ------------------------------
        // Verify account policies
        // ------------------------------
        LOG.debug("Account Policy enforcement");

        boolean suspend = false;
        boolean propagateSuspension = false;
        try {
            if (user.getUsername() == null) {
                throw new AccountPolicyException("Null username");
            }

            if (adminUser.equals(user.getUsername()) || anonymousUser.equals(user.getUsername())) {
                throw new AccountPolicyException("Not allowed: " + user.getUsername());
            }

            if (!USERNAME_PATTERN.matcher(user.getUsername()).matches()) {
                throw new AccountPolicyException("Character(s) not allowed");
            }

            for (AccountPolicy policy : getAccountPolicies(user)) {
                for (Implementation impl : policy.getRules()) {
                    Optional<AccountRule> rule = ImplementationManager.buildAccountRule(impl);
                    rule.ifPresent(accountRule -> accountRule.enforce(user));
                }

                suspend |= user.getFailedLogins() != null && policy.getMaxAuthenticationAttempts() > 0
                        && user.getFailedLogins() > policy.getMaxAuthenticationAttempts() && !user.isSuspended();
                propagateSuspension |= policy.isPropagateSuspension();
            }
        } catch (PersistenceException | InvalidEntityException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Invalid username for {}", user, e);
            throw new InvalidEntityException(User.class, EntityViolationType.InvalidUsername, e.getMessage());
        }

        return ImmutablePair.of(suspend, propagateSuspension);
    }

    protected Pair<User, Pair<Set<String>, Set<String>>> doSave(final User user) {
        // 1. save clear password value before save
        String clearPwd = user.getClearPassword();

        // 2. save
        User merged = super.save(user);

        // 3. set back the sole clear password value
        JPAUser.class.cast(merged).setClearPassword(clearPwd);

        // 4. enforce password and account policies
        try {
            enforcePolicies(merged);
        } catch (InvalidEntityException e) {
            entityManager().remove(merged);
            throw e;
        }

        publisher.publishEvent(new AnyCreatedUpdatedEvent<>(this, merged, AuthContextUtils.getDomain()));

        roleDAO.refreshDynMemberships(merged);
        Pair<Set<String>, Set<String>> dynGroupMembs = groupDAO.refreshDynMemberships(merged);
        dynRealmDAO.refreshDynMemberships(merged);

        return Pair.of(merged, dynGroupMembs);
    }

    @Override
    public User save(final User user) {
        return doSave(user).getLeft();
    }

    @Override
    public Pair<Set<String>, Set<String>> saveAndGetDynGroupMembs(final User user) {
        return doSave(user).getRight();
    }

    @Override
    public void delete(final User user) {
        roleDAO.removeDynMemberships(user.getKey());
        groupDAO.removeDynMemberships(user);
        dynRealmDAO.removeDynMemberships(user.getKey());

        AccessToken accessToken = accessTokenDAO.findByOwner(user.getUsername());
        if (accessToken != null) {
            accessTokenDAO.delete(accessToken);
        }

        entityManager().remove(user);
        publisher.publishEvent(new AnyDeletedEvent(
                this, AnyTypeKind.USER, user.getKey(), user.getUsername(), AuthContextUtils.getDomain()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    @Override
    public Collection<Role> findAllRoles(final User user) {
        Set<Role> result = new HashSet<>();
        result.addAll(user.getRoles());
        result.addAll(findDynRoles(user.getKey()));

        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    @Override
    @SuppressWarnings("unchecked")
    public List<Role> findDynRoles(final String key) {
        Query query = entityManager().createNativeQuery(
                "SELECT role_id FROM " + JPARoleDAO.DYNMEMB_TABLE + " WHERE any_id=?");
        query.setParameter(1, key);

        List<Role> result = new ArrayList<>();
        query.getResultList().stream().map(resultKey -> resultKey instanceof Object[]
                ? (String) ((Object[]) resultKey)[0]
                : ((String) resultKey)).
                forEachOrdered(roleKey -> {
                    Role role = roleDAO.find(roleKey.toString());
                    if (role == null) {
                        LOG.error("Could not find role {}, even though returned by the native query", roleKey);
                    } else if (!result.contains(role)) {
                        result.add(role);
                    }
                });
        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    @Override
    @SuppressWarnings("unchecked")
    public List<Group> findDynGroups(final String key) {
        Query query = entityManager().createNativeQuery(
                "SELECT group_id FROM " + JPAGroupDAO.UDYNMEMB_TABLE + " WHERE any_id=?");
        query.setParameter(1, key);

        List<Group> result = new ArrayList<>();
        query.getResultList().stream().map(resultKey -> resultKey instanceof Object[]
                ? (String) ((Object[]) resultKey)[0]
                : ((String) resultKey)).
                forEach(groupKey -> {
                    Group group = groupDAO.find(groupKey.toString());
                    if (group == null) {
                        LOG.error("Could not find group {}, even though returned by the native query", groupKey);
                    } else if (!result.contains(group)) {
                        result.add(group);
                    }
                });
        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    @Override
    public Collection<Group> findAllGroups(final User user) {
        Set<Group> result = new HashSet<>();
        result.addAll(user.getMemberships().stream().
                map(Relationship::getRightEnd).collect(Collectors.toSet()));
        result.addAll(findDynGroups(user.getKey()));

        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    @Override
    public Collection<String> findAllGroupKeys(final User user) {
        return findAllGroups(user).stream().map(Entity::getKey).collect(Collectors.toList());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    @Override
    public Collection<String> findAllGroupNames(final User user) {
        return findAllGroups(user).stream().map(Group::getName).collect(Collectors.toList());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    @Override
    public Collection<ExternalResource> findAllResources(final User user) {
        Set<ExternalResource> result = new HashSet<>();
        result.addAll(user.getResources());
        findAllGroups(user).forEach(group -> result.addAll(group.getResources()));

        return result;
    }

    @Transactional(readOnly = true)
    @Override
    public Collection<String> findAllResourceKeys(final String key) {
        return findAllResources(authFind(key)).stream().map(Entity::getKey).collect(Collectors.toList());
    }
}
