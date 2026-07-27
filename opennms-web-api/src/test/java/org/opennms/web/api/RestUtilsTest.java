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

import java.beans.PropertyDescriptor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;

import org.junit.Test;
import org.opennms.netmgt.enlinkd.model.UserDefinedLink;
import org.opennms.netmgt.model.OnmsAssetRecord;
import org.opennms.netmgt.model.OnmsCategory;
import org.opennms.netmgt.model.OnmsHwEntity;
import org.opennms.netmgt.model.OnmsIpInterface;
import org.opennms.netmgt.model.OnmsMonitoredService;
import org.opennms.netmgt.model.OnmsNode;
import org.opennms.netmgt.model.OnmsNode.NodeType;
import org.opennms.netmgt.model.OnmsServiceType;
import org.opennms.netmgt.model.OnmsSnmpInterface;
import org.opennms.netmgt.provision.persist.requisition.Requisition;
import org.opennms.netmgt.provision.persist.requisition.RequisitionNode;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.PropertyAccessorFactory;

public class RestUtilsTest {

    private static final Set<String> NONE = Collections.emptySet();

    /** Distinct from every identifier the fixture assigns, so a write is always observable. */
    private static final int FORGED_ID = 999;

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

    /** Depth does not launder a protected leaf, however many hops it hides behind. */
    @Test
    public void protectsProtectedLeavesAtAnyDepth() {
        assertTrue(isProtected(OnmsIpInterface.class, "snmpInterface.node.foreignSource"));
        assertTrue(isProtected(OnmsIpInterface.class, "snmp_interface.node.foreign_source"));
        assertTrue(isProtected(OnmsSnmpInterface.class, "node.assetRecord.node.foreignSource"));
        assertTrue(isProtected(OnmsMonitoredService.class, "ipInterface.node.parent.foreignSource"));
        assertTrue(isProtected(OnmsNode.class, "parent.parent.foreignSource"));
        assertTrue(isProtected(OnmsNode.class, "parent.assetRecord.node.foreign_source"));
    }

    /**
     * The boundary is the protected leaf, not the hop. A node reached through a relation is still
     * a node, so its ownership fields are refused there, while its ordinary fields bind exactly
     * as they did before this guard existed. Both directions are asserted so that tightening or
     * loosening the rule has to be a deliberate edit to this test.
     */
    @Test
    public void guardsProtectedLeavesOnAHoppedIntoNodeButNotOrdinaryOnes() {
        assertTrue(isProtected(OnmsNode.class, "parent.foreignSource"));
        assertTrue(isProtected(OnmsNode.class, "parent.foreign_source"));
        assertTrue(isProtected(OnmsNode.class, "parent.foreignId"));
        assertTrue(isProtected(OnmsNode.class, "parent.type"));
        assertTrue(isProtected(OnmsNode.class, "parent.id"));
        assertTrue(isProtected(OnmsIpInterface.class, "node.parent.foreign_source"));

        assertFalse(isProtected(OnmsNode.class, "parent.label"));
        assertFalse(isProtected(OnmsNode.class, "parent.sysContact"));
        assertFalse(isProtected(OnmsNode.class, "parent.sys_contact"));
        assertFalse(isProtected(OnmsNode.class, "assetRecord.node.label"));
        assertFalse(isProtected(OnmsNode.class, "asset_record.node.label"));
        assertFalse(isProtected(OnmsIpInterface.class, "node.label"));
        assertFalse(isProtected(OnmsIpInterface.class, "node.sysContact"));
        assertFalse(isProtected(OnmsSnmpInterface.class, "node.assetRecord.manufacturer"));
        assertFalse(isProtected(OnmsMonitoredService.class, "ipInterface.node.label"));
    }

    /**
     * An indexed hop is walked, not rejected: the element type comes from the property's generic
     * signature, so the leaf check still applies behind it. Only a hop whose element type cannot
     * be resolved at all is refused outright. A trailing index is a write to the collection
     * property itself and is judged on that property's own name.
     */
    @Test
    public void resolvesIndexedHopsAndStillGuardsTheLeaf() {
        assertTrue(isProtected(OnmsNode.class, "ipInterfaces[0].node.foreignSource"));
        assertTrue(isProtected(OnmsNode.class, "ip_interfaces[0].node.foreign_source"));
        assertTrue(isProtected(OnmsNode.class, "categories[0].authorizedGroups"));
        assertTrue(isProtected(OnmsNode.class, "categories[0].id"));
        assertTrue(isProtected(OnmsHwEntity.class, "children[0].node.foreignSource"));
        assertTrue(isProtected(OnmsNode.class, "ipInterfaces[0].monitoredServices[0].ipInterface.node.foreignSource"));
        // 'node' is not a collection, so Spring cannot index it and neither can the walk
        assertTrue(isProtected(OnmsIpInterface.class, "node[0].foreignSource"));
        assertTrue(isProtected(OnmsNode.class, "nosuchcollection[0].foreignSource"));
        assertTrue(isProtected(OnmsNode.class, "[0].foreignSource"));

        assertFalse(isProtected(OnmsNode.class, "categories[0]"));
        assertFalse(isProtected(OnmsNode.class, "ipInterfaces[2]"));
        assertFalse(isProtected(OnmsNode.class, "ipInterfaces[0].ipHostName"));
        assertFalse(isProtected(OnmsHwEntity.class, "children[0].entPhysicalName"));
    }

    /**
     * The type map is the only thing standing between a request parameter and an ownership field,
     * so an omission from it is not caught anywhere else. Reflect over what OnmsNode actually
     * exposes and fail on anything ownership-shaped the guard does not already refuse. A property
     * that trips this and is genuinely harmless belongs in the exempt set below, which is a
     * deliberate, reviewable act; the alternative is finding out from a takeover report.
     */
    @Test
    public void nodeOwnershipPropertiesAreAllListed() {
        final Set<String> exempt = Collections.singleton("sysObjectId");
        final List<String> unguarded = new ArrayList<>();
        for (final PropertyDescriptor descriptor
                : PropertyAccessorFactory.forBeanPropertyAccess(new OnmsNode()).getPropertyDescriptors()) {
            final String name = descriptor.getName();
            if (descriptor.getWriteMethod() == null || exempt.contains(name) || !isOwnershipShaped(name)) {
                continue;
            }
            if (!isProtected(OnmsNode.class, name)) {
                unguarded.add(name);
            }
        }
        assertEquals("OnmsNode exposes ownership-shaped writable properties the guard does not refuse."
                        + " Add them to RestUtils.PROTECTED_NODE_PROPERTIES, or to this test's exempt set"
                        + " if they really are not ownership fields",
                Collections.emptyList(), unguarded);
    }

    private static boolean isOwnershipShaped(final String name) {
        final String lower = name.toLowerCase();
        return lower.startsWith("foreign") || lower.endsWith("id");
    }

    /** UserDefinedLink names its primary key dbId, which is mass-assignable like any other. */
    @Test
    public void protectsTheUserDefinedLinkPrimaryKey() {
        assertTrue(isProtected(UserDefinedLink.class, "dbId"));
        assertTrue(isProtected(UserDefinedLink.class, "db_id"));
        assertTrue(isProtected(UserDefinedLink.class, "DbId"));
        assertTrue(RestUtils.isProtectedProperty("dbId", NONE));

        assertFalse(isProtected(UserDefinedLink.class, "nodeIdA"));
        assertFalse(isProtected(UserDefinedLink.class, "nodeIdZ"));
        assertFalse(isProtected(UserDefinedLink.class, "linkId"));
        assertFalse(isProtected(UserDefinedLink.class, "componentLabelA"));
        assertFalse(isProtected(UserDefinedLink.class, "owner"));
    }

    @Test
    public void userDefinedLinkUpdateCannotReassignThePrimaryKey() {
        final UserDefinedLink link = new UserDefinedLink();
        link.setDbId(7);
        link.setLinkId("link-1");
        final MultivaluedMap<String,String> params = new MultivaluedHashMap<>();
        params.putSingle("link_label", "LegitLabel");
        params.putSingle("db_id", "999");
        params.putSingle("dbId", "999");
        RestUtils.setBeanProperties(link, params);

        assertEquals("LegitLabel", link.getLinkLabel());
        assertEquals(Integer.valueOf(7), link.getDbId());
    }

    /** Primary keys are refused wherever they turn up, including a nested owner. */
    @Test
    public void protectsPrimaryKeys() {
        assertTrue(isProtected(OnmsNode.class, "id"));
        assertTrue(isProtected(OnmsIpInterface.class, "id"));
        assertTrue(isProtected(OnmsNode.class, "assetRecord.id"));
        assertTrue(isProtected(OnmsCategory.class, "authorizedGroups"));
        assertTrue(isProtected(OnmsIpInterface.class, "node.id"));
        assertTrue(isProtected(OnmsIpInterface.class, "node_id"));
        assertTrue(isProtected(OnmsMonitoredService.class, "ipInterface.id"));
        assertTrue(isProtected(OnmsMonitoredService.class, "ip_interface.id"));
        assertTrue(isProtected(OnmsNode.class, "parent.id"));
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
        params.putSingle("node.label", "Relabelled");
        RestUtils.setBeanProperties(iface, params);

        assertEquals("M", iface.getIsManaged());
        assertEquals("JUnit", node.getForeignSource());
        assertEquals("TestMachine1", node.getForeignId());
        // Non-protected fields on a hopped-into node stay writable, as they were before the guard.
        assertEquals("Relabelled", node.getLabel());
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
        // 'ForeignSource' belongs here because Spring falls back to the uncapitalized name, so a
        // capitalized first letter does bind. 'FOREIGNSOURCE' does not: it normalizes to
        // 'foreignsource', which is no property at all, so it is covered by the name-only tests
        // instead of sitting here writing nothing.
        final List<String> leaves = Arrays.asList("foreignSource", "foreign_source", "foreign-source",
                "ForeignSource", "foreignId", "foreign_id", "type", "Type", "TYPE",
                "id", "Id", "nodeId", "node_id", "authorizedGroups", "authorized_groups");
        // Every prefix here has to be able to bind, and the assertion at the end enforces that, so
        // the fixture has to wire up each relation these walk. The collection hops do bind: Spring
        // indexes a Set for navigation and refuses only element replacement, so ipInterfaces[0].x
        // is a live write path. 'node[0].' is absent because indexed access needs an array, List,
        // Set or Map and 'node' is a single reference; it is asserted as a refusal instead.
        final List<String> prefixes = Arrays.asList("", "node.", "Node.", "NODE.",
                "assetRecord.node.", "asset_record.node.", "assetRecord.node.assetRecord.node.",
                "ipInterface.node.", "ip_interface.node.", "snmpInterface.node.", "snmp_interface.node.",
                "ipInterface.snmpInterface.node.",
                // indexed traversal, where the walk has to resolve an element type from generics
                "ipInterfaces[0].node.", "ip_interfaces[0].node.", "snmpInterfaces[0].node.",
                "categories[0].", "monitoredServices[0].ipInterface.node.",
                "snmpInterface.ipInterfaces[0].node.", "children[0].node.",
                "ipInterfaces[0].monitoredServices[0].ipInterface.node.",
                // the parent hop reaches a different node than the request targets
                "parent.", "node.parent.", "parent.parent.", "parent.asset_record.node.");

        final Map<String,Integer> writesByLeaf = new LinkedHashMap<>();
        for (final String leaf : leaves) {
            writesByLeaf.put(leaf, 0);
        }
        final Map<String,Integer> writesByPrefix = new LinkedHashMap<>();
        for (final String prefix : prefixes) {
            writesByPrefix.put(prefix, 0);
        }
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
                        writesByLeaf.merge(leaf, 1, Integer::sum);
                        writesByPrefix.merge(prefix, 1, Integer::sum);
                        assertTrue("unguarded bind of '" + bound + "' on " + rootType.getSimpleName()
                                        + " wrote a protected field, but the guard allows key '" + key + "'",
                                RestUtils.isProtectedProperty(rootType, key, NONE));
                    }
                }
            }
        }

        // A leaf that never writes anything asserts nothing, so treat lost coverage as a failure
        // rather than letting the token sit in the list looking like it still tests something.
        final List<String> inert = new ArrayList<>();
        for (final Map.Entry<String,Integer> entry : writesByLeaf.entrySet()) {
            if (entry.getValue() == 0) {
                inert.add(entry.getKey());
            }
        }
        assertEquals("every leaf token must produce at least one real write, got " + writesByLeaf,
                Collections.emptyList(), inert);

        // A prefix only writes if the fixture wires up the relation it walks, so require every one
        // of them rather than let a change to the fixture quietly retire part of the matrix.
        final List<String> inertPrefixes = new ArrayList<>();
        for (final Map.Entry<String,Integer> entry : writesByPrefix.entrySet()) {
            if (entry.getValue() == 0) {
                inertPrefixes.add(entry.getKey());
            }
        }
        assertEquals("every path prefix must produce at least one real write, got " + writesByPrefix,
                Collections.emptyList(), inertPrefixes);
    }

    /** Each entry supplies a freshly built bind target plus the node it can reach. */
    private static List<Supplier<Object[]>> targets() {
        final List<Supplier<Object[]>> targets = new ArrayList<>();
        targets.add(() -> { final OnmsNode n = node(); return new Object[] { n, n }; });
        targets.add(() -> { final OnmsNode n = node(); return new Object[] { n.getAssetRecord(), n }; });
        targets.add(() -> { final OnmsNode n = node(); return new Object[] { n.getIpInterfaces().iterator().next(), n }; });
        targets.add(() -> { final OnmsNode n = node(); return new Object[] { n.getCategories().iterator().next(), n }; });
        targets.add(() -> {
            final OnmsNode n = node();
            final OnmsSnmpInterface snmpIface = new OnmsSnmpInterface();
            snmpIface.setNode(n);
            return new Object[] { snmpIface, n };
        });
        targets.add(() -> {
            final OnmsNode n = node();
            return new Object[] { n.getIpInterfaces().iterator().next().getMonitoredServices().iterator().next(), n };
        });
        targets.add(() -> {
            final OnmsNode n = node();
            final OnmsHwEntity entity = new OnmsHwEntity();
            entity.setNode(n);
            entity.setEntPhysicalIndex(1);
            final OnmsHwEntity child = new OnmsHwEntity();
            child.setNode(n);
            child.setEntPhysicalIndex(2);
            child.setId(51);
            entity.addChildEntity(child);
            return new Object[] { entity, n };
        });
        return targets;
    }

    /**
     * Write through a bare BeanWrapper, trying values the property can actually hold. Converting
     * one literal for every property silently turned an enum or integer target into a no-op,
     * which made the matrix look like it covered those leaves when it exercised nothing.
     */
    private static void bindUnguarded(final Object bean, final String path) {
        final BeanWrapper wrapper = PropertyAccessorFactory.forBeanPropertyAccess(bean);
        final Class<?> propertyType;
        try {
            if (!wrapper.isWritableProperty(path)) {
                return;
            }
            propertyType = wrapper.getPropertyType(path);
        } catch (final Exception e) {
            return; // an unbindable path is not an exploit
        }
        for (final Object value : forgedValues(wrapper, path, propertyType)) {
            try {
                wrapper.setPropertyValue(path, value);
                return;
            } catch (final Exception e) {
                // a value this property cannot hold is not an exploit; try the next candidate
            }
        }
    }

    /** Candidate forged values for a property, each distinct from what it currently holds. */
    private static List<Object> forgedValues(final BeanWrapper wrapper, final String path, final Class<?> propertyType) {
        Object current = null;
        try {
            current = wrapper.getPropertyValue(path);
        } catch (final Exception e) {
            // unreadable is fine, it only costs us the distinctness filter
        }
        final List<Object> candidates = new ArrayList<>();
        if (propertyType != null && propertyType.isEnum()) {
            candidates.addAll(Arrays.asList(propertyType.getEnumConstants()));
        } else {
            candidates.add("Forged");
            candidates.add(Integer.valueOf(FORGED_ID));
        }
        final List<Object> distinct = new ArrayList<>();
        for (final Object candidate : candidates) {
            if (!String.valueOf(candidate).equals(String.valueOf(current))) {
                distinct.add(candidate);
            }
        }
        return distinct;
    }

    /**
     * Every piece of protected state the matrix can reach, including through the collections the
     * indexed prefixes walk. State left out here makes a write to it indistinguishable from a
     * refusal, which silently drops the path out of coverage.
     */
    private static String fingerprint(final OnmsNode node, final Object bean) {
        final StringBuilder fingerprint = new StringBuilder();
        for (OnmsNode hop = node; hop != null; hop = hop.getParent()) {
            fingerprint.append(hop.getId()).append('|').append(hop.getForeignSource()).append('|')
                    .append(hop.getForeignId()).append('|').append(hop.getType()).append("//");
            for (final OnmsIpInterface iface : hop.getIpInterfaces()) {
                fingerprint.append(iface.getId()).append(',');
                for (final OnmsMonitoredService service : iface.getMonitoredServices()) {
                    fingerprint.append(service.getId()).append(',');
                }
            }
            for (final OnmsSnmpInterface snmpIface : hop.getSnmpInterfaces()) {
                fingerprint.append(snmpIface.getId()).append(',');
            }
            for (final OnmsCategory category : hop.getCategories()) {
                fingerprint.append(category.getId()).append(',').append(category.getAuthorizedGroups()).append(',');
            }
            fingerprint.append("//");
        }
        final BeanWrapper wrapper = PropertyAccessorFactory.forBeanPropertyAccess(bean);
        for (final String local : new String[] { "id", "authorizedGroups" }) {
            if (wrapper.isReadableProperty(local)) {
                fingerprint.append(wrapper.getPropertyValue(local)).append('|');
            }
        }
        if (bean instanceof OnmsHwEntity) {
            for (final OnmsHwEntity child : ((OnmsHwEntity) bean).getChildren()) {
                fingerprint.append(child.getId()).append(',');
            }
        }
        return fingerprint.toString();
    }

    private static OnmsNode node() {
        final OnmsNode node = newNode(1, "TestMachine1", "JUnit");
        // The parent chain reaches other, different nodes, so a write through it has to be
        // observable separately from a write to the request's own node.
        final OnmsNode parent = newNode(2, "ParentMachine", "ParentReq");
        parent.setParent(newNode(3, "GrandparentMachine", "GrandparentReq"));
        node.setParent(parent);
        return node;
    }

    /**
     * An IP interface with its SNMP interface and monitored service wired, and registered in the
     * node's collections, so every relation the matrix walks actually resolves. A collection left
     * empty makes the paths through it silently write nothing.
     */
    private static OnmsIpInterface ipInterface(final OnmsNode node) {
        final OnmsIpInterface iface = new OnmsIpInterface();
        iface.setNode(node);
        iface.setId(11);
        final OnmsSnmpInterface snmpIface = new OnmsSnmpInterface();
        snmpIface.setNode(node);
        snmpIface.setId(21);
        snmpIface.setIfIndex(1);
        iface.setSnmpInterface(snmpIface);
        snmpIface.getIpInterfaces().add(iface);
        node.getSnmpInterfaces().add(snmpIface);
        node.getIpInterfaces().add(iface);
        final OnmsMonitoredService service = new OnmsMonitoredService();
        service.setIpInterface(iface);
        service.setId(31);
        service.setServiceType(new OnmsServiceType("ICMP"));
        iface.getMonitoredServices().add(service);
        return iface;
    }

    private static OnmsNode newNode(final int id, final String label, final String foreignSource) {
        final OnmsNode node = new OnmsNode();
        node.setId(id);
        node.setLabel(label);
        node.setForeignSource(foreignSource);
        node.setForeignId(label);
        node.setType(NodeType.ACTIVE);
        final OnmsAssetRecord assetRecord = new OnmsAssetRecord();
        assetRecord.setNode(node);
        node.setAssetRecord(assetRecord);
        final OnmsCategory category = new OnmsCategory("Production");
        category.setId(41 + id);
        node.getCategories().add(category);
        ipInterface(node);
        return node;
    }

    private static MultivaluedMap<String,String> params(final String key) {
        final MultivaluedMap<String,String> params = new MultivaluedHashMap<>();
        params.putSingle(key, "value");
        return params;
    }
}
