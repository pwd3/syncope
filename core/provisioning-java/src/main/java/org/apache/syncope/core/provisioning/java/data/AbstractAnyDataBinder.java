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

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.syncope.common.lib.Attr;
import org.apache.syncope.common.lib.SyncopeClientCompositeException;
import org.apache.syncope.common.lib.SyncopeClientException;
import org.apache.syncope.common.lib.request.AnyCR;
import org.apache.syncope.common.lib.request.AnyUR;
import org.apache.syncope.common.lib.request.AttrPatch;
import org.apache.syncope.common.lib.request.StringPatchItem;
import org.apache.syncope.common.lib.to.AnyTO;
import org.apache.syncope.common.lib.to.MembershipTO;
import org.apache.syncope.common.lib.to.RelationshipTO;
import org.apache.syncope.common.lib.types.AttrSchemaType;
import org.apache.syncope.common.lib.types.ClientExceptionType;
import org.apache.syncope.common.lib.types.PatchOperation;
import org.apache.syncope.common.lib.types.ResourceOperation;
import org.apache.syncope.core.persistence.api.attrvalue.validation.InvalidPlainAttrValueException;
import org.apache.syncope.core.persistence.api.dao.AllowedSchemas;
import org.apache.syncope.core.persistence.api.dao.AnyObjectDAO;
import org.apache.syncope.core.persistence.api.dao.AnyTypeClassDAO;
import org.apache.syncope.core.persistence.api.dao.ExternalResourceDAO;
import org.apache.syncope.core.persistence.api.dao.GroupDAO;
import org.apache.syncope.core.persistence.api.dao.NotFoundException;
import org.apache.syncope.core.persistence.api.dao.PlainAttrDAO;
import org.apache.syncope.core.persistence.api.dao.PlainAttrValueDAO;
import org.apache.syncope.core.persistence.api.dao.PlainSchemaDAO;
import org.apache.syncope.core.persistence.api.dao.RealmDAO;
import org.apache.syncope.core.persistence.api.dao.RelationshipTypeDAO;
import org.apache.syncope.core.persistence.api.dao.UserDAO;
import org.apache.syncope.core.persistence.api.entity.Any;
import org.apache.syncope.core.persistence.api.entity.AnyTypeClass;
import org.apache.syncope.core.persistence.api.entity.AnyUtils;
import org.apache.syncope.core.persistence.api.entity.AnyUtilsFactory;
import org.apache.syncope.core.persistence.api.entity.DerSchema;
import org.apache.syncope.core.persistence.api.entity.Entity;
import org.apache.syncope.core.persistence.api.entity.EntityFactory;
import org.apache.syncope.core.persistence.api.entity.GroupablePlainAttr;
import org.apache.syncope.core.persistence.api.entity.GroupableRelatable;
import org.apache.syncope.core.persistence.api.entity.Membership;
import org.apache.syncope.core.persistence.api.entity.PlainAttr;
import org.apache.syncope.core.persistence.api.entity.PlainAttrValue;
import org.apache.syncope.core.persistence.api.entity.PlainSchema;
import org.apache.syncope.core.persistence.api.entity.Realm;
import org.apache.syncope.core.persistence.api.entity.VirSchema;
import org.apache.syncope.core.persistence.api.entity.anyobject.AnyObject;
import org.apache.syncope.core.persistence.api.entity.resource.ExternalResource;
import org.apache.syncope.core.persistence.api.entity.resource.MappingItem;
import org.apache.syncope.core.persistence.api.entity.resource.Provision;
import org.apache.syncope.core.provisioning.api.DerAttrHandler;
import org.apache.syncope.core.provisioning.api.IntAttrName;
import org.apache.syncope.core.provisioning.api.MappingManager;
import org.apache.syncope.core.provisioning.api.PropagationByResource;
import org.apache.syncope.core.provisioning.api.VirAttrHandler;
import org.apache.syncope.core.provisioning.java.IntAttrNameParser;
import org.apache.syncope.core.provisioning.java.jexl.JexlUtils;
import org.apache.syncope.core.provisioning.java.utils.MappingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

abstract class AbstractAnyDataBinder {

    protected static final Logger LOG = LoggerFactory.getLogger(AbstractAnyDataBinder.class);

    @Autowired
    protected RealmDAO realmDAO;

    @Autowired
    protected AnyTypeClassDAO anyTypeClassDAO;

    @Autowired
    protected AnyObjectDAO anyObjectDAO;

    @Autowired
    protected UserDAO userDAO;

    @Autowired
    protected GroupDAO groupDAO;

    @Autowired
    protected PlainSchemaDAO plainSchemaDAO;

    @Autowired
    protected PlainAttrDAO plainAttrDAO;

    @Autowired
    protected PlainAttrValueDAO plainAttrValueDAO;

    @Autowired
    protected ExternalResourceDAO resourceDAO;

    @Autowired
    protected RelationshipTypeDAO relationshipTypeDAO;

    @Autowired
    protected EntityFactory entityFactory;

    @Autowired
    protected AnyUtilsFactory anyUtilsFactory;

    @Autowired
    protected DerAttrHandler derAttrHandler;

    @Autowired
    protected VirAttrHandler virAttrHandler;

    @Autowired
    protected MappingManager mappingManager;

    @Autowired
    protected IntAttrNameParser intAttrNameParser;

    protected void setRealm(final Any<?> any, final AnyUR anyUR) {
        if (anyUR.getRealm() != null && StringUtils.isNotBlank(anyUR.getRealm().getValue())) {
            Realm newRealm = realmDAO.findByFullPath(anyUR.getRealm().getValue());
            if (newRealm == null) {
                LOG.debug("Invalid realm specified: {}, ignoring", anyUR.getRealm().getValue());
            } else {
                any.setRealm(newRealm);
            }
        }
    }

    protected PlainSchema getPlainSchema(final String schemaName) {
        PlainSchema schema = null;
        if (StringUtils.isNotBlank(schemaName)) {
            schema = plainSchemaDAO.find(schemaName);

            // safely ignore invalid schemas from Attr
            if (schema == null) {
                LOG.debug("Ignoring invalid schema {}", schemaName);
            } else if (schema.isReadonly()) {
                schema = null;
                LOG.debug("Ignoring readonly schema {}", schemaName);
            }
        }

        return schema;
    }

    private void fillAttr(
            final List<String> values,
            final AnyUtils anyUtils,
            final PlainSchema schema,
            final PlainAttr<?> attr,
            final SyncopeClientException invalidValues) {

        // if schema is multivalue, all values are considered for addition;
        // otherwise only the fist one - if provided - is considered
        List<String> valuesProvided = schema.isMultivalue()
                ? values
                : (values.isEmpty() || values.get(0) == null
                ? List.of()
                : List.of(values.get(0)));

        valuesProvided.forEach(value -> {
            if (StringUtils.isBlank(value)) {
                LOG.debug("Null value for {}, ignoring", schema.getKey());
            } else {
                try {
                    attr.add(value, anyUtils);
                } catch (InvalidPlainAttrValueException e) {
                    String valueToPrint = value.length() > 40
                            ? value.substring(0, 20) + "..."
                            : value;
                    LOG.warn("Invalid value for attribute " + schema.getKey() + ": " + valueToPrint, e);

                    invalidValues.getElements().add(schema.getKey() + ": " + valueToPrint + " - " + e.getMessage());
                }
            }
        });
    }

    private List<String> evaluateMandatoryCondition(final Provision provision, final Any<?> any) {
        List<String> missingAttrNames = new ArrayList<>();

        MappingUtils.getPropagationItems(provision.getMapping().getItems()).forEach(mapItem -> {
            IntAttrName intAttrName = null;
            try {
                intAttrName = intAttrNameParser.parse(mapItem.getIntAttrName(), provision.getAnyType().getKind());
            } catch (ParseException e) {
                LOG.error("Invalid intAttrName '{}', ignoring", mapItem.getIntAttrName(), e);
            }
            if (intAttrName != null && intAttrName.getSchema() != null) {
                AttrSchemaType schemaType = intAttrName.getSchema() instanceof PlainSchema
                        ? ((PlainSchema) intAttrName.getSchema()).getType()
                        : AttrSchemaType.String;

                Pair<AttrSchemaType, List<PlainAttrValue>> intValues =
                        mappingManager.getIntValues(provision, mapItem, intAttrName, schemaType, any);
                if (intValues.getRight().isEmpty()
                        && JexlUtils.evaluateMandatoryCondition(mapItem.getMandatoryCondition(), any)) {

                    missingAttrNames.add(mapItem.getIntAttrName());
                }
            }
        });

        return missingAttrNames;
    }

    private SyncopeClientException checkMandatoryOnResources(
            final Any<?> any, final Collection<? extends ExternalResource> resources) {

        SyncopeClientException reqValMissing = SyncopeClientException.build(ClientExceptionType.RequiredValuesMissing);

        resources.forEach(resource -> {
            Optional<? extends Provision> provision = resource.getProvision(any.getType());
            if (resource.isEnforceMandatoryCondition() && provision.isPresent()) {
                List<String> missingAttrNames = evaluateMandatoryCondition(provision.get(), any);
                if (!missingAttrNames.isEmpty()) {
                    LOG.error("Mandatory schemas {} not provided with values", missingAttrNames);

                    reqValMissing.getElements().addAll(missingAttrNames);
                }
            }
        });

        return reqValMissing;
    }

    private void checkMandatory(
            final PlainSchema schema,
            final PlainAttr<?> attr,
            final Any<?> any,
            final SyncopeClientException reqValMissing) {

        if (attr == null
                && !schema.isReadonly()
                && JexlUtils.evaluateMandatoryCondition(schema.getMandatoryCondition(), any)) {

            LOG.error("Mandatory schema " + schema.getKey() + " not provided with values");

            reqValMissing.getElements().add(schema.getKey());
        }
    }

    private SyncopeClientException checkMandatory(final Any<?> any, final AnyUtils anyUtils) {
        SyncopeClientException reqValMissing = SyncopeClientException.build(ClientExceptionType.RequiredValuesMissing);

        // Check if there is some mandatory schema defined for which no value has been provided
        AllowedSchemas<PlainSchema> allowedPlainSchemas = anyUtils.dao().findAllowedSchemas(any, PlainSchema.class);
        allowedPlainSchemas.getForSelf()
                .forEach(schema -> checkMandatory(schema, any.getPlainAttr(schema.getKey())
                .orElse(null), any, reqValMissing));
        if (any instanceof GroupableRelatable) {
            allowedPlainSchemas.getForMemberships().forEach((group, schemas) -> {
                GroupableRelatable<?, ?, ?, ?, ?> groupable = GroupableRelatable.class.cast(any);
                Membership<?> membership = groupable.getMembership(group.getKey()).orElse(null);
                schemas
                        .forEach(schema -> checkMandatory(schema, groupable.getPlainAttr(schema.getKey(), membership)
                        .orElse(null),
                        any, reqValMissing));
            });
        }

        return reqValMissing;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    protected void processAttrPatch(
            final Any any,
            final AttrPatch patch,
            final PlainSchema schema,
            final PlainAttr<?> attr,
            final AnyUtils anyUtils,
            final Collection<ExternalResource> resources,
            final PropagationByResource propByRes,
            final SyncopeClientException invalidValues) {

        switch (patch.getOperation()) {
            case ADD_REPLACE:
                // 1.1 remove values
                if (attr.getSchema().isUniqueConstraint()) {
                    if (attr.getUniqueValue() != null
                            && !patch.getAttr().getValues().isEmpty()
                            && !patch.getAttr().getValues().get(0).equals(attr.getUniqueValue().getValueAsString())) {

                        plainAttrValueDAO.deleteAll(attr, anyUtils);
                    }
                } else {
                    plainAttrValueDAO.deleteAll(attr, anyUtils);
                }

                // 1.2 add values
                List<String> valuesToBeAdded = patch.getAttr().getValues();
                if (!valuesToBeAdded.isEmpty()
                        && (!schema.isUniqueConstraint() || attr.getUniqueValue() == null
                        || !valuesToBeAdded.get(0).equals(attr.getUniqueValue().getValueAsString()))) {

                    fillAttr(valuesToBeAdded, anyUtils, schema, attr, invalidValues);
                }

                // if no values are in, the attribute can be safely removed
                if (attr.getValuesAsStrings().isEmpty()) {
                    plainAttrDAO.delete(attr);
                }
                break;

            case DELETE:
            default:
                any.remove(attr);
                plainAttrDAO.delete(attr);
        }

        resources.stream().
                filter(resource -> resource.getProvision(any.getType()).isPresent()
                && resource.getProvision(any.getType()).get().getMapping() != null).
                forEach(resource -> MappingUtils.getPropagationItems(
                resource.getProvision(any.getType()).get().getMapping().getItems()).stream().
                filter(item -> (schema.getKey().equals(item.getIntAttrName()))).
                forEach(item -> {
                    propByRes.add(ResourceOperation.UPDATE, resource.getKey());

                    if (item.isConnObjectKey() && !attr.getValuesAsStrings().isEmpty()) {
                        propByRes.addOldConnObjectKey(resource.getKey(), attr.getValuesAsStrings().get(0));
                    }
                }));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    protected PropagationByResource fill(
            final Any any,
            final AnyUR anyUR,
            final AnyUtils anyUtils,
            final SyncopeClientCompositeException scce) {

        PropagationByResource propByRes = new PropagationByResource();

        // 1. anyTypeClasses
        for (StringPatchItem patch : anyUR.getAuxClasses()) {
            AnyTypeClass auxClass = anyTypeClassDAO.find(patch.getValue());
            if (auxClass == null) {
                LOG.debug("Invalid " + AnyTypeClass.class.getSimpleName() + " {}, ignoring...", patch.getValue());
            } else {
                switch (patch.getOperation()) {
                    case ADD_REPLACE:
                        any.add(auxClass);
                        break;

                    case DELETE:
                    default:
                        any.getAuxClasses().remove(auxClass);
                }
            }
        }

        // 2. resources
        for (StringPatchItem patch : anyUR.getResources()) {
            ExternalResource resource = resourceDAO.find(patch.getValue());
            if (resource == null) {
                LOG.debug("Invalid " + ExternalResource.class.getSimpleName() + " {}, ignoring...", patch.getValue());
            } else {
                switch (patch.getOperation()) {
                    case ADD_REPLACE:
                        propByRes.add(ResourceOperation.CREATE, resource.getKey());
                        any.add(resource);
                        break;

                    case DELETE:
                    default:
                        propByRes.add(ResourceOperation.DELETE, resource.getKey());
                        any.getResources().remove(resource);
                }
            }
        }

        Set<ExternalResource> resources = anyUtils.getAllResources(any);
        SyncopeClientException invalidValues = SyncopeClientException.build(ClientExceptionType.InvalidValues);

        // 3. plain attributes
        anyUR.getPlainAttrs().stream().
                filter(patch -> patch.getAttr() != null).forEach(patch -> {
            PlainSchema schema = getPlainSchema(patch.getAttr().getSchema());
            if (schema == null) {
                LOG.debug("Invalid " + PlainSchema.class.getSimpleName() + " {}, ignoring...",
                        patch.getAttr().getSchema());
            } else {
                PlainAttr<?> attr = (PlainAttr<?>) any.getPlainAttr(schema.getKey()).orElse(null);
                if (attr == null) {
                    LOG.debug("No plain attribute found for schema {}", schema);

                    if (patch.getOperation() == PatchOperation.ADD_REPLACE) {
                        attr = anyUtils.newPlainAttr();
                        ((PlainAttr) attr).setOwner(any);
                        attr.setSchema(schema);
                        any.add(attr);

                    }
                }
                if (attr != null) {
                    processAttrPatch(any, patch, schema, attr, anyUtils, resources, propByRes, invalidValues);
                }
            }
        });
        if (!invalidValues.isEmpty()) {
            scce.addException(invalidValues);
        }

        SyncopeClientException requiredValuesMissing = checkMandatory(any, anyUtils);
        if (!requiredValuesMissing.isEmpty()) {
            scce.addException(requiredValuesMissing);
        }
        requiredValuesMissing = checkMandatoryOnResources(any, resources);
        if (!requiredValuesMissing.isEmpty()) {
            scce.addException(requiredValuesMissing);
        }

        return propByRes;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    protected void fill(
            final Any any,
            final AnyCR anyCR,
            final AnyUtils anyUtils,
            final SyncopeClientCompositeException scce) {

        // 0. aux classes
        any.getAuxClasses().clear();
        anyCR.getAuxClasses().stream().
                map(className -> anyTypeClassDAO.find(className)).
                forEachOrdered(auxClass -> {
                    if (auxClass == null) {
                        LOG.debug("Invalid " + AnyTypeClass.class.getSimpleName() + " {}, ignoring...", auxClass);
                    } else {
                        any.add(auxClass);
                    }
                });

        // 1. attributes
        SyncopeClientException invalidValues = SyncopeClientException.build(ClientExceptionType.InvalidValues);

        anyCR.getPlainAttrs().stream().
                filter(attrTO -> !attrTO.getValues().isEmpty()).
                forEach(attrTO -> {
                    PlainSchema schema = getPlainSchema(attrTO.getSchema());
                    if (schema != null) {
                        PlainAttr<?> attr = (PlainAttr<?>) any.getPlainAttr(schema.getKey()).orElse(null);
                        if (attr == null) {
                            attr = anyUtils.newPlainAttr();
                            ((PlainAttr) attr).setOwner(any);
                            attr.setSchema(schema);
                        }
                        fillAttr(attrTO.getValues(), anyUtils, schema, attr, invalidValues);

                        if (attr.getValuesAsStrings().isEmpty()) {
                            attr.setOwner(null);
                        } else {
                            any.add(attr);
                        }
                    }
                });

        if (!invalidValues.isEmpty()) {
            scce.addException(invalidValues);
        }

        SyncopeClientException requiredValuesMissing = checkMandatory(any, anyUtils);
        if (!requiredValuesMissing.isEmpty()) {
            scce.addException(requiredValuesMissing);
        }

        // 2. resources
        anyCR.getResources().forEach(resourceKey -> {
            ExternalResource resource = resourceDAO.find(resourceKey);
            if (resource == null) {
                LOG.debug("Invalid " + ExternalResource.class.getSimpleName() + " {}, ignoring...", resourceKey);
            } else {
                any.add(resource);
            }
        });

        requiredValuesMissing = checkMandatoryOnResources(any, anyUtils.getAllResources(any));
        if (!requiredValuesMissing.isEmpty()) {
            scce.addException(requiredValuesMissing);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    protected void fill(
            final Any any,
            final Membership membership,
            final MembershipTO membershipTO,
            final AnyUtils anyUtils,
            final SyncopeClientCompositeException scce) {

        SyncopeClientException invalidValues = SyncopeClientException.build(ClientExceptionType.InvalidValues);

        membershipTO.getPlainAttrs().stream().
                filter(attrTO -> !attrTO.getValues().isEmpty()).
                forEach(attrTO -> {
                    PlainSchema schema = getPlainSchema(attrTO.getSchema());
                    if (schema != null) {
                        GroupablePlainAttr attr = (GroupablePlainAttr) GroupableRelatable.class.cast(any).
                                getPlainAttr(schema.getKey(), membership).orElse(null);
                        if (attr == null) {
                            attr = anyUtils.newPlainAttr();
                            attr.setOwner(any);
                            attr.setMembership(membership);
                            attr.setSchema(schema);
                        }
                        fillAttr(attrTO.getValues(), anyUtils, schema, attr, invalidValues);

                        if (attr.getValuesAsStrings().isEmpty()) {
                            attr.setOwner(null);
                        } else {
                            any.add(attr);
                        }
                    }
                });

        if (!invalidValues.isEmpty()) {
            scce.addException(invalidValues);
        }
    }

    protected void fillTO(
            final AnyTO anyTO,
            final String realmFullPath,
            final Collection<? extends AnyTypeClass> auxClasses,
            final Collection<? extends PlainAttr<?>> plainAttrs,
            final Map<DerSchema, String> derAttrs,
            final Map<VirSchema, List<String>> virAttrs,
            final Collection<? extends ExternalResource> resources,
            final boolean details) {

        anyTO.setRealm(realmFullPath);

        anyTO.getAuxClasses().addAll(auxClasses.stream().map(Entity::getKey).collect(Collectors.toList()));

        plainAttrs
                .forEach(plainAttr -> anyTO.getPlainAttrs().add(new Attr.Builder(plainAttr.getSchema().getKey())
                .values(plainAttr.getValuesAsStrings()).build()));

        derAttrs.forEach((schema, value) -> anyTO.getDerAttrs()
                .add(new Attr.Builder(schema.getKey()).value(value).build()));

        virAttrs.forEach((schema, values) -> anyTO.getVirAttrs()
                .add(new Attr.Builder(schema.getKey()).values(values).build()));

        anyTO.getResources().addAll(resources.stream().map(Entity::getKey).collect(Collectors.toSet()));
    }

    protected RelationshipTO getRelationshipTO(final String relationshipType, final AnyObject otherEnd) {
        return new RelationshipTO.Builder().
                type(relationshipType).otherEnd(otherEnd.getType().getKey(), otherEnd.getKey(), otherEnd.getName()).
                build();
    }

    protected MembershipTO getMembershipTO(
            final Collection<? extends PlainAttr<?>> plainAttrs,
            final Map<DerSchema, String> derAttrs,
            final Map<VirSchema, List<String>> virAttrs,
            final Membership<? extends Any<?>> membership) {

        MembershipTO membershipTO = new MembershipTO.Builder(membership.getRightEnd().getKey())
                .groupName(membership.getRightEnd().getName())
                .build();

        plainAttrs.forEach(plainAttr -> membershipTO.getPlainAttrs()
                .add(new Attr.Builder(plainAttr.getSchema().getKey())
                        .values(plainAttr.getValuesAsStrings()).
                        build()));

        derAttrs.forEach((schema, value) -> membershipTO.getDerAttrs().add(new Attr.Builder(schema.getKey()).
                value(value).
                build()));

        virAttrs.forEach((schema, values) -> membershipTO.getVirAttrs().add(new Attr.Builder(schema.getKey()).
                values(values).
                build()));

        return membershipTO;
    }

    protected Map<String, String> getConnObjectKeys(final Any<?> any, final AnyUtils anyUtils) {
        Map<String, String> connObjectKeys = new HashMap<>();

        anyUtils.getAllResources(any).
                forEach(resource -> resource.getProvision(any.getType()).
                filter(provision -> provision.getMapping() != null).
                ifPresent(provision -> {
                    MappingItem connObjectKeyItem = MappingUtils.getConnObjectKeyItem(provision).
                            orElseThrow(() -> new NotFoundException(
                            "ConnObjectKey mapping for " + any.getType().getKey() + " " + any.getKey()
                            + " on resource '" + resource.getKey() + "'"));

                    mappingManager.getConnObjectKeyValue(any, provision).
                            ifPresent(value -> connObjectKeys.put(resource.getKey(), value));
                }));

        return connObjectKeys;
    }
}
