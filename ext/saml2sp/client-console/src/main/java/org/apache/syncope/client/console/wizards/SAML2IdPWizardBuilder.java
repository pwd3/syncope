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
package org.apache.syncope.client.console.wizards;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.syncope.client.console.SyncopeConsoleSession;
import org.apache.syncope.client.console.SyncopeWebApplication;
import org.apache.syncope.client.console.panels.SAML2IdPsDirectoryPanel;
import org.apache.syncope.client.console.rest.ImplementationRestClient;
import org.apache.syncope.client.console.rest.SAML2IdPsRestClient;
import org.apache.syncope.client.console.wizards.resources.JEXLTransformersTogglePanel;
import org.apache.syncope.client.console.wizards.resources.ItemTransformersTogglePanel;
import org.apache.syncope.client.console.wizards.resources.SAML2IdPMappingPanel;
import org.apache.syncope.client.ui.commons.markup.html.form.AjaxCheckBoxPanel;
import org.apache.syncope.client.ui.commons.markup.html.form.AjaxDropDownChoicePanel;
import org.apache.syncope.client.ui.commons.markup.html.form.AjaxPalettePanel;
import org.apache.syncope.client.ui.commons.markup.html.form.AjaxTextFieldPanel;
import org.apache.syncope.client.ui.commons.markup.html.form.FieldPanel;
import org.apache.syncope.client.ui.commons.wizards.AjaxWizardBuilder;
import org.apache.syncope.common.lib.to.EntityTO;
import org.apache.syncope.common.lib.to.ItemTO;
import org.apache.syncope.common.lib.to.SAML2IdPTO;
import org.apache.syncope.common.lib.types.SAML2BindingType;
import org.apache.syncope.common.lib.types.SAML2SPImplementationType;
import org.apache.wicket.Component;
import org.apache.wicket.PageReference;
import org.apache.wicket.extensions.wizard.WizardModel;
import org.apache.wicket.extensions.wizard.WizardStep;
import org.apache.wicket.markup.html.list.ListItem;
import org.apache.wicket.markup.html.list.ListView;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.LoadableDetachableModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.model.PropertyModel;
import org.apache.wicket.model.StringResourceModel;
import org.apache.wicket.model.util.ListModel;

public class SAML2IdPWizardBuilder extends AjaxWizardBuilder<SAML2IdPTO> {

    private static final long serialVersionUID = 5952696913893950460L;

    private final SAML2IdPsRestClient restClient = new SAML2IdPsRestClient();

    private final ImplementationRestClient implRestClient = new ImplementationRestClient();

    private final SAML2IdPsDirectoryPanel directoryPanel;

    private final IModel<List<String>> idpActions = new LoadableDetachableModel<List<String>>() {

        private static final long serialVersionUID = 5275935387613157437L;

        @Override
        protected List<String> load() {
            return implRestClient.list(SAML2SPImplementationType.IDP_ACTIONS).stream().
                    map(EntityTO::getKey).sorted().collect(Collectors.toList());
        }
    };

    private final IModel<List<String>> requestedAuthnContextProviders = new LoadableDetachableModel<List<String>>() {

        private static final long serialVersionUID = 4659376149825914247L;

        @Override
        protected List<String> load() {
            return implRestClient.list(SAML2SPImplementationType.REQUESTED_AUTHN_CONTEXT_PROVIDER).stream().
                    map(EntityTO::getKey).sorted().collect(Collectors.toList());
        }
    };

    public SAML2IdPWizardBuilder(
            final SAML2IdPsDirectoryPanel directoryPanel, final SAML2IdPTO idpTO, final PageReference pageRef) {

        super(idpTO, pageRef);
        this.directoryPanel = directoryPanel;
    }

    @Override
    protected WizardModel buildModelSteps(final SAML2IdPTO modelObject, final WizardModel wizardModel) {
        wizardModel.add(new IdP(modelObject));

        Mapping mapping = new Mapping(modelObject);
        mapping.setOutputMarkupId(true);

        ItemTransformersTogglePanel mapItemTransformers = new ItemTransformersTogglePanel(mapping, pageRef);
        addOuterObject(mapItemTransformers);
        JEXLTransformersTogglePanel jexlTransformers = new JEXLTransformersTogglePanel(mapping, pageRef);
        addOuterObject(jexlTransformers);
        mapping.add(new SAML2IdPMappingPanel("mapping", modelObject, mapItemTransformers, jexlTransformers));

        wizardModel.add(mapping);

        return wizardModel;
    }

    @Override
    protected long getMaxWaitTimeInSeconds() {
        return SyncopeWebApplication.get().getMaxWaitTimeInSeconds();
    }

    @Override
    protected void sendError(final String message) {
        SyncopeConsoleSession.get().error(message);
    }

    @Override
    protected void sendWarning(final String message) {
        SyncopeConsoleSession.get().warn(message);
    }

    @Override
    protected Future<Pair<Serializable, Serializable>> execute(
            final Callable<Pair<Serializable, Serializable>> future) {
        return SyncopeConsoleSession.get().execute(future);
    }

    private final class IdP extends WizardStep {

        private static final long serialVersionUID = 854012593185195024L;

        IdP(final SAML2IdPTO idpTO) {
            super(StringUtils.EMPTY, StringUtils.EMPTY);

            List<Component> fields = new ArrayList<>();

            FieldPanel<String> name = new AjaxTextFieldPanel(
                    "field", "name", new PropertyModel<>(idpTO, "name"), false);
            name.setRequired(true);
            fields.add(name);

            AjaxCheckBoxPanel createUnmatching = new AjaxCheckBoxPanel(
                    "field", "createUnmatching", new PropertyModel<>(idpTO, "createUnmatching"), false);
            fields.add(createUnmatching);

            AjaxCheckBoxPanel selfRegUnmatching = new AjaxCheckBoxPanel(
                    "field", "selfRegUnmatching", new PropertyModel<>(idpTO, "selfRegUnmatching"), false);
            fields.add(selfRegUnmatching);

            AjaxCheckBoxPanel updateMatching = new AjaxCheckBoxPanel(
                    "field", "updateMatching", new PropertyModel<>(idpTO, "updateMatching"), false);
            fields.add(updateMatching);

            AjaxCheckBoxPanel useDeflateEncoding = new AjaxCheckBoxPanel(
                    "field", "useDeflateEncoding", new PropertyModel<>(idpTO, "useDeflateEncoding"), false);
            fields.add(useDeflateEncoding);

            AjaxCheckBoxPanel supportUnsolicited = new AjaxCheckBoxPanel(
                    "field", "supportUnsolicited", new PropertyModel<>(idpTO, "supportUnsolicited"), false);
            fields.add(supportUnsolicited);

            AjaxDropDownChoicePanel<SAML2BindingType> bindingType =
                    new AjaxDropDownChoicePanel<>("field", "bindingType",
                            new PropertyModel<>(idpTO, "bindingType"), false);
            bindingType.setChoices(List.of(SAML2BindingType.values()));
            fields.add(bindingType);

            AjaxTextFieldPanel requestedAuthnContextProvider = new AjaxTextFieldPanel(
                    "field", "requestedAuthnContextProvider",
                    new PropertyModel<>(idpTO, "requestedAuthnContextProvider"));
            requestedAuthnContextProvider.setChoices(
                    requestedAuthnContextProviders.getObject());
            fields.add(requestedAuthnContextProvider);

            AjaxPalettePanel<String> actions = new AjaxPalettePanel.Builder<String>().
                    setAllowMoveAll(true).setAllowOrder(true).
                    setName(new StringResourceModel("actions", directoryPanel).getString()).
                    build("field",
                            new PropertyModel<>(idpTO, "actions"),
                            new ListModel<>(idpActions.getObject()));
            actions.setOutputMarkupId(true);
            fields.add(actions);

            add(new ListView<Component>("fields", fields) {

                private static final long serialVersionUID = -9180479401817023838L;

                @Override
                protected void populateItem(final ListItem<Component> item) {
                    item.add(item.getModelObject());
                }

            });
        }
    }

    /**
     * Mapping definition step.
     */
    private static final class Mapping extends WizardStep {

        private static final long serialVersionUID = 3454904947720856253L;

        Mapping(final SAML2IdPTO item) {
            setTitleModel(Model.of("Mapping"));
            setSummaryModel(Model.of(StringUtils.EMPTY));
        }
    }

    @Override
    protected Serializable onApplyInternal(final SAML2IdPTO modelObject) {
        long connObjectKeyCount = modelObject.getItems().stream().filter(ItemTO::isConnObjectKey).count();
        if (connObjectKeyCount != 1) {
            throw new IllegalArgumentException(
                    new StringResourceModel("connObjectKeyValidation", directoryPanel).getString());
        }

        restClient.update(modelObject);
        return modelObject;
    }

}
