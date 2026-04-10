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
package org.opennms.netmgt.model;

import java.io.Serializable;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import javax.persistence.Transient;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

/**
 * A time-bounded notification silence for a specific monitored service.
 *
 * <p>While a silence is active (startTime &lt;= now &lt; endTime), notifications
 * for the associated service are suppressed. Events and alarms are still
 * created normally.</p>
 */
@XmlRootElement(name = "service-silence")
@XmlAccessorType(XmlAccessType.NONE)
@Entity
@Table(name = "service_silences")
public class OnmsServiceSilence implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer m_id;
    private OnmsMonitoredService m_monitoredService;
    private Date m_startTime;
    private Date m_endTime;
    private String m_createdBy;

    public OnmsServiceSilence() {
    }

    public OnmsServiceSilence(OnmsMonitoredService monitoredService, Date startTime, Date endTime, String createdBy) {
        m_monitoredService = monitoredService;
        m_startTime = startTime;
        m_endTime = endTime;
        m_createdBy = createdBy;
    }

    @Id
    @XmlAttribute(name = "id")
    @Column(name = "id", nullable = false)
    @SequenceGenerator(name = "serviceSilenceSequence", sequenceName = "service_silences_nxtid")
    @GeneratedValue(generator = "serviceSilenceSequence")
    public Integer getId() {
        return m_id;
    }

    public void setId(Integer id) {
        m_id = id;
    }

    @XmlTransient
    @ManyToOne
    @JoinColumn(name = "ifserviceid", nullable = false)
    public OnmsMonitoredService getMonitoredService() {
        return m_monitoredService;
    }

    public void setMonitoredService(OnmsMonitoredService monitoredService) {
        m_monitoredService = monitoredService;
    }

    @XmlAttribute(name = "start-time")
    @Column(name = "start_time", nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    public Date getStartTime() {
        return m_startTime;
    }

    public void setStartTime(Date startTime) {
        m_startTime = startTime;
    }

    @XmlAttribute(name = "end-time")
    @Column(name = "end_time", nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    public Date getEndTime() {
        return m_endTime;
    }

    public void setEndTime(Date endTime) {
        m_endTime = endTime;
    }

    @XmlAttribute(name = "created-by")
    @Column(name = "created_by", length = 128)
    public String getCreatedBy() {
        return m_createdBy;
    }

    public void setCreatedBy(String createdBy) {
        m_createdBy = createdBy;
    }

    @Transient
    public boolean isActive() {
        return isActive(new Date());
    }

    @Transient
    public boolean isActive(Date now) {
        return m_startTime != null && m_endTime != null
                && !now.before(m_startTime) && now.before(m_endTime);
    }

    @Override
    public String toString() {
        return String.format("OnmsServiceSilence[id=%d, service=%s, start=%s, end=%s, createdBy=%s]",
                m_id, m_monitoredService, m_startTime, m_endTime, m_createdBy);
    }
}
