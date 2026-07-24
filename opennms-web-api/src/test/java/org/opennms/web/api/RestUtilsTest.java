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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.Set;

import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;

import org.junit.Test;

public class RestUtilsTest {

    private static boolean isProtected(final String key) {
        return RestUtils.isProtectedProperty(key, RestUtils.PROTECTED_NODE_PROPERTIES);
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

    @Test
    public void allowsOrdinaryProperties() {
        assertFalse(isProtected("sysContact"));
        assertFalse(isProtected("sys_contact"));
        assertFalse(isProtected("label"));
        assertFalse(isProtected("assetRecord.manufacturer"));
        assertFalse(isProtected("asset_record.operating_system"));
        assertFalse(isProtected("description"));
    }

    /** Without an additional set only the globally immutable properties are protected. */
    @Test
    public void nodePropertiesAreNotGloballyProtected() {
        final Set<String> none = Collections.emptySet();
        assertFalse(RestUtils.isProtectedProperty("foreignSource", none));
        assertTrue(RestUtils.isProtectedProperty("id", none));
        assertTrue(RestUtils.isProtectedProperty("authorized_groups", none));
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

    private static MultivaluedMap<String,String> params(final String key) {
        final MultivaluedMap<String,String> params = new MultivaluedHashMap<>();
        params.putSingle(key, "value");
        return params;
    }
}
