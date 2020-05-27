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

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.remoting.transport.ChannelHandlerAdapter;
import com.alibaba.dubbo.remoting.transport.ChannelHandlerDispatcher;

/**
 * Transporter facade. (API, Static, ThreadSafe)
 * 1.该类用到了设计模式的外观模式，通过该类的包装，我们就不会看到内部具体的实现细节，这样降低了程序的复杂度，也提高了程序的可维护性。比如这个类，包装了调用各种实现Transporter接口的方法，通过getTransporter来获得Transporter的实现对象，具体实现哪个实现类，取决于url中携带的配置信息，如果url中没有相应的配置，则默认选择@SPI中的默认值netty。
 * 2.bind和connect方法分别有两个重载方法，其中的操作只是把把字符串的url转化为URL对象。
 * 3.静态代码块中检测了一下jar包是否有重复。
 */
public class Transporters {

    static {
        // check duplicate jar package
        // 检查重复的 jar 包
        Version.checkDuplicate(Transporters.class);
        Version.checkDuplicate(RemotingException.class);
    }

    private Transporters() {
    }

    public static Server bind(String url, ChannelHandler... handler) throws RemotingException {
        return bind(URL.valueOf(url), handler);
    }

    public static Server bind(URL url, ChannelHandler... handlers) throws RemotingException {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        if (handlers == null || handlers.length == 0) {
            throw new IllegalArgumentException("handlers == null");
        }
        ChannelHandler handler;
        if (handlers.length == 1) {
            handler = handlers[0];
        } else {
            //如果有多个handler的话，需要使用ChannelHandler分发器包装下
            handler = new ChannelHandlerDispatcher(handlers);
        }
        // 获取自适应 Transporter 实例，并调用实例方法
        // 默认是NettyTransporter的bind方法）
        /**
         * dubbo/remoting/dubbo/remoting-netty4/META-INF/dubbo/internal/com.alibaba.dubbo.remoting.Transporter
         * 获取到的NettyTransporter是netty4包下的
         */
        return getTransporter().bind(url, handler);
    }

    public static Client connect(String url, ChannelHandler... handler) throws RemotingException {
        return connect(URL.valueOf(url), handler);
    }

    public static Client connect(URL url, ChannelHandler... handlers) throws RemotingException {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        ChannelHandler handler;
        if (handlers == null || handlers.length == 0) {
            handler = new ChannelHandlerAdapter();
        } else if (handlers.length == 1) {
            handler = handlers[0];
        } else {
            // 如果 handler 数量大于1，则创建一个 ChannelHandler 分发器
            handler = new ChannelHandlerDispatcher(handlers);
        }
        // 获取 Transporter 自适应拓展类，并调用 connect 方法生成 Client 实例
        return getTransporter().connect(url, handler);
    }

    public static Transporter getTransporter() {
        return ExtensionLoader.getExtensionLoader(Transporter.class).getAdaptiveExtension();
    }

}