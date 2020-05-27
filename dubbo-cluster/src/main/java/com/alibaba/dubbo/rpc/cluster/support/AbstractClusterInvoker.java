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
package com.alibaba.dubbo.rpc.cluster.support;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;
import com.alibaba.dubbo.rpc.cluster.directory.StaticDirectory;
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AbstractClusterInvoker
 *
 */
public abstract class AbstractClusterInvoker<T> implements Invoker<T> {

    private static final Logger logger = LoggerFactory
            .getLogger(AbstractClusterInvoker.class);

    /**
     * RegistryDirectory实例或者StaticDirectory
     * 目录，包含多个invoker
     */
    protected final Directory<T> directory;

    /**
     * 是否需要核对可用。默认true
     */
    protected final boolean availablecheck;

    /**
     * 是否销毁
     */
    private AtomicBoolean destroyed = new AtomicBoolean(false);

    /**
     * 粘滞连接的Invoker
     * 所谓粘滞连接是指让服务消费者尽可能的，调用同一个服务提供者，除非该提供者挂了再进行切换
     */
    private volatile Invoker<T> stickyInvoker = null;

    /**
     *
     * 1. {@link com.alibaba.dubbo.config.ReferenceConfig#createProxy(java.util.Map)}
     * 当使用多注册中心时directory为 {@link StaticDirectory}
     */
    public AbstractClusterInvoker(Directory<T> directory) {
        this(directory, directory.getUrl());
    }

    public AbstractClusterInvoker(Directory<T> directory, URL url) {
        if (directory == null)
            throw new IllegalArgumentException("service directory == null");

        this.directory = directory;
        //sticky: invoker.isAvailable() should always be checked before using when availablecheck is true.
        this.availablecheck = url.getParameter(Constants.CLUSTER_AVAILABLE_CHECK_KEY, Constants.DEFAULT_CLUSTER_AVAILABLE_CHECK);
    }

    @Override
    public Class<T> getInterface() {
        return directory.getInterface();
    }

    @Override
    public URL getUrl() {
        return directory.getUrl();
    }

    @Override
    public boolean isAvailable() {
        Invoker<T> invoker = stickyInvoker;
        if (invoker != null) {
            return invoker.isAvailable();
        }
        return directory.isAvailable();
    }

    @Override
    public void destroy() {
        if (destroyed.compareAndSet(false, true)) {
            directory.destroy();
        }
    }

    /**
     * Select a invoker using loadbalance policy.</br>
     * a)Firstly, select an invoker using loadbalance. If this invoker is in previously selected list, or, 
     * if this invoker is unavailable, then continue step b (reselect), otherwise return the first selected invoker</br>
     * b)Reslection, the validation rule for reselection: selected > available. This rule guarantees that
     * the selected invoker has the minimum chance to be one in the previously selected list, and also 
     * guarantees this invoker is available.
     *
     * @param loadbalance load balance policy
     * @param invocation
     * @param invokers invoker candidates 存活着的服务提供者列表
     * @param selected  exclude selected invokers or not 之前已经调用过的服务提供者列表，如果不为空，那么可以认为这些服务不可靠，因为只有重试时此selected才不为空，说明之前调用失败了。
     * @return
     * @throws RpcException
     *
     * select 方法的主要逻辑集中在了对粘滞连接特性的支持上。
     * 首先是获取 sticky 配置，然后再检测 invokers 列表中是否包含 stickyInvoker，
     * 如果不包含，则认为该 stickyInvoker 不可用，此时将其置空。
     * 这里的 invokers 列表可以看做是存活着的服务提供者列表，如果这个列表不包含 stickyInvoker，
     * 那自然而然的认为 stickyInvoker 挂了，所以置空。
     * 如果 stickyInvoker 存在于 invokers 列表中，此时要进行下一项检测 selected 中是否包含 stickyInvoker。
     * 如果包含的话，说明 stickyInvoker 在此之前没有成功提供服务（但其仍然处于存活状态）。
     * 此时我们认为这个服务不可靠，不应该在重试期间内再次被调用，因此这个时候不会返回该 stickyInvoker。
     * 如果 selected 不包含 stickyInvoker，此时还需要进行可用性检测，比如检测服务提供者网络连通性等。
     * 当可用性检测通过，才可返回 stickyInvoker，否则调用 doSelect 方法选择 Invoker。
     * 如果 sticky 为 true，此时会将 doSelect 方法选出的 Invoker 赋值给 stickyInvoker。
     */
    protected Invoker<T> select(LoadBalance loadbalance, Invocation invocation, List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {
        // 如果invokers为空，则返回null
        if (invokers == null || invokers.isEmpty())
            return null;
        // 获得方法名
        String methodName = invocation == null ? "" : invocation.getMethodName();

        // 获取 sticky 配置，sticky 表示粘滞连接。所谓粘滞连接是指让服务消费者尽可能的
        // 调用同一个服务提供者，除非该提供者挂了再进行切换
        boolean sticky = invokers.get(0).getUrl().getMethodParameter(methodName, Constants.CLUSTER_STICKY_KEY, Constants.DEFAULT_CLUSTER_STICKY);
        {
            //ignore overloaded method
            // 检测 invokers 列表是否包含 stickyInvoker，如果不包含，
            // 说明 stickyInvoker 代表的服务提供者挂了，此时需要将其置空
            if (stickyInvoker != null && !invokers.contains(stickyInvoker)) {
                stickyInvoker = null;
            }
            //ignore concurrency problem
            // stickyInvoker不为null,并且没在已选列表中，返回上次的服务提供者stickyInvoker，但之前强制校验可达性。
            // 由于stickyInvoker不能包含在selected列表中，通过代码看，可以得知forking和failover集群策略，用不了sticky属性
            if (sticky && stickyInvoker != null && (selected == null || !selected.contains(stickyInvoker))) {
                if (availablecheck && stickyInvoker.isAvailable()) {
                    return stickyInvoker;
                }
            }
        }
        // 如果线程走到当前代码处，说明前面的 stickyInvoker 为空，或者不可用。
        // 此时继续调用 doSelect利用负载均衡选一个提供者
        Invoker<T> invoker = doSelect(loadbalance, invocation, invokers, selected);

        // 如果 sticky 为 true，则将负载均衡组件选出的 Invoker 赋值给 stickyInvoker
        if (sticky) {
            stickyInvoker = invoker;
        }
        return invoker;
    }

    /**
     * doSelect 主要做了两件事:
     * 第一是通过负载均衡组件选择 Invoker。
     * 第二是，如果选出来的 Invoker 不稳定，或不可用，此时需要调用 reselect 方法进行重选。
     * 若 reselect 选出来的 Invoker 为空，此时定位 invoker 在 invokers 列表中的位置 index，
     * 然后获取 index + 1 处的 invoker，这也可以看做是重选逻辑的一部分
     */
    private Invoker<T> doSelect(LoadBalance loadbalance, Invocation invocation, List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {
        if (invokers == null || invokers.isEmpty())
            return null;
        if (invokers.size() == 1)
            return invokers.get(0);
        if (loadbalance == null) {
            // 如果 loadbalance 为空，这里通过 SPI 加载 Loadbalance，默认为 RandomLoadBalance
            loadbalance = ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(Constants.DEFAULT_LOADBALANCE);
        }
        // 通过负载均衡组件选择 Invoker
        Invoker<T> invoker = loadbalance.select(invokers, getUrl(), invocation);

        //If the `invoker` is in the  `selected` or invoker is unavailable && availablecheck is true, reselect.
        // 如果 selected 包含负载均衡选择出的 Invoker，或者该 Invoker 无法经过可用性检查，此时进行重选
        if ((selected != null && selected.contains(invoker))
                || (!invoker.isAvailable() && getUrl() != null && availablecheck)) {
            try {
                // 进行重选
                Invoker<T> rinvoker = reselect(loadbalance, invocation, invokers, selected, availablecheck);
                if (rinvoker != null) {
                    // 如果 rinvoker 不为空，则将其赋值给 invoker
                    invoker = rinvoker;
                } else {
                    //Check the index of current selected invoker, if it's not the last one, choose the one at index+1.
                    // rinvoker 为空，定位 invoker 在 invokers 中的位置
                    int index = invokers.indexOf(invoker);
                    try {
                        //Avoid collision
                        // 获取 index + 1 位置处的 Invoker，以下代码等价于：
                        // invoker = invokers.get((index + 1) % invokers.size());
                        invoker = index < invokers.size() - 1 ? invokers.get(index + 1) : invokers.get(0);
                    } catch (Exception e) {
                        logger.warn(e.getMessage() + " may because invokers list dynamic change, ignore.", e);
                    }
                }
            } catch (Throwable t) {
                logger.error("cluster reselect fail reason is :" + t.getMessage() + " if can not solve, you can set cluster.availablecheck=false in url", t);
            }
        }
        return invoker;
    }

    /**
     * Reselect, use invokers not in `selected` first, if all invokers are in `selected`, just pick an available one using loadbalance policy.
     *
     * @param loadbalance
     * @param invocation
     * @param invokers
     * @param selected
     * @return
     * @throws RpcException
     * reselect 方法总结下来其实只做了两件事情，
     * 第一，查找可用的 Invoker，并将其添加到 reselectInvokers 集合中。
     * 第二，如果 reselectInvokers 不为空，则通过负载均衡组件再次进行选择。其中第一件事情又可进行细分，一开始，reselect 从 invokers 列表中查找有效可用的 Invoker，若未能找到，此时再到 selected 列表中继续查找。
     */
    private Invoker<T> reselect(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected, boolean availablecheck)
            throws RpcException {

        //Allocating one in advance, this list is certain to be used.
        List<Invoker<T>> reselectInvokers = new ArrayList<Invoker<T>>(invokers.size() > 1 ? (invokers.size() - 1) : invokers.size());

        //First, try picking a invoker not in `selected`.
        // invoker.isAvailable() should be checked
        // 根据 availablecheck 进行不同的处理
        if (availablecheck) {
            // 遍历 invokers 列表
            for (Invoker<T> invoker : invokers) {
                // 检测可用性
                if (invoker.isAvailable()) {
                    // 如果 selected 列表不包含当前 invoker，则将其添加到 reselectInvokers 中
                    if (selected == null || !selected.contains(invoker)) {
                        reselectInvokers.add(invoker);
                    }
                }
            }
            // reselectInvokers 不为空，此时通过负载均衡组件进行选择
            if (!reselectInvokers.isEmpty()) {
                return loadbalance.select(reselectInvokers, getUrl(), invocation);
            }
        // 不检查 Invoker 可用性
        } else { // do not check invoker.isAvailable()
            for (Invoker<T> invoker : invokers) {
                // 如果 selected 列表不包含当前 invoker，则将其添加到 reselectInvokers 中
                if (selected == null || !selected.contains(invoker)) {
                    reselectInvokers.add(invoker);
                }
            }
            if (!reselectInvokers.isEmpty()) {
                // 通过负载均衡组件进行选择
                return loadbalance.select(reselectInvokers, getUrl(), invocation);
            }
        }
        // Just pick an available invoker using loadbalance policy
        {
            // 若线程走到此处，说明 reselectInvokers 集合为空，此时不会调用负载均衡组件进行筛选。
            // 这里从 selected 列表中查找可用的 Invoker，并将其添加到 reselectInvokers 集合中
            if (selected != null) {
                for (Invoker<T> invoker : selected) {
                    if ((invoker.isAvailable()) // available first
                            && !reselectInvokers.contains(invoker)) {
                        reselectInvokers.add(invoker);
                    }
                }
            }
            if (!reselectInvokers.isEmpty()) {
                // 再次进行选择，并返回选择结果
                return loadbalance.select(reselectInvokers, getUrl(), invocation);
            }
        }
        return null;
    }

    @Override
    public Result invoke(final Invocation invocation) throws RpcException {
        // 核对是否已经销毁
        checkWhetherDestroyed();
        LoadBalance loadbalance = null;

        // binding attachments into invocation.
        // 获得上下文的附加值
        Map<String, String> contextAttachments = RpcContext.getContext().getAttachments();
        // 把附加值放入到会话域中
        if (contextAttachments != null && contextAttachments.size() != 0) {
            ((RpcInvocation) invocation).addAttachments(contextAttachments);
        }

        // 生成服务提供者集合
        List<Invoker<T>> invokers = list(invocation);
        if (invokers != null && !invokers.isEmpty()) {
            // 获得负载均衡器
            loadbalance = ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(invokers.get(0).getUrl()
                    .getMethodParameter(RpcUtils.getMethodName(invocation), Constants.LOADBALANCE_KEY, Constants.DEFAULT_LOADBALANCE));
        }
        RpcUtils.attachInvocationIdIfAsync(getUrl(), invocation);
        return doInvoke(invocation, invokers, loadbalance);
    }

    protected void checkWhetherDestroyed() {

        if (destroyed.get()) {
            throw new RpcException("Rpc cluster invoker for " + getInterface() + " on consumer " + NetUtils.getLocalHost()
                    + " use dubbo version " + Version.getVersion()
                    + " is now destroyed! Can not invoke any more.");
        }
    }

    @Override
    public String toString() {
        return getInterface() + " -> " + getUrl().toString();
    }

    protected void checkInvokers(List<Invoker<T>> invokers, Invocation invocation) {
        if (invokers == null || invokers.isEmpty()) {
            throw new RpcException("Failed to invoke the method "
                    + invocation.getMethodName() + " in the service " + getInterface().getName()
                    + ". No provider available for the service " + directory.getUrl().getServiceKey()
                    + " from registry " + directory.getUrl().getAddress()
                    + " on the consumer " + NetUtils.getLocalHost()
                    + " using the dubbo version " + Version.getVersion()
                    + ". Please check if the providers have been started and registered.");
        }
    }

    protected abstract Result doInvoke(Invocation invocation, List<Invoker<T>> invokers,
                                       LoadBalance loadbalance) throws RpcException;

    protected List<Invoker<T>> list(Invocation invocation) throws RpcException {
        List<Invoker<T>> invokers = directory.list(invocation);
        return invokers;
    }
}
