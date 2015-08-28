/*
 * Copyright 2003-2015 MarkLogic Corporation

 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.marklogic.mapreduce;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.util.ReflectionUtils;

import com.marklogic.mapreduce.functions.LexiconFunction;
import com.marklogic.mapreduce.utilities.InternalUtilities;
import com.marklogic.xcc.AdhocQuery;
import com.marklogic.xcc.ContentSource;
import com.marklogic.xcc.RequestOptions;
import com.marklogic.xcc.ResultItem;
import com.marklogic.xcc.ResultSequence;
import com.marklogic.xcc.Session;
import com.marklogic.xcc.exceptions.RequestException;
import com.marklogic.xcc.exceptions.XccConfigException;
import com.marklogic.xcc.types.XSInteger;
import com.marklogic.xcc.types.XSString;

/**
 * MarkLogic-based InputFormat superclass, taking a generic key and value 
 * class. Use the provided subclasses to configure your job, such as 
 * {@link DocumentInputFormat}.
 * 
 * @author jchen
 */
public abstract class MarkLogicInputFormat<KEYIN, VALUEIN> 
extends InputFormat<KEYIN, VALUEIN> implements MarkLogicConstants {
    public static final Log LOG =
        LogFactory.getLog(MarkLogicInputFormat.class);
    static final String DEFAULT_DOCUMENT_SELECTOR = "fn:collection()";
    static final String DEFAULT_CTS_QUERY = "()";
    Configuration jobConf = null;
    String docSelector;
    
    private void appendNsBindings(StringBuilder buf) {
        Collection<String> nsCol = 
                jobConf.getStringCollection(PATH_NAMESPACE);        
            
        if (nsCol != null && !nsCol.isEmpty()) {
            boolean isAlias = true;    
            for (Iterator<String> nsIt = nsCol.iterator(); nsIt.hasNext();) {
                String ns = nsIt.next();
                if (isAlias) {
                    buf.append("declare namespace ");
                    buf.append(ns);
                    buf.append("=\"");
                    isAlias = false;
                } else {
                    buf.append(ns);
                    buf.append("\";");
                    isAlias = true;
                }
                if (nsIt.hasNext() && isAlias) {
                    buf.append('\n');
                }
            }
        }
    }
    
    private void appendDocumentSelector(StringBuilder buf) { 
        docSelector = jobConf.get(DOCUMENT_SELECTOR);
        if (docSelector != null) {
            buf.append(docSelector);
        } else {
            buf.append(DEFAULT_DOCUMENT_SELECTOR);
        }
    }
    
    private void appendQuery(StringBuilder buf) {
        String ctsQuery = jobConf.get(QUERY_FILTER);
        if (ctsQuery != null) {
            buf.append("\"cts:query(xdmp:unquote('");
            buf.append(ctsQuery.replaceAll("\"", "&#34;"));
            buf.append("')/*)\"");
        } else if (docSelector != null) {
            buf.append("'");
            buf.append(DEFAULT_CTS_QUERY); 
            buf.append("'");
        } else {
            Class<? extends LexiconFunction> lexiconClass = 
                    jobConf.getClass(INPUT_LEXICON_FUNCTION_CLASS, null,
                            LexiconFunction.class);
            if (lexiconClass != null) {
                LexiconFunction function = 
                        ReflectionUtils.newInstance(lexiconClass, jobConf);
                buf.append("'");
                buf.append(function.getLexiconQuery());
                buf.append("'");
            } else {
                buf.append("'");
                buf.append(DEFAULT_CTS_QUERY);
                buf.append("'");
            }
        }
    }

    /**
     * Get input splits.
     * @param jobContext job context
     * @return list of input splits    
     */
    @SuppressWarnings("unchecked")
    @Override
    public List<InputSplit> getSplits(JobContext jobContext) throws IOException,
            InterruptedException { 
        // get input from job configuration
        jobConf = jobContext.getConfiguration();
        
        boolean advancedMode = 
                jobConf.get(INPUT_MODE, BASIC_MODE).equals(ADVANCED_MODE);
        String splitQuery;
        String queryLanguage = null;
        if (advancedMode) {
            queryLanguage = jobConf.get(INPUT_QUERY_LANGUAGE);
            splitQuery = jobConf.get(SPLIT_QUERY);
        } else {
            StringBuilder buf = new StringBuilder();
            buf.append("xquery version \"1.0-ml\";\n");
            buf.append("import module namespace hadoop = ");
            buf.append("\"http://marklogic.com/xdmp/hadoop\" at ");
            buf.append("\"/MarkLogic/hadoop.xqy\";\n");
            buf.append("xdmp:host-name(xdmp:host()),\n");
            buf.append("hadoop:get-splits(\'"); 
            appendNsBindings(buf);
            buf.append("\', \'");
            appendDocumentSelector(buf);
            buf.append("\',");
            appendQuery(buf);
            buf.append(')');
            splitQuery = buf.toString();
        } 
        
        String mode = jobConf.get(EXECUTION_MODE,MODE_DISTRIBUTED);
        long defaultSplitSize = mode.equals(MODE_DISTRIBUTED) ? 
            DEFAULT_MAX_SPLIT_SIZE : DEFAULT_LOCAL_MAX_SPLIT_SIZE;
        long maxSplitSize = jobConf.getLong(MAX_SPLIT_SIZE, defaultSplitSize);
        if (maxSplitSize <= 0) {
            throw new IllegalStateException(
                "Max split size is required to be positive. It is set to " +
                maxSplitSize);
        }
        
        // fetch data from server
        List<ForestSplit> forestSplits = null;
        Session session = null;
        ResultSequence result = null;            
        
        if (LOG.isDebugEnabled()) {
            LOG.debug("Split query: " + splitQuery);
        }
        boolean localMode = MODE_LOCAL.equals(jobConf.get(EXECUTION_MODE));
        String localHost = null;
        try {
            ContentSource cs = InternalUtilities.getInputContentSource(jobConf);
            session = cs.newSession();
            RequestOptions options = new RequestOptions();
            options.setCacheResult(false);
            
            if (localMode && advancedMode) {
                AdhocQuery hostQuery = session.newAdhocQuery(
                    "xquery version \"1.0-ml\";xdmp:host-name(xdmp:host())");
                hostQuery.setOptions(options);
                result = session.submitRequest(hostQuery);
                if(result.hasNext()) {
                    ResultItem item = result.next();
                    localHost = item.asString();
                }
                if (result != null) {
                    result.close();
                }
            }
            
            AdhocQuery query = session.newAdhocQuery(splitQuery);
            if (queryLanguage != null) {
                InternalUtilities.checkQueryLanguage(queryLanguage);
                options.setQueryLanguage(queryLanguage);
            }
            query.setOptions(options);
            result = session.submitRequest(query);
            
            if (!advancedMode && result.hasNext()) {
                ResultItem item = result.next();
                localHost = item.asString();
            }
            
            int count = 0;
            while (result.hasNext()) {
                ResultItem item = result.next();
                if (forestSplits == null) {
                    forestSplits = new ArrayList<ForestSplit>();
                }
                int index = count % 3;
                if (index == 0) {
                    ForestSplit split = new ForestSplit();
                    split.forestId = ((XSInteger)item.getItem()).asBigInteger();
                    forestSplits.add(split);
                } else if (index == 1) {
                    forestSplits.get(forestSplits.size() - 1).recordCount = 
                        ((XSInteger)item.getItem()).asLong();
                } else if (index == 2) {
                    String hn = ((XSString) item.getItem()).asString();
                    if (localMode && hn.equals(localHost)) {
                        String inputHost = jobConf.get(INPUT_HOST);
                        forestSplits.get(forestSplits.size() - 1).hostName = inputHost;
                    } else {
                        forestSplits.get(forestSplits.size() - 1).hostName = hn;
                    }
                }
                count++;
            }
            LOG.info("Fetched " + 
                    (forestSplits == null ? 0 : forestSplits.size()) + 
                    " forest splits.");            
        } catch (XccConfigException e) {
            LOG.error(e);
            throw new IOException(e);
        } catch (RequestException e) {
            LOG.error(e);
            LOG.error("Query: " + splitQuery);
            throw new IOException(e);
        } catch (URISyntaxException e) {
            LOG.error(e);
            throw new IOException(e);
        } finally {
            if (result != null) {
                result.close();
            }
            if (session != null) {
                session.close();
            }
        }
        
        // create a split list per forest per host
        if (forestSplits == null || forestSplits.isEmpty()) {
            return new ArrayList<InputSplit>();
        }
        
        // construct a list of splits per forest per host
        Map<String, List<List<InputSplit>>> hostForestSplits = 
            new HashMap<String, List<List<InputSplit>>>();
        boolean tsQuery = (jobConf.get(INPUT_QUERY_TIMESTAMP) != null);
        for (int i = 0; i < forestSplits.size(); i++) {
            ForestSplit fsplit = forestSplits.get(i);
            List<InputSplit> splits = null;
            if (fsplit.recordCount > 0 || !tsQuery) {
                String host = fsplit.hostName;
                List<List<InputSplit>> splitLists = hostForestSplits.get(host);
                if (splitLists == null) {
                    splitLists = new ArrayList<List<InputSplit>>();
                    hostForestSplits.put(host, splitLists);
                }
                splits = new ArrayList<InputSplit>();
                splitLists.add(splits);
            } else {
                continue;
            }
            if (fsplit.recordCount < maxSplitSize) {
                MarkLogicInputSplit split = 
                    new MarkLogicInputSplit(0, fsplit.recordCount, 
                            fsplit.forestId, fsplit.hostName);
                split.setLastSplit(true);
                splits.add(split);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Added split " + split);
                }    
            } else {
                long splitCount = fsplit.recordCount / maxSplitSize;
                long remainder = fsplit.recordCount % maxSplitSize;
                if (remainder != 0) {
                    splitCount++;
                }
                long splitSize = fsplit.recordCount / splitCount;
                remainder = fsplit.recordCount % splitCount;
                if (remainder != 0) {
                    splitSize++;
                }
                if (this instanceof KeyValueInputFormat<?, ?>) {
                    // each split size has to be an even number
                    if ((splitSize & 0x1) != 0) {
                        splitSize++;
                    }
                }
                long remainingCount = fsplit.recordCount;
                while (remainingCount > 0) {
                    long start = fsplit.recordCount - remainingCount; 
                    long length = splitSize;
                    MarkLogicInputSplit split = 
                        new MarkLogicInputSplit(start, length, fsplit.forestId,
                                fsplit.hostName);
                    if (remainingCount <= maxSplitSize) {
                        split.setLastSplit(true);
                    }
                    splits.add(split);
                    remainingCount -= length;
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Added split " + split);
                    }
                }
            }
        }
        
        // mix the lists of splits into one per host
        Set<String> hosts = hostForestSplits.keySet();
        int hostCount = hosts.size();
        List<InputSplit>[] hostSplits = (List<InputSplit>[])
                new List<?>[hostCount];
        int i = 0;
        for (String host : hosts) {
            List<List<InputSplit>> splitLists = hostForestSplits.get(host);
            if (splitLists.size() == 1) {
                hostSplits[i++] = splitLists.get(0);
            } else {
                hostSplits[i] = new ArrayList<InputSplit>();
                boolean more = true;
                for (int j = 0; more; j++) {
                    more = false;
                    for (List<InputSplit> splitsPerForest : splitLists) {
                        if (j < splitsPerForest.size()) {
                            hostSplits[i].add(splitsPerForest.get(j));
                        }
                        more = more || (j + 1 < splitsPerForest.size());
                    }
                }
                i++;
            }
        }
        
        // mix hostSplits into one
        List<InputSplit> splitList = new ArrayList<InputSplit>();
        boolean more = true;
        for (int j = 0; more; j++) {
            more = false;
            for (List<InputSplit> splitsPerHost : hostSplits) {
                if (j < splitsPerHost.size()) {
                    splitList.add(splitsPerHost.get(j));
                }
                more = more || (j + 1 < splitsPerHost.size());
            }
        }
        
        LOG.info("Made " + splitList.size() + " splits.");
        if (LOG.isDebugEnabled()) {
            for (InputSplit split : splitList) {
                LOG.debug(split);
            }
        }
        return splitList;
    }

    /**
     * A per-forest split of the result records.
     * 
     * @author jchen
     */
    class ForestSplit {
        BigInteger forestId;
        String hostName;
        long recordCount;
    }
}
