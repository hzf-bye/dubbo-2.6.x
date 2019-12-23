/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.rpc;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * URL statistics. (API, Cached, ThreadSafe)
 *
 * @see com.alibaba.dubbo.rpc.filter.ActiveLimitFilter
 * @see com.alibaba.dubbo.rpc.filter.ExecuteLimitFilter
 * @see com.alibaba.dubbo.rpc.cluster.loadbalance.LeastActiveLoadBalance
 * RpcStatus总是记录的是某个提供者的一些信息，详见属性注释
 *
 * RpcStatus中的属性会在ActiveLimitFilter与ExecuteLimitFilter的invoke方法中赋值，
 * ActiveLimitFilter是消费者侧的过滤器
 *      因此在消费者侧 RpcStatus获取到的数据就是对应的消费者消费某个服务提供者 的 活跃数量、总次数、失败次数、总耗时等数据。
 *
 * ExecuteLimitFilter是消费者侧的过滤器
 *      因此在提供者侧 RpcStatus获取到的数据就是对应的提供者总的活跃数量、所有消费者消费的次数、所有消费者消费失败的次数、所有消费者消费失败的总耗时。
 *
 * 以上数据都是精确到服务的某个方法的
 *
 */
public class RpcStatus {

    /**
     * key 提供者url.toIdentityString()
     * value 是RpcStatus实例
     * 缓存的就是 每个提供者对应的RpcStatus信息
     */
    private static final ConcurrentMap<String, RpcStatus> SERVICE_STATISTICS = new ConcurrentHashMap<String, RpcStatus>();

    /**
     * key 提供者url.toIdentityString()
     * value 也是一个ConcurrentMap
     *        其中key是 提供者方法中的methodName, value是RpcStatus实例
     * 缓存的就是 每个提供者中每个方法对应的RpcStatus信息
     */
    private static final ConcurrentMap<String, ConcurrentMap<String, RpcStatus>> METHOD_STATISTICS = new ConcurrentHashMap<String, ConcurrentMap<String, RpcStatus>>();
    private final ConcurrentMap<String, Object> values = new ConcurrentHashMap<String, Object>();
    /** 活跃数量
     * 即当前消费者并行消费此服务的的数量
     * com.alibaba.dubbo.rpc.filter.ActiveLimitFilter#invoke(com.alibaba.dubbo.rpc.Invoker, com.alibaba.dubbo.rpc.Invocation)
     * com.alibaba.dubbo.rpc.filter.ExecuteLimitFilter#invoke(com.alibaba.dubbo.rpc.Invoker, com.alibaba.dubbo.rpc.Invocation)
     * 此过滤器中赋值当有一个请求来时 +1
     * 当请求完成后此值 -1
     */
    private final AtomicInteger active = new AtomicInteger();

    /**
     * 此服务总的消费次数
     * com.alibaba.dubbo.rpc.filter.ActiveLimitFilter#invoke(com.alibaba.dubbo.rpc.Invoker, com.alibaba.dubbo.rpc.Invocation)
     * com.alibaba.dubbo.rpc.filter.ExecuteLimitFilter#invoke(com.alibaba.dubbo.rpc.Invoker, com.alibaba.dubbo.rpc.Invocation)
     * 赋值
     */
    private final AtomicLong total = new AtomicLong();

    /**
     * 消费此服务失败的次数
     * com.alibaba.dubbo.rpc.filter.ActiveLimitFilter#invoke(com.alibaba.dubbo.rpc.Invoker, com.alibaba.dubbo.rpc.Invocation)
     * com.alibaba.dubbo.rpc.filter.ExecuteLimitFilter#invoke(com.alibaba.dubbo.rpc.Invoker, com.alibaba.dubbo.rpc.Invocation)
     * 赋值
     */
    private final AtomicInteger failed = new AtomicInteger();

    /**
     * 所有消费此服务总耗时
     */
    private final AtomicLong totalElapsed = new AtomicLong();

    /**
     * 所有消费此服务失败的总耗时
     */
    private final AtomicLong failedElapsed = new AtomicLong();

    /**
     * 所有此服务的消费，耗时最久的时间
     */
    private final AtomicLong maxElapsed = new AtomicLong();

    /**
     * 所有此服务消费失败的，耗时最久的时间
     */
    private final AtomicLong failedMaxElapsed = new AtomicLong();

    /**
     * 所有此服务消费成功的，耗时最久的时间
     */
    private final AtomicLong succeededMaxElapsed = new AtomicLong();

    /**
     * Semaphore used to control concurrency limit set by `executes`
     */
    private volatile Semaphore executesLimit;

    /**
     * executesLimit信号量里面的资源数量
     */
    private volatile int executesPermits;

    private RpcStatus() {
    }

    /**
     * @param url
     * @return status
     */
    public static RpcStatus getStatus(URL url) {
        String uri = url.toIdentityString();
        RpcStatus status = SERVICE_STATISTICS.get(uri);
        if (status == null) {
            SERVICE_STATISTICS.putIfAbsent(uri, new RpcStatus());
            status = SERVICE_STATISTICS.get(uri);
        }
        return status;
    }

    /**
     * @param url
     */
    public static void removeStatus(URL url) {
        String uri = url.toIdentityString();
        SERVICE_STATISTICS.remove(uri);
    }

    /**
     * @param url
     * @param methodName
     * @return status
     */
    public static RpcStatus getStatus(URL url, String methodName) {
        String uri = url.toIdentityString();
        ConcurrentMap<String, RpcStatus> map = METHOD_STATISTICS.get(uri);
        if (map == null) {
            METHOD_STATISTICS.putIfAbsent(uri, new ConcurrentHashMap<String, RpcStatus>());
            map = METHOD_STATISTICS.get(uri);
        }
        RpcStatus status = map.get(methodName);
        if (status == null) {
            map.putIfAbsent(methodName, new RpcStatus());
            status = map.get(methodName);
        }
        return status;
    }

    /**
     * @param url
     */
    public static void removeStatus(URL url, String methodName) {
        String uri = url.toIdentityString();
        ConcurrentMap<String, RpcStatus> map = METHOD_STATISTICS.get(uri);
        if (map != null) {
            map.remove(methodName);
        }
    }

    /**
     * @param url
     * 提供者对应的活跃数+1
     * 以及提供者+调用方法UI应的活跃数+1
     */
    public static void beginCount(URL url, String methodName) {
        beginCount(getStatus(url));
        beginCount(getStatus(url, methodName));
    }

    private static void beginCount(RpcStatus status) {
        status.active.incrementAndGet();
    }

    /**
     * @param url
     * @param elapsed
     * @param succeeded
     */
    public static void endCount(URL url, String methodName, long elapsed, boolean succeeded) {
        endCount(getStatus(url), elapsed, succeeded);
        endCount(getStatus(url, methodName), elapsed, succeeded);
    }

    private static void endCount(RpcStatus status, long elapsed, boolean succeeded) {
        status.active.decrementAndGet();
        status.total.incrementAndGet();
        status.totalElapsed.addAndGet(elapsed);
        if (status.maxElapsed.get() < elapsed) {
            status.maxElapsed.set(elapsed);
        }
        if (succeeded) {
            if (status.succeededMaxElapsed.get() < elapsed) {
                status.succeededMaxElapsed.set(elapsed);
            }
        } else {
            status.failed.incrementAndGet();
            status.failedElapsed.addAndGet(elapsed);
            if (status.failedMaxElapsed.get() < elapsed) {
                status.failedMaxElapsed.set(elapsed);
            }
        }
    }

    /**
     * set value.
     *
     * @param key
     * @param value
     */
    public void set(String key, Object value) {
        values.put(key, value);
    }

    /**
     * get value.
     *
     * @param key
     * @return value
     */
    public Object get(String key) {
        return values.get(key);
    }

    /**
     * get active.
     *
     * @return active
     */
    public int getActive() {
        return active.get();
    }

    /**
     * get total.
     *
     * @return total
     */
    public long getTotal() {
        return total.longValue();
    }

    /**
     * get total elapsed.
     *
     * @return total elapsed
     */
    public long getTotalElapsed() {
        return totalElapsed.get();
    }

    /**
     * get average elapsed.
     *
     * @return average elapsed
     */
    public long getAverageElapsed() {
        long total = getTotal();
        if (total == 0) {
            return 0;
        }
        return getTotalElapsed() / total;
    }

    /**
     * get max elapsed.
     *
     * @return max elapsed
     */
    public long getMaxElapsed() {
        return maxElapsed.get();
    }

    /**
     * get failed.
     *
     * @return failed
     */
    public int getFailed() {
        return failed.get();
    }

    /**
     * get failed elapsed.
     *
     * @return failed elapsed
     */
    public long getFailedElapsed() {
        return failedElapsed.get();
    }

    /**
     * get failed average elapsed.
     *
     * @return failed average elapsed
     */
    public long getFailedAverageElapsed() {
        long failed = getFailed();
        if (failed == 0) {
            return 0;
        }
        return getFailedElapsed() / failed;
    }

    /**
     * get failed max elapsed.
     *
     * @return failed max elapsed
     */
    public long getFailedMaxElapsed() {
        return failedMaxElapsed.get();
    }

    /**
     * get succeeded.
     *
     * @return succeeded
     */
    public long getSucceeded() {
        return getTotal() - getFailed();
    }

    /**
     * get succeeded elapsed.
     *
     * @return succeeded elapsed
     */
    public long getSucceededElapsed() {
        return getTotalElapsed() - getFailedElapsed();
    }

    /**
     * get succeeded average elapsed.
     *
     * @return succeeded average elapsed
     */
    public long getSucceededAverageElapsed() {
        long succeeded = getSucceeded();
        if (succeeded == 0) {
            return 0;
        }
        return getSucceededElapsed() / succeeded;
    }

    /**
     * get succeeded max elapsed.
     *
     * @return succeeded max elapsed.
     */
    public long getSucceededMaxElapsed() {
        return succeededMaxElapsed.get();
    }

    /**
     * Calculate average TPS (Transaction per second).
     *
     * @return tps
     */
    public long getAverageTps() {
        if (getTotalElapsed() >= 1000L) {
            return getTotal() / (getTotalElapsed() / 1000L);
        }
        return getTotal();
    }

    /**
     * Get the semaphore for thread number. Semaphore's permits is decided by {@link Constants#EXECUTES_KEY}
     *
     * @param maxThreadNum value of {@link Constants#EXECUTES_KEY}
     * @return thread number semaphore
     */
    public Semaphore getSemaphore(int maxThreadNum) {
        if(maxThreadNum <= 0) {
            return null;
        }

        if (executesLimit == null || executesPermits != maxThreadNum) {
            synchronized (this) {
                if (executesLimit == null || executesPermits != maxThreadNum) {
                    executesLimit = new Semaphore(maxThreadNum);
                    executesPermits = maxThreadNum;
                }
            }
        }

        return executesLimit;
    }
}