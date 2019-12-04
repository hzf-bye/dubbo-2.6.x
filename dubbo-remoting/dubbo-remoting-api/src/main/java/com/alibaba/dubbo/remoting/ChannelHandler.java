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
package com.alibaba.dubbo.remoting;

import com.alibaba.dubbo.common.extension.SPI;


/**
 * ChannelHandler. (API, Prototype, ThreadSafe)
 * 该接口是负责channel中的逻辑处理，并且可以看到这个接口有注解@SPI，是个可扩展接口，到时候都会在下面介绍各类NIO框架的时候会具体讲到它的实现类。
 *
 * @see com.alibaba.dubbo.remoting.Transporter#bind(com.alibaba.dubbo.common.URL, ChannelHandler)
 * @see com.alibaba.dubbo.remoting.Transporter#connect(com.alibaba.dubbo.common.URL, ChannelHandler)
 */
@SPI
public interface ChannelHandler {

    /**
     * on channel connected.
     * 连接该通道
     *
     * @param channel channel.
     */
    void connected(Channel channel) throws RemotingException;

    /**
     * on channel disconnected.
     * 断开该通道
     *
     * @param channel channel.
     */
    void disconnected(Channel channel) throws RemotingException;

    /**
     * on message sent.
     * 发送给这个通道消息
     *
     *
     * @param channel channel.
     * @param message message.
     */
    void sent(Channel channel, Object message) throws RemotingException;

    /**
     * on message received.
     * 从这个通道内接收消息
     *
     * @param channel channel.
     * @param message message.
     */
    void received(Channel channel, Object message) throws RemotingException;

    /**
     * on exception caught.
     * 从这个通道内捕获异常
     *
     * @param channel   channel.
     * @param exception exception.
     */
    void caught(Channel channel, Throwable exception) throws RemotingException;

}