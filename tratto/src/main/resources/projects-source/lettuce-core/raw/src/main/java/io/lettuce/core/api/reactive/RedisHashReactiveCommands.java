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
package io.lettuce.core.api.reactive;

import java.util.Map;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import io.lettuce.core.KeyValue;
import io.lettuce.core.MapScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.StreamScanCursor;
import io.lettuce.core.output.KeyStreamingChannel;
import io.lettuce.core.output.KeyValueStreamingChannel;
import io.lettuce.core.output.ValueStreamingChannel;

/**
 * Reactive executed commands for Hashes (Key-Value pairs).
 *
 * @param <K> Key type.
 * @param <V> Value type.
 * @author Mark Paluch
 * @since 4.0
 * @generated by io.lettuce.apigenerator.CreateReactiveApi
 */
public interface RedisHashReactiveCommands<K, V> {

    /**
     * Delete one or more hash fields.
     *
     * @param key the key.
     * @param fields the field type: key.
     * @return Long integer-reply the number of fields that were removed from the hash, not including specified but non existing
     *         fields.
     */
    Mono<Long> hdel(K key, K... fields);

    /**
     * Determine if a hash field exists.
     *
     * @param key the key.
     * @param field the field type: key.
     * @return Boolean integer-reply specifically:
     *
     *         {@code true} if the hash contains {@code field}. {@code false} if the hash does not contain {@code field}, or
     *         {@code key} does not exist.
     */
    Mono<Boolean> hexists(K key, K field);

    /**
     * Get the value of a hash field.
     *
     * @param key the key.
     * @param field the field type: key.
     * @return V bulk-string-reply the value associated with {@code field}, or {@code null} when {@code field} is not present in
     *         the hash or {@code key} does not exist.
     */
    Mono<V> hget(K key, K field);

    /**
     * Increment the integer value of a hash field by the given number.
     *
     * @param key the key.
     * @param field the field type: key.
     * @param amount the increment type: long.
     * @return Long integer-reply the value at {@code field} after the increment operation.
     */
    Mono<Long> hincrby(K key, K field, long amount);

    /**
     * Increment the float value of a hash field by the given amount.
     *
     * @param key the key.
     * @param field the field type: key.
     * @param amount the increment type: double.
     * @return Double bulk-string-reply the value of {@code field} after the increment.
     */
    Mono<Double> hincrbyfloat(K key, K field, double amount);

    /**
     * Get all the fields and values in a hash.
     *
     * @param key the key.
     * @return Map&lt;K,V&gt; array-reply list of fields and their values stored in the hash, or an empty list when {@code key}
     *         does not exist.
     */
    Flux<KeyValue<K, V>> hgetall(K key);

    /**
     * Stream over all the fields and values in a hash.
     *
     * @param channel the channel.
     * @param key the key.
     * @return Long count of the keys.
     * @deprecated since 6.0 in favor of consuming large results through the {@link org.reactivestreams.Publisher} returned by {@link #hgetall}.
     */
    @Deprecated
    Mono<Long> hgetall(KeyValueStreamingChannel<K, V> channel, K key);

    /**
     * Get all the fields in a hash.
     *
     * @param key the key.
     * @return K array-reply list of fields in the hash, or an empty list when {@code key} does not exist.
     */
    Flux<K> hkeys(K key);

    /**
     * Stream over all the fields in a hash.
     *
     * @param channel the channel.
     * @param key the key.
     * @return Long count of the keys.
     * @deprecated since 6.0 in favor of consuming large results through the {@link org.reactivestreams.Publisher} returned by {@link #hkeys}.
     */
    @Deprecated
    Mono<Long> hkeys(KeyStreamingChannel<K> channel, K key);

    /**
     * Get the number of fields in a hash.
     *
     * @param key the key.
     * @return Long integer-reply number of fields in the hash, or {@code 0} when {@code key} does not exist.
     */
    Mono<Long> hlen(K key);

    /**
     * Get the values of all the given hash fields.
     *
     * @param key the key.
     * @param fields the field type: key.
     * @return V array-reply list of values associated with the given fields, in the same.
     */
    Flux<KeyValue<K, V>> hmget(K key, K... fields);

    /**
     * Stream over the values of all the given hash fields.
     *
     * @param channel the channel.
     * @param key the key.
     * @param fields the fields.
     * @return Long count of the keys.
     * @deprecated since 6.0 in favor of consuming large results through the {@link org.reactivestreams.Publisher} returned by {@link #hmget}.
     */
    @Deprecated
    Mono<Long> hmget(KeyValueStreamingChannel<K, V> channel, K key, K... fields);

    /**
     * Set multiple hash fields to multiple values.
     *
     * @param key the key.
     * @param map the hash to apply.
     * @return String simple-string-reply.
     */
    Mono<String> hmset(K key, Map<K, V> map);

    /**
     * Return a random field from the hash stored at {@code key}.
     *
     * @param key the key.
     * @return hash field name.
     * @since 6.1
     */
    Mono<K> hrandfield(K key);

    /**
     * Return {@code count} random fields from the hash stored at {@code key}.
     *
     * @param key the key.
     * @param count the number of fields to return. If the provided count argument is positive, return an array of distinct
     *        fields.
     * @return array-reply list of field names.
     * @since 6.1
     */
    Flux<K> hrandfield(K key, long count);

    /**
     * Return a random field along its value from the hash stored at {@code key}.
     *
     * @param key the key.
     * @param count the number of fields to return. If the provided count argument is positive, return an array of distinct
     *        fields.
     * @return array-reply the key and value.
     * @since 6.1
     */
    Mono<KeyValue<K, V>> hrandfieldWithvalues(K key);

    /**
     * Return {@code count} random fields along their value from the hash stored at {@code key}.
     *
     * @param key the key.
     * @param count the number of fields to return. If the provided count argument is positive, return an array of distinct
     *        fields.
     * @return array-reply the keys and values.
     * @since 6.1
     */
    Flux<KeyValue<K, V>> hrandfieldWithvalues(K key, long count);

    /**
     * Incrementally iterate hash fields and associated values.
     *
     * @param key the key.
     * @return MapScanCursor&lt;K, V&gt; map scan cursor.
     */
    Mono<MapScanCursor<K, V>> hscan(K key);

    /**
     * Incrementally iterate hash fields and associated values.
     *
     * @param key the key.
     * @param scanArgs scan arguments.
     * @return MapScanCursor&lt;K, V&gt; map scan cursor.
     */
    Mono<MapScanCursor<K, V>> hscan(K key, ScanArgs scanArgs);

    /**
     * Incrementally iterate hash fields and associated values.
     *
     * @param key the key.
     * @param scanCursor cursor to resume from a previous scan, must not be {@code null}.
     * @param scanArgs scan arguments.
     * @return MapScanCursor&lt;K, V&gt; map scan cursor.
     */
    Mono<MapScanCursor<K, V>> hscan(K key, ScanCursor scanCursor, ScanArgs scanArgs);

    /**
     * Incrementally iterate hash fields and associated values.
     *
     * @param key the key.
     * @param scanCursor cursor to resume from a previous scan, must not be {@code null}.
     * @return MapScanCursor&lt;K, V&gt; map scan cursor.
     */
    Mono<MapScanCursor<K, V>> hscan(K key, ScanCursor scanCursor);

    /**
     * Incrementally iterate hash fields and associated values.
     *
     * @param channel streaming channel that receives a call for every key-value pair.
     * @param key the key.
     * @return StreamScanCursor scan cursor.
     * @deprecated since 6.0 in favor of consuming large results through the {@link org.reactivestreams.Publisher} returned by {@link #hscan}.
     */
    @Deprecated
    Mono<StreamScanCursor> hscan(KeyValueStreamingChannel<K, V> channel, K key);

    /**
     * Incrementally iterate hash fields and associated values.
     *
     * @param channel streaming channel that receives a call for every key-value pair.
     * @param key the key.
     * @param scanArgs scan arguments.
     * @return StreamScanCursor scan cursor.
     * @deprecated since 6.0 in favor of consuming large results through the {@link org.reactivestreams.Publisher} returned by {@link #hscan}.
     */
    @Deprecated
    Mono<StreamScanCursor> hscan(KeyValueStreamingChannel<K, V> channel, K key, ScanArgs scanArgs);

    /**
     * Incrementally iterate hash fields and associated values.
     *
     * @param channel streaming channel that receives a call for every key-value pair.
     * @param key the key.
     * @param scanCursor cursor to resume from a previous scan, must not be {@code null}.
     * @param scanArgs scan arguments.
     * @return StreamScanCursor scan cursor.
     * @deprecated since 6.0 in favor of consuming large results through the {@link org.reactivestreams.Publisher} returned by {@link #hscan}.
     */
    @Deprecated
    Mono<StreamScanCursor> hscan(KeyValueStreamingChannel<K, V> channel, K key, ScanCursor scanCursor, ScanArgs scanArgs);

    /**
     * Incrementally iterate hash fields and associated values.
     *
     * @param channel streaming channel that receives a call for every key-value pair.
     * @param key the key.
     * @param scanCursor cursor to resume from a previous scan, must not be {@code null}.
     * @return StreamScanCursor scan cursor.
     * @deprecated since 6.0 in favor of consuming large results through the {@link org.reactivestreams.Publisher} returned by {@link #hscan}.
     */
    @Deprecated
    Mono<StreamScanCursor> hscan(KeyValueStreamingChannel<K, V> channel, K key, ScanCursor scanCursor);

    /**
     * Set the string value of a hash field.
     *
     * @param key the key.
     * @param field the field type: key.
     * @param value the value.
     * @return Boolean integer-reply specifically:
     *
     *         {@code true} if {@code field} is a new field in the hash and {@code value} was set. {@code false} if
     *         {@code field} already exists in the hash and the value was updated.
     */
    Mono<Boolean> hset(K key, K field, V value);

    /**
     * Set multiple hash fields to multiple values.
     *
     * @param key the key of the hash.
     * @param map the field/value pairs to update.
     * @return Long integer-reply: the number of fields that were added.
     * @since 5.3
     */
    Mono<Long> hset(K key, Map<K, V> map);

    /**
     * Set the value of a hash field, only if the field does not exist.
     *
     * @param key the key.
     * @param field the field type: key.
     * @param value the value.
     * @return Boolean integer-reply specifically:
     *
     *         {@code 1} if {@code field} is a new field in the hash and {@code value} was set. {@code 0} if {@code field}
     *         already exists in the hash and no operation was performed.
     */
    Mono<Boolean> hsetnx(K key, K field, V value);

    /**
     * Get the string length of the field value in a hash.
     *
     * @param key the key.
     * @param field the field type: key.
     * @return Long integer-reply the string length of the {@code field} value, or {@code 0} when {@code field} is not present
     *         in the hash or {@code key} does not exist at all.
     */
    Mono<Long> hstrlen(K key, K field);

    /**
     * Get all the values in a hash.
     *
     * @param key the key.
     * @return V array-reply list of values in the hash, or an empty list when {@code key} does not exist.
     */
    Flux<V> hvals(K key);

    /**
     * Stream over all the values in a hash.
     *
     * @param channel streaming channel that receives a call for every value.
     * @param key the key.
     * @return Long count of the keys.
     * @deprecated since 6.0 in favor of consuming large results through the {@link org.reactivestreams.Publisher} returned by {@link #hvals}.
     */
    @Deprecated
    Mono<Long> hvals(ValueStreamingChannel<V> channel, K key);
}