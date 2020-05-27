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
package com.alibaba.dubbo.remoting.transport.dispatcher.all;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.threadlocal.NamedInternalThreadFactory;
import com.alibaba.dubbo.common.threadpool.support.AbortPolicyWithReport;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.Dispatcher;
import com.alibaba.dubbo.remoting.transport.dispatcher.ChannelHandlers;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * default thread pool configure
 * 所有消息都派发到线程池，包括请求，响应，连接事件，断开事件，心跳等。
 * 线程模型的确认事件
 * @see com.alibaba.dubbo.remoting.transport.netty4.NettyServer#NettyServer(com.alibaba.dubbo.common.URL, com.alibaba.dubbo.remoting.ChannelHandler)
 * @see ChannelHandlers#wrap(com.alibaba.dubbo.remoting.ChannelHandler, com.alibaba.dubbo.common.URL)
 */
public class AllDispatcher implements Dispatcher {

    public static final String NAME = "all";

    /**
     * 该调度方法是默认的调度方法。
     */
    @Override
    public ChannelHandler dispatch(ChannelHandler handler, URL url) {
        // 线程池调度方法：任何消息以及操作都分发到线程池中
        return new AllChannelHandler(handler, url);
    }

}
