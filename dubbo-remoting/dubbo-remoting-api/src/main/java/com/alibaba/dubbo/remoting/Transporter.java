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

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.SPI;

/**
 * Transporter. (SPI, Singleton, ThreadSafe)
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Transport_Layer">Transport Layer</a>
 * <a href="http://en.wikipedia.org/wiki/Client%E2%80%93server_model">Client/Server</a>
 *
 * @see com.alibaba.dubbo.remoting.Transporters
 *
 * 注解@SPI标识此接口是一个dubbo spi接口 即是一个扩展点
 *
 * 例如此处注解@SPI的value属性为netty,则代表使用NettyTransporter作为默认实现。
 */
@SPI("netty")
public interface Transporter {

    /**
     * Bind a server.
     *
     * @param url     server url
     * @param handler
     * @return server
     * @throws RemotingException
     * @see com.alibaba.dubbo.remoting.Transporters#bind(URL, ChannelHandler...)
     * 绑定一个服务器
     *
     * 方法级别的@Adaptive在第一个getExtension时会自动生成和编译一个动态的Adaptive类，从而达到动态实现的效果。
     *
     * 例如这里，duubo在初始化扩展点时，会生成一个Transporter$Adaptive类（详见同目录下的Transporter$Adaptive类，自己添加的，源码中没有）
     * 里面会实现这个方法，通过@Adaptive中的参数，找到并调用真正的实现类。
     * 如果熟悉装饰器模式那么会很容易理解这部分逻辑。
     *
     */
    @Adaptive({Constants.SERVER_KEY, Constants.TRANSPORTER_KEY})
    Server bind(URL url, ChannelHandler handler) throws RemotingException;

    /**
     * Connect to a server.
     * 连接一个服务器，即创建一个客户端
     *
     * @param url     server url
     * @param handler
     * @return client
     * @throws RemotingException
     * @see com.alibaba.dubbo.remoting.Transporters#connect(URL, ChannelHandler...)
     */
    @Adaptive({Constants.CLIENT_KEY, Constants.TRANSPORTER_KEY})
    Client connect(URL url, ChannelHandler handler) throws RemotingException;

}