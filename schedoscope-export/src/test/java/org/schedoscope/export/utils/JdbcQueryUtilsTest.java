package org.schedoscope.export.utils;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class JdbcQueryUtilsTest {

	Connection conn;
	Statement stmt;

	@Before
	public void setUp() throws SQLException {
		conn = mock(Connection.class);
		stmt = mock(Statement.class);
		when(conn.createStatement()).thenReturn(stmt);
	}

	@Test
	public void testDropTemporaryOutputTable() throws SQLException {

		JdbcQueryUtils.dropTemporaryOutputTable("my_table", conn);

		verify(stmt).executeUpdate("DROP TABLE my_table");
		verify(stmt).close();
	}

	@Test
	public void testDropTemporaryOutputTables() throws SQLException {

		int partitions = 30;
		JdbcQueryUtils.dropTemporaryOutputTables("your_table", partitions, conn);

		for (int i = 0; i < partitions; i++) {
			verify(stmt).executeUpdate("DROP TABLE your_table_" + i);
		}
		verify(stmt, times(partitions)).close();
	}

	@Test
	public void testMergeOutput() throws SQLException {

		ArgumentCaptor<String> argumentCaptor = ArgumentCaptor.forClass(String.class);
		JdbcQueryUtils.mergeOutput("her_table", 2, conn);

		verify(stmt).executeUpdate(argumentCaptor.capture());
		String sqlStmt = argumentCaptor.getValue();

		assertThat(sqlStmt, allOf(
				containsString("INSERT INTO her_table"),
				containsString("SELECT * FROM tmp_her_table_0"),
				containsString("UNION ALL"),
				containsString("SELECT * FROM tmp_her_table_1")
				));

		verify(stmt).close();
	}

	@Test
	public void testCreateTable() throws SQLException {
		JdbcQueryUtils.createTable("CREATE TABLE bla bla", conn);

		verify(stmt).executeUpdate("CREATE TABLE bla bla");
		verify(stmt).close();
	}

	@Test
	public void testCreateInsertQuery() {
		String[] fieldNames = new String[] {"id", "username"};
		String query = JdbcQueryUtils.createInsertQuery("his_table", fieldNames);

		assertEquals("INSERT INTO his_table (id,username) VALUES (?,?)", query);
	}
}
