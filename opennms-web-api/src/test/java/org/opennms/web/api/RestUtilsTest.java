/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2026 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2026 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.web.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;

import org.junit.Test;
import org.opennms.netmgt.model.OnmsAssetRecord;
import org.opennms.netmgt.model.OnmsCategory;
import org.opennms.netmgt.model.OnmsHwEntity;
import org.opennms.netmgt.model.OnmsIpInterface;
import org.opennms.netmgt.model.OnmsMonitoredService;
import org.opennms.netmgt.model.OnmsNode;
import org.opennms.netmgt.model.OnmsNode.NodeType;
import org.opennms.netmgt.model.OnmsSnmpInterface;
import org.opennms.netmgt.provision.persist.requisition.Requisition;
import org.opennms.netmgt.provision.persist.requisition.RequisitionNode;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.PropertyAccessorFactory;

public class RestUtilsTest {

    private static final Set<String> NONE = Collections.emptySet();

    private static boolean isProtected(final String key) {
        return RestUtils.isProtectedProperty(key, RestUtils.PROTECTED_NODE_PROPERTIES);
    }

    private static boolean isProtected(final Class<?> beanType, final String key) {
        return RestUtils.isProtectedProperty(beanType, key, NONE);
    }

    @Test
    public void protectsExactPropertyNames() {
        assertTrue(isProtected("id"));
        assertTrue(isProtected("nodeId"));
        assertTrue(isProtected("authorizedGroups"));
        assertTrue(isProtected("foreignSource"));
        assertTrue(isProtected("foreignId"));
        assertTrue(isProtected("type"));
    }

    /** Request keys are normalized before binding, so separator forms must be covered. */
    @Test
    public void protectsSeparatorAndCaseVariants() {
        assertTrue(isProtected("foreign_source"));
        assertTrue(isProtected("foreign-source"));
        assertTrue(isProtected("Foreign_Source"));
        assertTrue(isProtected("FOREIGNSOURCE"));
        assertTrue(isProtected("Type"));
        assertTrue(isProtected("authorized_groups"));
        assertTrue(isProtected("node_id"));
    }

    /** Spring's BeanWrapper resolves nested and indexed paths, which must not reach a protected property. */
    @Test
    public void protectsNestedAndIndexedPaths() {
        assertTrue(isProtected("assetRecord.node.foreignSource"));
        assertTrue(isProtected("asset_record.node.foreign_source"));
        assertTrue(isProtected("node.foreignId"));
        assertTrue(isProtected("assetRecord.id"));
        assertTrue(isProtected("categories[0].authorizedGroups"));
    }

    /** A closing bracket also ends a path segment; splitting on '[' alone left this unmatched. */
    @Test
    public void protectsKeyedPaths() {
        assertTrue(isProtected("attributes[foreignSource]"));
        assertTrue(isProtected("attributes[foreign_source]"));
        assertTrue(RestUtils.containsProperty(params("attributes[ifIndex]"), "ifIndex"));
    }

    @Test
    public void allowsOrdinaryProperties() {
        assertFalse(isProtected("sysContact"));
        assertFalse(isProtected("sys_contact"));
        assertFalse(isProtected("label"));
        assertFalse(isProtected("assetRecord.manufacturer"));
        assertFalse(isProtected("asset_record.operating_system"));
        assertFalse(isProtected("description"));
    }

    /** Without an additional set only the globally immutable properties are protected by name. */
    @Test
    public void nodePropertiesAreNotGloballyProtectedByName() {
        assertFalse(RestUtils.isProtectedProperty("foreignSource", NONE));
        assertTrue(RestUtils.isProtectedProperty("id", NONE));
        assertTrue(RestUtils.isProtectedProperty("authorized_groups", NONE));
    }

    @Test
    public void containsPropertyMatchesVariantsAndPaths() {
        assertTrue(RestUtils.containsProperty(params("ifIndex"), "ifIndex"));
        assertTrue(RestUtils.containsProperty(params("if_index"), "ifIndex"));
        assertTrue(RestUtils.containsProperty(params("IfIndex"), "ifIndex"));
        assertTrue(RestUtils.containsProperty(params("node.ifIndex"), "ifIndex"));
        assertTrue(RestUtils.containsProperty(params("Name"), "name"));
        assertTrue(RestUtils.containsProperty(params("ip_address"), "ipAddress"));

        assertFalse(RestUtils.containsProperty(params("ifDescr"), "ifIndex"));
        assertFalse(RestUtils.containsProperty(params("description"), "name"));
    }

    /**
     * The type-aware check does not need the call site to nominate the node properties: it
     * resolves the path and finds that it lands on an OnmsNode.
     */
    @Test
    public void protectsNodePropertiesWithoutCallSiteOptIn() {
        assertTrue(isProtected(OnmsNode.class, "foreignSource"));
        assertTrue(isProtected(OnmsNode.class, "foreign_source"));
        assertTrue(isProtected(OnmsNode.class, "foreignId"));
        assertTrue(isProtected(OnmsNode.class, "type"));
        assertTrue(isProtected(OnmsNode.class, "Type"));
        assertTrue(isProtected(OnmsNode.class, "assetRecord.node.foreign_source"));
        assertTrue(isProtected(OnmsNode.class, "asset_record.node.foreign_source"));
    }

    /** Every entity that carries a back-reference to its node is a route to the node's fields. */
    @Test
    public void protectsNodePropertiesReachedFromChildEntities() {
        assertTrue(isProtected(OnmsIpInterface.class, "node.foreign_source"));
        assertTrue(isProtected(OnmsIpInterface.class, "node.foreignSource"));
        assertTrue(isProtected(OnmsIpInterface.class, "node.foreign_id"));
        assertTrue(isProtected(OnmsIpInterface.class, "node.type"));
        assertTrue(isProtected(OnmsSnmpInterface.class, "node.foreign_source"));
        assertTrue(isProtected(OnmsAssetRecord.class, "node.foreign_source"));
        assertTrue(isProtected(OnmsHwEntity.class, "node.foreign_source"));
        assertTrue(isProtected(OnmsMonitoredService.class, "ip_interface.node.foreign_source"));
        assertTrue(isProtected(OnmsMonitoredService.class, "ipInterface.node.foreignSource"));
    }

    /**
     * A request against a child entity has no business writing the node at all, however harmless
     * the field looks; that is the traversal this class of bug rides on.
     */
    @Test
    public void refusesAnyTraversalFromChildEntityIntoNode() {
        assertTrue(isProtected(OnmsIpInterface.class, "node.label"));
        assertTrue(isProtected(OnmsIpInterface.class, "node.sysContact"));
        assertTrue(isProtected(OnmsSnmpInterface.class, "node.assetRecord.manufacturer"));
        assertTrue(isProtected(OnmsMonitoredService.class, "ipInterface.node.label"));
        assertTrue(isProtected(OnmsIpInterface.class, "snmpInterface.node.foreignSource"));
    }

    /**
     * The node relation is bidirectional in both directions: a node reaches its parent node and
     * its own back-reference through the asset record, so the hop itself is refused rather than
     * just the protected leaves behind it.
     */
    @Test
    public void refusesTraversalIntoAnyNodeEvenFromANodeRequest() {
        assertTrue(isProtected(OnmsNode.class, "parent.foreignSource"));
        assertTrue(isProtected(OnmsNode.class, "parent.foreign_source"));
        assertTrue(isProtected(OnmsNode.class, "parent.label"));
        assertTrue(isProtected(OnmsNode.class, "assetRecord.node.label"));
        assertTrue(isProtected(OnmsNode.class, "asset_record.node.label"));
    }

    /** Primary keys are refused wherever they turn up, including a nested owner. */
    @Test
    public void protectsPrimaryKeys() {
        assertTrue(isProtected(OnmsNode.class, "id"));
        assertTrue(isProtected(OnmsIpInterface.class, "id"));
        assertTrue(isProtected(OnmsNode.class, "assetRecord.id"));
        assertTrue(isProtected(OnmsCategory.class, "authorizedGroups"));
    }

    /**
     * Over-blocking is the other failure mode: these are ordinary writable fields whose names
     * merely resemble a protected one.
     */
    @Test
    public void allowsLegitimatePropertiesOnEntities() {
        assertFalse(isProtected(OnmsNode.class, "sysObjectId"));
        assertFalse(isProtected(OnmsNode.class, "sys_object_id"));
        assertFalse(isProtected(OnmsNode.class, "label"));
        assertFalse(isProtected(OnmsNode.class, "sysContact"));
        assertFalse(isProtected(OnmsSnmpInterface.class, "ifType"));
        assertFalse(isProtected(OnmsSnmpInterface.class, "if_type"));
        assertFalse(isProtected(OnmsSnmpInterface.class, "ifIndex"));
        assertFalse(isProtected(OnmsIpInterface.class, "ipHostName"));
        assertFalse(isProtected(OnmsIpInterface.class, "isManaged"));
        assertFalse(isProtected(OnmsMonitoredService.class, "status"));
        assertFalse(isProtected(OnmsHwEntity.class, "entPhysicalName"));
        assertFalse(isProtected(OnmsCategory.class, "description"));
    }

    /** Writing down into a node's own children is a documented, tested feature. */
    @Test
    public void allowsNestedWritesIntoOwnChildren() {
        assertFalse(isProtected(OnmsNode.class, "assetRecord.manufacturer"));
        assertFalse(isProtected(OnmsNode.class, "asset_record.operating_system"));
        assertFalse(isProtected(OnmsNode.class, "assetRecord.geolocation.city"));
        assertFalse(isProtected(OnmsIpInterface.class, "snmpInterface.ifAlias"));
    }

    /**
     * Requisition beans have their own foreignSource/foreignId and the requisition import binds
     * onto them; the node policy must not leak across.
     */
    @Test
    public void doesNotProtectRequisitionOwnershipFields() {
        assertFalse(isProtected(Requisition.class, "foreignSource"));
        assertFalse(isProtected(Requisition.class, "foreign-source"));
        assertFalse(isProtected(RequisitionNode.class, "foreignId"));
        assertFalse(isProtected(RequisitionNode.class, "nodeLabel"));
        assertFalse(isProtected(RequisitionNode.class, "parentForeignSource"));
    }

    @Test
    public void bindsLegitimatePropertiesAndSkipsProtectedOnes() {
        final OnmsNode node = node();
        final MultivaluedMap<String,String> params = new MultivaluedHashMap<>();
        params.putSingle("sys_contact", "LegitContact");
        params.putSingle("asset_record.manufacturer", "Apple");
        params.putSingle("foreign_source", "AttackerReq");
        params.putSingle("foreign_id", "999");
        params.putSingle("Type", "D");
        params.putSingle("asset_record.node.foreign_source", "NestedReq");
        RestUtils.setBeanProperties(node, params);

        assertEquals("LegitContact", node.getSysContact());
        assertEquals("Apple", node.getAssetRecord().getManufacturer());
        assertEquals("JUnit", node.getForeignSource());
        assertEquals("TestMachine1", node.getForeignId());
        assertEquals(NodeType.ACTIVE, node.getType());
    }

    @Test
    public void ipInterfaceUpdateCannotTakeOverTheNode() {
        final OnmsNode node = node();
        final OnmsIpInterface iface = new OnmsIpInterface();
        iface.setNode(node);
        final MultivaluedMap<String,String> params = new MultivaluedHashMap<>();
        params.putSingle("is_managed", "M");
        params.putSingle("node.foreign_source", "AttackerReq");
        params.putSingle("node.foreignSource", "AttackerReq");
        params.putSingle("node.foreign_id", "999");
        params.putSingle("node.label", "Hijacked");
        RestUtils.setBeanProperties(iface, params);

        assertEquals("M", iface.getIsManaged());
        assertEquals("JUnit", node.getForeignSource());
        assertEquals("TestMachine1", node.getForeignId());
        assertEquals("TestMachine1", node.getLabel());
    }

    @Test
    public void snmpInterfaceUpdateCannotTakeOverTheNode() {
        final OnmsNode node = node();
        final OnmsSnmpInterface snmpIface = new OnmsSnmpInterface();
        snmpIface.setNode(node);
        final MultivaluedMap<String,String> params = new MultivaluedHashMap<>();
        params.putSingle("if_alias", "LegitAlias");
        params.putSingle("node.foreign_source", "AttackerReq");
        RestUtils.setBeanProperties(snmpIface, params);

        assertEquals("LegitAlias", snmpIface.getIfAlias());
        assertEquals("JUnit", node.getForeignSource());
    }

    @Test
    public void monitoredServiceUpdateCannotTakeOverTheNode() {
        final OnmsNode node = node();
        final OnmsIpInterface iface = new OnmsIpInterface();
        iface.setNode(node);
        final OnmsMonitoredService service = new OnmsMonitoredService();
        service.setIpInterface(iface);
        final MultivaluedMap<String,String> params = new MultivaluedHashMap<>();
        params.putSingle("status", "A");
        params.putSingle("ip_interface.node.foreign_source", "AttackerReq");
        params.putSingle("ipInterface.node.foreignSource", "AttackerReq");
        RestUtils.setBeanProperties(service, params);

        assertEquals("A", service.getStatus());
        assertEquals("JUnit", node.getForeignSource());
    }

    @Test
    public void assetRecordUpdateCannotTakeOverTheNode() {
        final OnmsNode node = node();
        final OnmsAssetRecord assetRecord = node.getAssetRecord();
        final MultivaluedMap<String,String> params = new MultivaluedHashMap<>();
        params.putSingle("description", "LegitAsset");
        params.putSingle("node.foreign_source", "AttackerReq");
        params.putSingle("node.foreignSource", "AttackerReq");
        RestUtils.setBeanProperties(assetRecord, params);

        assertEquals("LegitAsset", assetRecord.getDescription());
        assertEquals("JUnit", node.getForeignSource());
    }

    @Test
    public void hardwareEntityUpdateCannotTakeOverTheNode() {
        final OnmsNode node = node();
        final OnmsHwEntity entity = new OnmsHwEntity();
        entity.setNode(node);
        final MultivaluedMap<String,String> params = new MultivaluedHashMap<>();
        params.putSingle("ent_physical_name", "LegitName");
        params.putSingle("node.foreign_source", "AttackerReq");
        params.putSingle("node_id", "99");
        RestUtils.setBeanProperties(entity, params);

        assertEquals("LegitName", entity.getEntPhysicalName());
        assertEquals("JUnit", node.getForeignSource());
    }

    /** A missing or null-valued intermediate must not produce an error, just no write. */
    @Test
    public void toleratesUnresolvablePaths() {
        final OnmsIpInterface iface = new OnmsIpInterface();
        final MultivaluedMap<String,String> params = new MultivaluedHashMap<>();
        params.putSingle("node.foreign_source", "AttackerReq");
        params.putSingle("nosuchthing.foreign_source", "AttackerReq");
        params.putSingle("no_such_property", "value");
        params.putSingle("", "value");
        RestUtils.setBeanProperties(iface, params);
        assertNull(iface.getNode());
    }

    /**
     * Enumeration is what let the earlier name denylist rot, so assert the property instead:
     * bind every combination through a bare BeanWrapper, keep the ones that actually reached a
     * protected field of the node, and require the guard to have refused each of them. This
     * fails if a new entity relationship opens a route, without anyone updating a list.
     */
    @Test
    public void noRequestKeyReachesProtectedNodeFieldsThroughAnyPath() {
        final List<String> leaves = Arrays.asList("foreignSource", "foreign_source", "foreign-source",
                "FOREIGNSOURCE", "ForeignSource", "foreignId", "foreign_id", "type", "Type", "TYPE",
                "id", "Id", "nodeId", "node_id");
        final List<String> prefixes = Arrays.asList("", "node.", "Node.", "NODE.", "node[0].",
                "assetRecord.node.", "asset_record.node.", "assetRecord.node.assetRecord.node.",
                "ipInterface.node.", "ip_interface.node.", "snmpInterface.node.", "snmp_interface.node.",
                "ipInterface.snmpInterface.node.", "snmpInterface.ipInterfaces[0].node.",
                "monitoredServices[0].ipInterface.node.", "ipInterfaces[0].node.", "children[0].node.",
                "node.categories[0].");

        int reached = 0;
        for (final Supplier<Object[]> target : targets()) {
            final Class<?> rootType = target.get()[0].getClass();
            for (final String prefix : prefixes) {
                for (final String leaf : leaves) {
                    final String key = prefix + leaf;
                    for (final String bound : new String[] { key, RestUtils.convertNameToPropertyName(key) }) {
                        final Object[] pair = target.get();
                        final OnmsNode owner = (OnmsNode) pair[1];
                        final String before = fingerprint(owner, pair[0]);
                        bindUnguarded(pair[0], bound);
                        if (before.equals(fingerprint(owner, pair[0]))) {
                            continue;
                        }
                        reached++;
                        assertTrue("unguarded bind of '" + bound + "' on " + rootType.getSimpleName()
                                        + " wrote a protected field, but the guard allows key '" + key + "'",
                                RestUtils.isProtectedProperty(rootType, key, NONE));
                    }
                }
            }
        }
        assertTrue("the matrix must actually exercise some writes", reached > 0);
    }

    /** Each entry supplies a freshly built bind target plus the node it can reach. */
    private static List<Supplier<Object[]>> targets() {
        final List<Supplier<Object[]>> targets = new ArrayList<>();
        targets.add(() -> { final OnmsNode n = node(); return new Object[] { n, n }; });
        targets.add(() -> { final OnmsNode n = node(); return new Object[] { n.getAssetRecord(), n }; });
        targets.add(() -> {
            final OnmsNode n = node();
            final OnmsIpInterface iface = new OnmsIpInterface();
            iface.setNode(n);
            return new Object[] { iface, n };
        });
        targets.add(() -> {
            final OnmsNode n = node();
            final OnmsSnmpInterface snmpIface = new OnmsSnmpInterface();
            snmpIface.setNode(n);
            return new Object[] { snmpIface, n };
        });
        targets.add(() -> {
            final OnmsNode n = node();
            final OnmsIpInterface iface = new OnmsIpInterface();
            iface.setNode(n);
            final OnmsMonitoredService service = new OnmsMonitoredService();
            service.setIpInterface(iface);
            return new Object[] { service, n };
        });
        targets.add(() -> {
            final OnmsNode n = node();
            final OnmsHwEntity entity = new OnmsHwEntity();
            entity.setNode(n);
            return new Object[] { entity, n };
        });
        targets.add(() -> { final OnmsNode n = node(); return new Object[] { new OnmsCategory("Production"), n }; });
        return targets;
    }

    private static void bindUnguarded(final Object bean, final String path) {
        try {
            final BeanWrapper wrapper = PropertyAccessorFactory.forBeanPropertyAccess(bean);
            if (wrapper.isWritableProperty(path)) {
                wrapper.setPropertyValue(path, wrapper.convertIfNecessary("Forged", wrapper.getPropertyType(path)));
            }
        } catch (final Exception e) {
            // an unbindable path is not an exploit
        }
    }

    private static String fingerprint(final OnmsNode node, final Object bean) {
        final BeanWrapper wrapper = PropertyAccessorFactory.forBeanPropertyAccess(bean);
        final Object beanId = wrapper.isReadableProperty("id") ? wrapper.getPropertyValue("id") : null;
        return node.getId() + "|" + node.getForeignSource() + "|" + node.getForeignId() + "|"
                + node.getType() + "|" + beanId;
    }

    private static OnmsNode node() {
        final OnmsNode node = new OnmsNode();
        node.setId(1);
        node.setLabel("TestMachine1");
        node.setForeignSource("JUnit");
        node.setForeignId("TestMachine1");
        node.setType(NodeType.ACTIVE);
        final OnmsAssetRecord assetRecord = new OnmsAssetRecord();
        assetRecord.setNode(node);
        node.setAssetRecord(assetRecord);
        return node;
    }

    private static MultivaluedMap<String,String> params(final String key) {
        final MultivaluedMap<String,String> params = new MultivaluedHashMap<>();
        params.putSingle(key, "value");
        return params;
    }
}
