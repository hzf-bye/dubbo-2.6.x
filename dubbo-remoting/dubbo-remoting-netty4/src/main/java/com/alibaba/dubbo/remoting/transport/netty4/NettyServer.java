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
package com.alibaba.dubbo.remoting.transport.netty4;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ExecutorUtil;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.Server;
import com.alibaba.dubbo.remoting.transport.AbstractServer;
import com.alibaba.dubbo.remoting.transport.DecodeHandler;
import com.alibaba.dubbo.remoting.transport.dispatcher.ChannelHandlers;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;

/**
 * NettyServer
 */
public class NettyServer extends AbstractServer implements Server {

    private static final Logger logger = LoggerFactory.getLogger(NettyServer.class);

    /**
     * 连接该服务器的通道集合 key为ip:port
     */
    private Map<String, Channel> channels;

    /**
     * 服务器引导类
     */
    private ServerBootstrap bootstrap;

    /**
     * 通道
     */
    private io.netty.channel.Channel channel;

    /**
     * boss线程组
     */
    private EventLoopGroup bossGroup;

    /**
     * worker线程组
     */
    private EventLoopGroup workerGroup;

    /**
     *
     * @param url 提供者URL
     * @param handler {@link DecodeHandler}
     * @throws RemotingException
     */
    public NettyServer(URL url, ChannelHandler handler) throws RemotingException {
        // 调用父类构造方法
        /*
         * ChannelHandlers.wrap方法中会根据SPI扩展机制动态生成Dispatcher的自适应类，默认使用AllDispatcher处理，
         * 会返回一个AllChannelHandler，
         * 会把线程池和DataStore都初始化了。然后经过HeartbeatHandler封装，再经过MultiMessageHandler封装后返回。
         */
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
        // 创建服务引导类
        bootstrap = new ServerBootstrap();

        // 创建boss线程组
        bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("NettyServerBoss", true));
        // 创建worker线程组
        workerGroup = new NioEventLoopGroup(getUrl().getPositiveParameter(Constants.IO_THREADS_KEY, Constants.DEFAULT_IO_THREADS),
                new DefaultThreadFactory("NettyServerWorker", true));

        // 创建服务器处理器
        final NettyServerHandler nettyServerHandler = new NettyServerHandler(getUrl(), this);
        // 获得通道集合
        channels = nettyServerHandler.getChannels();

        // 设置ventLoopGroup还有可选项
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                //是否启用nagle算法，true是关闭nagle算法
                /*
                 * Nagle 算法要求，当一个 TCP 连接中有在传数据（已经发出但还未确认的数据）时，
                 * 小于 MSS 的报文段就不能被发送，直到所有的在传数据都收到了 ACK。
                 * 同时收到 ACK 后，TCP 还不会马上就发送数据，会收集小包合并一起发送。
                 */
                .childOption(ChannelOption.TCP_NODELAY, Boolean.TRUE)
                //是否可以重用端口
                //服务端主动断开连接以后，需要等 2 个 MSL 以后才最终释放这个连接，重启以后要绑定同一个端口，默认情况下，操作系统的实现都会阻止新的监听套接字绑定到这个端口上。
                //启用 SO_REUSEADDR 套接字选项可以解除这个限制
                .childOption(ChannelOption.SO_REUSEADDR, Boolean.TRUE)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childHandler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel ch) throws Exception {
                        // 编解码器
                        NettyCodecAdapter adapter = new NettyCodecAdapter(getCodec(), getUrl(), NettyServer.this);
                        // 增加责任链
                        ch.pipeline()//.addLast("logging",new LoggingHandler(LogLevel.INFO))//for debug
                                .addLast("decoder", adapter.getDecoder())
                                .addLast("encoder", adapter.getEncoder())
                                .addLast("handler", nettyServerHandler);
                    }
                });
        // bind 绑定
        ChannelFuture channelFuture = bootstrap.bind(getBindAddress());
        // 等待绑定完成
        channelFuture.syncUninterruptibly();
        // 设置通道
        channel = channelFuture.channel();

    }

    @Override
    protected void doClose() throws Throwable {
        try {
            if (channel != null) {
                // unbind.
                channel.close();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            Collection<com.alibaba.dubbo.remoting.Channel> channels = getChannels();
            if (channels != null && channels.size() > 0) {
                for (com.alibaba.dubbo.remoting.Channel channel : channels) {
                    try {
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
                bossGroup.shutdownGracefully();
                workerGroup.shutdownGracefully();
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
        return channel.isActive();
    }

}
