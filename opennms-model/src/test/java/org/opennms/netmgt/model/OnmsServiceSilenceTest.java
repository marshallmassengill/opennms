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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Date;

import org.junit.Test;

public class OnmsServiceSilenceTest {

    @Test
    public void testIsActiveWithinWindow() {
        Date start = new Date(1000);
        Date end = new Date(5000);
        OnmsServiceSilence silence = new OnmsServiceSilence(null, start, end, "admin");

        assertTrue(silence.isActive(new Date(1000)));  // exactly at start
        assertTrue(silence.isActive(new Date(3000)));  // middle
        assertTrue(silence.isActive(new Date(4999)));  // just before end
    }

    @Test
    public void testIsActiveAtEndTime() {
        Date start = new Date(1000);
        Date end = new Date(5000);
        OnmsServiceSilence silence = new OnmsServiceSilence(null, start, end, "admin");

        // end time is exclusive
        assertFalse(silence.isActive(new Date(5000)));
    }

    @Test
    public void testIsActiveBeforeStart() {
        Date start = new Date(1000);
        Date end = new Date(5000);
        OnmsServiceSilence silence = new OnmsServiceSilence(null, start, end, "admin");

        assertFalse(silence.isActive(new Date(999)));
    }

    @Test
    public void testIsActiveAfterEnd() {
        Date start = new Date(1000);
        Date end = new Date(5000);
        OnmsServiceSilence silence = new OnmsServiceSilence(null, start, end, "admin");

        assertFalse(silence.isActive(new Date(6000)));
    }

    @Test
    public void testIsActiveWithNullTimes() {
        OnmsServiceSilence silence = new OnmsServiceSilence();
        assertFalse(silence.isActive(new Date()));
    }

    @Test
    public void testIsActiveWithNullStartTime() {
        OnmsServiceSilence silence = new OnmsServiceSilence();
        silence.setEndTime(new Date(5000));
        assertFalse(silence.isActive(new Date(3000)));
    }

    @Test
    public void testIsActiveWithNullEndTime() {
        OnmsServiceSilence silence = new OnmsServiceSilence();
        silence.setStartTime(new Date(1000));
        assertFalse(silence.isActive(new Date(3000)));
    }

    @Test
    public void testIsActiveNoArg() {
        // Silence from 1 hour ago to 1 hour from now
        long now = System.currentTimeMillis();
        Date start = new Date(now - 3600000);
        Date end = new Date(now + 3600000);
        OnmsServiceSilence silence = new OnmsServiceSilence(null, start, end, "admin");

        assertTrue(silence.isActive());
    }

    @Test
    public void testIsActiveNoArgExpired() {
        // Silence that ended 1 hour ago
        long now = System.currentTimeMillis();
        Date start = new Date(now - 7200000);
        Date end = new Date(now - 3600000);
        OnmsServiceSilence silence = new OnmsServiceSilence(null, start, end, "admin");

        assertFalse(silence.isActive());
    }
}
