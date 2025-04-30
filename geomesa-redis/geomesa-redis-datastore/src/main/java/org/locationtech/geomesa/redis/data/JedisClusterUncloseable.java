/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.redis.data;

import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.UnifiedJedis;

import java.time.Duration;
import java.util.Set;

/**
 * A subclass of JedisCluster that overrides the close method to prevent closing the cluster connection.
 */
public class JedisClusterUncloseable extends UnifiedJedis {
    public JedisClusterUncloseable(Set<HostAndPort> clusterNodes, JedisClientConfig clientConfig, int maxAttempts,
                        Duration maxTotalRetriesDuration) {
        super(clusterNodes, clientConfig, maxAttempts, maxTotalRetriesDuration);
    }

    @Override
    public void close() {
        // Override the close method to prevent closing the JedisCluster instance
        // This is intentional to avoid closing the cluster connection
        // super.close(); // Do not call the superclass close method
    }
}
