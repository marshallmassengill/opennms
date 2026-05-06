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
package org.opennms.netmgt.collection.client.rpc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementWrapper;
import javax.xml.bind.annotation.XmlRootElement;

import org.opennms.core.rpc.api.RemoteExecutionException;
import org.opennms.core.rpc.api.RpcResponse;
import org.opennms.netmgt.collection.api.CollectionSet;
import org.opennms.netmgt.collection.dto.CollectionSetDTO;

@XmlRootElement(name = "collector-response")
@XmlAccessorType(XmlAccessType.NONE)
public class CollectorResponseDTO implements RpcResponse {

    @XmlAttribute(name="error")
    private String error;

    @XmlElement(name = "collection-set", type=CollectionSetDTO.class)
    private CollectionSet collectionSet;

    /**
     * When the minion sees a 401/403 from the collected endpoint, it
     * sets this to the status code instead of failing the RPC. The
     * controller-side retry path keys off this being non-null.
     */
    @XmlAttribute(name="auth-failure-status-code")
    private Integer authFailureStatusCode;

    /**
     * Header values that were on the failed request. The controller-side
     * retry path runs each through TokenProvider.invalidateByTokenValue
     * to evict matching cache entries before retrying.
     */
    @XmlElementWrapper(name="auth-failure-header-values")
    @XmlElement(name="value")
    private List<String> authFailureHeaderValues;

    public CollectorResponseDTO() { }

    public CollectorResponseDTO(CollectionSet collectionSet) {
        this.collectionSet = collectionSet;
    }

    public CollectorResponseDTO(Throwable ex) {
        this.error = RemoteExecutionException.toErrorMessage(ex);
    }

    public CollectionSet getCollectionSet() {
        return collectionSet;
    }

    public Integer getAuthFailureStatusCode() {
        return authFailureStatusCode;
    }

    public void setAuthFailureStatusCode(final Integer authFailureStatusCode) {
        this.authFailureStatusCode = authFailureStatusCode;
    }

    public List<String> getAuthFailureHeaderValues() {
        return authFailureHeaderValues == null
                ? Collections.emptyList()
                : authFailureHeaderValues;
    }

    public void setAuthFailureHeaderValues(final List<String> values) {
        this.authFailureHeaderValues = values == null ? null : new ArrayList<>(values);
    }

    @Override
    public String getErrorMessage() {
        return error;
    }

    @Override
    public int hashCode() {
        return Objects.hash(error, collectionSet, authFailureStatusCode, authFailureHeaderValues);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        } else if (obj == null) {
            return false;
        } else if (!(obj instanceof CollectorResponseDTO)) {
            return false;
        }
        CollectorResponseDTO other = (CollectorResponseDTO) obj;
        return Objects.equals(this.error, other.error)
                && Objects.equals(this.collectionSet, other.collectionSet)
                && Objects.equals(this.authFailureStatusCode, other.authFailureStatusCode)
                && Objects.equals(this.authFailureHeaderValues, other.authFailureHeaderValues);
    }


}
