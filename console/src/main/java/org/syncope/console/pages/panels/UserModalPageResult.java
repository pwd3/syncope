/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.syncope.console.pages.panels;

import java.util.ArrayList;
import java.util.List;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.markup.html.AjaxLink;
import org.apache.wicket.ajax.markup.html.navigation.paging.AjaxPagingNavigator;
import org.apache.wicket.extensions.ajax.markup.html.IndicatingAjaxLink;
import org.apache.wicket.extensions.ajax.markup.html.modal.ModalWindow;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.image.Image;
import org.apache.wicket.markup.html.list.ListItem;
import org.apache.wicket.markup.html.list.PageableListView;
import org.apache.wicket.markup.html.panel.Fragment;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.request.resource.ContextRelativeResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.syncope.client.to.PropagationTO;
import org.syncope.client.to.UserTO;
import org.syncope.console.pages.UserModalPage;
import org.syncope.types.PropagationTaskExecStatus;

public class UserModalPageResult extends Panel {

    private static final long serialVersionUID = 2646115294319713723L;

    /**
     * Logger.
     */
    private static final Logger LOG =
            LoggerFactory.getLogger(UserModalPageResult.class);

    private static final int PROPAGATION_RESULT_PAGINATOR_ROWS = 7;

    public UserModalPageResult(
            final String id,
            final ModalWindow window,
            final UserModalPage.Mode mode,
            final UserTO userTO) {

        super(id);

        final WebMarkupContainer container =
                new WebMarkupContainer("container");
        container.setOutputMarkupId(true);
        add(container);

        final Fragment fragment = new Fragment("userModalResultFrag",
                mode == UserModalPage.Mode.SELF ? "userModalSelfResultFrag"
                : "userModalPropagationResultFrag", this);
        fragment.setOutputMarkupId(true);
        container.add(fragment);

        if (mode == UserModalPage.Mode.ADMIN) {

            // add Syncope propagation status
            PropagationTO syncope = new PropagationTO();
            syncope.setResourceName("Syncope");
            syncope.setStatus(PropagationTaskExecStatus.SUCCESS);

            List<PropagationTO> propagations = new ArrayList<PropagationTO>();
            propagations.add(syncope);
            propagations.addAll(userTO.getPropagationTOs());

            fragment.add(new Label("userInfo", userTO.getUsername()));

            final PageableListView<PropagationTO> propagationStatus =
                    new PageableListView<PropagationTO>(
                    "propagationResults",
                    propagations,
                    PROPAGATION_RESULT_PAGINATOR_ROWS) {

                        private static final long serialVersionUID =
                                -1020475259727720708L;

                        @Override
                        protected void populateItem(
                                final ListItem item) {
                            final PropagationTO propagation =
                                    (PropagationTO) item.getDefaultModelObject();

                            item.add(new Label("resourceName",
                                    propagation.getResourceName()));

                            item.add(new Label("propagation",
                                    propagation.getStatus() != null
                                    ? propagation.getStatus().toString()
                                    : "UNDEFINED"));

                            item.add(new Image("status",
                                    propagation.getStatus() != null
                                    && propagation.getStatus().isSuccessful()
                                    ? new ContextRelativeResource(
                                    "img/success.png")
                                    : new ContextRelativeResource(
                                    "img/warning.png")));
                        }
                    };
            fragment.add(propagationStatus);
            fragment.add(
                    new AjaxPagingNavigator("navigator", propagationStatus));
        }

        final AjaxLink close = new IndicatingAjaxLink("close") {

            private static final long serialVersionUID =
                    -7978723352517770644L;

            @Override
            public void onClick(final AjaxRequestTarget target) {
                window.close(target);
            }
        };
        container.add(close);
    }
}
