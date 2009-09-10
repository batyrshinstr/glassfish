/*
 * 
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2008 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 * 
 * Contributor(s):
 * 
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
package com.sun.enterprise.v3.server;

import com.sun.enterprise.config.serverbeans.Config;
import com.sun.enterprise.config.serverbeans.Configs;
import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.config.serverbeans.Server;
import com.sun.enterprise.config.serverbeans.SystemProperty;
import java.io.*;
import java.io.File;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;
import org.glassfish.internal.api.Init;
import com.sun.enterprise.config.serverbeans.JavaConfig;
import com.sun.enterprise.universal.i18n.LocalStringsImpl;
import com.sun.enterprise.universal.process.ProcessUtils;
import com.sun.enterprise.util.net.NetUtils;
import com.sun.enterprise.util.SystemPropertyConstants;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import org.jvnet.hk2.component.PostConstruct;
import org.jvnet.hk2.annotations.Inject;
import org.jvnet.hk2.annotations.Service;
import org.glassfish.config.support.TranslatedConfigView;
import org.glassfish.api.admin.ServerEnvironment;

import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Init service to take care of vm related tasks.
 *
 * @author Jerome Dochez
 * @author Byron Nevins
 */
// TODO: eventually use CageBuilder so that this gets triggered when JavaConfig enters Habitat.
@Service
public class SystemTasks implements Init, PostConstruct {

    // in embedded environment, JavaConfig is pointless, so make this optional
    @Inject(optional = true)
    JavaConfig javaConfig;
   
    @Inject(name= ServerEnvironment.DEFAULT_INSTANCE_NAME)
    Server server;
    
    @Inject
    Domain domain;
    
    Logger _logger = Logger.getLogger(SystemTasks.class.getName());

    public void postConstruct() {
        setSystemPropertiesFromEnv();
        setSystemPropertiesFromDomainXml();
        resolveJavaConfig();
        writePidFile();
        _logger.fine( "SystemTasks: loaded server named: " + server.getName() );
    }

    /*
     * Here is where we make the change Post-TP2 to *not* use JVM System Properties
     */
    private void setSystemProperty(String name, String value) {
        System.setProperty(name, value);
    }

    private void setSystemPropertiesFromEnv() {
        // adding our version of some system properties.
        setSystemProperty(SystemPropertyConstants.JAVA_ROOT_PROPERTY, System.getProperty("java.home"));

        String hostname = "localhost";
        try {
            // canonical name checks to make sure host is proper
            hostname = NetUtils.getCanonicalHostName();
        }
        catch (Exception ex) {
            if (_logger != null)
                _logger.log(Level.SEVERE, "cannot determine host name, will use localhost exclusively", ex);
        }
        if (_logger != null)
            setSystemProperty(SystemPropertyConstants.HOST_NAME_PROPERTY, hostname);
    }

    private void setSystemPropertiesFromDomainXml() {
        // precedence order from high to low
        // 0. server
        // 1. server-config
        // 2. domain
        // so we need to add System Properties in *reverse order* to get the
        // right precedence.

        List<SystemProperty> domainSPList = domain.getSystemProperty();
        List<SystemProperty> configSPList = getConfigSystemProperties();
        List<SystemProperty> serverSPList = server.getSystemProperty();

        setSystemProperties(domainSPList);
        setSystemProperties(configSPList);
        setSystemProperties(serverSPList);
    }

    private List<SystemProperty> getConfigSystemProperties() {
        try {
            String configName = server.getConfigRef();
            Configs configs = domain.getConfigs();
            List<Config> configsList = configs.getConfig();
            Config config = null;

            for (Config c : configsList) {
                if (c.getName().equals(configName)) {
                    config = c;
                    break;
                }
            }
            return (List<SystemProperty>)  (config != null ? config.getSystemProperty() : Collections.emptyList());
        }
        catch(Exception e) {  //possible NPE if domain.xml has issues!
            return Collections.emptyList();
        }
    }

    private void resolveJavaConfig() {
        if(javaConfig!=null) {
            Pattern p = Pattern.compile("-D([^=]*)=(.*)");
            for (String jvmOption : javaConfig.getJvmOptions()) {
                Matcher m = p.matcher(jvmOption);
                if (m.matches()) {
                    setSystemProperty(m.group(1), TranslatedConfigView.getTranslatedValue(m.group(2)).toString());
                    if (_logger.isLoggable(Level.FINE)) {
                        _logger.fine("Setting " + m.group(1) + " = " + TranslatedConfigView.getTranslatedValue(m.group(2)));
                    }
                }
            }
        }
    }

    private void setSystemProperties(List<SystemProperty> spList) {
        for (SystemProperty sp : spList) {
            String name = sp.getName();
            String value = sp.getValue();
            if(ok(name)) {
                setSystemProperty(name,value);
            }
        }
    }
    
    private void writePidFile() {
        Writer writer = null;
        File pidFile = null;

        try {
            pidFile = getPidFile();
            String pidString = getPidString();
            writer = new PrintWriter(pidFile);
            writer.write(pidString);
        }
        catch(PidException pe) {
            _logger.warning(pe.getMessage());
        }
        catch(Exception e) {
            _logger.warning(strings.get("internal_error", e));
        }
        finally {
            if(writer != null) {
                try {
                    writer.flush();
                    writer.close();
                }
                catch(Exception e) {
                    //ignore
                }
                pidFile.setReadable(true);
                pidFile.setWritable(true);
                pidFile.deleteOnExit();
            }
        }
    }

    private String getPidString() {
        return "" + ProcessUtils.getPid();
    }

    private File getPidFile() throws PidException{
        try {
            String configDirString = System.getProperty(SystemPropertyConstants.INSTANCE_ROOT_PROPERTY);

            if(!ok(configDirString))
                throw new PidException(strings.get("internal_error",
                        "Null or empty value for the System Property: " +
                        SystemPropertyConstants.INSTANCE_ROOT_PROPERTY));

            File configDir = new File(new File(configDirString), "config");

            if(!configDir.isDirectory())
                throw new PidException(strings.get("bad_config_dir", configDir));

            File pidFile = new File(configDir, "pid");

            if(pidFile.exists()) {
                pidFile.delete();

                if(pidFile.exists()) {
                    throw new PidException(strings.get("cant_delete_pid_file", pidFile));
                }
            }
            return pidFile;
        }
        catch(PidException pe) {
            throw pe;
        }
        catch(Exception e) {
            throw new PidException(e.getMessage());
        }
    }

    private static boolean ok(String s) {
        return s != null && s.length() > 0;
    }

    private static class PidException extends Exception {

        public PidException(String s) {
            super(s);
        }
    }
    private final static LocalStringsImpl strings = new LocalStringsImpl(SystemTasks.class);
}

