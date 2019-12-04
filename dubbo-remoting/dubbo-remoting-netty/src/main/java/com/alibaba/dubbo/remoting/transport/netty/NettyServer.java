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
package com.alibaba.dubbo.remoting.transport.netty;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ExecutorUtil;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.Server;
import com.alibaba.dubbo.remoting.transport.AbstractServer;
import com.alibaba.dubbo.remoting.transport.dispatcher.ChannelHandlers;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NettyServer
 * 该类继承了AbstractServer，实现了Server，是基于netty3实现的服务器类。
 */
public class NettyServer extends AbstractServer implements Server {

    private static final Logger logger = LoggerFactory.getLogger(NettyServer.class);

    /**
     * 连接该服务器的通道集合
     * <ip:port, channel>
     */
    private Map<String, Channel> channels;

    /**
     * 服务器引导类对象
     */
    private ServerBootstrap bootstrap;

    /**
     * 通道
     */
    private org.jboss.netty.channel.Channel channel;

    public NettyServer(URL url, ChannelHandler handler) throws RemotingException {
        super(url, ChannelHandlers.wrap(handler, ExecutorUtil.setThreadName(url, SERVER_THREAD_POOL_NAME)));
    }

    /**
     * doOpen方法创建Netty的Server端并打开，具体的事情就交给Netty去处理了。
     *
     * NIO框架接受到消息后，先由NettyCodecAdapter解码，再由NettyHandler处理具体的业务逻辑，再由NettyCodecAdapter编码后发送。
     * NettyServer既是Server又是Handler。
     * HeaderExchangerServer只是Server。
     * MultiMessageHandler是多消息处理Handler。
     * HeartbeatHandler是处理心跳事件的Handler。
     * AllChannelHandler是消息派发器，负责将请求放入线程池，并执行请求。
     * DecodeHandler是编解码Handler。
     * HeaderExchangerHandler是信息交换Handler，将请求转化成请求响应模式与同步转异步模式。
     * RequestHandler是最后执行的Handler，会在协议层选择Exporter后选择Invoker，进而执行Filter与Invoker，最终执行请求服务实现类方法。
     * Channel直接触发事件并执行Handler，Channel在有客户端连接Server的时候触发创建并封装成NettyChannel，再由HeaderExchangerHandler创建HeaderExchangerChannel，负责请求响应模式的处理。
     * NettyChannel其实是个Handler，HeaderExchangerChannel是个Channel，
     * 消息的序列化与反序列化工作在NettyCodecAdapter中发起完成。
     *
     * 当有客户端连接Server时的连接过程：
     *
     * NettyHandler.connected()
     * NettyServer.connected()
     * MultiMessageHandler.connected()
     * HeartbeatHandler.connected()
     * AllChannelHandler.connected()
     * DecodeHandler.connected()
     * HeaderExchangerHandler.connected()
     * requestHandler.connected()
     * 执行服务的onconnect事件的监听方法
     */
    @Override
    protected void doOpen() throws Throwable {
        NettyHelper.setNettyLoggerFactory();
        //boss线程池
        ExecutorService boss = Executors.newCachedThreadPool(new NamedThreadFactory("NettyServerBoss", true));
        //worker线程池
        ExecutorService worker = Executors.newCachedThreadPool(new NamedThreadFactory("NettyServerWorker", true));
        //ChannelFactory，没有指定工作者线程数量，就使用cpu+1
        ChannelFactory channelFactory = new NioServerSocketChannelFactory(boss, worker, getUrl().getPositiveParameter(Constants.IO_THREADS_KEY, Constants.DEFAULT_IO_THREADS));
        // 新建服务引导类对象
        bootstrap = new ServerBootstrap(channelFactory);

        // 新建通道处理器
        final NettyHandler nettyHandler = new NettyHandler(getUrl(), this);
        // 获得通道集合
        channels = nettyHandler.getChannels();
        // https://issues.jboss.org/browse/NETTY-365
        // https://issues.jboss.org/browse/NETTY-379
        // final Timer timer = new HashedWheelTimer(new NamedThreadFactory("NettyIdleTimer", true));
        // 禁用nagle算法，将数据立即发送出去。纳格算法是以减少封包传送量来增进TCP/IP网络的效能
        bootstrap.setOption("child.tcpNoDelay", true);
        // 设置 PipelineFactory
        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            /**
             * 获得通道
             */
            @Override
            public ChannelPipeline getPipeline() {
                // 新建编解码器
                NettyCodecAdapter adapter = new NettyCodecAdapter(getCodec(), getUrl(), NettyServer.this);
                // 获得通道
                ChannelPipeline pipeline = Channels.pipeline();
                /*int idleTimeout = getIdleTimeout();
                if (idleTimeout > 10000) {
                    pipeline.addLast("timer", new IdleStateHandler(timer, idleTimeout / 1000, 0, 0));
                }*/
                // 设置解码器
                pipeline.addLast("decoder", adapter.getDecoder());
                // 设置编码器
                pipeline.addLast("encoder", adapter.getEncoder());
                // 设置通道处理器
                pipeline.addLast("handler", nettyHandler);
                // 返回通道
                return pipeline;
            }
        });
        // bind
        // 绑定到指定的 ip 和端口上
        // bind 绑定地址，也就是启用服务器
        channel = bootstrap.bind(getBindAddress());
    }

    @Override
    protected void doClose() throws Throwable {
        try {
            if (channel != null) {
                // unbind.关闭通道
                channel.close();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            // 获得所有连接该服务器的通道集合
            Collection<com.alibaba.dubbo.remoting.Channel> channels = getChannels();
            if (channels != null && !channels.isEmpty()) {
                // 遍历通道集合
                for (com.alibaba.dubbo.remoting.Channel channel : channels) {
                    try {
                        // 关闭通道连接
                        channel.close();
                    } catch (Throwable e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            if (bootstrap != null) {
                // release external resource.
                bootstrap.releaseExternalResources();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            if (channels != null) {
                channels.clear();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
    }

    @Override
    public Collection<Channel> getChannels() {
        Collection<Channel> chs = new HashSet<Channel>();
        // 如果通道连接，则加入集合，返回
        for (Channel channel : this.channels.values()) {
            if (channel.isConnected()) {
                chs.add(channel);
            } else {
                channels.remove(NetUtils.toAddressString(channel.getRemoteAddress()));
            }
        }
        return chs;
    }

    @Override
    public Channel getChannel(InetSocketAddress remoteAddress) {
        return channels.get(NetUtils.toAddressString(remoteAddress));
    }

    @Override
    public boolean isBound() {
        return channel.isBound();
    }

}
