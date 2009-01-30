/**
 * Copyright 2009 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.zookeeper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Map.Entry;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HServerAddress;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.data.Stat;

/**
 * Wraps a ZooKeeper instance and adds HBase specific functionality.
 *
 * This class provides methods to:
 * - read/write/delete the root region location in ZooKeeper.
 * - set/check out of safe mode flag.
 */
public class ZooKeeperWrapper implements HConstants {
  protected static final Log LOG = LogFactory.getLog(ZooKeeperWrapper.class);

  // TODO: Replace this with ZooKeeper constant when ZOOKEEPER-277 is resolved.
  private static final String ZNODE_PATH_SEPARATOR = "/";

  private static String quorumServers = null;
  static {
    loadZooKeeperConfig();
  }

  private final ZooKeeper zooKeeper;
  private final WatcherWrapper watcher;

  private final String parentZNode;
  private final String rootRegionZNode;
  private final String outOfSafeModeZNode;

  /**
   * Create a ZooKeeperWrapper.
   * @param conf HBaseConfiguration to read settings from.
   * @throws IOException If a connection error occurs.
   */
  public ZooKeeperWrapper(HBaseConfiguration conf)
  throws IOException {
    this(conf, null);
  }

  /**
   * Create a ZooKeeperWrapper.
   * @param conf HBaseConfiguration to read settings from.
   * @param watcher ZooKeeper watcher to register.
   * @throws IOException If a connection error occurs.
   */
  public ZooKeeperWrapper(HBaseConfiguration conf, Watcher watcher)
  throws IOException {
    if (quorumServers == null) {
      throw new IOException("Could not read quorum servers from " +
                            ZOOKEEPER_CONFIG_NAME);
    }

    int sessionTimeout = conf.getInt(ZOOKEEPER_SESSION_TIMEOUT,
                                     DEFAULT_ZOOKEEPER_SESSION_TIMEOUT);
    this.watcher = new WatcherWrapper(watcher);
    try {
      zooKeeper = new ZooKeeper(quorumServers, sessionTimeout, this.watcher);
    } catch (IOException e) {
      LOG.error("Failed to create ZooKeeper object: " + e);
      throw new IOException(e);
    }

    parentZNode = conf.get(ZOOKEEPER_PARENT_ZNODE,
                           DEFAULT_ZOOKEEPER_PARENT_ZNODE);

    String rootServerZNodeName = conf.get(ZOOKEEPER_ROOT_SERVER_ZNODE,
                                          DEFAULT_ZOOKEEPER_ROOT_SERVER_ZNODE);
    if (rootServerZNodeName.startsWith(ZNODE_PATH_SEPARATOR)) {
      rootRegionZNode = rootServerZNodeName;
    } else {
      rootRegionZNode = parentZNode + ZNODE_PATH_SEPARATOR + rootServerZNodeName;
    }

    String outOfSafeModeZNodeName = conf.get(ZOOKEEPER_SAFE_MODE_ZNODE,
                                             DEFAULT_ZOOKEEPER_SAFE_MODE_ZNODE);
    if (outOfSafeModeZNodeName.startsWith(ZNODE_PATH_SEPARATOR)) {
      outOfSafeModeZNode = outOfSafeModeZNodeName;
    } else {
      outOfSafeModeZNode = parentZNode + ZNODE_PATH_SEPARATOR +
                           outOfSafeModeZNodeName;
    }
  }

  /**
   * This is for tests to directly set the ZooKeeper quorum servers.
   * @param servers comma separated host:port ZooKeeper quorum servers.
   */
  public static void setQuorumServers(String servers) {
    quorumServers = servers;
  }

  private static void loadZooKeeperConfig() {
    InputStream inputStream =
      ZooKeeperWrapper.class.getClassLoader().getResourceAsStream(ZOOKEEPER_CONFIG_NAME);
    if (inputStream == null) {
      LOG.error("fail to open ZooKeeper config file " + ZOOKEEPER_CONFIG_NAME);
      return;
    }

    Properties properties = new Properties();
    try {
      properties.load(inputStream);
    } catch (IOException e) {
      LOG.error("fail to read properties from " + ZOOKEEPER_CONFIG_NAME);
      return;
    }

    String clientPort = null;
    List<String> servers = new ArrayList<String>();

    // The clientPort option may come after the server.X hosts, so we need to
    // grab everything and then create the final host:port comma separated list.
    for (Entry<Object,Object> property : properties.entrySet()) {
      String key = property.getKey().toString().trim();
      String value = property.getValue().toString().trim();
      if (key.equals("clientPort")) {
        clientPort = value;
      }
      else if (key.startsWith("server.")) {
        String host = value.substring(0, value.indexOf(':'));
        servers.add(host);
      }
    }

    if (clientPort == null) {
      LOG.error("no clientPort found in " + ZOOKEEPER_CONFIG_NAME);
      return;
    }

    // If no server.X lines exist, then we're using a single instance ZooKeeper
    // on the master node.
    if (servers.isEmpty()) {
      HBaseConfiguration conf = new HBaseConfiguration();
      String masterAddress = conf.get(MASTER_ADDRESS, DEFAULT_MASTER_ADDRESS);
      String masterHost = "localhost";
      if (!masterAddress.equals("local")) {
        masterHost = masterAddress.substring(0, masterAddress.indexOf(':'));
      }
      servers.add(masterHost);
    }

    StringBuilder hostPortBuilder = new StringBuilder();
    for (int i = 0; i < servers.size(); ++i) {
      String host = servers.get(i);
      if (i > 0) {
        hostPortBuilder.append(',');
      }
      hostPortBuilder.append(host);
      hostPortBuilder.append(':');
      hostPortBuilder.append(clientPort);
    }

    quorumServers = hostPortBuilder.toString();
    LOG.info("Quorum servers: " + quorumServers);
  }

  /** @return true if currently connected to ZooKeeper, false otherwise. */
  public boolean isConnected() {
    return zooKeeper.getState() == States.CONNECTED;
  }

  /**
   * Read location of server storing root region.
   * @return HServerAddress pointing to server serving root region or null if
   *         there was a problem reading the ZNode.
   */
  public HServerAddress readRootRegionLocation() {
    byte[] data;
    try {
      data = zooKeeper.getData(rootRegionZNode, false, null);
    } catch (InterruptedException e) {
      return null;
    } catch (KeeperException e) {
      return null;
    }

    String addressString = Bytes.toString(data);
    LOG.debug("Read ZNode " + rootRegionZNode + " got " + addressString);
    HServerAddress address = new HServerAddress(addressString);
    return address;
  }

  private boolean ensureParentZNodeExists() {
    try {
      zooKeeper.create(parentZNode, new byte[0],
                       Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
      LOG.debug("Created ZNode " + parentZNode);
      return true;
    } catch (KeeperException.NodeExistsException e) {
      return true;      // ok, move on.
    } catch (KeeperException e) {
      LOG.warn("Failed to create " + parentZNode + ": " + e);
    } catch (InterruptedException e) {
      LOG.warn("Failed to create " + parentZNode + ": " + e);
    }

    return false;
  }

  /**
   * Delete ZNode containing root region location.
   * @return true if operation succeeded, false otherwise.
   */
  public boolean deleteRootRegionLocation()  {
    if (!ensureParentZNodeExists()) {
      return false;
    }

    try {
      zooKeeper.delete(rootRegionZNode, -1);
      LOG.debug("Deleted ZNode " + rootRegionZNode);
      return true;
    } catch (KeeperException.NoNodeException e) {
      return true;    // ok, move on.
    } catch (KeeperException e) {
      LOG.warn("Failed to delete " + rootRegionZNode + ": " + e);
    } catch (InterruptedException e) {
      LOG.warn("Failed to delete " + rootRegionZNode + ": " + e);
    }

    return false;
  }

  private boolean createRootRegionLocation(String address) {
    byte[] data = Bytes.toBytes(address);
    try {
      zooKeeper.create(rootRegionZNode, data, Ids.OPEN_ACL_UNSAFE,
                       CreateMode.PERSISTENT);
      LOG.debug("Created ZNode " + rootRegionZNode + " with data " + address);
      return true;
    } catch (KeeperException e) {
      LOG.warn("Failed to create root region in ZooKeeper: " + e);
    } catch (InterruptedException e) {
      LOG.warn("Failed to create root region in ZooKeeper: " + e);
    }

    return false;
  }

  private boolean updateRootRegionLocation(String address) {
    byte[] data = Bytes.toBytes(address);
    try {
      zooKeeper.setData(rootRegionZNode, data, -1);
      LOG.debug("SetData of ZNode " + rootRegionZNode + " with " + address);
      return true;
    } catch (KeeperException e) {
      LOG.warn("Failed to set root region location in ZooKeeper: " + e);
    } catch (InterruptedException e) {
      LOG.warn("Failed to set root region location in ZooKeeper: " + e);
    }

    return false;
  }

  /**
   * Write root region location to ZooKeeper. If address is null, delete ZNode.
   * containing root region location.
   * @param address HServerAddress to write to ZK.
   * @return true if operation succeeded, false otherwise.
   */
  public boolean writeRootRegionLocation(HServerAddress address) {
    if (address == null) {
      return deleteRootRegionLocation();
    }

    if (!ensureParentZNodeExists()) {
      return false;
    }

    String addressString = address.toString();

    if (checkExistenceOf(rootRegionZNode)) {
      return updateRootRegionLocation(addressString);
    }

    return createRootRegionLocation(addressString);
  }

  /**
   * Check if we're out of safe mode. Being out of safe mode is signified by an
   * ephemeral ZNode existing in ZooKeeper.
   * @return true if we're out of safe mode, false otherwise.
   */
  public boolean checkOutOfSafeMode() {
    if (!ensureParentZNodeExists()) {
      return false;
    }

    return checkExistenceOf(outOfSafeModeZNode);
  }

  /**
   * Create ephemeral ZNode signifying that we're out of safe mode.
   * @return true if ephemeral ZNode created successfully, false otherwise.
   */
  public boolean writeOutOfSafeMode() {
    if (!ensureParentZNodeExists()) {
      return false;
    }

    try {
      zooKeeper.create(outOfSafeModeZNode, new byte[0], Ids.OPEN_ACL_UNSAFE,
                       CreateMode.EPHEMERAL);
      LOG.debug("Wrote out of safe mode");
      return true;
    } catch (InterruptedException e) {
      LOG.warn("Failed to create out of safe mode in ZooKeeper: " + e);
    } catch (KeeperException e) {
      LOG.warn("Failed to create out of safe mode in ZooKeeper: " + e);
    }

    return false;
  }

  private boolean checkExistenceOf(String path) {
    Stat stat = null;
    try {
      stat = zooKeeper.exists(path, false);
    } catch (KeeperException e) {
      LOG.warn("checking existence of " + path, e);
    } catch (InterruptedException e) {
      LOG.warn("checking existence of " + path, e);
    }

    return stat != null;
  }

  /**
   * Close this ZooKeeper session.
   */
  public void close() {
    try {
      zooKeeper.close();
      LOG.debug("Closed connection with ZooKeeper");
    } catch (InterruptedException e) {
      LOG.warn("Failed to close connection with ZooKeeper");
    }
  }
}