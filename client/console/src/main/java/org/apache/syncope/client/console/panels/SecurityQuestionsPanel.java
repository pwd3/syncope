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
package org.apache.syncope.client.console.panels;

import de.agilecoders.wicket.core.markup.html.bootstrap.dialog.Modal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.syncope.client.console.SyncopeConsoleSession;
import org.apache.syncope.client.console.commons.Constants;
import org.apache.syncope.client.console.commons.DirectoryDataProvider;
import org.apache.syncope.client.console.commons.SortableDataProviderComparator;
import org.apache.syncope.client.console.pages.BasePage;
import org.apache.syncope.client.console.panels.SecurityQuestionsPanel.SecurityQuestionsProvider;
import org.apache.syncope.client.console.rest.SecurityQuestionRestClient;
import org.apache.syncope.client.console.wicket.extensions.markup.html.repeater.data.table.ActionColumn;
import org.apache.syncope.client.console.wicket.extensions.markup.html.repeater.data.table.KeyPropertyColumn;
import org.apache.syncope.client.console.wicket.markup.html.form.ActionLink;
import org.apache.syncope.client.console.wicket.markup.html.form.ActionLinksPanel;
import org.apache.syncope.client.console.wizards.AbstractModalPanelBuilder;
import org.apache.syncope.client.console.wizards.AjaxWizard;
import org.apache.syncope.client.console.wizards.WizardMgtPanel;
import org.apache.syncope.common.lib.to.SecurityQuestionTO;
import org.apache.syncope.common.lib.types.StandardEntitlement;
import org.apache.syncope.common.rest.api.service.SecurityQuestionService;
import org.apache.wicket.PageReference;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.authroles.authorization.strategies.role.metadata.MetaDataRoleAuthorizationStrategy;
import org.apache.wicket.event.Broadcast;
import org.apache.wicket.extensions.markup.html.repeater.data.table.IColumn;
import org.apache.wicket.extensions.markup.html.repeater.data.table.PropertyColumn;
import org.apache.wicket.model.CompoundPropertyModel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.ResourceModel;
import org.apache.wicket.model.StringResourceModel;

public class SecurityQuestionsPanel extends DirectoryPanel<
        SecurityQuestionTO, SecurityQuestionTO, SecurityQuestionsProvider, SecurityQuestionRestClient> {

    private static final long serialVersionUID = 3323019773236588850L;

    public SecurityQuestionsPanel(final String id, final PageReference pageRef) {
        super(id, new Builder<SecurityQuestionTO, SecurityQuestionTO, SecurityQuestionRestClient>(null, pageRef) {

            private static final long serialVersionUID = 8769126634538601689L;

            @Override
            protected WizardMgtPanel<SecurityQuestionTO> newInstance(final String id, final boolean wizardInModal) {
                throw new UnsupportedOperationException();
            }
        }.disableCheckBoxes());

        this.addNewItemPanelBuilder(
                new AbstractModalPanelBuilder<SecurityQuestionTO>(new SecurityQuestionTO(), pageRef) {

            private static final long serialVersionUID = -6388405037134399367L;

            @Override
            public WizardModalPanel<SecurityQuestionTO> build(
                    final String id, final int index, final AjaxWizard.Mode mode) {
                final SecurityQuestionTO modelObject = newModelObject();
                return new SecurityQuestionsModalPanel(modal, modelObject, pageRef);
            }
        }, true);

        setFooterVisibility(true);
        modal.addSubmitButton();
        modal.size(Modal.Size.Large);
        initResultTable();

        MetaDataRoleAuthorizationStrategy.authorize(addAjaxLink, RENDER, StandardEntitlement.SECURITY_QUESTION_CREATE);
    }

    private SecurityQuestionsPanel(
            final String id,
            final Builder<SecurityQuestionTO, SecurityQuestionTO, SecurityQuestionRestClient> builder) {

        super(id, builder);
    }

    @Override
    protected SecurityQuestionsProvider dataProvider() {
        return new SecurityQuestionsProvider(rows);
    }

    @Override
    protected String paginatorRowsKey() {
        return Constants.PREF_SECURITY_QUESTIONS_PAGINATOR_ROWS;
    }

    @Override
    protected Collection<ActionLink.ActionType> getBulkActions() {
        return Collections.<ActionLink.ActionType>emptyList();
    }

    @Override
    protected List<IColumn<SecurityQuestionTO, String>> getColumns() {
        List<IColumn<SecurityQuestionTO, String>> columns = new ArrayList<>();

        columns.add(new KeyPropertyColumn<SecurityQuestionTO>(
                new StringResourceModel("key", this), "key", "key"));

        columns.add(new PropertyColumn<SecurityQuestionTO, String>(
                new StringResourceModel("content", this), "content", "content"));

        columns.add(new ActionColumn<SecurityQuestionTO, String>(new ResourceModel("actions")) {

            private static final long serialVersionUID = -8089193528195091515L;

            @Override
            public ActionLinksPanel<SecurityQuestionTO> getActions(
                    final String componentId, final IModel<SecurityQuestionTO> model) {

                ActionLinksPanel<SecurityQuestionTO> panel = ActionLinksPanel.<SecurityQuestionTO>builder().
                        add(new ActionLink<SecurityQuestionTO>() {

                            private static final long serialVersionUID = -3722207913631435501L;

                            @Override
                            public void onClick(final AjaxRequestTarget target, final SecurityQuestionTO ignore) {
                                send(SecurityQuestionsPanel.this, Broadcast.EXACT,
                                        new AjaxWizard.EditItemActionEvent<>(model.getObject(), target));
                            }
                        }, ActionLink.ActionType.EDIT, StandardEntitlement.SECURITY_QUESTION_UPDATE).
                        add(new ActionLink<SecurityQuestionTO>() {

                            private static final long serialVersionUID = -3722207913631435501L;

                            @Override
                            public void onClick(final AjaxRequestTarget target, final SecurityQuestionTO ignore) {
                                try {
                                    SyncopeConsoleSession.get().getService(
                                            SecurityQuestionService.class).delete(model.getObject().getKey());
                                    SyncopeConsoleSession.get().info(getString(Constants.OPERATION_SUCCEEDED));
                                    target.add(container);
                                } catch (Exception e) {
                                    LOG.error("While deleting {}", model.getObject(), e);
                                    SyncopeConsoleSession.get().error(StringUtils.isBlank(e.getMessage())
                                            ? e.getClass().getName() : e.getMessage());
                                }
                                ((BasePage) pageRef.getPage()).getNotificationPanel().refresh(target);
                            }
                        }, ActionLink.ActionType.DELETE, StandardEntitlement.TASK_DELETE).
                        build(componentId);

                return panel;
            }

            @Override
            public ActionLinksPanel<SecurityQuestionTO> getHeader(final String componentId) {
                final ActionLinksPanel.Builder<SecurityQuestionTO> panel = ActionLinksPanel.builder();

                return panel.add(new ActionLink<SecurityQuestionTO>() {

                    private static final long serialVersionUID = -1140254463922516111L;

                    @Override
                    public void onClick(final AjaxRequestTarget target, final SecurityQuestionTO ignore) {
                        if (target != null) {
                            target.add(container);
                        }
                    }
                }, ActionLink.ActionType.RELOAD).build(componentId);
            }
        });

        return columns;
    }

    protected final class SecurityQuestionsProvider extends DirectoryDataProvider<SecurityQuestionTO> {

        private static final long serialVersionUID = -185944053385660794L;

        private final SortableDataProviderComparator<SecurityQuestionTO> comparator;

        private SecurityQuestionsProvider(final int paginatorRows) {
            super(paginatorRows);
            comparator = new SortableDataProviderComparator<>(this);
        }

        @Override
        public Iterator<SecurityQuestionTO> iterator(final long first, final long count) {
            final List<SecurityQuestionTO> list = SyncopeConsoleSession.get().getService(SecurityQuestionService.class).
                    list();
            Collections.sort(list, comparator);
            return list.subList((int) first, (int) first + (int) count).iterator();
        }

        @Override
        public long size() {
            return SyncopeConsoleSession.get().getService(SecurityQuestionService.class).list().size();
        }

        @Override
        public IModel<SecurityQuestionTO> model(final SecurityQuestionTO object) {
            return new CompoundPropertyModel<>(object);
        }
    }
}
