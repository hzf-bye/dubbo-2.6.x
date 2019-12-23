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

import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Cluster;
import com.alibaba.dubbo.rpc.cluster.Directory;

/**
 * {@link FailoverClusterInvoker}
 * 失败自动切换，当调用出现失败的时候，会自动切换集群中其他服务器，用户可以通过设置retries=2来设置重试次数；
 * FailoverCluster是dubbo的默认容错机制，会对请求做负载均衡；
 * 通常用于读操作或者幂等的写操作上，但重试会导致接口的延迟增大，在下游机器负载已经达到极限时，重试容易加重下游服务的负载。
 *
 *
 */
public class FailoverCluster implements Cluster {

    public final static String NAME = "failover";

    @Override
    public <T> Invoker<T> join(Directory<T> directory) throws RpcException {
        // 创建FailoverClusterInvoker
        return new FailoverClusterInvoker<T>(directory);
    }

}
