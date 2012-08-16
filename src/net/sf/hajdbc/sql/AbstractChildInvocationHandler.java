/*
 * HA-JDBC: High-Availability JDBC
 * Copyright (c) 2004-2007 Paul Ferraro
 * 
 * This library is free software; you can redistribute it and/or modify it 
 * under the terms of the GNU Lesser General Public License as published by the 
 * Free Software Foundation; either version 2.1 of the License, or (at your 
 * option) any later version.
 * 
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or 
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License 
 * for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; if not, write to the Free Software Foundation, 
 * Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 * 
 * Contact: ferraro@users.sourceforge.net
 */
package net.sf.hajdbc.sql;

import java.sql.SQLException;
import java.util.Map;

import net.sf.hajdbc.Database;

/**
 * @author Paul Ferraro
 * @param <D> 
 * @param <P> 
 * @param <T> 
 */
public abstract class AbstractChildInvocationHandler<D, P, T> extends AbstractInvocationHandler<D, T>
{
	private P parentObject;
	private SQLProxy<D, P> parentProxy;
	private Invoker<D, P, T> parentInvoker;

	protected AbstractChildInvocationHandler(P parent, SQLProxy<D, P> proxy, Invoker<D, P, T> invoker, Class<T> proxyClass, Map<Database<D>, T> objectMap) throws Exception
	{
		super(proxy.getDatabaseCluster(), proxyClass, objectMap);
		
		this.parentObject = parent;
		this.parentProxy = proxy;
		this.parentInvoker = invoker;
		this.parentProxy.addChild(this);
	}
	
	@Override
	protected T createObject(Database<D> database) throws Exception
	{
		P object = this.parentProxy.getObject(database);
		
		if (object == null)
		{
			throw new IllegalStateException();
		}
		
		return this.parentInvoker.invoke(database, object);
	}

	@Override
	protected void close(Database<D> database, T object)
	{
		try
		{
			this.close(this.parentProxy.getObject(database), object);
		}
		catch (SQLException e)
		{
			this.logger.info(e.getMessage(), e);
		}
	}
	
	protected abstract void close(P parent, T object) throws SQLException;
	
	/**
	 * @see net.sf.hajdbc.sql.SQLProxy#getRoot()
	 */
	@Override
	public final SQLProxy<D, ?> getRoot()
	{
		return this.parentProxy.getRoot();
	}
	
	protected P getParent()
	{
		return this.parentObject;
	}
	
	protected SQLProxy<D, P> getParentProxy()
	{
		return this.parentProxy;
	}
}