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
package com.alibaba.dubbo.rpc.cluster.directory;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Router;
import com.alibaba.dubbo.rpc.cluster.support.AbstractClusterInvoker;
import com.alibaba.dubbo.rpc.cluster.support.FailoverClusterInvoker;
import com.alibaba.dubbo.rpc.cluster.support.wrapper.MockClusterInvoker;

import java.util.List;

/**
 * StaticDirectory
 * 静态 Directory 实现类，将传入的 invokers 集合，封装成静态的 Directory 对象。
 *
 * 消费端使用多个注册中心时，把所有服务注册中心的invoker列表汇集到一个invoker列表中
 * 那么
 * 调用链路就是 AbstractClusterInvoker.invoke(此时AbstractClusterInvoker中的directory即为StaticDirectory)，返回此invokers列表
 * 然后均衡选择其中一个MockClusterInvoker,后续调用步骤与但注册中心场景一致
 * MockClusterInvoker(此时AbstractClusterInvoker中的directory即为RegistryDirectory)->FailoverClusterInvoker
 *
 */
public class StaticDirectory<T> extends AbstractDirectory<T> {

    /**
     *  Invoker 列表
     * @see com.alibaba.dubbo.registry.integration.RegistryDirectory#toMergeMethodInvokerMap(java.util.Map)
     * 中的invoker为 {@link com.alibaba.dubbo.registry.integration.RegistryDirectory.InvokerDelegate}实例
     *
     *
     * @see com.alibaba.dubbo.config.ReferenceConfig#createProxy(java.util.Map)
     * 当使用多注册中心时，此时invokers中的实例为 {@link MockClusterInvoker}实例
     */
    private final List<Invoker<T>> invokers;

    public StaticDirectory(List<Invoker<T>> invokers) {
        this(null, invokers, null);
    }

    public StaticDirectory(List<Invoker<T>> invokers, List<Router> routers) {
        this(null, invokers, routers);
    }

    public StaticDirectory(URL url, List<Invoker<T>> invokers) {
        this(url, invokers, null);
    }

    public StaticDirectory(URL url, List<Invoker<T>> invokers, List<Router> routers) {
        super(url == null && invokers != null && !invokers.isEmpty() ? invokers.get(0).getUrl() : url, routers);
        if (invokers == null || invokers.isEmpty())
            throw new IllegalArgumentException("invokers == null");
        this.invokers = invokers;
    }

    @Override
    public Class<T> getInterface() {
        // 获取接口类
        return invokers.get(0).getInterface();
    }

    /**
     * 检测服务目录是否可用
     */
    @Override
    public boolean isAvailable() {
        if (isDestroyed()) {
            return false;
        }
        for (Invoker<T> invoker : invokers) {
            // 只要有一个 Invoker 是可用的，就认为当前目录是可用的
            if (invoker.isAvailable()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void destroy() {
        if (isDestroyed()) {
            return;
        }
        // 调用父类销毁逻辑
        super.destroy();
        // 遍历 Invoker 列表，并执行相应的销毁逻辑
        for (Invoker<T> invoker : invokers) {
            invoker.destroy();
        }
        invokers.clear();
    }

    @Override
    protected List<Invoker<T>> doList(Invocation invocation) throws RpcException {

        // 列举 Inovker，也就是直接返回 invokers 成员变量
        return invokers;
    }

}
