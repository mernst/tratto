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
package io.lettuce.core.api;

/**
 * ${intent} for HyperLogLog (PF* commands).
 *
 * @param <K> Key type.
 * @param <V> Value type.
 * @author Mark Paluch
 * @since 3.0
 */
public interface RedisHLLCommands<K, V> {

    /**
     * Adds the specified elements to the specified HyperLogLog.
     *
     * @param key the key.
     * @param values the values.
     * @return Long integer-reply specifically:
     *
     *         1 if at least 1 HyperLogLog internal register was altered. 0 otherwise.
     */
    Long pfadd(K key, V... values);

    /**
     * Merge N different HyperLogLogs into a single one.
     *
     * @param destkey the destination key.
     * @param sourcekeys the source key.
     * @return String simple-string-reply The command just returns {@code OK}.
     */
    String pfmerge(K destkey, K... sourcekeys);

    /**
     * Return the approximated cardinality of the set(s) observed by the HyperLogLog at key(s).
     *
     * @param keys the keys.
     * @return Long integer-reply specifically:
     *
     *         The approximated number of unique elements observed via {@code PFADD}.
     */
    Long pfcount(K... keys);

}
