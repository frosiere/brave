/*
 * Copyright 2013-2022 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package brave.p6spy;

import brave.ScopedSpan;
import brave.Span.Kind;
import brave.Tracing;
import brave.handler.MutableSpan;
import brave.propagation.StrictCurrentTraceContext;
import brave.test.TestSpanHandler;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

public class ITTracingP6Factory {
  @Rule
  public TestName testName = new TestName();

  static final String QUERY = "SELECT 1 FROM SYSIBM.SYSDUMMY1";

  //Get rid of annoying derby.log
  static {
    DerbyUtils.disableLog();
  }

  /** JDBC is synchronous and we aren't using thread pools: everything happens on the main thread */
  StrictCurrentTraceContext currentTraceContext = StrictCurrentTraceContext.create();
  TestSpanHandler spans = new TestSpanHandler();
  Tracing tracing = Tracing.newBuilder()
    .currentTraceContext(currentTraceContext).addSpanHandler(spans).build();
  Connection connection;

  @Before
  public void setup() throws Exception {
    String url = String.format("jdbc:p6spy:derby:memory:%s;create=true", testName.getMethodName());
    connection = DriverManager.getConnection(url, "foo", "bar");
    Statement statement = connection.createStatement();
    statement.executeUpdate("create table t (i integer, c char )");
    statement.executeUpdate("insert into t (i, c) values (1, 'a')");
    statement.executeUpdate("insert into t (i, c) values (2, 'b')");
    statement.executeUpdate("insert into t (i, c) values (2, 'c')");
    statement.close();
    spans.clear();
  }

  @After
  public void close() throws Exception {
    if (connection != null) connection.close();
    tracing.close();
    currentTraceContext.close();
  }

  @Test
  public void makesChildOfCurrentSpan() throws Exception {
    ScopedSpan parent = tracing.tracer().startScopedSpan("test");
    try {
      prepareExecuteSelect(QUERY);
    } finally {
      parent.finish();
    }

    assertThat(spans)
      .hasSize(2);
  }

  @Test
  public void reportsClientKindToZipkin() throws Exception {
    prepareExecuteSelect(QUERY);

    assertThat(spans)
      .extracting(MutableSpan::kind)
      .containsExactly(Kind.CLIENT);
  }

  @Test
  public void defaultSpanNameIsOperationName() throws Exception {
    prepareExecuteSelect(QUERY);

    assertThat(spans)
      .extracting(MutableSpan::name)
      .containsExactly("SELECT");
  }

  @Test
  public void addsQueryTag() throws Exception {
    prepareExecuteSelect(QUERY);

    assertThat(spans)
      .flatExtracting(s -> s.tags().entrySet())
      .containsExactly(entry("sql.query", QUERY));
  }

  @Test
  public void addsAffectedRowsTagToPreparedUpdateStatements() throws Exception {
    prepareExecuteUpdate("update t set c='x' where i=2");

    assertThat(spans)
      .flatExtracting(s -> s.tags().entrySet())
      .contains(entry("sql.affected_rows", "2"));
  }

  @Test
  public void addsAffectedRowsTagToPlainUpdateStatements() throws Exception {
    executeUpdate("update t set c='x' where i=2");

    assertThat(spans)
      .flatExtracting(s -> s.tags().entrySet())
      .contains(entry("sql.affected_rows", "2"));
  }

  @Test
  public void addsAffectedRowsTagToPlainBatchUpdateStatements() throws Exception {
    executeBatch("update t set c='x' where i=2", "update t set c='y' where i=1");

    assertThat(spans)
      .flatExtracting(s -> s.tags().entrySet())
      .contains(entry("sql.affected_rows", "2,1"));
  }

  @Test
  public void doesNotProduceAnySpansForEmptyPlainBatchUpdates() throws Exception {
    // No SQL at all means no span is started in onBeforeAnyExecute due to there not being any loggable SQL
    // (see isLoggable)
    executeBatch();

    assertThat(spans).isEmpty();
  }

  @Test
  public void addsAffectedRowsTagToPreparedBatchUpdateStatementsWithOneBatch() throws Exception {
    prepareExecuteBatchWithInts("update t set c='x' where i=?", 2);

    assertThat(spans)
      .flatExtracting(s -> s.tags().entrySet())
      .contains(entry("sql.affected_rows", "2"));
  }

  @Test
  public void addsAffectedRowsTagToPreparedBatchUpdateStatementsWithOneBatchWithZeroUpdates() throws Exception {
    prepareExecuteBatchWithInts("update t set c='x' where i=?", 0);

    assertThat(spans)
      .flatExtracting(s -> s.tags().entrySet())
      .contains(entry("sql.affected_rows", "0"));
  }

  @Test
  public void addsAffectedRowsTagToPreparedBatchUpdateStatementsWithMoreThanOneBatch() throws Exception {
    prepareExecuteBatchWithInts("update t set c='x' where i=?", 2, 1);

    assertThat(spans)
      .flatExtracting(s -> s.tags().entrySet())
      .contains(entry("sql.affected_rows", "2,1"));
  }

  @Test
  public void addsAffectedRowsTagToPreparedBatchUpdateStatementsWithMoreThanOneBatchWhereOneBatcheHasZeroUpdates() throws Exception {
    prepareExecuteBatchWithInts("update t set c='x' where i=?", 2, 0, 1);

    assertThat(spans)
      .flatExtracting(s -> s.tags().entrySet())
      .contains(entry("sql.affected_rows", "2,0,1"));
  }

  @Test
  public void addsAffectedRowsTagToPreparedBatchUpdateStatementsWithMoreThanOneBatchWhereBatchesHaveZeroUpdates() throws Exception {
    prepareExecuteBatchWithInts("update t set c='x' where i=?", 2, 0, 3);

    assertThat(spans)
      .flatExtracting(s -> s.tags().entrySet())
      .contains(entry("sql.affected_rows", "2,0,0"));
  }

  @Test
  public void addsEmptyAffectedRowsTagToEmptyPreparedBatchUpdates() throws Exception {
    // In contrast to the plain statement case, this does produce loggable SQL, so a span is started. Since there are
    // no entries in the batch, no updates are made, so there are no update counts. Therefore, the span does not have
    // any sql.affected_rows tag.
    prepareExecuteBatchWithInts("update t set c='x' where i=?");

    assertThat(spans).anySatisfy(span -> {
      assertThat(span.tags())
        .contains(entry("sql.query", "update t set c='x' where i=?"))
        .doesNotContainKey("sql.affected_rows");
    });
  }

  @Test
  public void reportsServerAddress() throws Exception {
    prepareExecuteSelect(QUERY);

    assertThat(spans)
      .extracting(MutableSpan::remoteServiceName)
      .containsExactly("myservice");
  }

  void prepareExecuteSelect(String query) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(query)) {
      try (ResultSet resultSet = ps.executeQuery()) {
        while (resultSet.next()) {
          resultSet.getString(1);
        }
      }
    }
  }

  void prepareExecuteUpdate(String sql) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      ps.executeUpdate();
    }
  }

  void executeUpdate(String sql) throws SQLException {
    try (Statement s = connection.createStatement()) {
      s.executeUpdate(sql);
    }
  }

  void executeBatch(String... sqls) throws SQLException {
    try (Statement s = connection.createStatement()) {
      for (String sql : sqls) {
        s.addBatch(sql);
      }
      s.executeBatch();
    }
  }

  void prepareExecuteBatchWithInts(String sql, int... ints) throws SQLException {
    try (PreparedStatement s = connection.prepareStatement(sql)) {
      for (int i : ints) {
        s.setInt(1, i);
        s.addBatch();
      }
      s.executeBatch();
    }
  }
}
