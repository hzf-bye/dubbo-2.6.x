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
package com.alibaba.dubbo.remoting.exchange;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.SPI;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.exchange.support.header.HeaderExchanger;

/**
 * Exchanger. (SPI, Singleton, ThreadSafe)
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Message_Exchange_Pattern">Message Exchange Pattern</a>
 * <a href="http://en.wikipedia.org/wiki/Request-response">Request-Response</a>
 *
 * 该接口是数据交换者接口，该接口是一个可扩展接口默认实现的是HeaderExchanger类，
 * 并且用到了dubbo SPI的Adaptive机制，优先实现url携带的配置。
 *
 * 那么回到该接口定义的方法，定义了绑定和连接两个方法，分别返回信息交互服务器和客户端实例。
 */
@SPI(HeaderExchanger.NAME)
public interface Exchanger {

    /**
     * bind.
     * 绑定一个服务器
     * @param url 服务器url
     * @param handler 数据交换处理器
     * @return message server 数据交换服务器
     */
    @Adaptive({Constants.EXCHANGER_KEY})
    ExchangeServer bind(URL url, ExchangeHandler handler) throws RemotingException;

    /**
     * connect.
     * 连接一个服务器，也就是创建一个客户端
     * @param url  服务器url
     * @param handler 数据交换处理器
     * @return message channel 返回数据交换客户端
     */
    @Adaptive({Constants.EXCHANGER_KEY})
    ExchangeClient connect(URL url, ExchangeHandler handler) throws RemotingException;

}