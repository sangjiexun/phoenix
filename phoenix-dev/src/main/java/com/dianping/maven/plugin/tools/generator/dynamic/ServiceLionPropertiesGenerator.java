/**
 * Project: phoenix-router
 * 
 * File Created at 2013-4-15
 * $Id$
 * 
 * Copyright 2010 dianping.com.
 * All rights reserved.
 *
 * This software is the confidential and proprietary information of
 * Dianping Company. ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with dianping.com.
 */
package com.dianping.maven.plugin.tools.generator.dynamic;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;

import com.dianping.maven.plugin.tools.generator.BytemanScriptGenerator;
import com.dianping.maven.plugin.tools.scanner.Scanner;
import com.dianping.maven.plugin.tools.scanner.ServiceMetaScanner;
import com.dianping.maven.plugin.tools.scanner.ServicePortEntry;
import com.dianping.maven.plugin.tools.scanner.ServiceScanner;

/**
 * @author Leo Liang
 * 
 */
public class ServiceLionPropertiesGenerator {

    public void generate(File file, ServiceLionContext context) throws Exception {

        Scanner<ServicePortEntry> serviceMetaScanner = new ServiceMetaScanner();
        List<ServicePortEntry> servicePortList = serviceMetaScanner.scan(context.getServiceMetaConfig());
        Map<String, Integer> servicePortMapping = new HashMap<String, Integer>();
        for (ServicePortEntry entry : servicePortList) {
            servicePortMapping.put(entry.getService(), entry.getPort());
        }

        Map<String, String> serviceLionContents = new HashMap<String, String>();

        Scanner<String> serviceScanner = new ServiceScanner();
        for (Map.Entry<String, File> entry : context.getProjectBaseDirMapping().entrySet()) {
            Collection<File> allXmlFiles = FileUtils.listFiles(entry.getValue(), new String[] { "xml" }, true);

            List<String> serviceKeys = new ArrayList<String>();

            for (File xml : allXmlFiles) {
                serviceKeys.addAll(serviceScanner.scan(xml));
            }

            for (String serviceKey : serviceKeys) {
                serviceLionContents
                        .put(serviceKey, context.getServiceHost() + ":" + servicePortMapping.get(serviceKey));
            }

        }

        BytemanScriptGenerator bytemanScriptGenerator = new BytemanScriptGenerator();
        bytemanScriptGenerator.generate(file, serviceLionContents);

    }

}