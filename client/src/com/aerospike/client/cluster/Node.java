/*
 * Copyright 2012-2016 Aerospike, Inc.
 *
 * Portions may be licensed to Aerospike, Inc. under one or more contributor
 * license agreements WHICH ARE COMPATIBLE WITH THE APACHE LICENSE, VERSION 2.0.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.aerospike.client.cluster;

import java.io.Closeable;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.Host;
import com.aerospike.client.Info;
import com.aerospike.client.Log;
import com.aerospike.client.ResultCode;
import com.aerospike.client.admin.AdminCommand;
import com.aerospike.client.policy.BatchPolicy;
import com.aerospike.client.util.ThreadLocalData;
import com.aerospike.client.util.Util;

/**
 * Server node representation.  This class manages server node connections and health status.
 */
public class Node implements Closeable {
	/**
	 * Number of partitions for each namespace.
	 */
	public static final int PARTITIONS = 4096;
	
	public static final int HAS_GEO	= (1 << 0);
	public static final int HAS_DOUBLE = (1 << 1);
	public static final int HAS_BATCH_INDEX	= (1 << 2);
	public static final int HAS_REPLICAS_ALL = (1 << 3);
	public static final int HAS_PEERS = (1 << 4);

	protected final Cluster cluster;
	private final String name;
	private final Host host;
	protected final List<Host> aliases;
	protected final InetSocketAddress address;
	private final ArrayBlockingQueue<Connection> connectionQueue;
	private final AtomicInteger connectionCount;
	private Connection tendConnection;
	protected int peersGeneration;
	protected int partitionGeneration;
	protected int peersCount;
	protected int referenceCount;
	protected int failures;
	private final int features;
	protected boolean partitionChanged;
	protected volatile boolean active;

	/**
	 * Initialize server node with connection parameters.
	 * 
	 * @param cluster			collection of active server nodes 
	 * @param nv				connection parameters
	 */
	public Node(Cluster cluster, NodeValidator nv) {
		this.cluster = cluster;
		this.name = nv.name;
		this.aliases = nv.aliases;
		this.host = nv.primaryHost;
		this.address = nv.primaryAddress;
		this.tendConnection = nv.conn;
		this.features = nv.features;
				
		connectionQueue = new ArrayBlockingQueue<Connection>(cluster.connectionQueueSize);
		connectionCount = new AtomicInteger();
		peersGeneration = -1;
		partitionGeneration = -1;
		active = true;
	}
	
	/**
	 * Request current status from server node.
	 */
	public final void refresh(Peers peers) {
		if (! active) {
			return;
		}
		
		try {
			if (tendConnection.isClosed()) {
				tendConnection = new Connection(cluster.tlsPolicy, host.tlsName, address, cluster.getConnectionTimeout(), cluster.maxSocketIdleMillis);
			}
	
			if (peers.usePeers) {
				HashMap<String,String> infoMap = Info.request(tendConnection, "node", "peers-generation", "partition-generation");
				verifyNodeName(infoMap);
				verifyPeersGeneration(infoMap, peers);
				verifyPartitionGeneration(infoMap);
			}
			else {
				String[] commands = cluster.useServicesAlternate ? 
					new String[] {"node", "partition-generation", "services-alternate"} :
					new String[] {"node", "partition-generation", "services"};
					
				HashMap<String,String> infoMap = Info.request(tendConnection, commands);
				verifyNodeName(infoMap);
				verifyPartitionGeneration(infoMap);
				addFriends(infoMap, peers);		
			}
			peers.refreshCount++;
			failures = 0;
		}
		catch (Exception e) {
			refreshFailed(e);
		}
	}
	
	private final void verifyNodeName(HashMap <String,String> infoMap) {
		// If the node name has changed, remove node from cluster and hope one of the other host
		// aliases is still valid.  Round-robbin DNS may result in a hostname that resolves to a
		// new address.
		String infoName = infoMap.get("node");
		
		if (infoName == null || infoName.length() == 0) {
			throw new AerospikeException.Parse("Node name is empty");
		}

		if (! name.equals(infoName)) {
			// Set node to inactive immediately.
			active = false;
			throw new AerospikeException("Node name has changed. Old=" + name + " New=" + infoName);
		}
	}
	
	private final void verifyPeersGeneration(HashMap<String,String> infoMap, Peers peers) {	
		String genString = infoMap.get("peers-generation");
		
		if (genString == null || genString.length() == 0) {
			throw new AerospikeException.Parse("peers-generation is empty");
		}

		int gen = Integer.parseInt(genString);
		
		if (peersGeneration != gen) {
			peers.genChanged = true;
		}						
	}

	private final void verifyPartitionGeneration(HashMap<String,String> infoMap) {	
		String genString = infoMap.get("partition-generation");
				
		if (genString == null || genString.length() == 0) {
			throw new AerospikeException.Parse("partition-generation is empty");
		}

		int gen = Integer.parseInt(genString);
			
		if (partitionGeneration != gen) {
			this.partitionChanged = true;
		}
	}

	private final void addFriends(HashMap <String,String> infoMap, Peers peers) throws AerospikeException {
		// Parse the service addresses and add the friends to the list.
		String command = cluster.useServicesAlternate ? "services-alternate" : "services";
		String friendString = infoMap.get(command);
		
		if (friendString == null || friendString.length() == 0) {
			peersCount = 0;
			return;
		}

		String friendNames[] = friendString.split(";");
		peersCount = friendNames.length;

		for (String friend : friendNames) {
			String friendInfo[] = friend.split(":");
			String hostname = friendInfo[0];

			if (cluster.ipMap != null) {
				String alternativeHost = cluster.ipMap.get(hostname);
				
				if (alternativeHost != null) {
					hostname = alternativeHost;
				}				
			}
			
			int port = Integer.parseInt(friendInfo[1]);
			Host host = new Host(hostname, port);
			
			// Check global aliases for existing cluster.
			Node node = cluster.aliases.get(host);
			
			if (node == null) {
				// Check local aliases for this tend iteration.	
				if (! peers.hosts.contains(host)) {
					prepareFriend(host, peers);
				}
			}
			else {				
				node.referenceCount++;
			}
		}
	}
	
	private final boolean prepareFriend(Host host, Peers peers) {
		try {
			NodeValidator nv = new NodeValidator();
			nv.validateNode(cluster, host);
			
			// Check for duplicate nodes in nodes slated to be added.
			Node node = peers.nodes.get(nv.name);
			
			if (node != null) {
				// Duplicate node name found.  This usually occurs when the server 
				// services list contains both internal and external IP addresses 
				// for the same node.
				nv.conn.close();
				peers.hosts.add(host);
				node.aliases.add(host);
				return true;				
			}
			
			// Check for duplicate nodes in cluster.
			node = cluster.nodesMap.get(nv.name);
			
			if (node != null) {
				nv.conn.close();
				peers.hosts.add(host);
				node.aliases.add(host);
				node.referenceCount++;
				cluster.aliases.put(host, node);
				return true;
			}

			node = cluster.createNode(nv);
			peers.hosts.add(host);
			peers.nodes.put(nv.name, node);
			return true;
		}
		catch (Exception e) {
			if (Log.warnEnabled()) {
				Log.warn("Add node " + host + " failed: " + Util.getErrorMessage(e));
			}
			return false;
		}
	}

	protected final void refreshPeers(Peers peers) {
		// Do not refresh peers when node connection has already failed during this cluster tend iteration.
		if (failures > 0 || ! active) {
			return;
		}
		
		try { 
			if (Log.debugEnabled()) {
				Log.debug("Update peers for node " + this);
			}
			PeerParser parser = new PeerParser(cluster, tendConnection, peers.peers);
			peersGeneration = parser.generation;
			peersCount = peers.peers.size();
		
			for (Peer peer : peers.peers) {		
				if (findPeerNode(cluster, peers, peer.nodeName)) {
					// Node already exists. Do not even try to connect to hosts.				
					continue;
				}
	
				// Find first host that connects.
				for (Host host : peer.hosts) {
					try {
						// Attempt connection to host.
						NodeValidator nv = new NodeValidator();
						nv.validateNode(cluster, host);
						
						if (! peer.nodeName.equals(nv.name)) {					
							// Must look for new node name in the unlikely event that node names do not agree. 
							if (Log.warnEnabled()) {
								Log.warn("Peer node " + peer.nodeName + " is different than actual node " + nv.name + " for host " + host);
							}
							
							if (findPeerNode(cluster, peers, nv.name)) {
								// Node already exists. Do not even try to connect to hosts.				
								nv.conn.close();
								break;
							}
						}
						
						// Create new node.
						Node node = cluster.createNode(nv);
						peers.nodes.put(nv.name, node);
						break;
					}
					catch (Exception e) {
						if (Log.warnEnabled()) {
							Log.warn("Add node " + host + " failed: " + Util.getErrorMessage(e));
						}
					}
				}
			}
			peers.refreshCount++;
		}
		catch (Exception e) {
			refreshFailed(e);			
		}
	}
	
	private static boolean findPeerNode(Cluster cluster, Peers peers, String nodeName) {		
		// Check global node map for existing cluster.
		Node node = cluster.nodesMap.get(nodeName);
		
		if (node != null) {
			node.referenceCount++;
			return true;
		}

		// Check local node map for this tend iteration.
		node = peers.nodes.get(nodeName);

		if (node != null) {
			node.referenceCount++;
			return true;
		}
		return false;
	}
	
	protected final void refreshPartitions(Peers peers) {
		// Do not refresh partitions when node connection has already failed during this cluster tend iteration.
		// Also, avoid "split cluster" case where this node thinks it's a 1-node cluster.
		// Unchecked, such a node can dominate the partition map and cause all other
		// nodes to be dropped.
		if (failures > 0 || ! active || (peersCount == 0 && peers.refreshCount > 1)) {
			return;
		}
		
		try {
			if (Log.debugEnabled()) {
				Log.debug("Update partition map for node " + this);
			}
			PartitionParser parser = new PartitionParser(tendConnection, this, cluster.partitionMap, Node.PARTITIONS, cluster.requestProleReplicas);			

			if (parser.isPartitionMapCopied()) {		
				cluster.partitionMap = parser.getPartitionMap();
			}
			partitionGeneration = parser.getGeneration();
		}
		catch (Exception e) {
			refreshFailed(e);
		}
	}

	private final void refreshFailed(Exception e) {		
		failures++;	
		
		if (! tendConnection.isClosed()) {
			tendConnection.close();
		}
		
		// Only log message if cluster is still active.
		if (cluster.tendValid && Log.warnEnabled()) {
			Log.warn("Node " + this + " refresh failed: " + Util.getErrorMessage(e));
		}
	}

	/**
	 * Get a socket connection from connection pool to the server node.
	 * 
	 * @param timeoutMillis			connection timeout value in milliseconds if a new connection is created	
	 * @return						socket connection
	 * @throws AerospikeException	if a connection could not be provided 
	 */
	public final Connection getConnection(int timeoutMillis) throws AerospikeException {
		Connection conn;
		
		while ((conn = connectionQueue.poll()) != null) {		
			if (conn.isValid()) {
				try {
					conn.setTimeout(timeoutMillis);
					return conn;
				}
				catch (Exception e) {
					// Set timeout failed. Something is probably wrong with timeout
					// value itself, so don't empty queue retrying.  Just get out.
					closeConnection(conn);
					throw new AerospikeException.Connection(e);
				}
			}
			closeConnection(conn);
		}
		
		if (connectionCount.getAndIncrement() < cluster.connectionQueueSize) {
			try {
				conn = new Connection(cluster.tlsPolicy, host.tlsName, address, timeoutMillis, cluster.maxSocketIdleMillis);
			}
			catch (RuntimeException re) {
				connectionCount.getAndDecrement();
				throw re;
			}
			
			if (cluster.user != null) {
				try {
					AdminCommand command = new AdminCommand(ThreadLocalData.getBuffer());
					command.authenticate(conn, cluster.user, cluster.password);
				}
				catch (AerospikeException ae) {
					// Socket not authenticated.  Do not put back into pool.
					closeConnection(conn);
					throw ae;
				}
				catch (Exception e) {
					// Socket not authenticated.  Do not put back into pool.
					closeConnection(conn);
					throw new AerospikeException(e);
				}
			}
			return conn;		
		}
		else {
			connectionCount.getAndDecrement();
			throw new AerospikeException.Connection(ResultCode.NO_MORE_CONNECTIONS, 
				"Node " + this + " max connections " + cluster.connectionQueueSize + " would be exceeded.");
		}
	}
	
	/**
	 * Put connection back into connection pool.
	 * 
	 * @param conn					socket connection
	 */
	public final void putConnection(Connection conn) {
		conn.updateLastUsed();
		
		if (! active || ! connectionQueue.offer(conn)) {
			closeConnection(conn);
		}
	}
	
	/**
	 * Close connection and decrement connection count.
	 */
	public final void closeConnection(Connection conn) {
		connectionCount.getAndDecrement();
		conn.close();
	}

	/**
	 * Return server node IP address and port.
	 */
	public final Host getHost() {
		return host;
	}
	
	/**
	 * Return whether node is currently active.
	 */
	public final boolean isActive() {
		return active;
	}

	/**
	 * Return server node name.
	 */
	public final String getName() {
		return name;
	}
	
	public final InetSocketAddress getAddress() {
		return address;
	}
	
	/**
	 * Use new batch protocol if server supports it and useBatchDirect is not set.
	 */
	public final boolean useNewBatch(BatchPolicy policy) {
		return ! policy.useBatchDirect && hasBatchIndex();
	}
	
	/**
	 * Does server support batch index protocol.
	 */
	public final boolean hasBatchIndex() {
		return (features & HAS_BATCH_INDEX) != 0;
	}

	/**
	 * Does server support double particle types.
	 */
	public final boolean hasDouble() {
		return (features & HAS_DOUBLE) != 0;
	}

	/**
	 * Does server support replicas-all info command.
	 */
	public final boolean hasReplicasAll() {
		return (features & HAS_REPLICAS_ALL) != 0;
	}

	/**
	 * Does server support peers info command.
	 */
	public final boolean hasPeers() {
		return (features & HAS_PEERS) != 0;
	}

	/**
	 * Close all server node socket connections.
	 */
	public final void close() {
		active = false;
		closeConnections();
	}
	
	@Override
	public final String toString() {
		return name + ' ' + host;
	}

	@Override
	public final int hashCode() {
		return name.hashCode();
	}

	@Override
	public final boolean equals(Object obj) {
		Node other = (Node) obj;
		return this.name.equals(other.name);
	}
	
	@Override
	protected final void finalize() throws Throwable {
		try {
			// Close connections that slipped through the cracks on race conditions.
			closeConnections();
		}
		finally {
			super.finalize();
		}
	}
	
	protected void closeConnections() {
		// Close tend connection after making reference copy.
		Connection conn = tendConnection;
		conn.close();
		
		// Empty connection pool.
		while ((conn = connectionQueue.poll()) != null) {			
			conn.close();
		}		
	}	
}
