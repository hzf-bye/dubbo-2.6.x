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
package com.alibaba.dubbo.rpc.cluster;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;

import java.util.List;

/**
 * Router. (SPI, Prototype, ThreadSafe)
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Routing">Routing</a>
 * 路由规则 决定一次 dubbo 服务调用的目标服务器，分为条件路由规则和脚本路由规则，并且支持可扩展
 * 该接口是路由规则的接口，定义的两个方法，第一个方法是获得路由规则的url，第二个方法是筛选出跟规则匹配的Invoker集合。
 *
 * @see com.alibaba.dubbo.rpc.cluster.Cluster#join(Directory)
 * @see com.alibaba.dubbo.rpc.cluster.Directory#list(Invocation)
 */
public interface Router extends Comparable<Router>{

    /**
     * get the router url.
     * 获得路由规则的url
     *
     * @return url
     */
    URL getUrl();

    /**
     * route.
     * 筛选出跟规则匹配的Invoker集合
     * @param invokers
     * @param url        refer url
     * @param invocation
     * @return routed invokers
     * @throws RpcException
     */
    <T> List<Invoker<T>> route(List<Invoker<T>> invokers, URL url, Invocation invocation) throws RpcException;

    /**
     * Router's priority, used to sort routers.
     *
     * @return router's priority
     */
    int getPriority();

}