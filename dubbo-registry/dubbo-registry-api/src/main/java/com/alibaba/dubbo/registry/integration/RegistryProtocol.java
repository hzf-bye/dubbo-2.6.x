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
package com.alibaba.dubbo.registry.integration;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.registry.NotifyListener;
import com.alibaba.dubbo.registry.Registry;
import com.alibaba.dubbo.registry.RegistryFactory;
import com.alibaba.dubbo.registry.RegistryService;
import com.alibaba.dubbo.registry.support.ProviderConsumerRegTable;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.ProxyFactory;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Cluster;
import com.alibaba.dubbo.rpc.cluster.Configurator;
import com.alibaba.dubbo.rpc.cluster.support.Cluster$Adpative;
import com.alibaba.dubbo.rpc.protocol.InvokerWrapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.alibaba.dubbo.common.Constants.ACCEPT_FOREIGN_IP;
import static com.alibaba.dubbo.common.Constants.QOS_ENABLE;
import static com.alibaba.dubbo.common.Constants.QOS_PORT;
import static com.alibaba.dubbo.common.Constants.VALIDATION_KEY;
import static com.alibaba.dubbo.common.Constants.CATEGORY_KEY;
import static com.alibaba.dubbo.common.Constants.CONSUMERS_CATEGORY;
import static com.alibaba.dubbo.common.Constants.CHECK_KEY;

/**
 * RegistryProtocol
 *
 * RegistryProtocol实现了Protocol接口，也是Protocol接口等扩展类，
 * 但是它可以认为并不是一个真正的协议，他是实际的协议（dubbo . rmi）包装者，
 * 这样客户端的请求在一开始如果没有服务端的信息，
 * 会先从注册中心拉取服务的注册信息，然后再和服务端直连。
 * RegistryProtocol是基于注册中心发现服务提供者的实现协议。
 *
 */
public class RegistryProtocol implements Protocol {

    private final static Logger logger = LoggerFactory.getLogger(RegistryProtocol.class);
    private static RegistryProtocol INSTANCE;
    private final Map<URL, NotifyListener> overrideListeners = new ConcurrentHashMap<URL, NotifyListener>();
    //To solve the problem of RMI repeated exposure port conflicts, the services that have been exposed are no longer exposed.
    //providerurl <--> exporter
    private final Map<String, ExporterChangeableWrapper<?>> bounds = new ConcurrentHashMap<String, ExporterChangeableWrapper<?>>();

    /**
     * @see com.alibaba.dubbo.rpc.cluster.support.Cluster$Adpative
     */
    private Cluster cluster;
    /**
     * protocol实例，动态生成的类，实际不存在
     * @see com.alibaba.dubbo.config.Protocol$Adpative
     */
    private Protocol protocol;
    /**
     * registryFactory为以下实例
     *
     * import com.alibaba.dubbo.common.extension.ExtensionLoader;
     * public class RegistryFactory$Adpative implements com.alibaba.dubbo.registry.RegistryFactory {
     *     public com.alibaba.dubbo.registry.Registry getRegistry(com.alibaba.dubbo.common.URL arg0) {
     *
     *         if (arg0 == null) throw new IllegalArgumentException("url == null");
     *
     *         com.alibaba.dubbo.common.URL url = arg0;
     *         String extName = ( url.getProtocol() == null ? "dubbo" : url.getProtocol() );
     *
     *         if(extName == null) throw new IllegalStateException("Fail to get extension(com.alibaba.dubbo.registry.RegistryFactory) name from url(" + url.toString() + ") use keys([protocol])");
     *
     *         com.alibaba.dubbo.registry.RegistryFactory extension = (com.alibaba.dubbo.registry.RegistryFactory)ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.registry.RegistryFactory.class).getExtension(extName);
     *
     *         return extension.getRegistry(arg0);
     *     }
     * }
     */
    private RegistryFactory registryFactory;
    private ProxyFactory proxyFactory;

    public RegistryProtocol() {
        INSTANCE = this;
    }

    public static RegistryProtocol getRegistryProtocol() {
        if (INSTANCE == null) {
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(Constants.REGISTRY_PROTOCOL); // load
        }
        return INSTANCE;
    }

    //Filter the parameters that do not need to be output in url(Starting with .)
    private static String[] getFilteredKeys(URL url) {
        Map<String, String> params = url.getParameters();
        if (params != null && !params.isEmpty()) {
            List<String> filteredKeys = new ArrayList<String>();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (entry != null && entry.getKey() != null && entry.getKey().startsWith(Constants.HIDE_KEY_PREFIX)) {
                    filteredKeys.add(entry.getKey());
                }
            }
            return filteredKeys.toArray(new String[filteredKeys.size()]);
        } else {
            return new String[]{};
        }
    }

    public void setCluster(Cluster cluster) {
        this.cluster = cluster;
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public void setRegistryFactory(RegistryFactory registryFactory) {
        this.registryFactory = registryFactory;
    }

    public void setProxyFactory(ProxyFactory proxyFactory) {
        this.proxyFactory = proxyFactory;
    }

    @Override
    public int getDefaultPort() {
        return 9090;
    }

    public Map<URL, NotifyListener> getOverrideListeners() {
        return overrideListeners;
    }

    public void register(URL registryUrl, URL registedProviderUrl) {
        Registry registry = registryFactory.getRegistry(registryUrl);
        registry.register(registedProviderUrl);
    }

    @Override
    public <T> Exporter<T> export(final Invoker<T> originInvoker) throws RpcException {
        //export invoker
        //这里就交给了具体的协议去暴露服务
        //打开端口，把服务实例存储到map，暴露服务
        final ExporterChangeableWrapper<T> exporter = doLocalExport(originInvoker);

        // 获得注册中心的url
        //根据invoker中的url获取Registry实例
        //并且连接到注册中心
        //此时提供者作为消费者引用注册中心核心服务RegistryService
        // 这里面registryUrl的protocol已经是zookeeper了
        URL registryUrl = getRegistryUrl(originInvoker);

        //registry provider
        //创建注册中心实例
        // 根据 URL 加载 Registry 实现类，比如ZookeeperRegistry
        final Registry registry = getRegistry(originInvoker);
        // 获取服务提供者对应的URL并过滤URL参数一次
        // dubbo://127.0.0.1:20880/dubbo.common.hello.service.HelloService?anyhost=true&application=dubbo-provider&application.version=1.0....
        final URL registeredProviderUrl = getRegisteredProviderUrl(originInvoker);

        //to judge to delay publish whether or not
        // 获取 register 参数
        boolean register = registeredProviderUrl.getParameter("register", true);

        ProviderConsumerRegTable.registerProvider(originInvoker, registryUrl, registeredProviderUrl);

        // 如果需要注册服务
        if (register) {
            //调用远端注册中心的register方法进行服务注册
            //若有消费者订阅此服务，则推送消息让消费者引用此服务。
            //注册中心缓存了所有提供者注册的服务以供消费者发现。
            // 服务暴露后，向注册中心注册服务元数据
            register(registryUrl, registeredProviderUrl);
            // 设置reg为true，表示服务注册
            ProviderConsumerRegTable.getProviderWrapper(originInvoker).setReg(true);
        }

        // Subscribe the override data
        // FIXME 提供者订阅时，会影响同一JVM即暴露服务，又引用同一服务的的场景，因为subscribed以服务名为缓存的key，导致订阅信息覆盖。
        // FIXME When the provider subscribes, it will affect the scene : a certain JVM exposes the service and call the same service. Because the subscribed is cached key with the name of the service, it causes the subscription information to cover.
        // 获取override订阅 URL
        final URL overrideSubscribeUrl = getSubscribedOverrideUrl(registeredProviderUrl);
        // 创建override的监听器
        final OverrideListener overrideSubscribeListener = new OverrideListener(overrideSubscribeUrl, originInvoker);
        // 把监听器添加到集合
        overrideListeners.put(overrideSubscribeUrl, overrideSubscribeListener);

        //在注册到注册中心之后，registry会去订阅覆盖配置的服务，这一步之后就会在/dubbo/dubbo.common.hello.service/HelloService节点下多一个configurators节点

        //监听服务接口下configurators节点，用于处理动态配置
        //提供者向注册中心订阅所有注册服务的覆盖配置
        //当注册中心有此服务的覆盖配置注册进来时，推送消息给提供者，重新暴露服务，这由管理页面完成。
        registry.subscribe(overrideSubscribeUrl, overrideSubscribeListener);
        //Ensure that a new exporter instance is returned every time export
        return new DestroyableExporter<T>(exporter, originInvoker, overrideSubscribeUrl, registeredProviderUrl);
    }

    @SuppressWarnings("unchecked")
    private <T> ExporterChangeableWrapper<T> doLocalExport(final Invoker<T> originInvoker) {
        //获取服务提供者的URL.toFullString生成的key.
        /*
         * registry://127.0.0.1:2181/com.alibaba.dubbo.registry.RegistryService?
         *     application=dubbo-provider&application.version=1.0&dubbo=2.5.3....
         *     &export=dubbo%3A%2F%2F10.42.0.1%3A20880%2F
         *      dubbo.common.hello.service.HelloService%3Fanyhost%3Dtrue%26application%3Ddubbo-provider%26
         *      application.version%3D1.0%26dubbo%3D2.5.3%26environment%3Dproduct%26
         *      interface%3Ddubbo.common.hello.service.HelloService%26methods%3DsayHello%26
         *      organization%3Dchina%26owner%3Dcheng.xi%26pid%3D7876%26side%3Dprovider%26timestamp%3D1489057305001&
         *      organization=china&owner=cheng.xi&pid=7876&registry=zookeeper&timestamp=1489057304900
         */
        /*
         * 从原始的invoker中得到的key：
         *     dubbo://10.42.0.1:20880/dubbo.common.hello.service.HelloService?anyhost=true&application=dubbo-provider&
         *     application.version=1.0&dubbo=2.5.3&environment=product&interface=dubbo.common.hello.service.HelloService&
         *     methods=sayHello&organization=china&owner=cheng.xi&pid=7876&side=provider&timestamp=1489057305001
         */
        String key = getCacheKey(originInvoker);
        ExporterChangeableWrapper<T> exporter = (ExporterChangeableWrapper<T>) bounds.get(key);
        if (exporter == null) {
            synchronized (bounds) {
                exporter = (ExporterChangeableWrapper<T>) bounds.get(key);
                if (exporter == null) {
                    // 创建 Invoker 为委托类对象
                    //得到一个Invoker代理，里面包含原来的Invoker
                    final Invoker<?> invokerDelegete = new InvokerDelegete<T>(originInvoker, getProviderUrl(originInvoker));
                    //此处protocol还是最上面生成的代码，调用代码中的export方法，会根据协议名选择调用具体的实现类
                    //这里我们需要调用DubboProtocol的export方法
                    //这里的使用具体协议进行导出的invoker是个代理invoker
                    //导出完之后，返回一个新的ExporterChangeableWrapper实例

                    /*
                     * 这里protocol.export(invokerDelegete)就要去具体的DubboProtocol中执行了，
                     * DubboProtocol的外面包裹着ProtocolListenerWrapper，
                     * 再外面还包裹着ProtocolFilterWrapper。会先经过ProtocolFilterWrapper
                     *
                     * 调用链详见ProtocolListenerWrapper类解释
                     */
                    exporter = new ExporterChangeableWrapper<T>((Exporter<T>) protocol.export(invokerDelegete), originInvoker);
                    bounds.put(key, exporter);
                }
            }
        }
        return exporter;
    }

    /**
     * Reexport the invoker of the modified url
     *
     * @param originInvoker
     * @param newInvokerUrl
     */
    @SuppressWarnings("unchecked")
    private <T> void doChangeLocalExport(final Invoker<T> originInvoker, URL newInvokerUrl) {
        String key = getCacheKey(originInvoker);
        final ExporterChangeableWrapper<T> exporter = (ExporterChangeableWrapper<T>) bounds.get(key);
        if (exporter == null) {
            logger.warn(new IllegalStateException("error state, exporter should not be null"));
        } else {
            final Invoker<T> invokerDelegete = new InvokerDelegete<T>(originInvoker, newInvokerUrl);
            exporter.setExporter(protocol.export(invokerDelegete));
        }
    }

    /**
     * Get an instance of registry based on the address of invoker
     *
     * @param originInvoker
     * @return
     */
    private Registry getRegistry(final Invoker<?> originInvoker) {
        URL registryUrl = getRegistryUrl(originInvoker);
        //根据SPI机制获取具体的Registry实例，这里获取到的是ZookeeperRegistry
        //因为此时registryUrl的protocol已经是zookeeper了
        return registryFactory.getRegistry(registryUrl);
    }

    private URL getRegistryUrl(Invoker<?> originInvoker) {
        URL registryUrl = originInvoker.getUrl();
        //如果protocol是registry，那么parameters中有具体的协议名称如zookeeper
        if (Constants.REGISTRY_PROTOCOL.equals(registryUrl.getProtocol())) {
            //获取registry的值，这里获得是zookeeper，默认值是dubbo
            String protocol = registryUrl.getParameter(Constants.REGISTRY_KEY, Constants.DEFAULT_DIRECTORY);
            registryUrl = registryUrl.setProtocol(protocol).removeParameter(Constants.REGISTRY_KEY);
        }
        return registryUrl;
    }


    /**
     * Return the url that is registered to the registry and filter the url parameter once
     *
     * @param originInvoker
     * @return
     */
    private URL getRegisteredProviderUrl(final Invoker<?> originInvoker) {
        URL providerUrl = getProviderUrl(originInvoker);
        //The address you see at the registry
        return providerUrl.removeParameters(getFilteredKeys(providerUrl))
                .removeParameter(Constants.MONITOR_KEY)
                .removeParameter(Constants.BIND_IP_KEY)
                .removeParameter(Constants.BIND_PORT_KEY)
                .removeParameter(QOS_ENABLE)
                .removeParameter(QOS_PORT)
                .removeParameter(ACCEPT_FOREIGN_IP)
                .removeParameter(VALIDATION_KEY);
    }

    private URL getSubscribedOverrideUrl(URL registedProviderUrl) {
        return registedProviderUrl.setProtocol(Constants.PROVIDER_PROTOCOL)
                .addParameters(Constants.CATEGORY_KEY, Constants.CONFIGURATORS_CATEGORY,
                        Constants.CHECK_KEY, String.valueOf(false));
    }

    /**
     * Get the address of the providerUrl through the url of the invoker
     *
     * @param origininvoker
     * @return
     */
    private URL getProviderUrl(final Invoker<?> origininvoker) {
        String export = origininvoker.getUrl().getParameterAndDecoded(Constants.EXPORT_KEY);
        if (export == null || export.length() == 0) {
            throw new IllegalArgumentException("The registry export url is null! registry: " + origininvoker.getUrl());
        }

        URL providerUrl = URL.valueOf(export);
        return providerUrl;
    }

    /**
     * Get the key cached in bounds by invoker
     *
     * @param originInvoker
     * @return
     */
    private String getCacheKey(final Invoker<?> originInvoker) {
        URL providerUrl = getProviderUrl(originInvoker);
        String key = providerUrl.removeParameters("dynamic", "enabled").toFullString();
        return key;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        //这里获得的url是
        //zookeeper://127.0.0.1:2181/com.alibaba.dubbo.registry.RegistryService?
        //application=dubbo-consumer&dubbo=2.5.3&pid=12272&
        //refer=application%3Ddubbo-consumer%26dubbo%3D2.5.3%26
        //interface%3Ddubbo.common.hello.service.HelloService%26
        //methods%3DsayHello%26pid%3D12272%26side%3D
        //consumer%26timeout%3D100000%26
        //timestamp%3D1489318676447&timestamp=1489318676641
        url = url.setProtocol(url.getParameter(Constants.REGISTRY_KEY, Constants.DEFAULT_REGISTRY)).removeParameter(Constants.REGISTRY_KEY);
        //根据url获取Registry对象
        //先连接注册中心，把消费者注册到注册中心
        Registry registry = registryFactory.getRegistry(url);
        //判断引用是否是注册中心RegistryService，如果是直接返回刚得到的注册中心服务
        if (RegistryService.class.equals(type)) {
            return proxyFactory.getInvoker((T) registry, type, url);
        }

        //以下是普通服务，需要进入注册中心和集群下面的逻辑
        // group="a,b" or group="*"
        //获取ref的各种属性
        Map<String, String> qs = StringUtils.parseQueryString(url.getParameterAndDecoded(Constants.REFER_KEY));
        //获取分组属性
        String group = qs.get(Constants.GROUP_KEY);
        //先判断引用服务是否需要合并不同实现的返回结果
        if (group != null && group.length() > 0) {
            // 如果有多个组，或者组配置为*，则使用MergeableCluster，并调用 doRefer 继续执行服务引用逻辑
            //使用默认的分组聚合集群策略
            if ((Constants.COMMA_SPLIT_PATTERN.split(group)).length > 1
                    || "*".equals(group)) {
                return doRefer(getMergeableCluster(), registry, type, url);
            }
        }
        // 只有一个组或者没有组配置，则直接执行doRefer
        //选择配置的集群策略（cluster="failback"）或者默认策略
        return doRefer(cluster, registry, type, url);
    }

    private Cluster getMergeableCluster() {
        return ExtensionLoader.getExtensionLoader(Cluster.class).getExtension("mergeable");
    }

    private <T> Invoker<T> doRefer(Cluster cluster, Registry registry, Class<T> type, URL url) {
        // 创建 RegistryDirectory 实例
        //组装Directory，可以看成一个消费端的List，可以随着注册中心的消息推送而动态的变化服务的Invoker
        //封装了所有服务真正引用逻辑，覆盖配置，路由规则等逻辑
        //初始化时只需要向注册中心发起订阅请求，其他逻辑均是异步处理，包括服务的引用等
        //缓存接口所有的提供者端Invoker以及注册中心接口相关的配置等
        RegistryDirectory<T> directory = new RegistryDirectory<T>(type, url);
        // 设置注册中心
        directory.setRegistry(registry);
        // 设置协议
        directory.setProtocol(protocol);
        // all attributes of REFER_KEY
        // 所有属性放到map中
        Map<String, String> parameters = new HashMap<String, String>(directory.getUrl().getParameters());
        // 生成服务消费者链接
        //此处的subscribeUrl为
        //consumer://192.168.1.100/dubbo.common.hello.service.HelloService?
        //application=dubbo-consumer&dubbo=2.5.3&
        //interface=dubbo.common.hello.service.HelloService&
        //methods=sayHello&pid=16409&
        //side=consumer&timeout=100000&timestamp=1489322133987
        URL subscribeUrl = new URL(Constants.CONSUMER_PROTOCOL, parameters.remove(Constants.REGISTER_IP_KEY), 0, type.getName(), parameters);
        // 注册服务消费者，在 consumers 目录下新节点
        if (!Constants.ANY_VALUE.equals(url.getServiceInterface())
                && url.getParameter(Constants.REGISTER_KEY, true)) {
            URL registeredConsumerUrl = getRegisteredConsumerUrl(subscribeUrl, url);
            // 注册服务消费者
            //到注册中心注册服务
            //此处registy是上面一步获得的registry，即是ZookeeperRegistry，包含zkClient的实例
            //会先经过AbstractRegistry的处理，然后经过FailbackRegistry的处理。
            registry.register(registeredConsumerUrl);
            directory.setRegisteredConsumerUrl(registeredConsumerUrl);
        }
        // 订阅 providers、configurators、routers 等节点数据
        //有服务提供的时候，注册中心会推送服务消息给消费者，消费者再进行服务的引用。
        directory.subscribe(subscribeUrl.addParameter(Constants.CATEGORY_KEY,
                Constants.PROVIDERS_CATEGORY
                        + "," + Constants.CONFIGURATORS_CATEGORY
                        + "," + Constants.ROUTERS_CATEGORY));

        // 一个注册中心可能有多个服务提供者，因此这里需要将多个服务提供者合并为一个，生成一个invoker
        //服务的引用与变更全部由Directory异步完成
        //集群策略会将Directory伪装成一个Invoker返回
        //合并所有相同的invoker
        /*
         * 这里由Cluster组件创建一个Invoker并返回，
         * 这里的cluster默认是用FailoverCluster，最后返回的是经过FailoverClusterInvoker包装过的Invoker。
         * 继续返回到ReferenceConfig中createProxy方法，这时候我们已经完成了消费者端引用服务的Invoker
         *
         */
        Invoker invoker = cluster.join(directory);
        // 在服务提供者处注册消费者
        ProviderConsumerRegTable.registerConsumer(invoker, url, subscribeUrl, directory);
        return invoker;
    }

    public URL getRegisteredConsumerUrl(final URL consumerUrl, URL registryUrl) {
        return consumerUrl.addParameters(CATEGORY_KEY, CONSUMERS_CATEGORY,
                CHECK_KEY, String.valueOf(false));
    }

    @Override
    public void destroy() {
        List<Exporter<?>> exporters = new ArrayList<Exporter<?>>(bounds.values());
        for (Exporter<?> exporter : exporters) {
            exporter.unexport();
        }
        bounds.clear();
    }

    public static class InvokerDelegete<T> extends InvokerWrapper<T> {
        private final Invoker<T> invoker;

        /**
         * @param invoker
         * @param url     invoker.getUrl return this value
         */
        public InvokerDelegete(Invoker<T> invoker, URL url) {
            super(invoker, url);
            this.invoker = invoker;
        }

        public Invoker<T> getInvoker() {
            if (invoker instanceof InvokerDelegete) {
                return ((InvokerDelegete<T>) invoker).getInvoker();
            } else {
                return invoker;
            }
        }
    }

    /**
     * Reexport: the exporter destroy problem in protocol
     * 1.Ensure that the exporter returned by registryprotocol can be normal destroyed
     * 2.No need to re-register to the registry after notify
     * 3.The invoker passed by the export method , would better to be the invoker of exporter
     */
    private class OverrideListener implements NotifyListener {

        private final URL subscribeUrl;
        private final Invoker originInvoker;

        public OverrideListener(URL subscribeUrl, Invoker originalInvoker) {
            this.subscribeUrl = subscribeUrl;
            this.originInvoker = originalInvoker;
        }

        /**
         * @param urls The list of registered information , is always not empty, The meaning is the same as the return value of {@link com.alibaba.dubbo.registry.RegistryService#lookup(URL)}.
         */
        @Override
        public synchronized void notify(List<URL> urls) {
            logger.debug("original override urls: " + urls);
            List<URL> matchedUrls = getMatchedUrls(urls, subscribeUrl);
            logger.debug("subscribe url: " + subscribeUrl + ", override urls: " + matchedUrls);
            // No matching results
            if (matchedUrls.isEmpty()) {
                return;
            }

            List<Configurator> configurators = RegistryDirectory.toConfigurators(matchedUrls);

            final Invoker<?> invoker;
            if (originInvoker instanceof InvokerDelegete) {
                invoker = ((InvokerDelegete<?>) originInvoker).getInvoker();
            } else {
                invoker = originInvoker;
            }
            //The origin invoker
            URL originUrl = RegistryProtocol.this.getProviderUrl(invoker);
            String key = getCacheKey(originInvoker);
            ExporterChangeableWrapper<?> exporter = bounds.get(key);
            if (exporter == null) {
                logger.warn(new IllegalStateException("error state, exporter should not be null"));
                return;
            }
            //The current, may have been merged many times
            URL currentUrl = exporter.getInvoker().getUrl();
            //Merged with this configuration
            URL newUrl = getConfigedInvokerUrl(configurators, originUrl);
            if (!currentUrl.equals(newUrl)) {
                RegistryProtocol.this.doChangeLocalExport(originInvoker, newUrl);
                logger.info("exported provider url changed, origin url: " + originUrl + ", old export url: " + currentUrl + ", new export url: " + newUrl);
            }
        }

        private List<URL> getMatchedUrls(List<URL> configuratorUrls, URL currentSubscribe) {
            List<URL> result = new ArrayList<URL>();
            for (URL url : configuratorUrls) {
                URL overrideUrl = url;
                // Compatible with the old version
                if (url.getParameter(Constants.CATEGORY_KEY) == null
                        && Constants.OVERRIDE_PROTOCOL.equals(url.getProtocol())) {
                    overrideUrl = url.addParameter(Constants.CATEGORY_KEY, Constants.CONFIGURATORS_CATEGORY);
                }

                // Check whether url is to be applied to the current service
                if (UrlUtils.isMatch(currentSubscribe, overrideUrl)) {
                    result.add(url);
                }
            }
            return result;
        }

        //Merge the urls of configurators
        private URL getConfigedInvokerUrl(List<Configurator> configurators, URL url) {
            for (Configurator configurator : configurators) {
                url = configurator.configure(url);
            }
            return url;
        }
    }

    /**
     * exporter proxy, establish the corresponding relationship between the returned exporter and the exporter exported by the protocol, and can modify the relationship at the time of override.
     *
     * @param <T>
     */
    private class ExporterChangeableWrapper<T> implements Exporter<T> {

        private final Invoker<T> originInvoker;
        private Exporter<T> exporter;

        public ExporterChangeableWrapper(Exporter<T> exporter, Invoker<T> originInvoker) {
            this.exporter = exporter;
            this.originInvoker = originInvoker;
        }

        public Invoker<T> getOriginInvoker() {
            return originInvoker;
        }

        @Override
        public Invoker<T> getInvoker() {
            return exporter.getInvoker();
        }

        public void setExporter(Exporter<T> exporter) {
            this.exporter = exporter;
        }

        @Override
        public void unexport() {
            String key = getCacheKey(this.originInvoker);
            bounds.remove(key);
            exporter.unexport();
        }
    }

    static private class DestroyableExporter<T> implements Exporter<T> {

        public static final ExecutorService executor = Executors.newSingleThreadExecutor(new NamedThreadFactory("Exporter-Unexport", true));

        private Exporter<T> exporter;
        private Invoker<T> originInvoker;
        private URL subscribeUrl;
        private URL registerUrl;

        public DestroyableExporter(Exporter<T> exporter, Invoker<T> originInvoker, URL subscribeUrl, URL registerUrl) {
            this.exporter = exporter;
            this.originInvoker = originInvoker;
            this.subscribeUrl = subscribeUrl;
            this.registerUrl = registerUrl;
        }

        @Override
        public Invoker<T> getInvoker() {
            return exporter.getInvoker();
        }

        @Override
        public void unexport() {
            Registry registry = RegistryProtocol.INSTANCE.getRegistry(originInvoker);
            try {
                registry.unregister(registerUrl);
            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }
            try {
                NotifyListener listener = RegistryProtocol.INSTANCE.overrideListeners.remove(subscribeUrl);
                registry.unsubscribe(subscribeUrl, listener);
            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }

            executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        int timeout = ConfigUtils.getServerShutdownTimeout();
                        if (timeout > 0) {
                            logger.info("Waiting " + timeout + "ms for registry to notify all consumers before unexport. Usually, this is called when you use dubbo API");
                            Thread.sleep(timeout);
                        }
                        exporter.unexport();
                    } catch (Throwable t) {
                        logger.warn(t.getMessage(), t);
                    }
                }
            });
        }
    }
}
