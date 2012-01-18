/*
 * Copyright (c) 2003-2012 MarkLogic Corporation. All rights reserved.
 */
package com.marklogic.mapreduce;

/**
 * Type of supported node operations.
 * 
 * <p>
 *  When using {@link NodeOutputFormat}, set the configuration property
 *  <code>mapreduce.marklogic.output.node.optype</code> to
 *  one of these values to control how the output node value is stored
 *  relative to the {@link NodePath} key.
 * </p>
 * <p>
 *  For example if the <code>node.optype</code> is set to "INSERT_BEFORE",
 *  then the new node is inserted as an immediately preceding sibling of
 *  the node addressed by the node path in the key.
 * </p>
 * <p>
 *  For more information, see the following built-in functions in the
 *  <em>XQuery & XSLT API Reference</em>:
 *  <ul>
 *   <li>xdmp:node-insert-before</li>
 *   <li>xdmp:node-insert-after</li>
 *   <li>xdmp:node-insert-child</li>
 *   <li>xdmp:node-insert-replace</li>
 *  </ul>
 * </p>
 * 
 * @author jchen
 */
public enum NodeOpType {
    INSERT_BEFORE {
        public String getFunctionName() {
            return "xdmp:node-insert-before";
        }
    },
    INSERT_AFTER {
        public String getFunctionName() {
            return "xdmp:node-insert-after";
        }
    },
    INSERT_CHILD {
        public String getFunctionName() {
            return "xdmp:node-insert-child";
        }
    },
    REPLACE {
        public String getFunctionName() {
            return "xdmp:node-replace";
        }
    };
    
    abstract public String getFunctionName();
    
    public String getQuery(String namespace) {
        StringBuilder buf = new StringBuilder();
        buf.append("xquery version \"1.0-ml\"; \n");
        buf.append("declare variable $");
        buf.append(NodeWriter.PATH_VARIABLE_NAME);
        buf.append(" as xs:string external;\n");
        buf.append("declare variable $");
        buf.append(NodeWriter.NODE_VARIABLE_NAME);
        buf.append(" as element() external;\n");
        buf.append("xdmp:with-namespaces((");
        buf.append(namespace);
        buf.append("),");
        buf.append(getFunctionName());
        buf.append("(xdmp:unpath($");
        buf.append(NodeWriter.PATH_VARIABLE_NAME);
        buf.append("), $");
        buf.append(NodeWriter.NODE_VARIABLE_NAME);
        buf.append("))");
        return buf.toString(); 
    }
}
