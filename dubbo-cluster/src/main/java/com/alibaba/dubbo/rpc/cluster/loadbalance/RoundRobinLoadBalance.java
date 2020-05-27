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
package com.alibaba.dubbo.rpc.cluster.loadbalance;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Round robin load balance.
 * 加权轮询算法实现负载均衡
 * 我们先来了解一下什么是加权轮询。这里从最简单的轮询开始讲起，所谓轮询是指将请求轮流分配给每台服务器。
 * 举个例子，我们有三台服务器 A、B、C。我们将第一个请求分配给服务器 A，第二个请求分配给服务器 B，
 * 第三个请求分配给服务器 C，第四个请求再次分配给服务器 A。
 * 这个过程就叫做轮询。轮询是一种无状态负载均衡算法，实现简单，适用于每台服务器性能相近的场景下。
 * 但现实情况下，我们并不能保证每台服务器性能均相近。如果我们将等量的请求分配给性能较差的服务器，这显然是不合理的。
 * 因此，这个时候我们需要对轮询过程进行加权，以调控每台服务器的负载。
 * 经过加权后，每台服务器能够得到的请求数比例，接近或等于他们的权重比。比如服务器 A、B、C 权重比为 5:2:1。
 * 那么在8次请求中，服务器 A 将收到其中的5次请求，服务器 B 会收到其中的2次请求，服务器 C 则收到其中的1次请求。
 *
 * 例子说明参考文档
 * http://dubbo.apache.org/zh-cn/docs/source_code_guide/loadbalance.html
 * 
 * Smoothly round robin's implementation @since 2.6.5 
 */
public class RoundRobinLoadBalance extends AbstractLoadBalance {
    public static final String NAME = "roundrobin";

    /**
     * 回收间隔
     */
    private static int RECYCLE_PERIOD = 60000;

    /**
     * 该内部类是一个加权轮询器，它记录了某一个服务提供者的一些数据，比如权重、比如当前已经有多少请求落在该服务提供者上等。
     */
    protected static class WeightedRoundRobin {

        /**
         * 权重
         */
        private int weight;

        /**
         * 当前权重
         * 当前已经有多少请求落在该服务提供者身上，也可以看成是一个动态的权重
         */
        private AtomicLong current = new AtomicLong(0);

        /**
         * 最后一次更新时间
         */
        private long lastUpdate;
        public int getWeight() {
            return weight;
        }
        public void setWeight(int weight) {
            this.weight = weight;
            // 初始情况下，current = 0
            current.set(0);
        }
        public long increaseCurrent() {
            // current = current + weight；
            return current.addAndGet(weight);
        }
        public void sel(int total) {
            // current = current - total;
            current.addAndGet(-1 * total);
        }
        public long getLastUpdate() {
            return lastUpdate;
        }
        public void setLastUpdate(long lastUpdate) {
            this.lastUpdate = lastUpdate;
        }
    }

    // 嵌套 Map 结构，存储的数据结构示例如下：
    // {
    //     "com.xxx.UserService.query": {
    //         "url1": WeightedRoundRobin@123,
    //         "url2": WeightedRoundRobin@456,
    //     },
    //     "com.xxx.UserService.update": {
    //         "url1": WeightedRoundRobin@123,
    //         "url2": WeightedRoundRobin@456,
    //     }
    // }
    // 最外层为服务类名 + 方法名，第二层为 url 到 WeightedRoundRobin 的映射关系。
    // 这里我们可以将 url 看成是服务提供者的 id
    private ConcurrentMap<String, ConcurrentMap<String, WeightedRoundRobin>> methodWeightMap = new ConcurrentHashMap<String, ConcurrentMap<String, WeightedRoundRobin>>();

    // 原子更新锁
    private AtomicBoolean updateLock = new AtomicBoolean();
    
    /**
     * get invoker addr list cached for specified invocation
     * <p>
     * <b>for unit test only</b>
     * 
     * @param invokers
     * @param invocation
     * @return
     */
    protected <T> Collection<String> getInvokerAddrList(List<Invoker<T>> invokers, Invocation invocation) {
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        Map<String, WeightedRoundRobin> map = methodWeightMap.get(key);
        if (map != null) {
            return map.keySet();
        }
        return null;
    }

    /**
     * 例子说明参考文档
     * http://dubbo.apache.org/zh-cn/docs/source_code_guide/loadbalance.html
     */
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // key = 全限定类名 + "." + 方法名，比如 com.xxx.DemoService.sayHello
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        // 获取 url 到 WeightedRoundRobin 映射表，如果为空，则创建一个新的
        ConcurrentMap<String, WeightedRoundRobin> map = methodWeightMap.get(key);
        if (map == null) {
            methodWeightMap.putIfAbsent(key, new ConcurrentHashMap<String, WeightedRoundRobin>());
            map = methodWeightMap.get(key);
        }
        // 权重总和
        int totalWeight = 0;
        // 最大权重
        long maxCurrent = Long.MIN_VALUE;
        // 获得现在的时间戳
        long now = System.currentTimeMillis();
        // 创建已经选择的invoker
        Invoker<T> selectedInvoker = null;
        // 创建加权轮询器
        WeightedRoundRobin selectedWRR = null;

        /*
         * 下面这个循环主要做了这样几件事情：
         *
         * 1. 遍历 Invoker 列表，检测当前 Invoker 是否有
         *    相应的 WeightedRoundRobin，没有则创建
         * 2. 检测 Invoker 权重是否发生了变化，若变化了，
         *    则更新 WeightedRoundRobin 的 weight 字段
         * 3. 让 current 字段加上自身权重，等价于 current += weight
         * 4. 设置 lastUpdate 字段，即 lastUpdate = now
         * 5. 寻找具有最大 current 的 Invoker，以及 Invoker 对应的 WeightedRoundRobin，
         *    暂存起来，留作后用
         * 6. 计算权重总和
         */
        for (Invoker<T> invoker : invokers) {
            // 获得identify的值
            String identifyString = invoker.getUrl().toIdentityString();
            // 获得加权轮询器
            WeightedRoundRobin weightedRoundRobin = map.get(identifyString);
            // 计算权重
            int weight = getWeight(invoker, invocation);
            // 如果权重小于0，则设置0
            if (weight < 0) {
                weight = 0;
            }
            // 检测当前 Invoker 是否有对应的 WeightedRoundRobin，没有则创建
            if (weightedRoundRobin == null) {
                // 创建加权轮询器
                weightedRoundRobin = new WeightedRoundRobin();
                // 设置权重
                weightedRoundRobin.setWeight(weight);
                // 存储 url 唯一标识 identifyString 到 weightedRoundRobin 的映射关系
                map.putIfAbsent(identifyString, weightedRoundRobin);
                weightedRoundRobin = map.get(identifyString);
            }
            // Invoker 权重不等于 WeightedRoundRobin 中保存的权重，说明权重变化了，此时进行更新
            if (weight != weightedRoundRobin.getWeight()) {
                //weight changed
                weightedRoundRobin.setWeight(weight);
            }
            // 让 current 加上自身权重，等价于 current += weight
            long cur = weightedRoundRobin.increaseCurrent();
            // 设置 lastUpdate，表示近期更新过
            weightedRoundRobin.setLastUpdate(now);
            // 找出最大的 current
            if (cur > maxCurrent) {
                maxCurrent = cur;
                // 将具有最大 current 权重的 Invoker 赋值给 selectedInvoker
                selectedInvoker = invoker;
                // 将 Invoker 对应的 weightedRoundRobin 赋值给 selectedWRR，留作后用
                selectedWRR = weightedRoundRobin;
            }
            totalWeight += weight;
        }

        // 对 <identifyString, WeightedRoundRobin> 进行检查，过滤掉长时间未被更新的节点。
        // 该节点可能挂了，invokers 中不包含该节点，所以该节点的 lastUpdate 长时间无法被更新。
        // 若未更新时长超过阈值后，就会被移除掉，默认阈值为60秒。
        if (!updateLock.get() && invokers.size() != map.size()) {
            if (updateLock.compareAndSet(false, true)) {
                try {
                    // copy -> modify -> update reference
                    ConcurrentMap<String, WeightedRoundRobin> newMap = new ConcurrentHashMap<String, WeightedRoundRobin>();
                    // 拷贝
                    newMap.putAll(map);

                    // 遍历修改，即移除过期记录
                    Iterator<Entry<String, WeightedRoundRobin>> it = newMap.entrySet().iterator();
                    while (it.hasNext()) {
                        Entry<String, WeightedRoundRobin> item = it.next();
                        if (now - item.getValue().getLastUpdate() > RECYCLE_PERIOD) {
                            it.remove();
                        }
                    }

                    // 更新引用
                    methodWeightMap.put(key, newMap);
                } finally {
                    updateLock.set(false);
                }
            }
        }
        if (selectedInvoker != null) {
            // 让 current 减去权重总和，等价于 current -= totalWeight
            selectedWRR.sel(totalWeight);
            // 返回具有最大 current 的 Invoker
            return selectedInvoker;
        }
        // should not happen here
        return invokers.get(0);
    }

}
