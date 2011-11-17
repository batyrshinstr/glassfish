/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2011 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.enterprise.v3.admin.adapter;



import org.jvnet.hk2.annotations.Inject;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.component.PostConstruct;
import org.jvnet.hk2.config.types.Property;
import org.glassfish.internal.api.PostStartup;
import org.glassfish.server.ServerEnvironmentImpl;

import com.sun.enterprise.config.serverbeans.*;
import com.sun.logging.LogDomains;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.List;

@Service(name = "AdminConsoleStartupService")
public class AdminConsoleStartupService implements  PostConstruct, PostStartup {

    @Inject
    private AdminService adminService;

    @Inject
    private AdminConsoleAdapter adminConsoleAdapter;

    @Inject
    private ServerEnvironmentImpl env;

    @Inject
    private Domain domain;

    private static final Logger logger = LogDomains.getLogger(AdminConsoleStartupService.class, LogDomains.CORE_LOGGER);

    @Override
    public void postConstruct() {
        // FIXME : Use ServerTags, when this is finalized.

        /* This service must run only on the server where the console should run. Currently, that server is DAS. If and when
         *  the console becomes dis-associated with DAS, this logic will need to be modified.
         */
        if (!env.isDas())
            return;

        Property initProp = adminService.getProperty("adminConsoleStartup");
        String initPropVal = null;
        if (initProp != null) {
            initPropVal = initProp.getValue();
        }
        if (initPropVal == null) {
            return;
        }

        if (adminConsoleAdapter == null) { // should not happen
            logger.log(Level.WARNING, "AdminConsoleAdapter is not initialized, could not process AdminConsoleStartupService");
            return;
        }

        if (logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE, "AdminConsoleStartupService, console loading option is {0}", initPropVal);
        }

        if (initPropVal.equalsIgnoreCase("SYSTEM_DEFAULT")) {
            handleDefault();
        } else if (initPropVal.equalsIgnoreCase("OPTIMIZE_LOW")) {
            handleLow();
        } else if (initPropVal.equalsIgnoreCase("OPTIMIZE_HIGH")) {
            handleHigh();
        }
    }

    private void handleDefault() {

        /* if there are servers other than DAS */
        if ((domain.getServers().getServer().size() > 1)) {
            handleHigh();
        }
    }

    private void handleLow() {
        adminConsoleAdapter.initRest();
    }


    private void handleHigh() {
        handleLow();
        synchronized(this) {
            if (!adminConsoleAdapter.isInstalling() && !adminConsoleAdapter.isApplicationLoaded()) {
                adminConsoleAdapter.loadConsole();
            }
        }
    }



}
