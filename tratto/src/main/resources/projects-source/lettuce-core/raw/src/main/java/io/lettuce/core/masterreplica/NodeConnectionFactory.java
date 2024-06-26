/*
 * Copyright 2020-2024 the original author or authors.
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
package io.lettuce.core.masterreplica;

import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;

import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.RedisCodec;

/**
 * Factory interface to obtain {@link StatefulRedisConnection connections} to Redis nodes.
 *
 * @author Mark Paluch
 * @since 4.4
 */
interface NodeConnectionFactory {

    /**
     * Connects to a {@link SocketAddress} with the given {@link RedisCodec} asynchronously.
     *
     * @param codec must not be {@code null}.
     * @param redisURI must not be {@code null}.
     * @param <K> Key type.
     * @param <V> Value type.
     * @return a new {@link StatefulRedisConnection}
     */
    <K, V> CompletableFuture<StatefulRedisConnection<K, V>> connectToNodeAsync(RedisCodec<K, V> codec, RedisURI redisURI);

}
