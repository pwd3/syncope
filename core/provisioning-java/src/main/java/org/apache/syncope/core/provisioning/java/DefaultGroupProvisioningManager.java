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
package org.apache.syncope.core.provisioning.java;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.syncope.common.lib.request.GroupCR;
import org.apache.syncope.common.lib.request.GroupUR;
import org.apache.syncope.common.lib.to.PropagationStatus;
import org.apache.syncope.common.lib.types.AnyTypeKind;
import org.apache.syncope.common.lib.types.ResourceOperation;
import org.apache.syncope.core.persistence.api.dao.GroupDAO;
import org.apache.syncope.core.provisioning.api.GroupProvisioningManager;
import org.apache.syncope.core.provisioning.api.PropagationByResource;
import org.apache.syncope.core.provisioning.api.WorkflowResult;
import org.apache.syncope.core.provisioning.api.propagation.PropagationManager;
import org.apache.syncope.core.provisioning.api.propagation.PropagationReporter;
import org.apache.syncope.core.provisioning.api.propagation.PropagationTaskExecutor;
import org.apache.syncope.core.provisioning.api.VirAttrHandler;
import org.apache.syncope.core.provisioning.api.data.GroupDataBinder;
import org.apache.syncope.core.provisioning.api.propagation.PropagationTaskInfo;
import org.apache.syncope.core.workflow.api.GroupWorkflowAdapter;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class DefaultGroupProvisioningManager implements GroupProvisioningManager {

    @Autowired
    protected GroupWorkflowAdapter gwfAdapter;

    @Autowired
    protected PropagationManager propagationManager;

    @Autowired
    protected PropagationTaskExecutor taskExecutor;

    @Autowired
    protected GroupDataBinder groupDataBinder;

    @Autowired
    protected GroupDAO groupDAO;

    @Autowired
    protected VirAttrHandler virtAttrHandler;

    @Override
    public Pair<String, List<PropagationStatus>> create(final GroupCR groupCR, final boolean nullPriorityAsync) {
        WorkflowResult<String> created = gwfAdapter.create(groupCR);

        List<PropagationTaskInfo> tasks = propagationManager.getCreateTasks(
                AnyTypeKind.GROUP,
                created.getResult(),
                null,
                created.getPropByRes(),
                groupCR.getVirAttrs(),
                Set.of());
        PropagationReporter propagationReporter = taskExecutor.execute(tasks, nullPriorityAsync);

        return Pair.of(created.getResult(), propagationReporter.getStatuses());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Override
    public Pair<String, List<PropagationStatus>> create(
            final GroupCR groupCR,
            final Map<String, String> groupOwnerMap,
            final Set<String> excludedResources,
            final boolean nullPriorityAsync) {

        WorkflowResult<String> created = gwfAdapter.create(groupCR);

        // see ConnObjectUtils#getAnyTOFromConnObject for GroupOwnerSchema
        groupCR.getPlainAttr(StringUtils.EMPTY).
                ifPresent(groupOwner -> groupOwnerMap.put(created.getResult(), groupOwner.getValues().get(0)));

        List<PropagationTaskInfo> tasks = propagationManager.getCreateTasks(
                AnyTypeKind.GROUP,
                created.getResult(),
                null,
                created.getPropByRes(),
                groupCR.getVirAttrs(),
                excludedResources);
        PropagationReporter propagationReporter = taskExecutor.execute(tasks, nullPriorityAsync);

        return Pair.of(created.getResult(), propagationReporter.getStatuses());
    }

    @Override
    public Pair<GroupUR, List<PropagationStatus>> update(
            final GroupUR groupUR, final boolean nullPriorityAsync) {

        return update(groupUR, Set.of(), nullPriorityAsync);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Override
    public Pair<GroupUR, List<PropagationStatus>> update(
            final GroupUR groupUR, final Set<String> excludedResources, final boolean nullPriorityAsync) {

        WorkflowResult<GroupUR> updated = gwfAdapter.update(groupUR);

        List<PropagationTaskInfo> tasks = propagationManager.getUpdateTasks(
                AnyTypeKind.GROUP,
                updated.getResult().getKey(),
                false,
                null,
                updated.getPropByRes(),
                groupUR.getVirAttrs(),
                excludedResources);
        PropagationReporter propagationReporter = taskExecutor.execute(tasks, nullPriorityAsync);

        return Pair.of(updated.getResult(), propagationReporter.getStatuses());
    }

    @Override
    public List<PropagationStatus> delete(final String key, final boolean nullPriorityAsync) {
        return delete(key, Set.of(), nullPriorityAsync);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Override
    public List<PropagationStatus> delete(
            final String key, final Set<String> excludedResources, final boolean nullPriorityAsync) {

        List<PropagationTaskInfo> taskInfos = new ArrayList<>();

        // Generate propagation tasks for deleting users and any objects from group resources, 
        // if they are on those resources only because of the reason being deleted (see SYNCOPE-357)
        groupDataBinder.findUsersWithTransitiveResources(key).entrySet().
                forEach(entry -> taskInfos.addAll(propagationManager.getDeleteTasks(
                        AnyTypeKind.USER,
                        entry.getKey(),
                        entry.getValue(),
                        excludedResources)));
        groupDataBinder.findAnyObjectsWithTransitiveResources(key).entrySet().
                forEach(entry -> taskInfos.addAll(propagationManager.getDeleteTasks(
                        AnyTypeKind.ANY_OBJECT,
                        entry.getKey(),
                        entry.getValue(),
                        excludedResources)));

        // Generate propagation tasks for deleting this group from resources
        taskInfos.addAll(propagationManager.getDeleteTasks(
                AnyTypeKind.GROUP,
                key,
                null,
                null));

        PropagationReporter propagationReporter = taskExecutor.execute(taskInfos, nullPriorityAsync);

        gwfAdapter.delete(key);

        return propagationReporter.getStatuses();
    }

    @Override
    public String unlink(final GroupUR groupUR) {
        return gwfAdapter.update(groupUR).getResult().getKey();
    }

    @Override
    public List<PropagationStatus> provision(
            final String key, final Collection<String> resources, final boolean nullPriorityAsync) {

        PropagationByResource propByRes = new PropagationByResource();
        propByRes.addAll(ResourceOperation.UPDATE, resources);

        List<PropagationTaskInfo> taskInfos = propagationManager.getUpdateTasks(
                AnyTypeKind.GROUP,
                key,
                false,
                null,
                propByRes,
                null,
                null);
        PropagationReporter propagationReporter = taskExecutor.execute(taskInfos, nullPriorityAsync);

        return propagationReporter.getStatuses();
    }

    @Override
    public List<PropagationStatus> deprovision(
            final String key, final Collection<String> resources, final boolean nullPriorityAsync) {

        PropagationByResource propByRes = new PropagationByResource();
        propByRes.addAll(ResourceOperation.DELETE, resources);

        List<PropagationTaskInfo> taskInfos = propagationManager.getDeleteTasks(
                AnyTypeKind.GROUP,
                key,
                propByRes,
                groupDAO.findAllResourceKeys(key).stream().
                        filter(resource -> !resources.contains(resource)).
                        collect(Collectors.toList()));
        PropagationReporter propagationReporter = taskExecutor.execute(taskInfos, nullPriorityAsync);

        return propagationReporter.getStatuses();
    }

    @Override
    public String link(final GroupUR groupUR) {
        return gwfAdapter.update(groupUR).getResult().getKey();
    }
}
