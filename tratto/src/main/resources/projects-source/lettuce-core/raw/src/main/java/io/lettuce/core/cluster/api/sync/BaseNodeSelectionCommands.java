/*
 * Copyright 2017-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.lettuce.core.cluster.api.sync;

import java.util.List;
import java.util.Map;

/**
 * Synchronous executed commands on a node selection for basic commands.
 *
 * @param <K> Key type.
 * @param <V> Value type.
 * @author Mark Paluch
 * @since 4.0
 * @generated by io.lettuce.apigenerator.CreateSyncNodeSelectionClusterApi
 */
public interface BaseNodeSelectionCommands<K, V> {

    /**
     * Post a message to a channel.
     *
     * @param channel the channel type: key.
     * @param message the message type: value.
     * @return Long integer-reply the number of clients that received the message.
     */
    Executions<Long> publish(K channel, V message);

    /**
     * Lists the currently *active channels*.
     *
     * @return List&lt;K&gt; array-reply a list of active channels, optionally matching the specified pattern.
     */
    Executions<List<K>> pubsubChannels();

    /**
     * Lists the currently *active channels*.
     *
     * @param channel the key.
     * @return List&lt;K&gt; array-reply a list of active channels, optionally matching the specified pattern.
     */
    Executions<List<K>> pubsubChannels(K channel);

    /**
     * Returns the number of subscribers (not counting clients subscribed to patterns) for the specified channels.
     *
     * @param channels channel keys.
     * @return array-reply a list of channels and number of subscribers for every channel.
     */
    Executions<Map<K, Long>> pubsubNumsub(K... channels);

    /**
     * Returns the number of subscriptions to patterns.
     *
     * @return Long integer-reply the number of patterns all the clients are subscribed to.
     */
    Executions<Long> pubsubNumpat();

    /**
     * Echo the given string.
     *
     * @param msg the message type: value.
     * @return V bulk-string-reply.
     */
    Executions<V> echo(V msg);

    /**
     * Return the role of the instance in the context of replication.
     *
     * @return List&lt;Object&gt; array-reply where the first element is one of master, slave, sentinel and the additional
     *         elements are role-specific.
     */
    Executions<List<Object>> role();

    /**
     * Ping the server.
     *
     * @return String simple-string-reply.
     */
    Executions<String> ping();

    /**
     * Instructs Redis to disconnect the connection. Note that if auto-reconnect is enabled then Lettuce will auto-reconnect if
     * the connection was disconnected. Use {@link io.lettuce.core.api.StatefulConnection#close} to close connections and
     * release resources.
     *
     * @return String simple-string-reply always OK.
     */
    Executions<String> quit();

    /**
     * Wait for replication.
     *
     * @param replicas minimum number of replicas.
     * @param timeout timeout in milliseconds.
     * @return number of replicas.
     */
    Executions<Long> waitForReplication(int replicas, long timeout);
}