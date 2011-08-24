/**
 * Copyright (c) 2011, salesforce.com, inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided
 * that the following conditions are met:
 *
 *    Redistributions of source code must retain the above copyright notice, this list of conditions and the
 *    following disclaimer.
 *
 *    Redistributions in binary form must reproduce the above copyright notice, this list of conditions and
 *    the following disclaimer in the documentation and/or other materials provided with the distribution.
 *
 *    Neither the name of salesforce.com, inc. nor the names of its contributors may be used to endorse or
 *    promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
 * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.force.sdk.jpa.connection;

import com.force.sdk.connector.ForceConnectorConfig;
import com.force.sdk.connector.ForceServiceConnector;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import java.util.HashMap;
import java.util.Map;

/**
 * Tests the order in which the JPA layer get a connection.
 *
 * @author Tim Kral
 */
public class JPAConnectionConstructOrderTest extends BaseJPAConnectionTest {
    
    @DataProvider
    protected Object[][] connectionConstructOrderProvider() {
        return new Object[][] {
            {"testThreadLocalIsFirst", true, false},
            {"testConnUrlFromPersistencePropIsSecond", false, true},
            {"testUserInfoFromPersistencePropIsThird", false, false},
        };
    }
    
    @Test(dataProvider = "connectionConstructOrderProvider")
    public void testConnectionConstructOrder(String testName, Boolean addGoodThreadLocal,
            Boolean addGoodConnUrl) throws Exception {
        try {
            if (addGoodThreadLocal != null && addGoodThreadLocal) {
                ForceConnectorConfig config = new ForceConnectorConfig();
                config.setAuthEndpoint(userInfo.getServerEndpoint());
                config.setUsername(userInfo.getUserName());
                config.setPassword(userInfo.getPassword());
                
                ForceServiceConnector.setThreadLocalConnectorConfig(config);
            }
            
            Map<String, String> persistencePropMap = new HashMap<String, String>();
            persistencePropMap.put("datanucleus.storeManagerType", "force");
            persistencePropMap.put("datanucleus.ConnectionUrl", "force://deadbeef?user=u&password=p");

            if (addGoodConnUrl != null && addGoodConnUrl) {
                persistencePropMap.put("datanucleus.ConnectionUrl", createConnectionUrl());
            }

            EntityManagerFactory emf = Persistence.createEntityManagerFactory("badUserInfo", persistencePropMap);

            // We should get a good JPA connection because it will use good state
            // based on the order of how things are used for construction
            verifyEntityManager(emf.createEntityManager());
        } finally {
            // Always clear the ThreadLocal config
            ForceServiceConnector.setThreadLocalConnectorConfig(null);
        }
    }
}
