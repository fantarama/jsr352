/*
 * Copyright (c) 2013 Red Hat, Inc. and/or its affiliates.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * Cheng Fang - Initial API and implementation
 */

package org.jberet.job.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Properties;
import javax.batch.operations.BatchRuntimeException;

import org.jberet.job.model.Transition.End;
import org.jberet.job.model.Transition.Fail;
import org.jberet.job.model.Transition.Next;
import org.jberet.job.model.Transition.Stop;

import static org.jberet.util.BatchLogger.LOGGER;

public final class PropertyResolver {
    protected static final String jobParametersToken = "jobParameters";
    protected static final String jobPropertiesToken = "jobProperties";
    protected static final String systemPropertiesToken = "systemProperties";
    protected static final String partitionPlanToken = "partitionPlan";

    private static final String prefix = "#{";
    private static final String defaultValuePrefix = "?:";

    private static final int shortestTemplateLen = "#{jobProperties['x']}".length();
    private static final int prefixLen = prefix.length();

    private Properties systemProperties = System.getProperties();
    private Properties jobParameters;
    private Properties partitionPlanProperties;
    private Deque<org.jberet.job.model.Properties> jobPropertiesStack = new ArrayDeque<org.jberet.job.model.Properties>();

    private boolean resolvePartitionPlanProperties;

    public void setSystemProperties(Properties systemProperties) {
        this.systemProperties = systemProperties;
    }

    public void setJobParameters(Properties jobParameters) {
        this.jobParameters = jobParameters;
    }

    public void setPartitionPlanProperties(Properties partitionPlanProperties) {
        this.partitionPlanProperties = partitionPlanProperties;
    }

    public void pushJobProperties(org.jberet.job.model.Properties jobProps) {
        this.jobPropertiesStack.push(jobProps);
    }

    public void setResolvePartitionPlanProperties(boolean resolvePartitionPlanProperties) {
        this.resolvePartitionPlanProperties = resolvePartitionPlanProperties;
    }

    /**
     * Resolves property expressions for job-level elements contained in the job.
     * The job's direct job properties, job parameters and system properties should already have been set up properly,
     * e.g., when this instance was instantiated.
     *
     * @param job the job element whose properties need to be resolved
     */
    public void resolve(Job job) {
        String oldVal, newVal;
        oldVal = job.getRestartable();
        if (oldVal != null) {
            newVal = resolve(oldVal);
            if (!oldVal.equals(newVal)) {
                job.setRestartable(newVal);
            }
        }

        org.jberet.job.model.Properties props = job.getProperties();
        //do not push or pop the top-level properties.  They need to be sticky and may be referenced by lower-level props
        resolve(props, false);

        resolve(job.getListeners());
        resolveJobElements(job.getJobElements());

        if (props != null) {  // the properties instance to pop is different from job.getProperties (a clone).
            jobPropertiesStack.pop();
        }
    }

    private void resolveJobElements(List<?> jobElements) {
        if (jobElements == null) {
            return;
        }
        for (Object e : jobElements) {
            if (e instanceof Step) {
                resolve((Step) e);
            } else if (e instanceof Flow) {
                resolve((Flow) e);
            } else if (e instanceof Decision) {
                resolve((Decision) e);
            } else if (e instanceof Split) {
                resolve((Split) e);
            }
        }
    }

    /**
     * Resolves property expressions for step-level elements contained in the step.
     * The step's direct job properties, job parameters and system properties should already have been set up properly,
     * e.g., when this instance was instantiated.
     *
     * @param step the step element whose properties need to be resolved
     */
    public void resolve(Step step) {
        resolve(step.getPartition());
        String oldVal, newVal;
        oldVal = step.getAttributeNext();
        if (oldVal != null) {
            newVal = resolve(oldVal);
            if (!oldVal.equals(newVal)) {
                step.setAttributeNext(newVal);
            }
        }
        oldVal = step.getAllowStartIfComplete();
        if (oldVal != null) {
            newVal = resolve(oldVal);
            if (!oldVal.equals(newVal)) {
                step.setAllowStartIfComplete(newVal);
            }
        }
        oldVal = step.getStartLimit();
        if (oldVal != null) {
            newVal = resolve(oldVal);
            if (!oldVal.equals(newVal)) {
                step.setStartLimit(newVal);
            }
        }
        org.jberet.job.model.Properties props = step.getProperties();
        resolve(props, false);
        resolve(step.getListeners());
        RefArtifact batchlet = step.getBatchlet();

        if (batchlet != null) {
            oldVal = batchlet.getRef();
            newVal = resolve(oldVal);
            if (!oldVal.equals(newVal)) {
                batchlet.setRef(newVal);
            }
            resolve(batchlet.getProperties(), true);
        }
        resolve(step.getChunk());
        resolveTransitionElements(step.getTransitionElements());

        if (props != null) {
            jobPropertiesStack.pop();
        }
    }

    private void resolve(Partition partition) {
        if (partition == null) {
            return;
        }
        String oldVal, newVal;
        RefArtifact analyzer = partition.getAnalyzer();
        if (analyzer != null) {
            oldVal = analyzer.getRef();
            newVal = resolve(oldVal);
            if (!oldVal.equals(newVal)) {
                analyzer.setRef(newVal);
            }
            resolve(analyzer.getProperties(), true);
        }
        RefArtifact collector = partition.getCollector();
        if (collector != null) {
            oldVal = collector.getRef();
            newVal = resolve(oldVal);
            if (!oldVal.equals(newVal)) {
                collector.setRef(newVal);
            }
            resolve(collector.getProperties(), true);

        }
        RefArtifact reducer = partition.getReducer();
        if (reducer != null) {
            oldVal = reducer.getRef();
            newVal = resolve(oldVal);
            if (!oldVal.equals(newVal)) {
                reducer.setRef(newVal);
            }
            resolve(reducer.getProperties(), true);
        }
        PartitionPlan plan = partition.getPlan();
        if (plan != null) {
            oldVal = plan.getPartitions();
            if (oldVal != null) {
                newVal = resolve(oldVal);
                if (!oldVal.equals(newVal)) {
                    plan.setPartitions(newVal);
                }
            }
            oldVal = plan.getThreads();
            if (oldVal != null) {
                newVal = resolve(oldVal);
                if (!oldVal.equals(newVal)) {
                    plan.setThreads(newVal);
                }
            }
            //plan properties should be resolved at job load time (1st pass)
            if (!resolvePartitionPlanProperties) {
                for (org.jberet.job.model.Properties p : plan.getPropertiesList()) {
                    resolve(p, true);
                }
            }
        }
        RefArtifact mapper = partition.getMapper();
        if (mapper != null) {
            oldVal = mapper.getRef();
            newVal = resolve(oldVal);
            if (!oldVal.equals(newVal)) {
                mapper.setRef(newVal);
            }
            //mapper properties should be resolved at job load time (1st pass)
            if (!resolvePartitionPlanProperties) {
                resolve(mapper.getProperties(), true);
            }
        }
    }

    private void resolve(Chunk chunk) {
        if (chunk == null) {
            return;
        }
        resolve(chunk.getSkippableExceptionClasses());
        resolve(chunk.getRetryableExceptionClasses());
        resolve(chunk.getNoRollbackExceptionClasses());

        String oldVal, newVal;
        oldVal = chunk.getCheckpointPolicy();
        if (oldVal != null) {
            newVal = resolve(oldVal);
            if (!oldVal.equals(newVal)) {
                chunk.setCheckpointPolicy(newVal);
            }
        }
        oldVal = chunk.getItemCount();
        if (oldVal != null) {
            newVal = resolve(oldVal);
            if (!oldVal.equals(newVal)) {
                chunk.setItemCount(newVal);
            }
        }
        oldVal = chunk.getTimeLimit();
        if (oldVal != null) {
            newVal = resolve(oldVal);
            if (!oldVal.equals(newVal)) {
                chunk.setTimeLimit(newVal);
            }
        }
        oldVal = chunk.getSkipLimit();
        if (oldVal != null) {
            newVal = resolve(oldVal);
            if (!oldVal.equals(newVal)) {
                chunk.setSkipLimit(newVal);
            }
        }
        oldVal = chunk.getRetryLimit();
        if (oldVal != null) {
            newVal = resolve(oldVal);
            if (!oldVal.equals(newVal)) {
                chunk.setRetryLimit(newVal);
            }
        }

        RefArtifact reader = chunk.getReader();
        oldVal = reader.getRef();
        newVal = resolve(oldVal);
        if (!oldVal.equals(newVal)) {
            reader.setRef(newVal);
        }
        resolve(reader.getProperties(), true);

        RefArtifact writer = chunk.getWriter();
        oldVal = writer.getRef();
        newVal = resolve(oldVal);
        if (!oldVal.equals(newVal)) {
            writer.setRef(newVal);
        }
        resolve(writer.getProperties(), true);

        RefArtifact processor = chunk.getProcessor();
        if (processor != null) {
            oldVal = processor.getRef();
            newVal = resolve(oldVal);
            if (!oldVal.equals(newVal)) {
                processor.setRef(newVal);
            }
            resolve(processor.getProperties(), true);
        }

        RefArtifact checkpointAlgorithm = chunk.getCheckpointAlgorithm();
        if (checkpointAlgorithm != null) {
            oldVal = checkpointAlgorithm.getRef();
            if (oldVal != null) {  //TODO remove the if, ref attr should be required
                newVal = resolve(oldVal);
                if (!oldVal.equals(newVal)) {
                    checkpointAlgorithm.setRef(newVal);
                }
            }
            resolve(checkpointAlgorithm.getProperties(), true);
        }
    }

    private void resolve(ExceptionClassFilter filter) {
        if (filter == null) {
            return;
        }
        resolveIncludeOrExclude(filter.getInclude());
        resolveIncludeOrExclude(filter.getExclude());
    }

    private void resolveIncludeOrExclude(List<String> clude) {
        for (ListIterator<String> it = clude.listIterator(); it.hasNext();) {
            String oldVal = it.next();
            String newVal = resolve(oldVal);
            if (!oldVal.equals(newVal)) {
                it.set(newVal);
            }
        }
    }

    private void resolve(Split split) {
        String oldVal = split.getAttributeNext();
        if (oldVal != null) {
            String newVal = resolve(oldVal);
            if (!oldVal.equals(newVal)) {
                split.setAttributeNext(newVal);
            }
        }
        for (Flow e : split.getFlows()) {
            resolve(e);
        }
    }

    public void resolve(Flow flow) {
        String oldVal = flow.getAttributeNext();
        if (oldVal != null) {
            String newVal = resolve(oldVal);
            if (!oldVal.equals(newVal)) {
                flow.setAttributeNext(newVal);
            }
        }
        resolveTransitionElements(flow.getTransitionElements());
        resolveJobElements(flow.getJobElements());
    }

    private void resolve(org.jberet.job.model.Properties props, boolean popProps) {
        if (props == null) {
            return;
        }

        //push individual property to resolver one by one, so only those properties that are already defined can be
        //visible to the resolver.
        org.jberet.job.model.Properties propsToPush = new org.jberet.job.model.Properties();
        jobPropertiesStack.push(propsToPush);

        try {
            final String oldPartitionVal = props.getPartition();
            if (oldPartitionVal != null) {
                final String newPartitionVal = resolve(oldPartitionVal);
                if (!oldPartitionVal.equals(newPartitionVal)) {
                    props.setPartition(newPartitionVal);
                }
            }

            Map<String, String> propertiesMapping = props.getPropertiesMapping();
            for (final Map.Entry<String, String> entry : propertiesMapping.entrySet()) {
                final String oldKey = entry.getKey();
                final String newKey = resolve(oldKey);
                final String oldVal = entry.getValue();
                final String newVal = resolve(oldVal);
                if (oldKey.equals(newKey)) {
                    if (!oldVal.equals(newVal)) {
                        props.add(newKey, newVal);
                    }
                } else {
                    props.remove(oldKey);
                    props.add(newKey, newVal);
                }
                propsToPush.add(newKey, newVal);
            }
        } finally {
            if (popProps) {
                jobPropertiesStack.pop();
            }
        }
    }

    private void resolve(List<RefArtifact> listeners) {
        if (listeners == null) {
            return;
        }
        for (RefArtifact l : listeners) {
            String oldVal = l.getRef();
            String newVal = resolve(oldVal);
            if (!oldVal.equals(newVal)) {
                l.setRef(newVal);
            }
            resolve(l.getProperties(), true);
        }
    }

    private void resolve(Decision decision) {
        String oldVal = decision.getRef();
        String newVal = resolve(oldVal);
        if (!oldVal.equals(newVal)) {
            decision.setRef(newVal);
        }
        resolve(decision.getProperties(), true);
        resolveTransitionElements(decision.getTransitionElements());
    }

    private void resolveTransitionElements(List<?> transitions) {
        String oldVal, newVal;
        for (Object e : transitions) {
            if (e instanceof Next) {
                Next next = (Next) e;
                oldVal = next.getTo();
                newVal = resolve(oldVal);
                if (!oldVal.equals(newVal)) {
                    next.setTo(newVal);
                }
                oldVal = next.getOn();
                newVal = resolve(oldVal);
                if (!oldVal.equals(newVal)) {
                    next.setOn(newVal);
                }
            } else if (e instanceof Fail) {
                Fail fail = (Fail) e;
                oldVal = fail.getOn();
                newVal = resolve(oldVal);
                if (!oldVal.equals(newVal)) {
                    fail.setOn(newVal);
                }
                oldVal = fail.getExitStatus();
                if (oldVal != null) {
                    newVal = resolve(oldVal);
                    if (!oldVal.equals(newVal)) {
                        fail.setExitStatus(newVal);
                    }
                }
            } else if (e instanceof End) {
                End end = (End) e;
                oldVal = end.getOn();
                newVal = resolve(oldVal);
                if (!oldVal.equals(newVal)) {
                    end.setOn(newVal);
                }
                oldVal = end.getExitStatus();
                if (oldVal != null) {
                    newVal = resolve(oldVal);
                    if (!oldVal.equals(newVal)) {
                        end.setExitStatus(newVal);
                    }
                }
            } else if (e instanceof Stop) {
                Stop stop = (Stop) e;
                oldVal = stop.getOn();
                newVal = resolve(oldVal);
                if (!oldVal.equals(newVal)) {
                    stop.setOn(newVal);
                }
                oldVal = stop.getExitStatus();
                if (oldVal != null) {
                    newVal = resolve(oldVal);
                    if (!oldVal.equals(newVal)) {
                        stop.setExitStatus(newVal);
                    }
                }
                oldVal = stop.getRestart();
                if (oldVal != null) {
                    newVal = resolve(oldVal);
                    if (!oldVal.equals(newVal)) {
                        stop.setRestart(newVal);
                    }
                }
            }
        }
    }




    public String resolve(String rawVale) {
        if (rawVale.length() < shortestTemplateLen || !rawVale.contains(prefix)) {
            return rawVale;
        }
        StringBuilder sb = new StringBuilder(rawVale);
        try {
            resolve(sb, 0, true, null);
        } catch (BatchRuntimeException e) {
            LOGGER.unresolvableExpression(e.getMessage());
            return null;
        }
        return sb.toString();
    }

    private void resolve(StringBuilder sb, int start, boolean defaultAllowed, LinkedList<String> referringExpressions)
                                        throws BatchRuntimeException {
        //distance-to-end doesn't have space for any template, so no variable referenced
        if (sb.length() - start < shortestTemplateLen) {
            return;
        }
        int startExpression = sb.indexOf(prefix, start);
        if (startExpression < 0) {    //doesn't reference any variable
            return;
        }
        int startPropCategory = startExpression + prefixLen;
        int openBracket = sb.indexOf("[", startPropCategory);
        String propCategory = sb.substring(startPropCategory, openBracket);
        int startVariableName = openBracket + 2;  //jump to the next char after ', the start of variable name
        int endBracket = sb.indexOf("]", startVariableName + 1);
        int endExpression = endBracket + 1;

        if (endExpression >= sb.length()) {
            //this can happen when missing an ending } (e.g.,   #{jobProperties['step-prop']   )
            LOGGER.possibleSyntaxErrorInProperty(sb.toString());
            endExpression = sb.length() - 1;
        }

        int endCurrentPass = endExpression;
        String expression = sb.substring(startExpression, endExpression + 1);
        if (referringExpressions != null && referringExpressions.contains(expression)) {
            throw LOGGER.cycleInPropertyReference(referringExpressions);
        }

        if (!resolvePartitionPlanProperties && propCategory.equals(partitionPlanToken)) {
            resolve(sb, endCurrentPass + 1, true, null);
            return;
        }

        String variableName = sb.substring(startVariableName, endBracket - 1);  // ['abc']
        String val = getPropertyValue(variableName, propCategory, sb);
        if (val != null) {
            val = reresolve(expression, val, defaultAllowed, referringExpressions);
        }

        if (!defaultAllowed) {  //a default expression should not have default again
            if (val == null) {
                //not resolved:
                //in xml space, unresolved properties is set to "",for example, a#{jobProperties['no.such.prop']}b => ab
                //when injecting unresolved properties to artifact class, null is injected for property value "".
                //throw LOGGER.unresolvableExpressionException(sb.toString());
                val = "";
            }
            endCurrentPass = replaceAndGetEndPosition(sb, startExpression, endExpression, val);
        } else {
            int startDefaultMarker = endExpression + 1;
            int endDefaultMarker = startDefaultMarker + 1;  //?:
            String next2Chars = null;
            if (endDefaultMarker >= sb.length()) {
                //no default value expression
            } else {
                next2Chars = sb.substring(startDefaultMarker, endDefaultMarker + 1);
            }
            boolean hasDefault = defaultValuePrefix.equals(next2Chars);

            int endDefaultExpressionMarker = sb.indexOf(";", endDefaultMarker + 1);
            if (endDefaultExpressionMarker < 0) {
                endDefaultExpressionMarker = sb.length();
            }

            if (val != null) {
                if (!hasDefault) {  //resolved, no default: replace the expression with value
                    endCurrentPass = replaceAndGetEndPosition(sb, startExpression, endExpression, val);
                } else {  //resolved, has default: replace    the expression and the default expression    with value
                    endCurrentPass = replaceAndGetEndPosition(sb, startExpression, endDefaultExpressionMarker, val);
                }
            } else {
                if (!hasDefault) {  //not resolved, no default:
                    //throw LOGGER.unresolvableExpressionException(sb.toString());
                    endCurrentPass = replaceAndGetEndPosition(sb, startExpression, endExpression, "");
                } else {  //not resolved, has default: resolve and apply the default
                    StringBuilder sb4DefaultExpression = new StringBuilder(sb.substring(endDefaultMarker + 1, endDefaultExpressionMarker));
                    resolve(sb4DefaultExpression, 0, false, null);
                    endCurrentPass = replaceAndGetEndPosition(sb, startExpression, endDefaultExpressionMarker, sb4DefaultExpression.toString());
                }
            }
        }

        resolve(sb, endCurrentPass + 1, true, null);
    }

    private String reresolve(String expression, String currentlyResolvedToVal, boolean defaultAllowed, LinkedList<String> referringExpressions)
                                throws BatchRuntimeException {
        if (currentlyResolvedToVal.length() < shortestTemplateLen || !currentlyResolvedToVal.contains(prefix)) {
            return currentlyResolvedToVal;
        }
        if (referringExpressions == null) {
            referringExpressions = new LinkedList<String>();
        }
        referringExpressions.add(expression);
        StringBuilder sb = new StringBuilder(currentlyResolvedToVal);

        try {
            resolve(sb, 0, defaultAllowed, referringExpressions);
        } catch (BatchRuntimeException e) {
            LOGGER.unresolvableExpression(e.getMessage());
            return null;
        }
        return sb.toString();
    }

    private int replaceAndGetEndPosition(StringBuilder sb, int startExpression, int endExpression, String replacingVal) {
        sb.replace(startExpression, endExpression + 1, replacingVal);
        return startExpression - 1 + replacingVal.length();
    }

    private String getPropertyValue(String variableName, String propCategory, StringBuilder sb) {
        String val = null;
        if (propCategory.equals(jobParametersToken)) {
            if (jobParameters != null) {
                val = jobParameters.getProperty(variableName);
            }
        } else if (propCategory.equals(jobPropertiesToken)) {
            for (org.jberet.job.model.Properties p : jobPropertiesStack) {
                val = p.get(variableName);
                if (val != null) {
                    break;
                }
            }
        } else if (propCategory.equals(systemPropertiesToken)) {
            val = systemProperties.getProperty(variableName);
        } else if (propCategory.equals(partitionPlanToken)) {
            if (partitionPlanProperties != null) {
                val = partitionPlanProperties.getProperty(variableName);
            }
        } else {
            LOGGER.unrecognizedPropertyReference(propCategory, variableName, sb.toString());
        }
        return val;
    }

}