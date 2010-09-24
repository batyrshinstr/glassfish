/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2010 Oracle and/or its affiliates. All rights reserved.
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

/*
 * ListManager.java
 *
 * Created on March 30, 2004, 9:01 PM
 */

package com.sun.enterprise.backup;

import com.sun.enterprise.admin.util.ColumnFormatter;
import com.sun.enterprise.backup.util.FileUtils;
import com.sun.enterprise.universal.i18n.LocalStringsImpl;

import java.io.*;
import java.util.*;


/**
 *
 * This class is responsible for returning information about backups.
 * It opens each backup zip file and examines the properties file for the 
 * information that was stored when the backup was performed.
 * It returns all this information to CLI as a String.
 *
 * @author  bnevins
 */
public class ListManager extends BackupRestoreManager
{

    /** Creates an instance of ListManager.
     * The superclass will call init() so it is
     * possible for Exceptions to be thrown.
     * @param req The BackupRequest instance with required information.
     * @throws BackupException if there is a fatal error with the 
     * BackupRequest object.
     * @throws BackupWarningException if there is a non-fatal error with the 
     * BackupRequest object.
     */
    public ListManager(BackupRequest req) 
        throws BackupException, BackupWarningException {

        super(req);
    }

    /** 
     * Find all backup zip files in a domain and return a String
     * summarizing information about the backup.
     * The summary is shorter if the "terse" option is true.
     * @return a String summary
     * @throws BackupException if there is a fatal error
     */
    public String list() throws BackupException {
        StringBuffer sb = new StringBuffer();
        String headings[] = { BACKUP, USER, DATE, FILENAME };
        List<Integer> badPropsList = null;
        ColumnFormatter cf = null;
        boolean itemInRow = false;
        
        // If a backup config was not provided then look for all zips
        // including those in the backup config directories.
        // If a backup config was provided then only look for zips in
        // that backup config directory.
        findZips(request.backupConfig == null);

        // it is GUARANTEED that the length > 0
        for(int i = 0; i < zips.length; i++) {
            Status status = new Status();

            if (request.verbose) {
                sb.append(status.read(zips[i], request.terse));
                sb.append("\n\n");
            } else {
                if (cf == null) {
                    cf = new ColumnFormatter(headings);
                    badPropsList = new ArrayList<Integer>();
                }

                //XXX: if (request.terse)  How indicate no headers?

                if (!status.loadProps(zips[i])) {
                    badPropsList.add(new Integer(i));
                } else {
                    String filename = status.getFileName();

                    if (filename == null)
                        filename = strings.get("backup-list.unavailable");

                    cf.addRow(new Object[] {
                        status.getBackupConfigName(),
                        status.getUserName(),
                        status.getTimeStamp(),
                        filename
                    });

                    itemInRow = true;
                }
            }
        }

        if (cf != null) 
            sb.append(cf.toString());

        // If no items in the row and we are not in terse mode indicate
        // there was nothing to list.
        if (!itemInRow && !request.terse)
            sb.append("\n" + strings.get("backup-list.nothing"));

        // List any zips that had bad props.
        if (badPropsList != null && !badPropsList.isEmpty()) {
            sb.append("\n\n");
            sb.append(strings.get("backup-list.bad-props"));
            for (Integer iInt : badPropsList) {
                sb.append("\n");
                sb.append(zips[iInt.intValue()]);
            }
        }

        return sb.toString();
    }

    
    /**
     * Finish initializing the BackupRequest object.
     * note: this method is called by the super class...
     * @throws BackupException for fatal errors
     * @throws BackupWarningException for non-fatal errors - these are errors
     * where we can not continue execution.
     */    
    void init() throws BackupException, BackupWarningException {
        super.init();
        
        if(!FileUtils.safeIsDirectory(request.domainDir))
            throw new BackupException("backup-res.NoDomainDir",
                                      request.domainDir);

        // It's a warning to not exist...
        if(!FileUtils.safeIsDirectory(getBackupDirectory(request)))
            throw new BackupWarningException("backup-res.NoBackupDir", 
                                             getBackupDirectory(request));
    }
    
    /** Look through the backups directory/subdirectories and assemble
     * a list of all backup files found.
     *
     * @param  subdirs If true search the first level subdirectories too
     * @throws BackupWarningException if there are no backup zip files
     */    
    private void findZips(boolean subdirs) throws BackupWarningException {

        File[] dirs;
        File[] files;
        List<File>zipList = new ArrayList<File>();

        
        files = getBackupDirectory(request).listFiles(new ZipFilenameFilter());

        if (subdirs) {
            for (int i = 0; files != null && i < files.length; i++)
                zipList.add(files[i]);

            // Get the list of directories
            dirs = getBackupDirectory(request).listFiles(new DirectoryFilter());

            // For each directory we find in the domain's backup dir...
            for(int i = 0; dirs != null && i < dirs.length; i++) {
                files = dirs[i].listFiles(new ZipFilenameFilter());
                    // Add the files with a zip extension to the List
                    for (int j = 0; files != null && j < files.length; j++)
                        zipList.add(files[j]);
            }

            if (zipList.size() > 0) 
                zips = zipList.toArray(new File[0]);

        } else
            zips = files;

        if(zips == null || zips.length <= 0)
            throw new BackupWarningException("backup-res.NoBackupFiles", 
                                             getBackupDirectory(request));
    }

    private static final LocalStringsImpl strings =
                new LocalStringsImpl(ListManager.class);

    File[] zips;
    private static final String BACKUP = strings.get("backup-list.backup-config");
    private static final String USER = strings.get("backup-list.user-name");
    private static final String DATE = strings.get("backup-list.date");
    private static final String FILENAME = strings.get("backup-list.filename");
}
