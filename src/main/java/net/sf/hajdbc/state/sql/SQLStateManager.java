/*
 * HA-JDBC: High-Availability JDBC
 * Copyright 2004-2009 Paul Ferraro
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.sf.hajdbc.state.sql;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.ExceptionType;
import net.sf.hajdbc.durability.Durability;
import net.sf.hajdbc.durability.DurabilityListener;
import net.sf.hajdbc.durability.InvocationEvent;
import net.sf.hajdbc.durability.InvocationEventImpl;
import net.sf.hajdbc.durability.InvokerEvent;
import net.sf.hajdbc.durability.InvokerEventImpl;
import net.sf.hajdbc.durability.InvokerResult;
import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;
import net.sf.hajdbc.pool.Pool;
import net.sf.hajdbc.pool.PoolFactory;
import net.sf.hajdbc.pool.sql.ConnectionFactory;
import net.sf.hajdbc.pool.sql.ConnectionPoolProvider;
import net.sf.hajdbc.state.DatabaseEvent;
import net.sf.hajdbc.state.DurabilityListenerAdapter;
import net.sf.hajdbc.state.SerializedDurabilityListener;
import net.sf.hajdbc.state.StateManager;
import net.sf.hajdbc.util.Objects;

/**
 * @author Paul Ferraro
 */
public class SQLStateManager<Z, D extends Database<Z>> implements StateManager, ConnectionFactory, SerializedDurabilityListener
{
	private static final String STATE_TABLE = "cluster_state";
	private static final String DATABASE_COLUMN = "database_id";

	private static final String INVOCATION_TABLE = "cluster_invocation";
	private static final String INVOKER_TABLE = "cluster_invoker";
	private static final String TRANSACTION_COLUMN = "tx_id";
	private static final String PHASE_COLUMN = "phase_id";
	private static final String EXCEPTION_COLUMN = "exception_id";
	private static final String RESULT_COLUMN = "result";
	
	static final String SELECT_STATE_SQL = MessageFormat.format("SELECT {1} FROM {0}", STATE_TABLE, DATABASE_COLUMN);
	static final String INSERT_STATE_SQL = MessageFormat.format("INSERT INTO {0} ({1}) VALUES (?)", STATE_TABLE, DATABASE_COLUMN);
	static final String DELETE_STATE_SQL = MessageFormat.format("DELETE FROM {0} WHERE {1} = ?", STATE_TABLE, DATABASE_COLUMN);
	static final String CLEAR_STATE_SQL = MessageFormat.format("DELETE FROM {0}", STATE_TABLE);
	static final String TRUNCATE_STATE_SQL = MessageFormat.format("DELETE FROM {0}", STATE_TABLE);

	static final String SELECT_INVOCATION_SQL = MessageFormat.format("SELECT {1}, {2}, {3} FROM {0}", INVOCATION_TABLE, TRANSACTION_COLUMN, PHASE_COLUMN, EXCEPTION_COLUMN);
	static final String INSERT_INVOCATION_SQL = MessageFormat.format("INSERT INTO {0} ({1}, {2}, {3}) VALUES (?, ?, ?)", INVOCATION_TABLE, TRANSACTION_COLUMN, PHASE_COLUMN, EXCEPTION_COLUMN);
	static final String DELETE_INVOCATION_SQL = MessageFormat.format("DELETE FROM {0} WHERE {1} = ? AND {2} = ?", INVOCATION_TABLE, TRANSACTION_COLUMN, PHASE_COLUMN);
	
	static final String SELECT_INVOKER_SQL = MessageFormat.format("SELECT {1}, {2}, {3}, {4}, FROM {0}", INVOKER_TABLE, TRANSACTION_COLUMN, PHASE_COLUMN, DATABASE_COLUMN, RESULT_COLUMN);
	static final String INSERT_INVOKER_SQL = MessageFormat.format("INSERT INTO {0} ({1}, {2}, {3}) VALUES (?, ?, ?)", INVOKER_TABLE, TRANSACTION_COLUMN, PHASE_COLUMN, DATABASE_COLUMN);
	static final String UPDATE_INVOKER_SQL = MessageFormat.format("UPDATE {0} SET {4} = ? WHERE {1} = ? AND {2} = ? AND {3} = ?", INVOKER_TABLE, TRANSACTION_COLUMN, PHASE_COLUMN, DATABASE_COLUMN, RESULT_COLUMN);
	static final String DELETE_INVOKER_SQL = MessageFormat.format("DELETE FROM {0} WHERE {1} = ? AND {2} = ?", INVOKER_TABLE, TRANSACTION_COLUMN, PHASE_COLUMN);

	private static final String CREATE_INVOCATION_SQL = MessageFormat.format("CREATE TABLE IF NOT EXISTS {0} ({1} BINARY NOT NULL, {2} INTEGER NOT NULL, {3} INTEGER NOT NULL, PRIMARY KEY ({1}, {2}))", INVOCATION_TABLE, TRANSACTION_COLUMN, PHASE_COLUMN, EXCEPTION_COLUMN);
	private static final String CREATE_INVOKER_SQL = MessageFormat.format("CREATE TABLE IF NOT EXISTS {0} ({1} BINARY NOT NULL, {2} INTEGER NOT NULL, {3} VARCHAR NOT NULL, {4} BINARY, PRIMARY KEY ({1}, {2}, {3}))", INVOKER_TABLE, TRANSACTION_COLUMN, PHASE_COLUMN, DATABASE_COLUMN, RESULT_COLUMN);
	private static final String CREATE_STATE_SQL = MessageFormat.format("CREATE TABLE IF NOT EXISTS {0} ({1} VARCHAR NOT NULL, PRIMARY KEY ({1}))", STATE_TABLE, DATABASE_COLUMN);
	
	private static Logger logger = LoggerFactory.getLogger(SQLStateManager.class);
	
	private final DurabilityListener listener = new DurabilityListenerAdapter(this);
	private final DatabaseCluster<Z, D> cluster;
	private final PoolFactory poolFactory;
	private final Database<Driver> database;
	
	private String password;
	private Driver driver;
	private Pool<Connection, SQLException> pool;
	
	public SQLStateManager(DatabaseCluster<Z, D> cluster, Database<Driver> database, PoolFactory poolFactory)
	{
		this.cluster = cluster;
		this.database = database;
		this.poolFactory = poolFactory;
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.state.StateManager#getActiveDatabases()
	 */
	@Override
	public Set<String> getActiveDatabases()
	{
		Set<String> set = new TreeSet<String>();
		
		try
		{
			Connection connection = this.pool.take();
			
			try
			{
				if (Boolean.getBoolean(StateManager.CLEAR_LOCAL_STATE))
				{
					PreparedStatement statement = connection.prepareStatement(CLEAR_STATE_SQL);
					
					try
					{
						statement.executeUpdate();
					}
					finally
					{
						close(statement);
					}
				}
				else
				{
					PreparedStatement statement = connection.prepareStatement(SELECT_STATE_SQL);
					
					try
					{
						ResultSet resultSet = statement.executeQuery();
						
						try
						{
							while (resultSet.next())
							{
								set.add(resultSet.getString(1));
							}
						}
						finally
						{
							close(resultSet);
						}
					}
					finally
					{
						close(statement);
					}
				}
			}
			finally
			{
				this.pool.release(connection);
			}
		}
		catch (SQLException e)
		{
			logger.log(Level.ERROR, e, e.getMessage());
		}
		
		return set;
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.state.StateManager#setActiveDatabases(java.util.Set)
	 */
	@Override
	public void setActiveDatabases(final Set<String> databases)
	{
		Transaction transaction = new Transaction()
		{
			@Override
			public void execute(Connection connection) throws SQLException
			{
				PreparedStatement statement = connection.prepareStatement(INSERT_STATE_SQL);
				
				try
				{
					statement.addBatch(TRUNCATE_STATE_SQL);
					
					for (String database: databases)
					{
						statement.clearParameters();
						
						statement.setString(1, database);
						
						statement.addBatch();
					}
					
					statement.executeBatch();
				}
				finally
				{
					close(statement);
				}
			}
		};

		try
		{
			this.execute(transaction);
		}
		catch (SQLException e)
		{
			logger.log(Level.ERROR, e, e.getMessage());
		}
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.DatabaseClusterListener#activated(net.sf.hajdbc.state.DatabaseEvent)
	 */
	@Override
	public void activated(final DatabaseEvent event)
	{
		try
		{
			throw new Exception();
		}
		catch (Exception e)
		{
			logger.log(Level.INFO, e, "activated(" + event + ")");
		}
		Transaction transaction = new Transaction()
		{
			@Override
			public void execute(Connection connection) throws SQLException
			{
				SQLStateManager.this.execute(connection, INSERT_STATE_SQL, event);
			}
		};
		
		try
		{
			this.execute(transaction);
		}
		catch (SQLException e)
		{
			logger.log(Level.ERROR, e, e.getMessage());
		}
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.DatabaseClusterListener#deactivated(net.sf.hajdbc.state.DatabaseEvent)
	 */
	@Override
	public void deactivated(final DatabaseEvent event)
	{
		Transaction transaction = new Transaction()
		{
			@Override
			public void execute(Connection connection) throws SQLException
			{
				SQLStateManager.this.execute(connection, DELETE_STATE_SQL, event);
			}
		};
		
		try
		{
			this.execute(transaction);
		}
		catch (SQLException e)
		{
			logger.log(Level.ERROR, e, e.getMessage());
		}
	}

	void execute(Connection connection, String sql, DatabaseEvent event) throws SQLException
	{
		PreparedStatement statement = connection.prepareStatement(sql);
		
		try
		{
			statement.setString(1, event.getDatabaseId());
			
			statement.executeUpdate();
		}
		finally
		{
			close(statement);
		}
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.state.SerializedDurabilityListener#beforeInvocation(byte[], int, int)
	 */
	@Override
	public void beforeInvocation(final byte[] transactionId, final int phase, final int exceptionType)
	{
		Transaction transaction = new Transaction()
		{
			@Override
			public void execute(Connection connection) throws SQLException
			{
				PreparedStatement statement = connection.prepareStatement(INSERT_INVOCATION_SQL);
				
				try
				{
					statement.setBytes(1, transactionId);
					statement.setInt(2, phase);
					statement.setInt(3, exceptionType);
					
					statement.executeUpdate();
				}
				finally
				{
					close(statement);
				}
			}
		};
		
		try
		{
			this.execute(transaction);
		}
		catch (SQLException e)
		{
			logger.log(Level.ERROR, e, e.getMessage());
		}
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.state.SerializedDurabilityListener#afterInvocation(byte[], int)
	 */
	@Override
	public void afterInvocation(final byte[] transactionId, final int phase)
	{
		Transaction transaction = new Transaction()
		{
			@Override
			public void execute(Connection connection) throws SQLException
			{
				SQLStateManager.this.execute(connection, DELETE_INVOKER_SQL, transactionId, phase);
				SQLStateManager.this.execute(connection, DELETE_INVOCATION_SQL, transactionId, phase);
			}
		};
		
		try
		{
			this.execute(transaction);
		}
		catch (SQLException e)
		{
			logger.log(Level.ERROR, e, e.getMessage());
		}
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.state.SerializedDurabilityListener#beforeInvoker(byte[], int, java.lang.String)
	 */
	@Override
	public void beforeInvoker(final byte[] transactionId, final int phase, final String databaseId)
	{
		Transaction transaction = new Transaction()
		{
			@Override
			public void execute(Connection connection) throws SQLException
			{
				PreparedStatement statement = connection.prepareStatement(INSERT_INVOKER_SQL);
				
				try
				{
					statement.setBytes(1, transactionId);
					statement.setInt(2, phase);
					statement.setString(3, databaseId);
					
					statement.executeUpdate();
				}
				finally
				{
					close(statement);
				}
			}
		};
		
		try
		{
			this.execute(transaction);
		}
		catch (SQLException e)
		{
			logger.log(Level.ERROR, e, e.getMessage());
		}
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.state.SerializedDurabilityListener#afterInvoker(byte[], int, java.lang.String, byte[])
	 */
	@Override
	public void afterInvoker(final byte[] transactionId, final int phase, final String databaseId, final byte[] result)
	{
		Transaction transaction = new Transaction()
		{
			@Override
			public void execute(Connection connection) throws SQLException
			{
				PreparedStatement statement = connection.prepareStatement(UPDATE_INVOKER_SQL);
				
				try
				{
					statement.setBytes(1, transactionId);
					statement.setInt(2, phase);
					statement.setString(3, databaseId);					
					statement.setBytes(4, result);
					
					statement.executeUpdate();
				}
				finally
				{
					close(statement);
				}
			}
		};
		
		try
		{
			this.execute(transaction);
		}
		catch (SQLException e)
		{
			logger.log(Level.ERROR, e, e.getMessage());
		}
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.durability.DurabilityListener#beforeInvocation(net.sf.hajdbc.durability.InvocationEvent)
	 */
	@Override
	public void beforeInvocation(InvocationEvent event)
	{
		this.listener.beforeInvocation(event);
	}
	
	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.durability.DurabilityListener#afterInvocation(net.sf.hajdbc.durability.InvocationEvent)
	 */
	@Override
	public void afterInvocation(InvocationEvent event)
	{
		this.listener.afterInvocation(event);
	}
	
	void execute(Connection connection, String sql, byte[] transactionId, int phase) throws SQLException 
	{
		PreparedStatement statement = connection.prepareStatement(sql);
		
		try
		{
			statement.setBytes(1, transactionId);
			statement.setInt(2, phase);
			
			statement.executeUpdate();
		}
		finally
		{
			close(statement);
		}
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.state.StateManager#recover()
	 */
	@Override
	public Map<InvocationEvent, Map<String, InvokerEvent>> recover()
	{
		Map<InvocationEvent, Map<String, InvokerEvent>> map = new HashMap<InvocationEvent, Map<String, InvokerEvent>>();
		
		try
		{
			Connection connection = this.pool.take();
			
			try
			{
				PreparedStatement statement = connection.prepareStatement(SELECT_INVOCATION_SQL);

				try
				{
					ResultSet resultSet = statement.executeQuery();
					
					try
					{
						while (resultSet.next())
						{
							map.put(new InvocationEventImpl(Objects.deserialize(resultSet.getBytes(1)), Durability.Phase.values()[resultSet.getInt(2)], ExceptionType.values()[resultSet.getInt(3)]), new HashMap<String, InvokerEvent>());
						}
					}
					finally
					{
						close(resultSet);
					}
				}
				finally
				{
					close(statement);
				}

				statement = connection.prepareStatement(SELECT_INVOKER_SQL);
				
				try
				{
					ResultSet resultSet = statement.executeQuery();
					
					while (resultSet.next())
					{
						Object transactionId = Objects.deserialize(resultSet.getBytes(1));
						Durability.Phase phase = Durability.Phase.values()[resultSet.getInt(2)];
						ExceptionType type = ExceptionType.values()[resultSet.getInt(3)];
						
						Map<String, InvokerEvent> invokers = map.get(new InvocationEventImpl(transactionId, phase, type));
						
						if (invokers != null)
						{
							String databaseId = resultSet.getString(3);
							
							InvokerEvent event = new InvokerEventImpl(transactionId, phase, databaseId);
							
							byte[] bytes = resultSet.getBytes(4);
							
							if (!resultSet.wasNull())
							{
								event.setResult(Objects.deserialize(InvokerResult.class, bytes));
							}

							invokers.put(databaseId, event);
						}
					}
					
					close(resultSet);
				}
				finally
				{
					close(statement);
				}
				
				return map;
			}
			finally
			{
				this.pool.release(connection);
			}
		}
		catch (SQLException e)
		{
			throw new IllegalStateException(e);
		}
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.durability.DurabilityListener#beforeInvoker(net.sf.hajdbc.durability.InvokerEvent)
	 */
	@Override
	public void beforeInvoker(InvokerEvent event)
	{
		this.listener.beforeInvoker(event);
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.durability.DurabilityListener#afterInvoker(net.sf.hajdbc.durability.InvokerEvent)
	 */
	@Override
	public void afterInvoker(InvokerEvent event)
	{
		this.listener.afterInvoker(event);
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.Lifecycle#start()
	 */
	@Override
	public void start() throws Exception
	{
		this.driver = this.database.createConnectionSource();
		this.password = this.database.decodePassword(this.cluster.getCodec());
		this.pool = this.poolFactory.createPool(new ConnectionPoolProvider(this));
		
		Connection connection = this.pool.take();
		
		boolean autoCommit = connection.getAutoCommit();
		
		connection.setAutoCommit(true);
		
		try
		{
			Statement statement = connection.createStatement();
			
			try
			{
				statement.addBatch(CREATE_STATE_SQL);
				statement.addBatch(CREATE_INVOCATION_SQL);
				statement.addBatch(CREATE_INVOKER_SQL);
				
				statement.executeBatch();
			}
			finally
			{
				close(statement);
			}
			
			connection.setAutoCommit(autoCommit);
		}
		finally
		{
			this.pool.release(connection);
		}
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.Lifecycle#stop()
	 */
	@Override
	public void stop()
	{
		this.pool.close();
	}

	private void execute(Transaction transaction) throws SQLException
	{
		Connection connection = this.pool.take();
		
		try
		{
			transaction.execute(connection);
			
			connection.commit();
		}
		finally
		{
			this.pool.release(connection);
		}
	}
	
	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.pool.sql.ConnectionFactory#getConnection()
	 */
	@Override
	public Connection getConnection() throws SQLException
	{
		Connection connection = this.database.connect(this.driver, this.password);
		
		connection.setAutoCommit(false);
		
		return connection;
	}

	interface Transaction
	{
		void execute(Connection connection) throws SQLException;
	}
	
	static void close(Statement statement)
	{
		try
		{
			statement.close();
		}
		catch (SQLException e)
		{
			logger.log(Level.WARN, e, e.getMessage());
		}
	}
	
	static void close(ResultSet resultSet)
	{
		try
		{
			resultSet.close();
		}
		catch (SQLException e)
		{
			logger.log(Level.WARN, e, e.getMessage());
		}
	}
}
