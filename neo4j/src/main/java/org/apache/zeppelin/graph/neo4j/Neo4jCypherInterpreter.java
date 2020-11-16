/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zeppelin.graph.neo4j;

import org.apache.commons.lang3.StringUtils;
import org.neo4j.driver.internal.types.InternalTypeSystem;
import org.neo4j.driver.internal.util.Iterables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;
import org.neo4j.driver.types.TypeSystem;
import org.neo4j.driver.util.Pair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import org.apache.zeppelin.graph.neo4j.utils.Neo4jConversionUtils;
import org.apache.zeppelin.interpreter.Interpreter;
import org.apache.zeppelin.interpreter.InterpreterContext;
import org.apache.zeppelin.interpreter.InterpreterResult;
import org.apache.zeppelin.interpreter.InterpreterResult.Code;
import org.apache.zeppelin.interpreter.graph.GraphResult;
import org.apache.zeppelin.scheduler.Scheduler;
import org.apache.zeppelin.scheduler.SchedulerFactory;

/**
 * Neo4j interpreter for Zeppelin.
 */
public class Neo4jCypherInterpreter extends Interpreter {

  private static final Logger LOGGER = LoggerFactory.getLogger(Neo4jCypherInterpreter.class);

  private static final String TABLE = "%table";
  public static final String NEW_LINE = "\n";
  public static final String TAB = "\t";

  private static final String MAP_KEY_TEMPLATE = "%s.%s";

  private Map<String, String> labels;

  private Set<String> types;

  private final Neo4jConnectionManager neo4jConnectionManager;

  private final boolean isMultiStatementEnabled;

  public static final String NEO4J_MULTI_STATEMENT = "neo4j.multi.statement";

  public Neo4jCypherInterpreter(Properties properties) {
    super(properties);
    boolean isMultiStatementEnabled = isMultiStatementEnabled(properties);
    this.isMultiStatementEnabled = isMultiStatementEnabled;
    this.neo4jConnectionManager = new Neo4jConnectionManager(properties);
  }

  private boolean isMultiStatementEnabled(Properties properties) {
    try {
      return Boolean.parseBoolean(properties
              .getProperty(NEO4J_MULTI_STATEMENT, "true"));
    } catch (Exception ignored) {
      return true;
    }
  }

  @Override
  public void open() {
    this.neo4jConnectionManager.open();
  }

  @Override
  public void close() {
    this.neo4jConnectionManager.close();
  }

  public Map<String, String> getLabels(boolean refresh) {
    if (labels == null || refresh) {
      Map<String, String> old = labels == null ?
              new LinkedHashMap<>() : new LinkedHashMap<>(labels);
      labels = new LinkedHashMap<>();
      Iterator<Record> result = this.neo4jConnectionManager.execute("CALL db.labels()")
              .iterator();
      Set<String> colors = new HashSet<>();
      while (result.hasNext()) {
        Record record = result.next();
        String label = record.get("label").asString();
        String color = old.get(label);
        while (color == null || colors.contains(color)) {
          color = Neo4jConversionUtils.getRandomLabelColor();
        }
        colors.add(color);
        labels.put(label, color);
      }
    }
    return labels;
  }

  private Set<String> getTypes(boolean refresh) {
    if (types == null || refresh) {
      types = new HashSet<>();
      Iterator<Record> result = this.neo4jConnectionManager.execute("CALL db.relationshipTypes()")
              .iterator();
      while (result.hasNext()) {
        Record record = result.next();
        types.add(record.get("relationshipType").asString());
      }
    }
    return types;
  }

  @Override
  public InterpreterResult interpret(String cypherQuery, InterpreterContext interpreterContext) {
    LOGGER.info("Opening session");
    if (StringUtils.isBlank(cypherQuery)) {
      return new InterpreterResult(Code.SUCCESS);
    }
    final List<String> queries = isMultiStatementEnabled ?
            Arrays.asList(cypherQuery.split(";[^'|^\"|^(\\w+`)]")) : Arrays.asList(cypherQuery);
    if (queries.size() == 1) {
      final String query = queries.get(0);
      return runQuery(query, interpreterContext);
    } else {
      final int lastIndex = queries.size() - 1;
      final List<String> subQueries = queries.subList(0, lastIndex);
      for (String query : subQueries) {
        runQuery(query, interpreterContext);
      }
      return runQuery(queries.get(lastIndex), interpreterContext);
    }
  }

  private InterpreterResult runQuery(String cypherQuery, InterpreterContext interpreterContext) {
    if (StringUtils.isBlank(cypherQuery)) {
      return new InterpreterResult(Code.SUCCESS);
    }
    try {
      Iterator<Record> result = this.neo4jConnectionManager.execute(cypherQuery,
              interpreterContext).iterator();
      Set<Node> nodes = new HashSet<>();
      Set<Relationship> relationships = new HashSet<>();
      List<String> columns = new ArrayList<>();
      List<List<String>> lines = new ArrayList<List<String>>();
      while (result.hasNext()) {
        Record record = result.next();
        List<Pair<String, Value>> fields = record.fields();
        List<String> line = new ArrayList<>();
        for (Pair<String, Value> field : fields) {
          if (field.value().hasType(InternalTypeSystem.TYPE_SYSTEM.NODE())) {
            nodes.add(field.value().asNode());
          } else if (field.value().hasType(InternalTypeSystem.TYPE_SYSTEM.RELATIONSHIP())) {
            relationships.add(field.value().asRelationship());
          } else if (field.value().hasType(InternalTypeSystem.TYPE_SYSTEM.PATH())) {
            nodes.addAll(Iterables.asList(field.value().asPath().nodes()));
            relationships.addAll(Iterables.asList(field.value().asPath().relationships()));
          } else {
            setTabularResult(field.key(), field.value(), columns, line,
                    InternalTypeSystem.TYPE_SYSTEM);
          }
        }
        if (!line.isEmpty()) {
          lines.add(line);
        }
      }
      if (!nodes.isEmpty()) {
        return renderGraph(nodes, relationships);
      } else {
        return renderTable(columns, lines);
      }
    } catch (Exception e) {
      LOGGER.error("Exception while interpreting cypher query", e);
      return new InterpreterResult(Code.ERROR, e.getMessage());
    }
  }

  private void setTabularResult(String key, Object obj, List<String> columns, List<String> line,
                                TypeSystem typeSystem) {
    if (obj instanceof Value) {
      Value value = (Value) obj;
      if (value.hasType(typeSystem.MAP())) {
        Map<String, Object> map = value.asMap();
        for (Entry<String, Object> entry : map.entrySet()) {
          setTabularResult(String.format(MAP_KEY_TEMPLATE, key, entry.getKey()), entry.getValue(),
                  columns, line, typeSystem);
        }
      } else {
        addValueToLine(key, columns, line, value);
      }
    } else if (obj instanceof Map) {
      Map<String, Object> map = (Map<String, Object>) obj;
      for (Entry<String, Object> entry : map.entrySet()) {
        setTabularResult(String.format(MAP_KEY_TEMPLATE, key, entry.getKey()), entry.getValue(),
                columns, line, typeSystem);
      }
    } else {
      addValueToLine(key, columns, line, obj);
    }
  }

  private void addValueToLine(String key, List<String> columns, List<String> line, Object value) {
    if (!columns.contains(key)) {
      columns.add(key);
    }
    int position = columns.indexOf(key);
    if (line.size() < columns.size()) {
      for (int i = line.size(); i < columns.size(); i++) {
        line.add(null);
      }
    }
    if (value != null) {
      if (value instanceof Value) {
        value = Neo4jConversionUtils.convertValue((Value) value);
      }
      if (value instanceof Collection || value instanceof Map) {
        try {
          value = Neo4jConversionUtils.JSON_MAPPER.writer().writeValueAsString(value);
        } catch (Exception e) {
          LOGGER.debug("ignored exception: " + e.getMessage());
        }
      }
    }
    line.set(position, value == null ? null : value.toString());
  }

  private InterpreterResult renderTable(List<String> cols, List<List<String>> lines) {
    LOGGER.info("Executing renderTable method");
    StringBuilder msg = null;
    if (cols.isEmpty()) {
      msg = new StringBuilder();
    } else {
      msg = new StringBuilder(TABLE);
      msg.append(NEW_LINE);
      msg.append(StringUtils.join(cols, TAB));
      msg.append(NEW_LINE);
      for (List<String> line : lines) {
        if (line.size() < cols.size()) {
          for (int i = line.size(); i < cols.size(); i++) {
            line.add(null);
          }
        }
        msg.append(StringUtils.join(line, TAB));
        msg.append(NEW_LINE);
      }
    }
    return new InterpreterResult(Code.SUCCESS, msg.toString());
  }

  private InterpreterResult renderGraph(Set<Node> nodes,
      Set<Relationship> relationships) {
    LOGGER.info("Executing renderGraph method");
    List<org.apache.zeppelin.tabledata.Node> nodesList = new ArrayList<>();
    List<org.apache.zeppelin.tabledata.Relationship> relsList = new ArrayList<>();
    for (Relationship rel : relationships) {
      relsList.add(Neo4jConversionUtils.toZeppelinRelationship(rel));
    }
    Map<String, String> labels = getLabels(true);
    for (Node node : nodes) {
      nodesList.add(Neo4jConversionUtils.toZeppelinNode(node, labels));
    }
    return new GraphResult(Code.SUCCESS,
            new GraphResult.Graph(nodesList, relsList, labels, getTypes(true), true));
  }

  @Override
  public Scheduler getScheduler() {
    return SchedulerFactory.singleton()
            .createOrGetParallelScheduler(Neo4jCypherInterpreter.class.getName() + this.hashCode(),
                    Integer.parseInt(getProperty(Neo4jConnectionManager.NEO4J_MAX_CONCURRENCY)));
  }

  @Override
  public int getProgress(InterpreterContext context) {
    return 0;
  }

  @Override
  public FormType getFormType() {
    return FormType.SIMPLE;
  }

  @Override
  public void cancel(InterpreterContext context) {
  }
}
