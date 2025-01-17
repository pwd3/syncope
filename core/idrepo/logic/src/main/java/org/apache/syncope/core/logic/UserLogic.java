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
package org.apache.syncope.core.logic;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.syncope.common.keymaster.client.api.ConfParamOps;
import org.apache.syncope.common.lib.SyncopeClientException;
import org.apache.syncope.common.lib.request.BooleanReplacePatchItem;
import org.apache.syncope.common.lib.request.PasswordPatch;
import org.apache.syncope.common.lib.request.StatusR;
import org.apache.syncope.common.lib.request.StringPatchItem;
import org.apache.syncope.common.lib.request.UserCR;
import org.apache.syncope.common.lib.request.UserUR;
import org.apache.syncope.common.lib.to.PropagationStatus;
import org.apache.syncope.common.lib.to.ProvisioningResult;
import org.apache.syncope.common.lib.to.UserTO;
import org.apache.syncope.common.lib.types.AnyTypeKind;
import org.apache.syncope.common.lib.types.ClientExceptionType;
import org.apache.syncope.common.lib.types.PatchOperation;
import org.apache.syncope.common.lib.types.IdRepoEntitlement;
import org.apache.syncope.core.persistence.api.dao.AccessTokenDAO;
import org.apache.syncope.core.persistence.api.dao.AnySearchDAO;
import org.apache.syncope.core.persistence.api.dao.NotFoundException;
import org.apache.syncope.core.persistence.api.dao.search.OrderByClause;
import org.apache.syncope.core.persistence.api.dao.search.SearchCond;
import org.apache.syncope.core.persistence.api.entity.group.Group;
import org.apache.syncope.core.persistence.api.entity.user.User;
import org.apache.syncope.core.provisioning.api.LogicActions;
import org.apache.syncope.core.provisioning.api.UserProvisioningManager;
import org.apache.syncope.core.provisioning.api.data.UserDataBinder;
import org.apache.syncope.core.provisioning.api.serialization.POJOHelper;
import org.apache.syncope.core.provisioning.api.utils.RealmUtils;
import org.apache.syncope.core.spring.security.AuthContextUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Note that this controller does not extend {@link AbstractTransactionalLogic}, hence does not provide any
 * Spring's Transactional logic at class level.
 */
@Component
public class UserLogic extends AbstractAnyLogic<UserTO, UserCR, UserUR> {

    @Autowired
    protected AnySearchDAO searchDAO;

    @Autowired
    protected AccessTokenDAO accessTokenDAO;

    @Autowired
    protected ConfParamOps confParamOps;

    @Autowired
    protected UserDataBinder binder;

    @Autowired
    protected UserProvisioningManager provisioningManager;

    @Autowired
    protected SyncopeLogic syncopeLogic;

    @PreAuthorize("isAuthenticated() and not(hasRole('" + IdRepoEntitlement.MUST_CHANGE_PASSWORD + "'))")
    @Transactional(readOnly = true)
    public Pair<String, UserTO> selfRead() {
        return Pair.of(
                POJOHelper.serialize(AuthContextUtils.getAuthorizations()),
                binder.returnUserTO(binder.getAuthenticatedUserTO()));
    }

    @PreAuthorize("hasRole('" + IdRepoEntitlement.USER_READ + "')")
    @Transactional(readOnly = true)
    @Override
    public UserTO read(final String key) {
        return binder.returnUserTO(binder.getUserTO(key));
    }

    @PreAuthorize("hasRole('" + IdRepoEntitlement.USER_SEARCH + "')")
    @Transactional(readOnly = true)
    @Override
    public Pair<Integer, List<UserTO>> search(
            final SearchCond searchCond,
            final int page, final int size, final List<OrderByClause> orderBy,
            final String realm,
            final boolean details) {

        int count = searchDAO.count(RealmUtils.getEffective(
                AuthContextUtils.getAuthorizations().get(IdRepoEntitlement.USER_SEARCH), realm),
            Optional.ofNullable(searchCond).orElseGet(() -> userDAO.getAllMatchingCond()), AnyTypeKind.USER);

        List<User> matching = searchDAO.search(RealmUtils.getEffective(
                AuthContextUtils.getAuthorizations().get(IdRepoEntitlement.USER_SEARCH), realm),
            Optional.ofNullable(searchCond).orElseGet(() -> userDAO.getAllMatchingCond()),
                page, size, orderBy, AnyTypeKind.USER);
        List<UserTO> result = matching.stream().
                map(user -> binder.returnUserTO(binder.getUserTO(user, details))).
                collect(Collectors.toList());

        return Pair.of(count, result);
    }

    @PreAuthorize("isAnonymous() or hasRole('" + IdRepoEntitlement.ANONYMOUS + "')")
    public ProvisioningResult<UserTO> selfCreate(final UserCR createReq, final boolean nullPriorityAsync) {
        return doCreate(createReq, true, nullPriorityAsync);
    }

    @PreAuthorize("hasRole('" + IdRepoEntitlement.USER_CREATE + "')")
    public ProvisioningResult<UserTO> create(final UserCR createReq, final boolean nullPriorityAsync) {
        return doCreate(createReq, false, nullPriorityAsync);
    }

    protected ProvisioningResult<UserTO> doCreate(
            final UserCR userCR,
            final boolean self,
            final boolean nullPriorityAsync) {

        Pair<UserCR, List<LogicActions>> before = beforeCreate(userCR);

        if (before.getLeft().getRealm() == null) {
            throw SyncopeClientException.build(ClientExceptionType.InvalidRealm);
        }

        if (!self) {
            Set<String> effectiveRealms = RealmUtils.getEffective(
                    AuthContextUtils.getAuthorizations().get(IdRepoEntitlement.USER_CREATE),
                    before.getLeft().getRealm());
            securityChecks(effectiveRealms, before.getLeft().getRealm(), null);
        }

        Pair<String, List<PropagationStatus>> created = provisioningManager.create(before.getLeft(), nullPriorityAsync);

        return afterCreate(
                binder.returnUserTO(binder.getUserTO(created.getKey())), created.getRight(), before.getRight());
    }

    @PreAuthorize("isAuthenticated() "
            + "and not(hasRole('" + IdRepoEntitlement.ANONYMOUS + "')) "
            + "and not(hasRole('" + IdRepoEntitlement.MUST_CHANGE_PASSWORD + "'))")
    public ProvisioningResult<UserTO> selfUpdate(final UserUR userUR, final boolean nullPriorityAsync) {
        UserTO userTO = binder.getAuthenticatedUserTO();
        userUR.setKey(userTO.getKey());
        ProvisioningResult<UserTO> updated = doUpdate(userUR, true, nullPriorityAsync);

        // Ensures that, if the self update above moves the user into a status from which no authentication
        // is possible, the existing Access Token is clean up to avoid issues with future authentications
        List<String> authStatuses = List.of(confParamOps.get(AuthContextUtils.getDomain(),
                "authentication.statuses", new String[] {}, String[].class));
        if (!authStatuses.contains(updated.getEntity().getStatus())) {
            String accessToken = accessTokenDAO.findByOwner(updated.getEntity().getUsername()).getKey();
            if (accessToken != null) {
                accessTokenDAO.delete(accessToken);
            }
        }

        return updated;
    }

    @PreAuthorize("hasRole('" + IdRepoEntitlement.USER_UPDATE + "')")
    @Override
    public ProvisioningResult<UserTO> update(final UserUR userUR, final boolean nullPriorityAsync) {
        return doUpdate(userUR, false, nullPriorityAsync);
    }

    protected ProvisioningResult<UserTO> doUpdate(
            final UserUR userUR, final boolean self, final boolean nullPriorityAsync) {

        UserTO userTO = binder.getUserTO(userUR.getKey());
        Set<String> dynRealmsBefore = new HashSet<>(userTO.getDynRealms());
        Pair<UserUR, List<LogicActions>> before = beforeUpdate(userUR, userTO.getRealm());

        boolean authDynRealms = false;
        if (!self
                && before.getLeft().getRealm() != null
                && StringUtils.isNotBlank(before.getLeft().getRealm().getValue())) {

            Set<String> effectiveRealms = RealmUtils.getEffective(
                    AuthContextUtils.getAuthorizations().get(IdRepoEntitlement.USER_UPDATE),
                    before.getLeft().getRealm().getValue());
            authDynRealms =
                    securityChecks(effectiveRealms, before.getLeft().getRealm().getValue(), before.getLeft().getKey());
        }

        Pair<UserUR, List<PropagationStatus>> updated =
                provisioningManager.update(before.getLeft(), nullPriorityAsync);

        return afterUpdate(
                binder.returnUserTO(binder.getUserTO(updated.getLeft().getKey())),
                updated.getRight(),
                before.getRight(),
                authDynRealms,
                dynRealmsBefore);
    }

    protected Pair<String, List<PropagationStatus>> setStatusOnWfAdapter(
            final StatusR statusR, final boolean nullPriorityAsync) {

        Pair<String, List<PropagationStatus>> updated;

        switch (statusR.getType()) {
            case SUSPEND:
                updated = provisioningManager.suspend(statusR, nullPriorityAsync);
                break;

            case REACTIVATE:
                updated = provisioningManager.reactivate(statusR, nullPriorityAsync);
                break;

            case ACTIVATE:
            default:
                updated = provisioningManager.activate(statusR, nullPriorityAsync);
                break;

        }

        return updated;
    }

    @PreAuthorize("hasRole('" + IdRepoEntitlement.USER_UPDATE + "')")
    public ProvisioningResult<UserTO> status(final StatusR statusR, final boolean nullPriorityAsync) {
        // security checks
        UserTO toUpdate = binder.getUserTO(statusR.getKey());
        Set<String> effectiveRealms = RealmUtils.getEffective(
                AuthContextUtils.getAuthorizations().get(IdRepoEntitlement.USER_UPDATE),
                toUpdate.getRealm());
        securityChecks(effectiveRealms, toUpdate.getRealm(), toUpdate.getKey());

        // ensures the actual user key is effectively on the request - as the binder.getUserTO(statusR.getKey())
        // call above works with username as well
        statusR.setKey(toUpdate.getKey());
        Pair<String, List<PropagationStatus>> updated = setStatusOnWfAdapter(statusR, nullPriorityAsync);

        return afterUpdate(
                binder.returnUserTO(binder.getUserTO(updated.getKey())),
                updated.getRight(),
                Collections.<LogicActions>emptyList(),
                false,
                Set.of());
    }

    @PreAuthorize("isAuthenticated() and not(hasRole('" + IdRepoEntitlement.MUST_CHANGE_PASSWORD + "'))")
    public ProvisioningResult<UserTO> selfStatus(final StatusR statusR, final boolean nullPriorityAsync) {
        statusR.setKey(userDAO.findKey(AuthContextUtils.getUsername()));
        Pair<String, List<PropagationStatus>> updated = setStatusOnWfAdapter(statusR, nullPriorityAsync);

        return afterUpdate(
                binder.returnUserTO(binder.getUserTO(updated.getKey())),
                updated.getRight(),
                Collections.<LogicActions>emptyList(),
                false,
                Set.of());
    }

    @PreAuthorize("hasRole('" + IdRepoEntitlement.MUST_CHANGE_PASSWORD + "')")
    public ProvisioningResult<UserTO> mustChangePassword(final String password, final boolean nullPriorityAsync) {
        UserUR userUR = new UserUR();
        userUR.setPassword(new PasswordPatch.Builder().value(password).build());
        userUR.setMustChangePassword(new BooleanReplacePatchItem.Builder().value(false).build());
        return selfUpdate(userUR, nullPriorityAsync);
    }

    @PreAuthorize("isAnonymous() or hasRole('" + IdRepoEntitlement.ANONYMOUS + "')")
    @Transactional
    public void requestPasswordReset(final String username, final String securityAnswer) {
        if (username == null) {
            throw new NotFoundException("Null username");
        }

        User user = userDAO.findByUsername(username);
        if (user == null) {
            throw new NotFoundException("User " + username);
        }

        if (syncopeLogic.isPwdResetRequiringSecurityQuestions()
                && (securityAnswer == null || !securityAnswer.equals(user.getSecurityAnswer()))) {

            throw SyncopeClientException.build(ClientExceptionType.InvalidSecurityAnswer);
        }

        provisioningManager.requestPasswordReset(user.getKey());
    }

    @PreAuthorize("isAnonymous() or hasRole('" + IdRepoEntitlement.ANONYMOUS + "')")
    @Transactional
    public void confirmPasswordReset(final String token, final String password) {
        User user = userDAO.findByToken(token);
        if (user == null) {
            throw new NotFoundException("User with token " + token);
        }
        provisioningManager.confirmPasswordReset(user.getKey(), token, password);
    }

    @PreAuthorize("isAuthenticated() "
            + "and not(hasRole('" + IdRepoEntitlement.ANONYMOUS + "')) "
            + "and not(hasRole('" + IdRepoEntitlement.MUST_CHANGE_PASSWORD + "'))")
    public ProvisioningResult<UserTO> selfDelete(final boolean nullPriorityAsync) {
        UserTO userTO = binder.getAuthenticatedUserTO();
        return doDelete(userTO, true, nullPriorityAsync);
    }

    @PreAuthorize("hasRole('" + IdRepoEntitlement.USER_DELETE + "')")
    @Override
    public ProvisioningResult<UserTO> delete(final String key, final boolean nullPriorityAsync) {
        UserTO userTO = binder.getUserTO(key);
        return doDelete(userTO, false, nullPriorityAsync);
    }

    protected ProvisioningResult<UserTO> doDelete(
            final UserTO userTO, final boolean self, final boolean nullPriorityAsync) {

        Pair<UserTO, List<LogicActions>> before = beforeDelete(userTO);

        if (!self) {
            Set<String> effectiveRealms = RealmUtils.getEffective(
                    AuthContextUtils.getAuthorizations().get(IdRepoEntitlement.USER_DELETE),
                    before.getLeft().getRealm());
            securityChecks(effectiveRealms, before.getLeft().getRealm(), before.getLeft().getKey());
        }

        List<Group> ownedGroups = groupDAO.findOwnedByUser(before.getLeft().getKey());
        if (!ownedGroups.isEmpty()) {
            SyncopeClientException sce = SyncopeClientException.build(ClientExceptionType.GroupOwnership);
            sce.getElements().addAll(ownedGroups.stream().
                    map(group -> group.getKey() + " " + group.getName()).collect(Collectors.toList()));
            throw sce;
        }

        List<PropagationStatus> statuses = provisioningManager.delete(before.getLeft().getKey(), nullPriorityAsync);

        UserTO deletedTO;
        if (userDAO.find(before.getLeft().getKey()) == null) {
            deletedTO = new UserTO();
            deletedTO.setKey(before.getLeft().getKey());
        } else {
            deletedTO = binder.getUserTO(before.getLeft().getKey());
        }

        return afterDelete(binder.returnUserTO(deletedTO), statuses, before.getRight());
    }

    @PreAuthorize("hasRole('" + IdRepoEntitlement.USER_UPDATE + "')")
    @Override
    public UserTO unlink(final String key, final Collection<String> resources) {
        // security checks
        UserTO user = binder.getUserTO(key);
        Set<String> effectiveRealms = RealmUtils.getEffective(
                AuthContextUtils.getAuthorizations().get(IdRepoEntitlement.USER_UPDATE),
                user.getRealm());
        securityChecks(effectiveRealms, user.getRealm(), user.getKey());

        UserUR req = new UserUR();
        req.setKey(key);
        req.getResources().addAll(resources.stream().map(resource
                -> new StringPatchItem.Builder().operation(PatchOperation.DELETE).value(resource).build()).
                collect(Collectors.toList()));

        return binder.returnUserTO(binder.getUserTO(provisioningManager.unlink(req)));
    }

    @PreAuthorize("hasRole('" + IdRepoEntitlement.USER_UPDATE + "')")
    @Override
    public UserTO link(final String key, final Collection<String> resources) {
        // security checks
        UserTO user = binder.getUserTO(key);
        Set<String> effectiveRealms = RealmUtils.getEffective(
                AuthContextUtils.getAuthorizations().get(IdRepoEntitlement.USER_UPDATE),
                user.getRealm());
        securityChecks(effectiveRealms, user.getRealm(), user.getKey());

        UserUR req = new UserUR();
        req.setKey(key);
        req.getResources().addAll(resources.stream().map(resource
                -> new StringPatchItem.Builder().operation(PatchOperation.ADD_REPLACE).value(resource).build()).
                collect(Collectors.toList()));

        return binder.returnUserTO(binder.getUserTO(provisioningManager.link(req)));
    }

    @PreAuthorize("hasRole('" + IdRepoEntitlement.USER_UPDATE + "')")
    @Override
    public ProvisioningResult<UserTO> unassign(
            final String key, final Collection<String> resources, final boolean nullPriorityAsync) {

        // security checks
        UserTO user = binder.getUserTO(key);
        Set<String> effectiveRealms = RealmUtils.getEffective(
                AuthContextUtils.getAuthorizations().get(IdRepoEntitlement.USER_UPDATE),
                user.getRealm());
        securityChecks(effectiveRealms, user.getRealm(), user.getKey());

        UserUR req = new UserUR();
        req.setKey(key);
        req.getResources().addAll(resources.stream().map(resource
                -> new StringPatchItem.Builder().operation(PatchOperation.DELETE).value(resource).build()).
                collect(Collectors.toList()));

        return update(req, nullPriorityAsync);
    }

    @PreAuthorize("hasRole('" + IdRepoEntitlement.USER_UPDATE + "')")
    @Override
    public ProvisioningResult<UserTO> assign(
            final String key,
            final Collection<String> resources,
            final boolean changepwd,
            final String password,
            final boolean nullPriorityAsync) {

        // security checks
        UserTO user = binder.getUserTO(key);
        Set<String> effectiveRealms = RealmUtils.getEffective(
                AuthContextUtils.getAuthorizations().get(IdRepoEntitlement.USER_UPDATE),
                user.getRealm());
        securityChecks(effectiveRealms, user.getRealm(), user.getKey());

        UserUR req = new UserUR();
        req.setKey(key);
        req.getResources().addAll(resources.stream().map(resource
                -> new StringPatchItem.Builder().operation(PatchOperation.ADD_REPLACE).value(resource).build()).
                collect(Collectors.toList()));

        if (changepwd) {
            req.setPassword(new PasswordPatch.Builder().
                    value(password).onSyncope(false).resources(resources).build());
        }

        return update(req, nullPriorityAsync);
    }

    @PreAuthorize("hasRole('" + IdRepoEntitlement.USER_UPDATE + "')")
    @Override
    public ProvisioningResult<UserTO> deprovision(
            final String key, final Collection<String> resources, final boolean nullPriorityAsync) {

        // security checks
        UserTO user = binder.getUserTO(key);
        Set<String> effectiveRealms = RealmUtils.getEffective(
                AuthContextUtils.getAuthorizations().get(IdRepoEntitlement.USER_UPDATE),
                user.getRealm());
        securityChecks(effectiveRealms, user.getRealm(), user.getKey());

        List<PropagationStatus> statuses = provisioningManager.deprovision(key, resources, nullPriorityAsync);

        ProvisioningResult<UserTO> result = new ProvisioningResult<>();
        result.setEntity(binder.returnUserTO(binder.getUserTO(key)));
        result.getPropagationStatuses().addAll(statuses);
        return result;
    }

    @PreAuthorize("hasRole('" + IdRepoEntitlement.USER_UPDATE + "')")
    @Override
    public ProvisioningResult<UserTO> provision(
            final String key,
            final Collection<String> resources,
            final boolean changePwd,
            final String password,
            final boolean nullPriorityAsync) {

        // security checks
        UserTO user = binder.getUserTO(key);
        Set<String> effectiveRealms = RealmUtils.getEffective(
                AuthContextUtils.getAuthorizations().get(IdRepoEntitlement.USER_UPDATE),
                user.getRealm());
        securityChecks(effectiveRealms, user.getRealm(), user.getKey());

        List<PropagationStatus> statuses = provisioningManager.provision(key, changePwd, password, resources,
                nullPriorityAsync);

        ProvisioningResult<UserTO> result = new ProvisioningResult<>();
        result.setEntity(binder.returnUserTO(binder.getUserTO(key)));
        result.getPropagationStatuses().addAll(statuses);
        return result;
    }

    @Override
    protected UserTO resolveReference(final Method method, final Object... args) throws UnresolvedReferenceException {
        String key = null;

        if ("requestPasswordReset".equals(method.getName())) {
            key = userDAO.findKey((String) args[0]);
        } else if (!"confirmPasswordReset".equals(method.getName()) && ArrayUtils.isNotEmpty(args)) {
            for (int i = 0; key == null && i < args.length; i++) {
                if (args[i] instanceof String) {
                    key = (String) args[i];
                } else if (args[i] instanceof UserTO) {
                    key = ((UserTO) args[i]).getKey();
                } else if (args[i] instanceof UserUR) {
                    key = ((UserUR) args[i]).getKey();
                } else if (args[i] instanceof StatusR) {
                    key = ((StatusR) args[i]).getKey();
                }
            }
        }

        if (key != null) {
            try {
                return binder.getUserTO(key);
            } catch (Throwable ignore) {
                LOG.debug("Unresolved reference", ignore);
                throw new UnresolvedReferenceException(ignore);
            }
        }

        throw new UnresolvedReferenceException();
    }
}
