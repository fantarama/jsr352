/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
 
package org.mybatch.testapps.loadBatchXml;

import javax.batch.annotation.BatchProperty;
import javax.batch.api.AbstractJobListener;
import javax.batch.api.JobListener;
import javax.batch.runtime.context.JobContext;
import javax.inject.Inject;
import javax.inject.Named;

import org.junit.Assert;

@Named("L1")
public class JobListener1 extends AbstractJobListener implements JobListener {
    @BatchProperty(name="job-prop")
    private String jobProp;  //nothing is injected

    @BatchProperty(name = "listener-prop")
    private String listenerProp;  //injected

    @BatchProperty(name = "reference-job-prop")
    private Object referenceJobProp;

    @BatchProperty(name="reference-job-param")
    private String referenceJobParam;

    @BatchProperty(name="reference-system-property")
    private String referenceSystemProperty;

    @Inject
    private JobContext jobContext;

    @Override
    public void beforeJob() throws Exception {
        System.out.printf("In beforeJob of %s%n", this);
        Assert.assertEquals(null, jobProp);
        Assert.assertEquals("listener-prop", listenerProp);
        Assert.assertEquals("job-prop", referenceJobProp);
        Assert.assertEquals("job-param", referenceJobParam);
        Assert.assertEquals(System.getProperty("java.version"), referenceSystemProperty);

        Assert.assertEquals(2, jobContext.getProperties().size());
        Assert.assertEquals("job-prop", jobContext.getProperties().get("job-prop"));
    }

    @Override
    public void afterJob() throws Exception {
        System.out.printf("In afterJob of %s%n", this);
    }
}