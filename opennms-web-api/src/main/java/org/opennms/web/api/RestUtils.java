/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2013-2014 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2014 The OpenNMS Group, Inc.
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

import java.beans.PropertyDescriptor;
import java.beans.PropertyEditor;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.core.MultivaluedMap;
import javax.xml.datatype.XMLGregorianCalendar;

import org.opennms.netmgt.model.InetAddressTypeEditor;
import org.opennms.netmgt.model.OnmsNode;
import org.opennms.netmgt.model.OnmsSeverity;
import org.opennms.netmgt.model.OnmsSeverityEditor;
import org.opennms.netmgt.model.PrimaryType;
import org.opennms.netmgt.model.PrimaryTypeEditor;
import org.opennms.netmgt.provision.persist.StringXmlCalendarPropertyEditor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.PropertyAccessorFactory;

public abstract class RestUtils {

	private static final Logger LOG = LoggerFactory.getLogger(RestUtils.class);

	/**
	 * Primary keys and the category access-control collection: never settable from request
	 * parameters, whatever the bean.
	 */
	public static final Set<String> IMMUTABLE_PROPERTIES = Collections.unmodifiableSet(
	        new HashSet<>(Arrays.asList("id", "nodeId", "dbId", "authorizedGroups")));

	/**
	 * Node identity and provisioning-ownership properties.
	 */
	public static final Set<String> PROTECTED_NODE_PROPERTIES = Collections.unmodifiableSet(
	        new HashSet<>(Arrays.asList("foreignSource", "foreignId", "type")));

	/**
	 * Protected properties keyed by the type that owns them. The guard resolves a request key to
	 * the type the bound path actually lands on and consults this map, so an entry applies to
	 * every endpoint and to every nested route that reaches the type. Call sites do not opt in.
	 *
	 * <p>This map is the whole of the policy: nothing else refuses a write on the strength of what
	 * it would reach, so an ownership property missing from here is unprotected everywhere. Adding
	 * a field of that kind to a type listed below means adding it here too, and
	 * {@code RestUtilsTest.nodeOwnershipPropertiesAreAllListed} is meant to fail if that is
	 * forgotten for {@code OnmsNode}.</p>
	 */
	private static final Map<Class<?>,Set<String>> PROTECTED_PROPERTIES_BY_TYPE =
	        Collections.singletonMap(OnmsNode.class, PROTECTED_NODE_PROPERTIES);

	/**
	 * Whether a request parameter name resolves to a protected property, by name alone. Prefer
	 * {@link #isProtectedProperty(Class, String, Set)}: without the target type this can only
	 * compare names, which neither knows what a path resolves to nor which spelling will bind.
	 */
	public static boolean isProtectedProperty(final String key, final Set<String> additionalProtectedProperties) {
	    return pathReachesProperty(key, IMMUTABLE_PROPERTIES) || pathReachesProperty(key, additionalProtectedProperties);
	}

	/**
	 * Whether binding the given request key against the given bean type would write a protected
	 * property. The key is resolved against the type hierarchy rather than pattern-matched, so
	 * {@code foreign_source}, {@code node.foreign_source} and
	 * {@code ip_interface.node.foreign_source} are all refused for the same reason.
	 */
	public static boolean isProtectedProperty(final Class<?> beanType, final String key, final Set<String> additionalProtectedProperties) {
	    if (isProtectedProperty(key, additionalProtectedProperties)) {
	        return true;
	    }
	    if (beanType == null || key == null) {
	        return false;
	    }
	    for (final String path : new String[] { key, convertNameToPropertyName(key) }) {
	        if (pathReachesProtectedProperty(beanType, path, additionalProtectedProperties)) {
	            return true;
	        }
	    }
	    return false;
	}

	/**
	 * Whether any request parameter resolves to the given property, accounting for name
	 * normalization and nested paths. For endpoints that reject such a request outright.
	 */
	public static boolean containsProperty(final MultivaluedMap<String,String> properties, final String propertyName) {
	    final Set<String> wanted = Collections.singleton(propertyName);
	    for (final String key : properties.keySet()) {
	        if (pathReachesProperty(key, wanted)) {
	            return true;
	        }
	    }
	    return false;
	}

	/**
	 * Whether any segment of the given request key names one of the properties. Callers bind
	 * either the raw key or the normalized one, and normalization is not case-preserving, so
	 * both spellings are compared and the comparison ignores case. Spring's BeanWrapper resolves
	 * nested ('.') and indexed ('[', ']') paths, hence the per-segment check.
	 */
	private static boolean pathReachesProperty(final String key, final Set<String> propertyNames) {
	    if (key == null) {
	        return false;
	    }
	    for (final String path : new String[] { key, convertNameToPropertyName(key) }) {
	        for (final String segment : path.split("[.\\[\\]]")) {
	            for (final String propertyName : propertyNames) {
	                if (propertyName.equalsIgnoreCase(segment)) {
	                    return true;
	                }
	            }
	        }
	    }
	    return false;
	}

	/**
	 * Walk the property path one segment at a time, tracking the type each hop lands on, and
	 * report whether the write it would perform is protected. The hop itself is never the
	 * objection: only a segment naming a protected property of the type it sits on is, at
	 * whatever depth it appears. A path whose owning type cannot be determined is refused,
	 * because a write we cannot place is a write we cannot vouch for. Only declared types are
	 * consulted; no getter is invoked, so this cannot trigger a lazy load or fail on a null
	 * mid-path.
	 */
	private static boolean pathReachesProtectedProperty(final Class<?> rootType, final String path, final Set<String> additionalProtectedProperties) {
	    final String[] segments = path.split("\\.", -1);
	    Class<?> owner = rootType;
	    for (int i = 0; i < segments.length; i++) {
	        final String segment = segments[i];
	        final int bracket = segment.indexOf('[');
	        final String name = bracket < 0 ? segment : segment.substring(0, bracket);
	        if (name.isEmpty()) {
	            return true;
	        }
	        if (isProtectedOnType(owner, name, additionalProtectedProperties)) {
	            return true;
	        }
	        if (i == segments.length - 1) {
	            return false;
	        }
	        final PropertyDescriptor descriptor = findPropertyDescriptor(owner, name);
	        if (descriptor == null || descriptor.getReadMethod() == null) {
	            return true;
	        }
	        final Class<?> hop = bracket < 0 ? descriptor.getPropertyType() : findElementType(descriptor);
	        if (hop == null) {
	            return true;
	        }
	        owner = hop;
	    }
	    return false;
	}

	private static boolean isProtectedOnType(final Class<?> owner, final String name, final Set<String> additionalProtectedProperties) {
	    // A key can mix spellings across segments, so normalize each segment as well as the path.
	    final String normalized = convertNameToPropertyName(name);
	    if (containsIgnoreCase(IMMUTABLE_PROPERTIES, name) || containsIgnoreCase(IMMUTABLE_PROPERTIES, normalized)
	            || containsIgnoreCase(additionalProtectedProperties, name) || containsIgnoreCase(additionalProtectedProperties, normalized)) {
	        return true;
	    }
	    for (final Map.Entry<Class<?>,Set<String>> entry : PROTECTED_PROPERTIES_BY_TYPE.entrySet()) {
	        if (mayBe(owner, entry.getKey())
	                && (containsIgnoreCase(entry.getValue(), name) || containsIgnoreCase(entry.getValue(), normalized))) {
	            return true;
	        }
	    }
	    return false;
	}

	/**
	 * Whether a value declared as {@code declared} could be an instance of {@code candidate}.
	 * The BeanWrapper resolves nested paths against runtime types, so a declared supertype has
	 * to be treated as a possible match.
	 */
	private static boolean mayBe(final Class<?> declared, final Class<?> candidate) {
	    return candidate.isAssignableFrom(declared) || declared.isAssignableFrom(candidate);
	}

	/**
	 * Property lookup that also accepts the separator and case variants a request key can arrive
	 * in: an unresolved segment makes the walk refuse the write, so failing to recognise a
	 * spelling that would bind would be over-blocking.
	 */
	private static PropertyDescriptor findPropertyDescriptor(final Class<?> type, final String name) {
	    final PropertyDescriptor exact = BeanUtils.getPropertyDescriptor(type, name);
	    if (exact != null) {
	        return exact;
	    }
	    final String normalized = convertNameToPropertyName(name);
	    for (final PropertyDescriptor candidate : BeanUtils.getPropertyDescriptors(type)) {
	        if (candidate.getName().equalsIgnoreCase(name) || candidate.getName().equalsIgnoreCase(normalized)) {
	            return candidate;
	        }
	    }
	    return null;
	}

	/** Element type of an indexed, keyed or array property; null when it cannot be determined. */
	private static Class<?> findElementType(final PropertyDescriptor descriptor) {
	    final Class<?> propertyType = descriptor.getPropertyType();
	    if (propertyType != null && propertyType.isArray()) {
	        return propertyType.getComponentType();
	    }
	    final Method readMethod = descriptor.getReadMethod();
	    final Type generic = readMethod == null ? null : readMethod.getGenericReturnType();
	    if (generic instanceof ParameterizedType) {
	        final Type[] arguments = ((ParameterizedType) generic).getActualTypeArguments();
	        if (arguments.length > 0) {
	            // Collection<E> has one argument, Map<K,V> two; the value type is the last.
	            final Type element = arguments[arguments.length - 1];
	            if (element instanceof Class) {
	                return (Class<?>) element;
	            }
	            if (element instanceof ParameterizedType && ((ParameterizedType) element).getRawType() instanceof Class) {
	                return (Class<?>) ((ParameterizedType) element).getRawType();
	            }
	        }
	    }
	    return null;
	}

	private static boolean containsIgnoreCase(final Set<String> names, final String name) {
	    for (final String candidate : names) {
	        if (candidate.equalsIgnoreCase(name)) {
	            return true;
	        }
	    }
	    return false;
	}

	/**
	 * <p>Use Spring's {@link PropertyAccessorFactory} to set values on the specified bean.
	 * This call registers several {@link PropertyEditor} classes to properly convert
	 * values.</p>
	 * 
	 * <ul>
	 * <li>{@link StringXmlCalendarPropertyEditor}</li>
	 * <li>{@link ISO8601DateEditor}</li>
	 * <li>{@link InetAddressTypeEditor}</li>
	 * <li>{@link OnmsSeverityEditor}</li>
	 * <li>{@link PrimaryTypeEditor}</li>
	 * </ul>
	 * 
	 * @param bean
	 * @param properties
	 */
	public static void setBeanProperties(final Object bean, final MultivaluedMap<String,String> properties) {
	    setBeanProperties(bean, properties, Collections.emptySet());
	}

	/**
	 * As {@link #setBeanProperties(Object, MultivaluedMap)}, with protected property names that
	 * apply to this call site only. The type-based policy is enforced either way.
	 */
	public static void setBeanProperties(final Object bean, final MultivaluedMap<String,String> properties, final Set<String> additionalProtectedProperties) {
	    final BeanWrapper wrapper = PropertyAccessorFactory.forBeanPropertyAccess(bean);
	    wrapper.registerCustomEditor(XMLGregorianCalendar.class, new StringXmlCalendarPropertyEditor());
	    wrapper.registerCustomEditor(Date.class, new ISO8601DateEditor());
	    wrapper.registerCustomEditor(InetAddress.class, new InetAddressTypeEditor());
	    wrapper.registerCustomEditor(OnmsSeverity.class, new OnmsSeverityEditor());
	    wrapper.registerCustomEditor(PrimaryType.class, new PrimaryTypeEditor());
	    for(final String key : properties.keySet()) {
	        final String propertyName = convertNameToPropertyName(key);
	        if (isProtectedProperty(bean.getClass(), key, additionalProtectedProperties)) {
	            LOG.warn("Ignoring attempt to set protected property '{}' from request parameters", propertyName);
	            continue;
	        }
	        if (wrapper.isWritableProperty(propertyName)) {
	            final String stringValue = properties.getFirst(key);
	            Object value = convertIfNecessary(wrapper, propertyName, stringValue);
	            wrapper.setPropertyValue(propertyName, value);
	        }
	    }
	}

	private static Object convertIfNecessary(final BeanWrapper wrapper,	final String propertyName, final String stringValue) {
		LOG.debug("convertIfNecessary({}, {})", propertyName, stringValue);
		return wrapper.convertIfNecessary(stringValue, wrapper.getPropertyType(propertyName));
	}

	/**
	 * Convert a column name with underscores to the corresponding property name using "camel case".  A name
	 * like "customer_number" would match a "customerNumber" property name.
	 *
	 * @param name the column name to be converted
	 * @return the name using "camel case"
	 */
	public static String convertNameToPropertyName(String name) {
	    final StringBuilder result = new StringBuilder();
	    boolean nextIsUpper = false;
	    if (name != null && name.length() > 0) {
	        if (name.length() > 1 && (name.substring(1, 2).equals("_") || (name.substring(1, 2).equals("-")))) {
	            result.append(name.substring(0, 1).toUpperCase());
	        } else {
	            result.append(name.substring(0, 1).toLowerCase());
	        }
	        for (int i = 1; i < name.length(); i++) {
	            String s = name.substring(i, i + 1);
	            if (s.equals("_") || s.equals("-")) {
	                nextIsUpper = true;
	            } else {
	                if (nextIsUpper) {
	                    result.append(s.toUpperCase());
	                    nextIsUpper = false;
	                } else {
	                    result.append(s.toLowerCase());
	                }
	            }
	        }
	    }
	    return result.toString();
	}
}
