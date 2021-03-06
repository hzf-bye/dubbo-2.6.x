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
package com.alibaba.dubbo.rpc.protocol.dubbo;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.serialize.support.SerializableClassRegistry;
import com.alibaba.dubbo.common.serialize.support.SerializationOptimizer;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.Transporter;
import com.alibaba.dubbo.remoting.exchange.ExchangeChannel;
import com.alibaba.dubbo.remoting.exchange.ExchangeClient;
import com.alibaba.dubbo.remoting.exchange.ExchangeHandler;
import com.alibaba.dubbo.remoting.exchange.ExchangeServer;
import com.alibaba.dubbo.remoting.exchange.Exchangers;
import com.alibaba.dubbo.remoting.exchange.support.ExchangeHandlerAdapter;
import com.alibaba.dubbo.remoting.exchange.support.header.HeaderExchangeClient;
import com.alibaba.dubbo.remoting.exchange.support.header.HeaderExchangeServer;
import com.alibaba.dubbo.remoting.transport.AbstractServer;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.protocol.AbstractProtocol;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * dubbo protocol support.
 */
public class  DubboProtocol extends AbstractProtocol {

    public static final String NAME = "dubbo";

    public static final int DEFAULT_PORT = 20880;
    private static final String IS_CALLBACK_SERVICE_INVOKE = "_isCallBackServiceInvoke";
    private static DubboProtocol INSTANCE;
    /**
     * 缓存提供者的HeaderExchangeServer
     * @see DubboProtocol#openServer(com.alibaba.dubbo.common.URL)
     * key:ip:port
     * value {@link HeaderExchangeServer}
     */
    private final Map<String, ExchangeServer> serverMap = new ConcurrentHashMap<String, ExchangeServer>(); // <host:port,Exchanger>
    /**
     * 缓存客户端信息
     * key: 提供者 host:port
     * value: {@link HeaderExchangeClient}
     * @see DubboProtocol#getClients(com.alibaba.dubbo.common.URL)
     * 创建的客户端连接 缓存在此
     */
    private final Map<String, ReferenceCountExchangeClient> referenceClientMap = new ConcurrentHashMap<String, ReferenceCountExchangeClient>(); // <host:port,Exchanger>
    private final ConcurrentMap<String, LazyConnectExchangeClient> ghostClientMap = new ConcurrentHashMap<String, LazyConnectExchangeClient>();
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<String, Object>();
    private final Set<String> optimizers = new ConcurrentHashSet<String>();
    //consumer side export a stub service for dispatching event
    //servicekey-stubmethods
    private final ConcurrentMap<String, String> stubServiceMethodsMap = new ConcurrentHashMap<String, String>();

    /**
     * 通过内部类继承ExchangeHandlerAdapter，完成服务方Invoker实例的查找并进行服务费的真实调用
     *
     * 在服务暴露时服务已经按照特定规则（端口、接口名、接口版本、接口分组）把Invoker实例存储到hashMap（exporterMap）中，
     * 客户端调用过来时必须携带相同信息构造的key，找到对应的Exporter然后调用。
     */
    private ExchangeHandler requestHandler = new ExchangeHandlerAdapter() {

        @Override
        public Object reply(ExchangeChannel channel, Object message) throws RemotingException {
            if (message instanceof Invocation) {
                Invocation inv = (Invocation) message;
                //查找Invocation关联的Invoker, com.alibaba.dubbo.registry.integration.RegistryProtocol.InvokerDelegete实例
                Invoker<?> invoker = getInvoker(channel, inv);
                // need to consider backward-compatibility if it's a callback
                if (Boolean.TRUE.toString().equals(inv.getAttachments().get(IS_CALLBACK_SERVICE_INVOKE))) {
                    String methodsStr = invoker.getUrl().getParameters().get("methods");
                    boolean hasMethod = false;
                    if (methodsStr == null || methodsStr.indexOf(",") == -1) {
                        hasMethod = inv.getMethodName().equals(methodsStr);
                    } else {
                        String[] methods = methodsStr.split(",");
                        for (String method : methods) {
                            if (inv.getMethodName().equals(method)) {
                                hasMethod = true;
                                break;
                            }
                        }
                    }
                    if (!hasMethod) {
                        logger.warn(new IllegalStateException("The methodName " + inv.getMethodName()
                                + " not found in callback service interface ,invoke will be ignored."
                                + " please update the api interface. url is:"
                                + invoker.getUrl()) + " ,invocation is :" + inv);
                        return null;
                    }
                }
                RpcContext.getContext().setRemoteAddress(channel.getRemoteAddress());
                //调用业务方具体方法
                return invoker.invoke(inv);
            }
            throw new RemotingException(channel, "Unsupported request: "
                    + (message == null ? null : (message.getClass().getName() + ": " + message))
                    + ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress());
        }

        /**
         * 接受到客户端的调用请求
         */
        @Override
        public void received(Channel channel, Object message) throws RemotingException {
            if (message instanceof Invocation) {
                reply((ExchangeChannel) channel, message);
            } else {
                super.received(channel, message);
            }
        }

        /**
         * 消费端发起tcp连接并完成后，服务端此方法被调用
         */
        @Override
        public void connected(Channel channel) throws RemotingException {
            invoke(channel, Constants.ON_CONNECT_KEY);
        }

        @Override
        public void disconnected(Channel channel) throws RemotingException {
            if (logger.isInfoEnabled()) {
                logger.info("disconnected from " + channel.getRemoteAddress() + ",url:" + channel.getUrl());
            }
            invoke(channel, Constants.ON_DISCONNECT_KEY);
        }

        private void invoke(Channel channel, String methodKey) {
            //创建Invocation对象
            Invocation invocation = createInvocation(channel, channel.getUrl(), methodKey);
            if (invocation != null) {
                try {
                    received(channel, invocation);
                } catch (Throwable t) {
                    logger.warn("Failed to invoke event method " + invocation.getMethodName() + "(), cause: " + t.getMessage(), t);
                }
            }
        }

        private Invocation createInvocation(Channel channel, URL url, String methodKey) {
            //如果不包含返回null，比如消费端连接时可以调动初始化方法，或者断开连接时可以调用一些清理方法吧？
            String method = url.getParameter(methodKey);
            if (method == null || method.length() == 0) {
                return null;
            }
            //根据method创建RpcInvocation对象
            RpcInvocation invocation = new RpcInvocation(method, new Class<?>[0], new Object[0]);
            invocation.setAttachment(Constants.PATH_KEY, url.getPath());
            invocation.setAttachment(Constants.GROUP_KEY, url.getParameter(Constants.GROUP_KEY));
            invocation.setAttachment(Constants.INTERFACE_KEY, url.getParameter(Constants.INTERFACE_KEY));
            invocation.setAttachment(Constants.VERSION_KEY, url.getParameter(Constants.VERSION_KEY));
            if (url.getParameter(Constants.STUB_EVENT_KEY, false)) {
                invocation.setAttachment(Constants.STUB_EVENT_KEY, Boolean.TRUE.toString());
            }
            return invocation;
        }
    };

    public DubboProtocol() {
        INSTANCE = this;
    }

    public static DubboProtocol getDubboProtocol() {
        if (INSTANCE == null) {
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(DubboProtocol.NAME); // load
        }
        return INSTANCE;
    }

    public Collection<ExchangeServer> getServers() {
        return Collections.unmodifiableCollection(serverMap.values());
    }

    public Collection<Exporter<?>> getExporters() {
        return Collections.unmodifiableCollection(exporterMap.values());
    }

    Map<String, Exporter<?>> getExporterMap() {
        return exporterMap;
    }

    private boolean isClientSide(Channel channel) {
        InetSocketAddress address = channel.getRemoteAddress();
        URL url = channel.getUrl();
        return url.getPort() == address.getPort() &&
                NetUtils.filterLocalHost(channel.getUrl().getIp())
                        .equals(NetUtils.filterLocalHost(address.getAddress().getHostAddress()));
    }

    Invoker<?> getInvoker(Channel channel, Invocation inv) throws RemotingException {
        boolean isCallBackServiceInvoke = false;
        boolean isStubServiceInvoke = false;
        //获取服务暴露协议的端口
        int port = channel.getLocalAddress().getPort();
        //后去调用传递的接口
        String path = inv.getAttachments().get(Constants.PATH_KEY);
        // if it's callback service on client side
        isStubServiceInvoke = Boolean.TRUE.toString().equals(inv.getAttachments().get(Constants.STUB_EVENT_KEY));
        if (isStubServiceInvoke) {
            port = channel.getRemoteAddress().getPort();
        }
        //callback
        isCallBackServiceInvoke = isClientSide(channel) && !isStubServiceInvoke;
        if (isCallBackServiceInvoke) {
            path = inv.getAttachments().get(Constants.PATH_KEY) + "." + inv.getAttachments().get(Constants.CALLBACK_SERVICE_KEY);
            inv.getAttachments().put(IS_CALLBACK_SERVICE_INVOKE, Boolean.TRUE.toString());
        }
        //根据端口、接口名、接口分组、版本号构造唯一的key
        String serviceKey = serviceKey(port, path, inv.getAttachments().get(Constants.VERSION_KEY), inv.getAttachments().get(Constants.GROUP_KEY));

        //从exporterMap获取exporter
        DubboExporter<?> exporter = (DubboExporter<?>) exporterMap.get(serviceKey);

        if (exporter == null)
            throw new RemotingException(channel, "Not found exported service: " + serviceKey + " in " + exporterMap.keySet() + ", may be version or group mismatch " + ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress() + ", message:" + inv);

        return exporter.getInvoker();
    }

    public Collection<Invoker<?>> getInvokers() {
        return Collections.unmodifiableCollection(invokers);
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }


    /**
     *
     * @param invoker Service invoker {@link com.alibaba.dubbo.registry.integration.RegistryProtocol.InvokerDelegete} 实例
     */
    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        //dubbo://10.42.0.1:20880/dubbo.common.hello.service.HelloService?
        //anyhost=true&application=dubbo-provider&
        //application.version=1.0&dubbo=2.5.3&environment=product&
        //interface=dubbo.common.hello.service.HelloService&
        //methods=sayHello&organization=china&owner=cheng.xi&
        //pid=7876&side=provider&timestamp=1489057305001
        URL url = invoker.getUrl();

        // export service.
        //key由serviceName，port，version，group组成
        //当nio客户端发起远程调用时，nio服务端通过此key来决定调用哪个Exporter，也就是执行的Invoker。
        //dubbo.common.hello.service.HelloService:20880
        // 获取服务标识，理解成服务坐标也行。由服务组名，服务名，服务版本号以及端口组成。比如：
        // demoGroup/com.alibaba.dubbo.demo.DemoService:1.0.1:20880
        String key = serviceKey(url);
        //将Invoker转换成Exporter
        //直接new一个新实例
        //没做啥处理，就是做一些赋值操作
        //这里的exporter就包含了invoker
        DubboExporter<T> exporter = new DubboExporter<T>(invoker, key, exporterMap);
        //把export保存到单例的dubboProtocol中
        exporterMap.put(key, exporter);

        //export an stub service for dispatching event
        //是否支持本地存根
        //远程服务后，客户端通常只剩下接口，而实现全在服务器端，
        //但提供方有些时候想在客户端也执行部分逻辑，比如：做ThreadLocal缓存，
        //提前验证参数，调用失败后伪造容错数据等等，此时就需要在API中带上Stub，
        //客户端生成Proxy实，会把Proxy通过构造函数传给Stub，
        //然后把Stub暴露组给用户，Stub可以决定要不要去调Proxy。
        Boolean isStubSupportEvent = url.getParameter(Constants.STUB_EVENT_KEY, Constants.DEFAULT_STUB_EVENT);
        Boolean isCallbackservice = url.getParameter(Constants.IS_CALLBACK_SERVICE, false);
        if (isStubSupportEvent && !isCallbackservice) {
            String stubServiceMethods = url.getParameter(Constants.STUB_EVENT_METHODS_KEY);
            if (stubServiceMethods == null || stubServiceMethods.length() == 0) {
                if (logger.isWarnEnabled()) {
                    logger.warn(new IllegalStateException("consumer [" + url.getParameter(Constants.INTERFACE_KEY) +
                            "], has set stubproxy support event ,but no stub methods founded."));
                }
            } else {
                stubServiceMethodsMap.put(url.getServiceKey(), stubServiceMethods);
            }
        }

        //根据URL绑定IP与端口，建立NIO框架的Server
        openServer(url);
        // 优化序列化
        optimizeSerialization(url);
        return exporter;
    }

    /**
     * 在同一台机器上（单网卡），同一个端口上仅允许启动一个服务器实例。
     * 若某个端口上已有服务器实例，此时则调用 reset 方法重置服务器的一些配置。
     */
    private void openServer(URL url) {
        // find server.
        //key是IP:PORT
        //192.168.110.197:20880
        String key = url.getAddress();
        //client can export a service which's only for server to invoke
        //client 也可以暴露一个只有server可以调用的服务。
        //只有服务提供端才会启动监听
        boolean isServer = url.getParameter(Constants.IS_SERVER_KEY, true);
        if (isServer) {
            //同一JVM中，同协议的服务，共享同一个Server，
            //第一个暴露服务的时候创建server，
            //以后相同协议的服务都使用同一个server
            ExchangeServer server = serverMap.get(key);
            if (server == null) {
                serverMap.put(key, createServer(url));
            } else {
                // server supports reset, use together with override
                /**
                 * 同协议的服务后来暴露服务的则使用第一次创建的同一Server
                 * server支持reset,配合override功能使用
                 * accept、idleTimeout、threads、heartbeat参数的变化会引起Server的属性发生变化
                 * 这时需要重新设置Server
                 * @see AbstractServer#reset(com.alibaba.dubbo.common.URL)
                 */
                server.reset(url);
            }
        }
    }

    /**
     * url为：
     * dubbo://192.168.110.197:20880/dubbo.common.hello.service.HelloService?
     * anyhost=true&application=dubbo-provider&
     * application.version=1.0&dubbo=2.5.3&environment=product&
     * interface=dubbo.common.hello.service.HelloService&
     * methods=sayHello&organization=china&owner=cheng.xi&
     * pid=720&side=provider&timestamp=1489716708276
     */
    private ExchangeServer createServer(URL url) {
        // send readonly event when server closes, it's enabled by default
        //默认开启server关闭时发送readonly事件
        url = url.addParameterIfAbsent(Constants.CHANNEL_READONLYEVENT_SENT_KEY, Boolean.TRUE.toString());
        // enable heartbeat by default
        //默认开启heartbeat
        url = url.addParameterIfAbsent(Constants.HEARTBEAT_KEY, String.valueOf(Constants.DEFAULT_HEARTBEAT));
        //默认使用netty
        String str = url.getParameter(Constants.SERVER_KEY, Constants.DEFAULT_REMOTING_SERVER);

        // 通过 SPI 检测是否存在 server 参数所代表的 Transporter 拓展，不存在则抛出异常
        if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str))
            throw new RpcException("Unsupported server type: " + str + ", url: " + url);

        // 添加编码解码器参数
        url = url.addParameter(Constants.CODEC_KEY, DubboCodec.NAME);
        ExchangeServer server;
        try {
            //Exchangers是门面类，里面封装的是Exchanger的逻辑。
            //Exchanger默认只有一个实现HeaderExchanger.
            //Exchanger负责数据交换和网络通信。
            //从Protocol进入Exchanger，标志着程序进入了remote层。
            //这里requestHandler是ExchangeHandlerAdapter
            server = Exchangers.bind(url, requestHandler);
        } catch (RemotingException e) {
            throw new RpcException("Fail to start server(url: " + url + ") " + e.getMessage(), e);
        }
        // 获取 client 参数，可指定 netty，mina
        str = url.getParameter(Constants.CLIENT_KEY);
        if (str != null && str.length() > 0) {
            // 获取所有的 Transporter 实现类名称集合，比如 supportedTypes = [netty, mina]
            Set<String> supportedTypes = ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions();
            // 检测当前 Dubbo 所支持的 Transporter 实现类名称列表中，
            // 是否包含 client 所表示的 Transporter，若不包含，则抛出异常
            if (!supportedTypes.contains(str)) {
                throw new RpcException("Unsupported client type: " + str);
            }
        }
        return server;
    }

    private void optimizeSerialization(URL url) throws RpcException {
        String className = url.getParameter(Constants.OPTIMIZER_KEY, "");
        if (StringUtils.isEmpty(className) || optimizers.contains(className)) {
            return;
        }

        logger.info("Optimizing the serialization process for Kryo, FST, etc...");

        try {
            Class clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
            if (!SerializationOptimizer.class.isAssignableFrom(clazz)) {
                throw new RpcException("The serialization optimizer " + className + " isn't an instance of " + SerializationOptimizer.class.getName());
            }

            SerializationOptimizer optimizer = (SerializationOptimizer) clazz.newInstance();

            if (optimizer.getSerializableClasses() == null) {
                return;
            }

            for (Class c : optimizer.getSerializableClasses()) {
                SerializableClassRegistry.registerClass(c);
            }

            optimizers.add(className);
        } catch (ClassNotFoundException e) {
            throw new RpcException("Cannot find the serialization optimizer class: " + className, e);
        } catch (InstantiationException e) {
            throw new RpcException("Cannot instantiate the serialization optimizer class: " + className, e);
        } catch (IllegalAccessException e) {
            throw new RpcException("Cannot instantiate the serialization optimizer class: " + className, e);
        }
    }

    /**
     *
     * @param serviceType 服务提供者的Class
     * @param url  服务提供者的URL
     */
    @Override
    public <T> Invoker<T> refer(Class<T> serviceType, URL url) throws RpcException {
        optimizeSerialization(url);
        // create rpc invoker.
        //这里有一个getClients方法
        //创建DubboInvoker，创建过程中连接了服务端，包含一个ExchangeClient等
        DubboInvoker<T> invoker = new DubboInvoker<T>(serviceType, url, getClients(url), invokers);
        invokers.add(invoker);
        return invoker;
    }

    private ExchangeClient[] getClients(URL url) {
        // whether to share connection
        //是否共享连接
        boolean service_share_connect = false;
        //如果connections不配置，则共享连接，否则每服务每连接
        //即消费端引用同一个服务提供者机器上多个服务时，这些服务复用一个netty连接。
        int connections = url.getParameter(Constants.CONNECTIONS_KEY, 0);
        // if not configured, connection is shared, otherwise, one connection for one service
        if (connections == 0) {
            service_share_connect = true;
            connections = 1;
        }

        ExchangeClient[] clients = new ExchangeClient[connections];
        for (int i = 0; i < clients.length; i++) {
            //共享连接就使用getSharedClient
            //getSharedClient中先去缓存中查找，没有的话就会新建，也是调用initClient方法
            if (service_share_connect) {
                clients[i] = getSharedClient(url);
            } else {
                clients[i] = initClient(url);
            }
        }
        return clients;
    }

    /**
     * Get shared connection
     */
    private ExchangeClient getSharedClient(URL url) {
        String key = url.getAddress();
        //先从缓存中获取，获取到了直接返回
        ReferenceCountExchangeClient client = referenceClientMap.get(key);
        if (client != null) {
            if (!client.isClosed()) {
                client.incrementAndGetCount();
                return client;
            } else {
                referenceClientMap.remove(key);
            }
        }

        locks.putIfAbsent(key, new Object());
        synchronized (locks.get(key)) {
            if (referenceClientMap.containsKey(key)) {
                return referenceClientMap.get(key);
            }

            /*
             * NettyClient初始化完成之后，返回给Transporters，
             * 再返回给HeaderExchanger，HeaderExchanger中将NettyClient包装成HeaderExchangeClient返回给DubboProtocol的initClient方法中，
             * 到此在getSharedClient中就获取到了一个ExchangeClient，
             * 然后包装一下返回client = new ReferenceCountExchangeClient(exchagneclient, ghostClientMap)
             */
            ExchangeClient exchangeClient = initClient(url);
            // 将 ExchangeClient 实例传给 ReferenceCountExchangeClient，这里使用了装饰模式
            client = new ReferenceCountExchangeClient(exchangeClient, ghostClientMap);
            referenceClientMap.put(key, client);
            ghostClientMap.remove(key);
            locks.remove(key);
            return client;
        }
    }

    /**
     * Create new connection
     * @return {@link HeaderExchangeClient}
     */
    private ExchangeClient initClient(URL url) {

        // client type setting.
        // 获取客户端类型，默认为 netty
        String str = url.getParameter(Constants.CLIENT_KEY, url.getParameter(Constants.SERVER_KEY, Constants.DEFAULT_REMOTING_CLIENT));

        // 添加编解码和心跳包参数到 url 中
        url = url.addParameter(Constants.CODEC_KEY, DubboCodec.NAME);
        // enable heartbeat by default，默认60s
        url = url.addParameterIfAbsent(Constants.HEARTBEAT_KEY, String.valueOf(Constants.DEFAULT_HEARTBEAT));

        // BIO is not allowed since it has severe performance issue.
        // BIO存在严重性能问题，暂时不允许使用
        // 检测客户端类型是否存在，不存在则抛出异常
        if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported client type: " + str + "," +
                    " supported client type is " + StringUtils.join(ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions(), " "));
        }

        ExchangeClient client;
        try {
            // connection should be lazy
            //如果lazy属性没有配置为true（默认为false）ExchangeClient会马上和服务端建立连接
            //设置连接应该是lazy的
            // 获取 lazy 配置，并根据配置值决定创建的客户端类型
            if (url.getParameter(Constants.LAZY_CONNECT_KEY, false)) {
                client = new LazyConnectExchangeClient(url, requestHandler);
            } else {
                //立即和服务端建立连接
                // 创建普通 HeaderExchangeClient 实例
                client = Exchangers.connect(url, requestHandler);
            }
        } catch (RemotingException e) {
            throw new RpcException("Fail to create remoting client for service(" + url + "): " + e.getMessage(), e);
        }
        return client;
    }

    @Override
    public void destroy() {
        for (String key : new ArrayList<String>(serverMap.keySet())) {
            ExchangeServer server = serverMap.remove(key);
            if (server != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Close dubbo server: " + server.getLocalAddress());
                    }
                    server.close(ConfigUtils.getServerShutdownTimeout());
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }

        for (String key : new ArrayList<String>(referenceClientMap.keySet())) {
            ExchangeClient client = referenceClientMap.remove(key);
            if (client != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Close dubbo connect: " + client.getLocalAddress() + "-->" + client.getRemoteAddress());
                    }
                    client.close(ConfigUtils.getServerShutdownTimeout());
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }

        for (String key : new ArrayList<String>(ghostClientMap.keySet())) {
            ExchangeClient client = ghostClientMap.remove(key);
            if (client != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Close dubbo connect: " + client.getLocalAddress() + "-->" + client.getRemoteAddress());
                    }
                    client.close(ConfigUtils.getServerShutdownTimeout());
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }
        stubServiceMethodsMap.clear();
        super.destroy();
    }
}
