/*
 * HA-JDBC: High-Availability JDBC
 * Copyright (c) 2004-2009 Paul Ferraro
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
package net.sf.hajdbc.state.distributed;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.distributed.Command;
import net.sf.hajdbc.state.DatabaseEvent;
import net.sf.hajdbc.state.StateManager;

/**
 * @author paul
 *
 */
public abstract class StateCommand<Z, D extends Database<Z>> implements Command<Boolean, StateCommandContext<Z, D>>
{
	private static final long serialVersionUID = 8689116981769588205L;

	private final DatabaseEvent event;
	
	protected StateCommand(DatabaseEvent event)
	{
		this.event = event;
	}
	
	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.distributed.Command#execute(java.lang.Object)
	 */
	@Override
	public Boolean execute(StateCommandContext<Z, D> context)
	{
		DatabaseCluster<Z, D> cluster = context.getDatabaseCluster();
		
		return this.execute(cluster.getDatabase(this.event.getDatabaseId()), cluster, context.getLocalStateManager());
	}

	protected abstract boolean execute(D database, DatabaseCluster<Z, D> cluster, StateManager stateManager);
	
	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.distributed.Command#marshalResult(java.lang.Object)
	 */
	@Override
	public Object marshalResult(Boolean result)
	{
		return result;
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.distributed.Command#unmarshalResult(java.lang.Object)
	 */
	@Override
	public Boolean unmarshalResult(Object object)
	{
		return (Boolean) object;
	}
}