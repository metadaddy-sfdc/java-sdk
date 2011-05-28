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

package com.force.sdk.oauth;

import java.io.IOException;
import java.net.URLEncoder;
import java.security.Principal;

import javax.servlet.*;
import javax.servlet.http.*;

import com.force.sdk.connector.ForceConnectorConfig;
import com.force.sdk.connector.ForceServiceConnector;
import com.force.sdk.oauth.connector.ForceOAuthConnectionInfo;
import com.force.sdk.oauth.connector.ForceOAuthConnector;
import com.force.sdk.oauth.context.*;
import com.force.sdk.oauth.context.store.*;
import com.force.sdk.oauth.exception.ForceOAuthSessionExpirationException;
import com.force.sdk.oauth.userdata.*;
import com.sforce.ws.*;

/**
 * This filter can be used to add Force.com OAuth Authentication to any web application. When configuring web.xml all
 * requests that need to be authenticated should be sent through AuthFilter. The OAuth callback (usually _auth) must
 * also be sent through AuthFilter. To use the connector, add the following servlet filter to your application's web.xml
 * file:
 * <p>
 * {@code
 * 
 * <!-- Enables Security -->
 * <filter>
 *     <filter-name>AuthFilter</filter-name>
 *     <filter-class>com.force.sdk.oauth.AuthFilter</filter-class>
 *          <init-param>
 *             <param-name>connectionName</param-name>
 *             <param-value>nameOfConnectionToUse</param-value>
 *         </init-param>
 * </filter>
 * <filter-mapping>
 *     <filter-name>AuthFilter</filter-name>
 *     <url-pattern>/*</url-pattern>
 * </filter-mapping>
 * 
 * }
 * <p>
 * The OAuth Connector uses the Force.com API Connector to access the Force.com APIs. The connectionName is used to look
 * up OAuth properties defined in an environment variable, or a Java system property, or in a properties file on the
 * classpath. See @doclink connection-url for more information. Other init parameters that can be set are:
 * <ul>
 * <li>securityContextStorageMethod - valid values are "cookie" or "session". Defaults to "cookie". See @doclink
 * force-security for more information on session management and security</li>
 * <li>secure-key-file - specify the location of the file where your AES secure key is stored.</li> For Cookie based
 * session management.
 * </ul>
 * 
 * @author Fiaz Hossain
 * @author John Simone
 */
public class AuthFilter implements Filter, SessionRenewer {

    static final String FILTER_ALREADY_VISITED = "__force_auth_filter_already_visited";
    static final String SECURITY_AUTH_SUBJECT = "javax.security.auth.subject";
    static final String SECURITY_CONFIG_NAME = "ForceLogin";
    static final String DEFAULT_USER_PROFILE = "myProfile";
    static final String CONTEXT_STORE_SESSION_VALUE = "session";

    private ForceOAuthConnector oauthConnector;
    private SecurityContextService securityContextService = null;

    /**
     * Initialize the filter from the init params.
     * {@inheritDoc} 
     */
    @Override
    public void init(FilterConfig config) throws ServletException {

        SecurityContextServiceImpl securityContextServiceImpl = new SecurityContextServiceImpl();

        String customDataRetrieverName = config.getInitParameter("customDataRetriever");
        boolean storeUsername = true;

        if ("false".equals(config.getInitParameter("storeUsername"))) {
            storeUsername = false;
        }

        UserDataRetrievalService userDataRetrievalService = null;

        if (customDataRetrieverName != null) {
            try {
                Class<?> customDataRetrievalClass = Class.forName(customDataRetrieverName);
                Object customDataRetrievalObject = customDataRetrievalClass.newInstance();

                if (customDataRetrievalObject instanceof CustomUserDataRetriever) {
                    CustomUserDataRetriever<?> customDataRetriever = (CustomUserDataRetriever<?>) customDataRetrievalObject;
                    userDataRetrievalService = new CustomUserDataRetrievalService(customDataRetriever, storeUsername);
                }

            } catch (ClassNotFoundException e) {
                throw new ServletException("Custom user data retriever class not found: " + customDataRetrieverName, e);
            } catch (InstantiationException e) {
                throw new ServletException("Custom user data retriever class could not be instantiated: "
                        + customDataRetrieverName, e);
            } catch (IllegalAccessException e) {
                throw new ServletException("Custom user data retriever class could not be instantiated: "
                        + customDataRetrieverName, e);
            }
        } else {
            userDataRetrievalService = new UserDataRetrievalService(storeUsername);
        }

        // Now that the data retrieval service is created set the
        securityContextServiceImpl.setUserDataRetrievalService(userDataRetrievalService);
        oauthConnector = new ForceOAuthConnector(userDataRetrievalService);

        // Build a ForceOAuthConnectionInfo object, if applicable
        ForceOAuthConnectionInfo connInfo = null;
        if (config.getInitParameter("endpoint") != null) {
            connInfo = new ForceOAuthConnectionInfo();
            connInfo.setEndpoint(config.getInitParameter("endpoint"));
            connInfo.setOauthKey(config.getInitParameter("oauthKey"));
            connInfo.setOauthSecret(config.getInitParameter("oauthSecret"));
            oauthConnector.setConnectionInfo(connInfo);
        } else if (config.getInitParameter("url") != null) {
            connInfo = new ForceOAuthConnectionInfo();
            connInfo.setConnectionUrl(config.getInitParameter("url"));
            oauthConnector.setConnectionInfo(connInfo);
        } else if (config.getInitParameter("connectionName") != null) {
            oauthConnector.setConnectionName(config.getInitParameter("connectionName"));
        } else {
            throw new IllegalArgumentException("Could not find any init state for AuthFilter. "
                    + "Please specify an endpoint, oauthKey and oauthSecret or a connection url or a connection name.");
        }

        if (CONTEXT_STORE_SESSION_VALUE.equals(config.getInitParameter("securityContextStorageMethod"))) {
            securityContextServiceImpl.setSecurityContextStorageService(new SecurityContextSessionStore());
        } else {
            SecurityContextCookieStore cookieStore = new SecurityContextCookieStore();

            try {
                cookieStore.setKeyFileName(config.getInitParameter("secure-key-file"));
            } catch (ForceEncryptionException e) {
                throw new ServletException(e);
            }

            securityContextServiceImpl.setSecurityContextStorageService(cookieStore);
        }

        securityContextService = securityContextServiceImpl;
    }

    /**
     * Handle the secured requests.
     * {@inheritDoc} 
     */
    @Override
    public void doFilter(ServletRequest sreq, ServletResponse sres, FilterChain chain) throws IOException,
            ServletException {
        HttpServletRequest request = (HttpServletRequest) sreq;
        HttpServletResponse response = (HttpServletResponse) sres;

        if (request.getAttribute(FILTER_ALREADY_VISITED) != null) {
            // ensure we do not get into infinite loop here
            chain.doFilter(request, response);
            return;
        }

        SecurityContext sc = null;

        // if this isn't the callback from an OAuth handshake
        // get the security context from the session
        if (!ForceOAuthConnector.REDIRECT_AUTH_URI.equals(request.getServletPath())) {
            sc = securityContextService.getSecurityContextFromSession(request);
        }

        // if there is no valid security context then initiate an OAuth handshake
        if (sc == null) {
            doOAuthLogin(request, response);
            return;
        } else {
            securityContextService.setSecurityContextToSession(request, response, sc);
        }
        ForceSecurityContextHolder.set(sc);
        
        ForceConnectorConfig cc = new ForceConnectorConfig();
        cc.setSessionId(sc.getSessionId());
        cc.setServiceEndpoint(sc.getEndPoint());
        cc.setSessionRenewer(this);

        try {
            ForceServiceConnector.setThreadLocalConnectorConfig(cc);
            request.setAttribute(FILTER_ALREADY_VISITED, Boolean.TRUE);
            chain.doFilter(new AuthenticatedRequestWrapper(request, sc), response);
        } catch (ForceOAuthSessionExpirationException e) {
            doOAuthLogin(request, response);
        } catch (SecurityException se) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, request.getRequestURI());
        } finally {
            try {
                request.removeAttribute(FILTER_ALREADY_VISITED);
            } finally {
                ForceSecurityContextHolder.release();
                ForceServiceConnector.setThreadLocalConnectorConfig(null);
            }
        }
    }

    /**
     * Send the authentication redirect or save the security context to the session depending on which phase of the
     * handshake we're in.
     * 
     * @param request
     * @param response
     * @throws IOException
     */
    private void doOAuthLogin(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (ForceOAuthConnector.REDIRECT_AUTH_URI.equals(request.getServletPath())) {
            securityContextService.setSecurityContextToSession(
                    request,
                    response,
                    oauthConnector.getAccessToken(oauthConnector.getAccessCode(request),
                            oauthConnector.getRedirectUri(request)));
            // response.sendRedirect(URLEncoder.encode(request.getParameter("state"), "UTF-8"));
            response.sendRedirect(response.encodeRedirectURL(URLEncoder.encode(request.getParameter("state"), "UTF-8")));
        } else {
            response.sendRedirect(oauthConnector.getLoginRedirectUrl(request));
        }
    }

    /**
     * No resources to release.
     */
    @Override
    public void destroy() {  }

    public SecurityContextService getSecurityContextService() {
        return securityContextService;
    }

    /**
     * Wrap the request and provide methods that will make the authenticated user information available.
     */
    private static final class AuthenticatedRequestWrapper extends HttpServletRequestWrapper {

        private final ForceUserPrincipal userP;
        private final ForceRolePrincipal roleP;

        public AuthenticatedRequestWrapper(HttpServletRequest request, SecurityContext sc) {
            super(request);
            this.userP = new ForceUserPrincipal(sc.getUserName(), sc.getSessionId());
            this.roleP = new ForceRolePrincipal(sc.getRole());
        }

        @Override
        public String getRemoteUser() {
            return userP != null ? userP.getName() : super.getRemoteUser();
        }

        @Override
        public Principal getUserPrincipal() {
            return userP != null ? userP : super.getUserPrincipal();
        }

        @Override
        public boolean isUserInRole(String role) {
            return roleP != null ? roleP.getName().endsWith(role) : super.isUserInRole(role);
        }
    }

    @Override
    public SessionRenewalHeader renewSession(ConnectorConfig config) throws ConnectionException {
        throw new ForceOAuthSessionExpirationException();
    }
}