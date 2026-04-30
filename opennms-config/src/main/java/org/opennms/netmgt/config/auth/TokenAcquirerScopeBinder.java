/*
 * Licensed to The OpenNMS Group, Inc (TOG) under one or more
 * contributor license agreements.  See the LICENSE.md file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * TOG licenses this file to You under the GNU Affero General
 * Public License Version 3 (the "License") or (at your option)
 * any later version.  You may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at:
 *
 *      https://www.gnu.org/licenses/agpl-3.0.txt
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package org.opennms.netmgt.config.auth;

import java.util.Objects;

import org.opennms.core.mate.api.EntityScopeProvider;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * Late-binds an {@link EntityScopeProvider} onto a {@link TokenAcquirer},
 * tolerating its absence so that the same {@code applicationContext-auth.xml}
 * can load in two shapes: (a) co-resident with core/mate/model's
 * EntityScopeProviderImpl, in which case the provider is wired and
 * per-node placeholder resolution is enabled, or (b) standalone in test
 * contexts and minion-light bundles that pull in opennms-config but not
 * core/mate/model, in which case the acquirer runs in degraded mode (SCV
 * and env scopes only).
 *
 * <p>Hard-referencing {@code entityScopeProvider} via a Spring
 * {@code <property>} or {@code MethodInvokingFactoryBean} fails the
 * Spring context build whenever one half of the pair is missing -- that
 * was the failure mode before this binder existed.</p>
 */
public class TokenAcquirerScopeBinder implements ApplicationContextAware, InitializingBean {

    private final TokenAcquirer acquirer;
    private ApplicationContext applicationContext;

    public TokenAcquirerScopeBinder(final TokenAcquirer acquirer) {
        this.acquirer = Objects.requireNonNull(acquirer, "acquirer");
    }

    @Override
    public void setApplicationContext(final ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterPropertiesSet() {
        try {
            final EntityScopeProvider provider = applicationContext.getBean(EntityScopeProvider.class);
            acquirer.setEntityScopeProvider(provider);
        } catch (final NoSuchBeanDefinitionException ignored) {
            // No EntityScopeProvider on the classpath; TokenAcquirer
            // runs without per-node placeholder resolution.
        }
    }
}
