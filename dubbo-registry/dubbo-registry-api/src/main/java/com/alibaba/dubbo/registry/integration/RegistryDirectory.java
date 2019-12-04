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
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.registry.NotifyListener;
import com.alibaba.dubbo.registry.Registry;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.cluster.Cluster;
import com.alibaba.dubbo.rpc.cluster.Configurator;
import com.alibaba.dubbo.rpc.cluster.ConfiguratorFactory;
import com.alibaba.dubbo.rpc.cluster.Router;
import com.alibaba.dubbo.rpc.cluster.RouterFactory;
import com.alibaba.dubbo.rpc.cluster.directory.AbstractDirectory;
import com.alibaba.dubbo.rpc.cluster.directory.StaticDirectory;
import com.alibaba.dubbo.rpc.cluster.support.ClusterUtils;
import com.alibaba.dubbo.rpc.protocol.InvokerWrapper;
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RegistryDirectory
 * 注册中心服务，维护着所有可用的远程Invoker或者本地的Invoker。
 * 它的Invoker集合是从注册中心获取的， 它实现了NotifyListener接口实现了回调接口notify方法。
 * 比如消费方要调用某远程服务，会向注册中心订阅这个服务的所有服务提供方，
 * 订阅时和服务提供方数据有变动时回调消费方的NotifyListener服务的notify方法，
 * 回调接口传入所有服务的提供方的url地址然后将urls转化为invokers, 也就是refer应用远程服务
 *
 */
public class RegistryDirectory<T> extends AbstractDirectory<T> implements NotifyListener {

    private static final Logger logger = LoggerFactory.getLogger(RegistryDirectory.class);

    private static final Cluster cluster = ExtensionLoader.getExtensionLoader(Cluster.class).getAdaptiveExtension();

    private static final RouterFactory routerFactory = ExtensionLoader.getExtensionLoader(RouterFactory.class).getAdaptiveExtension();

    private static final ConfiguratorFactory configuratorFactory = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class).getAdaptiveExtension();
    /**
     * 提供这URL#getServiceKey
     */
    private final String serviceKey; // Initialization at construction time, assertion not null
    /**
     * 提供者Class
     */
    private final Class<T> serviceType; // Initialization at construction time, assertion not null
    private final Map<String, String> queryMap; // Initialization at construction time, assertion not null
    private final URL directoryUrl; // Initialization at construction time, assertion not null, and always assign non null value
    /**
     * 存储的是服务提供方的所有方法
     */
    private final String[] serviceMethods;
    /**
     * directoryUrl中的group是否包含多个值
     */
    private final boolean multiGroup;
    private Protocol protocol; // Initialization at the time of injection, the assertion is not null
    private Registry registry; // Initialization at the time of injection, the assertion is not null
    private volatile boolean forbidden = false;

    private volatile URL overrideDirectoryUrl; // Initialization at construction time, assertion not null, and always assign non null value

    private volatile URL registeredConsumerUrl;

    /**
     * override rules
     * Priority: override>-D>consumer>provider
     * Rule one: for a certain provider <ip:port,timeout=100>
     * Rule two: for all providers <* ,timeout=5000>
     */
    private volatile List<Configurator> configurators; // The initial value is null and the midway may be assigned to null, please use the local variable reference

    // Map<url, Invoker> cache service url to invoker mapping.
    /**
     * key 为提供者url.toFullString()
     * value 提供者url对应的Invoker，InvokerDelegate实例
     */
    private volatile Map<String, Invoker<T>> urlInvokerMap; // The initial value is null and the midway may be assigned to null, please use the local variable reference

    // Map<methodName, Invoker> cache service method to invokers mapping.
    /**
     * 提供者方法名与提供者url对应的Invoker之间的映射
     */
    private volatile Map<String, List<Invoker<T>>> methodInvokerMap; // The initial value is null and the midway may be assigned to null, please use the local variable reference

    // Set<invokerUrls> cache invokeUrls to invokers mapping.
    //缓存的是当前消费者消费的提供者URL
    private volatile Set<URL> cachedInvokerUrls; // The initial value is null and the midway may be assigned to null, please use the local variable reference

    public RegistryDirectory(Class<T> serviceType, URL url) {
        super(url);
        if (serviceType == null)
            throw new IllegalArgumentException("service type is null.");
        if (url.getServiceKey() == null || url.getServiceKey().length() == 0)
            throw new IllegalArgumentException("registry serviceKey is null.");
        this.serviceType = serviceType;
        this.serviceKey = url.getServiceKey();
        this.queryMap = StringUtils.parseQueryString(url.getParameterAndDecoded(Constants.REFER_KEY));
        this.overrideDirectoryUrl = this.directoryUrl = url.setPath(url.getServiceInterface()).clearParameters().addParameters(queryMap).removeParameter(Constants.MONITOR_KEY);
        String group = directoryUrl.getParameter(Constants.GROUP_KEY, "");
        this.multiGroup = group != null && ("*".equals(group) || group.contains(","));
        String methods = queryMap.get(Constants.METHODS_KEY);
        this.serviceMethods = methods == null ? null : Constants.COMMA_SPLIT_PATTERN.split(methods);
    }

    /**
     * Convert override urls to map for use when re-refer.
     * Send all rules every time, the urls will be reassembled and calculated
     *
     * @param urls Contract:
     *             </br>1.override://0.0.0.0/...( or override://ip:port...?anyhost=true)&para1=value1... means global rules (all of the providers take effect)
     *             </br>2.override://ip:port...?anyhost=false Special rules (only for a certain provider)
     *             </br>3.override:// rule is not supported... ,needs to be calculated by registry itself.
     *             </br>4.override://0.0.0.0/ without parameters means clearing the override
     * @return
     */
    public static List<Configurator> toConfigurators(List<URL> urls) {
        if (urls == null || urls.isEmpty()) {
            return Collections.emptyList();
        }

        List<Configurator> configurators = new ArrayList<Configurator>(urls.size());
        for (URL url : urls) {
            if (Constants.EMPTY_PROTOCOL.equals(url.getProtocol())) {
                configurators.clear();
                break;
            }
            Map<String, String> override = new HashMap<String, String>(url.getParameters());
            //The anyhost parameter of override may be added automatically, it can't change the judgement of changing url
            override.remove(Constants.ANYHOST_KEY);
            if (override.size() == 0) {
                configurators.clear();
                continue;
            }
            configurators.add(configuratorFactory.getConfigurator(url));
        }
        Collections.sort(configurators);
        return configurators;
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public void setRegistry(Registry registry) {
        this.registry = registry;
    }

    public void subscribe(URL url) {
        //设置消费者url
        setConsumerUrl(url);
        //这里的registry是ZookeeperRegistry
        registry.subscribe(url, this);
    }

    @Override
    public void destroy() {
        if (isDestroyed()) {
            return;
        }

        // unregister.
        try {
            if (getRegisteredConsumerUrl() != null && registry != null && registry.isAvailable()) {
                registry.unregister(getRegisteredConsumerUrl());
            }
        } catch (Throwable t) {
            logger.warn("unexpected error when unregister service " + serviceKey + "from registry" + registry.getUrl(), t);
        }

        // unsubscribe.
        try {
            if (getConsumerUrl() != null && registry != null && registry.isAvailable()) {
                registry.unsubscribe(getConsumerUrl(), this);
            }
        } catch (Throwable t) {
            logger.warn("unexpeced error when unsubscribe service " + serviceKey + "from registry" + registry.getUrl(), t);
        }
        super.destroy(); // must be executed after unsubscribing
        try {
            destroyAllInvokers();
        } catch (Throwable t) {
            logger.warn("Failed to destroy service " + serviceKey, t);
        }
    }

    /**
     * 当收到服务变更通知时触发。
     * <p>
     * 通知需处理契约：<br>
     * 1. 总是以服务接口和数据类型为维度全量通知，即不会通知一个服务的同类型的部分数据，用户不需要对比上一次通知结果。<br>
     * 2. 订阅时的第一次通知，必须是一个服务的所有类型数据的全量通知。<br>
     * 3. 中途变更时，允许不同类型的数据分开通知，比如：providers, consumers, routers, overrides，允许只通知其中一种类型，但该类型的数据必须是全量的，不是增量的。<br>
     * 4. 如果一种类型的数据为空，需通知一个empty协议并带category参数的标识性URL数据。<br>
     * 5. 通知者(即注册中心实现)需保证通知的顺序，比如：单线程推送，队列串行化，带版本对比。<br>
     *
     * @param urls 已注册信息列表，总不为空，含义同{@link com.alibaba.dubbo.registry.RegistryService#lookup(URL)}的返回值。
     */
    @Override
    public synchronized void notify(List<URL> urls) {
        // 定义三个集合，分别用于存放服务提供者 url，路由 url，配置器 url
        List<URL> invokerUrls = new ArrayList<URL>();
        List<URL> routerUrls = new ArrayList<URL>();
        List<URL> configuratorUrls = new ArrayList<URL>();
        for (URL url : urls) {
            String protocol = url.getProtocol();
            // 获取 category 参数
            String category = url.getParameter(Constants.CATEGORY_KEY, Constants.DEFAULT_CATEGORY);
            // 根据 category 参数将 url 分别放到不同的列表中
            if (Constants.ROUTERS_CATEGORY.equals(category)
                    || Constants.ROUTE_PROTOCOL.equals(protocol)) {
                // 添加路由器 url
                routerUrls.add(url);
            } else if (Constants.CONFIGURATORS_CATEGORY.equals(category)
                    || Constants.OVERRIDE_PROTOCOL.equals(protocol)) {
                // 添加配置器 url
                configuratorUrls.add(url);
            } else if (Constants.PROVIDERS_CATEGORY.equals(category)) {
                // 添加服务提供者 url
                invokerUrls.add(url);
            } else {
                logger.warn("Unsupported category " + category + " in notified url: " + url + " from registry " + getUrl().getAddress() + " to consumer " + NetUtils.getLocalHost());
            }
        }
        // configurators
        // configurators 更新缓存的服务提供方配置
        if (configuratorUrls != null && !configuratorUrls.isEmpty()) {
            // 将 url 转成 Configurator
            this.configurators = toConfigurators(configuratorUrls);
        }
        // routers
        //更新缓存的路由规则配置
        if (routerUrls != null && !routerUrls.isEmpty()) {
            // 将 url 转成 Router
            List<Router> routers = toRouters(routerUrls);
            if (routers != null) { // null - do nothing
                setRouters(routers);
            }
        }
        List<Configurator> localConfigurators = this.configurators; // local reference
        // merge override parameters
        // 合并override参数
        this.overrideDirectoryUrl = directoryUrl;
        if (localConfigurators != null && !localConfigurators.isEmpty()) {
            for (Configurator configurator : localConfigurators) {
                // 配置 overrideDirectoryUrl
                this.overrideDirectoryUrl = configurator.configure(overrideDirectoryUrl);
            }
        }
        // providers
        //根据服务提供者Url重建invoker实例
        refreshInvoker(invokerUrls);
    }

    /**
     * Convert the invokerURL list to the Invoker Map. The rules of the conversion are as follows:
     * 1.If URL has been converted to invoker, it is no longer re-referenced and obtained directly from the cache, and notice that any parameter changes in the URL will be re-referenced.
     * 2.If the incoming invoker list is not empty, it means that it is the latest invoker list
     * 3.If the list of incoming invokerUrl is empty, It means that the rule is only a override rule or a route rule, which needs to be re-contrasted to decide whether to re-reference.
     *
     * @param invokerUrls this parameter can't be null
     */
    /**
     * 根据invokerURL列表转换为invoker列表。转换规则如下：
     * 1.如果url已经被转换为invoker，则不在重新引用，直接从缓存中获取，注意如果url中任何一个参数变更也会重新引用
     * 2.如果传入的invoker列表不为空，则表示最新的invoker列表
     * 3.如果传入的invokerUrl列表是空，则表示只是下发的override规则或route规则，需要重新交叉对比，决定是否需要重新引用。
     * @param invokerUrls 传入的参数不能为null，为服务提供者Url
     */
    // TODO: 2017/8/31 FIXME The thread pool should be used to refresh the address, otherwise the task may be accumulated.
    private void refreshInvoker(List<URL> invokerUrls) {
        // invokerUrls 仅有一个元素，且 url 协议头为 empty，此时表示禁用所有服务
        if (invokerUrls != null && invokerUrls.size() == 1 && invokerUrls.get(0) != null
                && Constants.EMPTY_PROTOCOL.equals(invokerUrls.get(0).getProtocol())) {
            // 禁止访问
            // Forbid to access
            this.forbidden = true;
            // 置空列表
            // Set the method invoker map to null
            this.methodInvokerMap = null;
            // 关闭所有Invoker
            // Close all invokers
            destroyAllInvokers();
        } else {
            // 允许访问
            // Allow to access
            this.forbidden = false;
            // local reference
            Map<String, Invoker<T>> oldUrlInvokerMap = this.urlInvokerMap;
            if (invokerUrls.isEmpty() && this.cachedInvokerUrls != null) {
                // 添加缓存 url 到 invokerUrls 中
                invokerUrls.addAll(this.cachedInvokerUrls);
            } else {
                this.cachedInvokerUrls = new HashSet<URL>();
                //Cached invoker urls, convenient for comparison
                //缓存invokerUrls列表，便于交叉对比
                this.cachedInvokerUrls.addAll(invokerUrls);
            }
            if (invokerUrls.isEmpty()) {
                return;
            }
            // Translate url list to Invoker map
            //会重新走一遍服务的引用过程
            //给每个提供者创建一个Invoker
            Map<String, Invoker<T>> newUrlInvokerMap = toInvokers(invokerUrls);
            // Change method name to map Invoker Map
            // 将 newUrlInvokerMap 转成方法名到 Invoker 列表的映射
            Map<String, List<Invoker<T>>> newMethodInvokerMap = toMethodInvokers(newUrlInvokerMap);
            // state change
            // If the calculation is wrong, it is not processed.
            // 转换出错，直接打印异常，并返回
            if (newUrlInvokerMap == null || newUrlInvokerMap.size() == 0) {
                logger.error(new IllegalStateException("urls to invokers error .invokerUrls.size :" + invokerUrls.size() + ", invoker.size :0. urls :" + invokerUrls.toString()));
                return;
            }
            // 合并多个组的 Invoker
            //服务提供者Invoker保存在这个map中
            this.methodInvokerMap = multiGroup ? toMergeMethodInvokerMap(newMethodInvokerMap) : newMethodInvokerMap;
            this.urlInvokerMap = newUrlInvokerMap;
            try {
                // Close the unused Invokerx
                // 销毁无用 Invoker
                destroyUnusedInvokers(oldUrlInvokerMap, newUrlInvokerMap);
            } catch (Exception e) {
                logger.warn("destroyUnusedInvokers error. ", e);
            }
        }
    }

    /**
     * 方法首先是生成 group 到 Invoker 类比的映射关系表，
     * 若关系表中的映射关系数量大于1，表示有多组服务。此时通过集群类合并每组 Invoker，
     * 并将合并结果存储到 groupInvokers 中。之后将方法名与 groupInvokers 存到到 result 中，并返回，整个逻辑结束。
     */
    private Map<String, List<Invoker<T>>> toMergeMethodInvokerMap(Map<String, List<Invoker<T>>> methodMap) {
        Map<String, List<Invoker<T>>> result = new HashMap<String, List<Invoker<T>>>();
        // 遍历入参
        for (Map.Entry<String, List<Invoker<T>>> entry : methodMap.entrySet()) {
            String method = entry.getKey();
            List<Invoker<T>> invokers = entry.getValue();
            // group -> Invoker 列表
            Map<String, List<Invoker<T>>> groupMap = new HashMap<String, List<Invoker<T>>>();
            // 遍历 Invoker 列表
            for (Invoker<T> invoker : invokers) {
                // 获取分组配置
                String group = invoker.getUrl().getParameter(Constants.GROUP_KEY, "");
                List<Invoker<T>> groupInvokers = groupMap.get(group);
                if (groupInvokers == null) {
                    groupInvokers = new ArrayList<Invoker<T>>();
                    // 缓存 <group, List<Invoker>> 到 groupMap 中
                    groupMap.put(group, groupInvokers);
                }
                // 存储 invoker 到 groupInvokers
                groupInvokers.add(invoker);
            }
            if (groupMap.size() == 1) {
                // 如果 groupMap 中仅包含一组键值对，此时直接取出该键值对的值即可
                result.put(method, groupMap.values().iterator().next());

            } else if (groupMap.size() > 1) {
                // groupMap.size() > 1 成立，表示 groupMap 中包含多组键值对，比如：
                // {
                //     "dubbo": [invoker1, invoker2, invoker3, ...],
                //     "hello": [invoker4, invoker5, invoker6, ...]
                // }
                List<Invoker<T>> groupInvokers = new ArrayList<Invoker<T>>();
                for (List<Invoker<T>> groupList : groupMap.values()) {
                    // 通过集群类合并每个分组对应的 Invoker 列表
                    groupInvokers.add(cluster.join(new StaticDirectory<T>(groupList)));
                }
                // 缓存结果
                result.put(method, groupInvokers);
            } else {
                result.put(method, invokers);
            }
        }
        return result;
    }

    /**
     * @param urls
     * @return null : no routers ,do nothing
     * else :routers list
     */
    private List<Router> toRouters(List<URL> urls) {
        List<Router> routers = new ArrayList<Router>();
        if (urls == null || urls.isEmpty()) {
            return routers;
        }
        if (urls != null && !urls.isEmpty()) {
            for (URL url : urls) {
                if (Constants.EMPTY_PROTOCOL.equals(url.getProtocol())) {
                    continue;
                }
                String routerType = url.getParameter(Constants.ROUTER_KEY);
                if (routerType != null && routerType.length() > 0) {
                    url = url.setProtocol(routerType);
                }
                try {
                    Router router = routerFactory.getRouter(url);
                    if (!routers.contains(router))
                        routers.add(router);
                } catch (Throwable t) {
                    logger.error("convert router url to router error, url: " + url, t);
                }
            }
        }
        return routers;
    }

    /**
     * toInvokers 方法一开始会对服务提供者 url 进行检测，若服务消费端的配置不支持服务端的协议，
     * 或服务端 url 协议头为 empty 时，toInvokers 均会忽略服务提供方 url。
     * 必要的检测做完后，紧接着是合并 url，然后访问缓存，尝试获取与 url 对应的 invoker。
     * 如果缓存命中，直接将 Invoker 存入 newUrlInvokerMap 中即可。如果未命中，则需新建 Invoker。
     */
    private Map<String, Invoker<T>> toInvokers(List<URL> urls) {
        Map<String, Invoker<T>> newUrlInvokerMap = new HashMap<String, Invoker<T>>();
        if (urls == null || urls.isEmpty()) {
            return newUrlInvokerMap;
        }
        Set<String> keys = new HashSet<String>();
        //queryMap在构造方法中初始化，消费者Url.parameters中的数据
        String queryProtocols = this.queryMap.get(Constants.PROTOCOL_KEY);
        for (URL providerUrl : urls) {
            // If protocol is configured at the reference side, only the matching protocol is selected

            /*
             * 此时url是dubbo://192.168.110.197:20880/dubbo.common.hello.service.HelloService?anyhost=true&
             * application=dubbo-provider&application.version=1.0&dubbo=2.5.3&environment=product&
             * interface=dubbo.common.hello.service.HelloService&methods=sayHello&organization=china&
             * owner=cheng.xi&pid=5631&side=provider&timestamp=1489367571986
             * 从注册中心获取到的携带提供者信息的url
             * 如果reference（消费者）端配置了protocol，则只选择匹配的protocol
             */
            if (queryProtocols != null && queryProtocols.length() > 0) {
                boolean accept = false;
                String[] acceptProtocols = queryProtocols.split(",");
                // 检测服务提供者协议是否被服务消费者所支持
                for (String acceptProtocol : acceptProtocols) {
                    if (providerUrl.getProtocol().equals(acceptProtocol)) {
                        accept = true;
                        break;
                    }
                }
                if (!accept) {
                    // 若服务消费者协议头不被消费者所支持，则忽略当前 providerUrl
                    continue;
                }
            }
            // 忽略 empty 协议
            if (Constants.EMPTY_PROTOCOL.equals(providerUrl.getProtocol())) {
                continue;
            }
            // 通过 SPI 检测服务端协议是否被消费端支持，不支持则抛出异常
            if (!ExtensionLoader.getExtensionLoader(Protocol.class).hasExtension(providerUrl.getProtocol())) {
                logger.error(new IllegalStateException("Unsupported protocol " + providerUrl.getProtocol() + " in notified url: " + providerUrl + " from registry " + getUrl().getAddress() + " to consumer " + NetUtils.getLocalHost()
                        + ", supported protocol: " + ExtensionLoader.getExtensionLoader(Protocol.class).getSupportedExtensions()));
                continue;
            }
            // 合并 url
            URL url = mergeUrl(providerUrl);

            // The parameter urls are sorted
            // URL参数是排序的
            String key = url.toFullString();
            // Repeated url
            // 重复URL
            if (keys.contains(key)) {
                continue;
            }
            keys.add(key);
            // Cache key is url that does not merge with consumer side parameters, regardless of how the consumer combines parameters, if the server url changes, then refer again
            // 缓存key为没有合并消费端参数的URL，不管消费端如何合并参数，如果服务端URL发生变化，则重新refer
            Map<String, Invoker<T>> localUrlInvokerMap = this.urlInvokerMap; // local reference
            Invoker<T> invoker = localUrlInvokerMap == null ? null : localUrlInvokerMap.get(key);
            // 缓存中没有，重新refer
            if (invoker == null) { // Not in the cache, refer again
                try {
                    boolean enabled = true;
                    if (url.hasParameter(Constants.DISABLED_KEY)) {
                        // 获取 disable 配置，取反，然后赋值给 enable 变量
                        enabled = !url.getParameter(Constants.DISABLED_KEY, false);
                    } else {
                        // 获取 enable 配置，并赋值给 enable 变量
                        enabled = url.getParameter(Constants.ENABLED_KEY, true);
                    }
                    if (enabled) {
                        /*
                         * 先使用DubboProtocol的refer方法，
                         * 这一步会依次调用ProtocolFilterWrapper，ProtocolListenerWrapper，DubboProtocol中的refer方法。
                         * 经过两个Wrapper中，会添加对应的InvokerListener并构建Invoker Filter链，
                         * 在DubboProtocol中会创建一个DubboInvoker对象，该Invoker对象持有服务Class，providerUrl，负责和服务提供端通信的ExchangeClient。
                         * 接着使用得到的Invoker创建一个InvokerDelegate
                         */
                        invoker = new InvokerDelegate<T>(protocol.refer(serviceType, url), url, providerUrl);
                    }
                } catch (Throwable t) {
                    logger.error("Failed to refer invoker for interface:" + serviceType + ",url:(" + url + ")" + t.getMessage(), t);
                }
                if (invoker != null) { // Put new invoker in cache
                    // 缓存 Invoker 实例
                    newUrlInvokerMap.put(key, invoker);
                }
            } else {
                // 将 invoker 存储到 newUrlInvokerMap 中
                newUrlInvokerMap.put(key, invoker);
            }
        }
        keys.clear();
        return newUrlInvokerMap;
    }

    /**
     * Merge url parameters. the order is: override > -D >Consumer > Provider
     *
     * @param providerUrl
     * @return
     */
    private URL mergeUrl(URL providerUrl) {
        providerUrl = ClusterUtils.mergeUrl(providerUrl, queryMap); // Merge the consumer side parameters

        List<Configurator> localConfigurators = this.configurators; // local reference
        if (localConfigurators != null && !localConfigurators.isEmpty()) {
            for (Configurator configurator : localConfigurators) {
                providerUrl = configurator.configure(providerUrl);
            }
        }

        providerUrl = providerUrl.addParameter(Constants.CHECK_KEY, String.valueOf(false)); // Do not check whether the connection is successful or not, always create Invoker!

        // The combination of directoryUrl and override is at the end of notify, which can't be handled here
        this.overrideDirectoryUrl = this.overrideDirectoryUrl.addParametersIfAbsent(providerUrl.getParameters()); // Merge the provider side parameters

        if ((providerUrl.getPath() == null || providerUrl.getPath().length() == 0)
                && "dubbo".equals(providerUrl.getProtocol())) { // Compatible version 1.0
            //fix by tony.chenl DUBBO-44
            String path = directoryUrl.getParameter(Constants.INTERFACE_KEY);
            if (path != null) {
                int i = path.indexOf('/');
                if (i >= 0) {
                    path = path.substring(i + 1);
                }
                i = path.lastIndexOf(':');
                if (i >= 0) {
                    path = path.substring(0, i);
                }
                providerUrl = providerUrl.setPath(path);
            }
        }
        return providerUrl;
    }

    private List<Invoker<T>> route(List<Invoker<T>> invokers, String method) {
        Invocation invocation = new RpcInvocation(method, new Class<?>[0], new Object[0]);
        List<Router> routers = getRouters();
        if (routers != null) {
            for (Router router : routers) {
                // If router's url not null and is not route by runtime,we filter invokers here
                if (router.getUrl() != null && !router.getUrl().getParameter(Constants.RUNTIME_KEY, false)) {
                    invokers = router.route(invokers, getConsumerUrl(), invocation);
                }
            }
        }
        return invokers;
    }

    /**
     * 主要做了三件事情， 第一是对入参进行遍历，然后从 Invoker 的 url 成员变量中获取 methods 参数，
     * 并切分成数组。随后以方法名为键，Invoker 列表为值，将映射关系存储到 newMethodInvokerMap 中。
     * 第二是分别基于类和方法对 Invoker 列表进行路由操作。第三是对 Invoker 列表进行排序，并转成不可变列表。
     * 关于 toMethodInvokers 方法就先分析到这，我们继续向下分析，这次要分析的多组服务的合并逻辑。
     */
    private Map<String, List<Invoker<T>>> toMethodInvokers(Map<String, Invoker<T>> invokersMap) {
        // 方法名 -> Invoker 列表
        Map<String, List<Invoker<T>>> newMethodInvokerMap = new HashMap<String, List<Invoker<T>>>();
        // According to the methods classification declared by the provider URL, the methods is compatible with the registry to execute the filtered methods
        List<Invoker<T>> invokersList = new ArrayList<Invoker<T>>();
        if (invokersMap != null && invokersMap.size() > 0) {
            for (Invoker<T> invoker : invokersMap.values()) {
                // 获取 methods 参数
                String parameter = invoker.getUrl().getParameter(Constants.METHODS_KEY);
                if (parameter != null && parameter.length() > 0) {
                    // 切分 methods 参数值，得到方法名数组
                    String[] methods = Constants.COMMA_SPLIT_PATTERN.split(parameter);
                    if (methods != null && methods.length > 0) {
                        for (String method : methods) {
                            // 方法名不为 *
                            if (method != null && method.length() > 0
                                    && !Constants.ANY_VALUE.equals(method)) {
                                // 根据方法名获取 Invoker 列表
                                List<Invoker<T>> methodInvokers = newMethodInvokerMap.get(method);
                                if (methodInvokers == null) {
                                    methodInvokers = new ArrayList<Invoker<T>>();
                                    newMethodInvokerMap.put(method, methodInvokers);
                                }
                                // 存储 Invoker 到列表中
                                methodInvokers.add(invoker);
                            }
                        }
                    }
                }
                invokersList.add(invoker);
            }
        }
        // 进行服务级别路由，参考 pull request #749
        List<Invoker<T>> newInvokersList = route(invokersList, null);
        // 存储 <*, newInvokersList> 映射关系
        newMethodInvokerMap.put(Constants.ANY_VALUE, newInvokersList);
        if (serviceMethods != null && serviceMethods.length > 0) {
            for (String method : serviceMethods) {
                List<Invoker<T>> methodInvokers = newMethodInvokerMap.get(method);
                if (methodInvokers == null || methodInvokers.isEmpty()) {
                    methodInvokers = newInvokersList;
                }
                // 进行方法级别路由
                newMethodInvokerMap.put(method, route(methodInvokers, method));
            }
        }
        // sort and unmodifiable
        // 排序，转成不可变列表
        for (String method : new HashSet<String>(newMethodInvokerMap.keySet())) {
            List<Invoker<T>> methodInvokers = newMethodInvokerMap.get(method);
            Collections.sort(methodInvokers, InvokerComparator.getComparator());
            newMethodInvokerMap.put(method, Collections.unmodifiableList(methodInvokers));
        }
        return Collections.unmodifiableMap(newMethodInvokerMap);
    }

    /**
     * Close all invokers
     */
    private void destroyAllInvokers() {
        Map<String, Invoker<T>> localUrlInvokerMap = this.urlInvokerMap; // local reference
        if (localUrlInvokerMap != null) {
            for (Invoker<T> invoker : new ArrayList<Invoker<T>>(localUrlInvokerMap.values())) {
                try {
                    invoker.destroy();
                } catch (Throwable t) {
                    logger.warn("Failed to destroy service " + serviceKey + " to provider " + invoker.getUrl(), t);
                }
            }
            localUrlInvokerMap.clear();
        }
        methodInvokerMap = null;
    }

    /**
     * destroyUnusedInvokers 方法的主要逻辑是通过 newUrlInvokerMap 找出待删除 Invoker 对应的 url，
     * 并将 url 存入到 deleted 列表中。然后再遍历 deleted 列表，
     * 并从 oldUrlInvokerMap 中移除相应的 Invoker，销毁之
     */
    private void destroyUnusedInvokers(Map<String, Invoker<T>> oldUrlInvokerMap, Map<String, Invoker<T>> newUrlInvokerMap) {
        if (newUrlInvokerMap == null || newUrlInvokerMap.size() == 0) {
            destroyAllInvokers();
            return;
        }
        // check deleted invoker
        List<String> deleted = null;
        if (oldUrlInvokerMap != null) {
            // 获取新生成的 Invoker 列表
            Collection<Invoker<T>> newInvokers = newUrlInvokerMap.values();
            // 遍历老的 <url, Invoker> 映射表
            for (Map.Entry<String, Invoker<T>> entry : oldUrlInvokerMap.entrySet()) {
                // 检测 newInvokers 中是否包含老的 Invoker
                if (!newInvokers.contains(entry.getValue())) {
                    if (deleted == null) {
                        deleted = new ArrayList<String>();
                    }
                    // 若不包含，则将老的 Invoker 对应的 url 存入 deleted 列表中
                    deleted.add(entry.getKey());
                }
            }
        }

        if (deleted != null) {
            // 遍历 deleted 集合，并到老的 <url, Invoker> 映射关系表查出 Invoker，销毁之
            for (String url : deleted) {
                if (url != null) {
                    // 从 oldUrlInvokerMap 中移除 url 对应的 Invoker
                    Invoker<T> invoker = oldUrlInvokerMap.remove(url);
                    if (invoker != null) {
                        try {
                            // 销毁 Invoker
                            invoker.destroy();
                            if (logger.isDebugEnabled()) {
                                logger.debug("destroy invoker[" + invoker.getUrl() + "] success. ");
                            }
                        } catch (Exception e) {
                            logger.warn("destroy invoker[" + invoker.getUrl() + "] faild. " + e.getMessage(), e);
                        }
                    }
                }
            }
        }
    }

    /**
     * Invoker 列举逻辑
     * 一般情况下，普通的调用可通过方法名获取到对应的 Invoker 列表，泛化调用可通过 * 获取到 Invoker 列表
     */
    @Override
    public List<Invoker<T>> doList(Invocation invocation) {
        // 服务提供者关闭或禁用了服务，此时抛出 No provider 异常
        if (forbidden) {
            // 1. No service provider 2. Service providers are disabled
            throw new RpcException(RpcException.FORBIDDEN_EXCEPTION,
                "No provider available from registry " + getUrl().getAddress() + " for service " + getConsumerUrl().getServiceKey() + " on consumer " +  NetUtils.getLocalHost()
                        + " use dubbo version " + Version.getVersion() + ", please check status of providers(disabled, not registered or in blacklist).");
        }
        List<Invoker<T>> invokers = null;
        // 获取 Invoker 本地缓存
        Map<String, List<Invoker<T>>> localMethodInvokerMap = this.methodInvokerMap; // local reference
        if (localMethodInvokerMap != null && localMethodInvokerMap.size() > 0) {
            // 获取方法名和参数列表
            String methodName = RpcUtils.getMethodName(invocation);
            Object[] args = RpcUtils.getArguments(invocation);
            // 检测参数列表的第一个参数是否为 String 或 enum 类型
            if (args != null && args.length > 0 && args[0] != null
                    && (args[0] instanceof String || args[0].getClass().isEnum())) {
                // 通过 方法名 + 第一个参数名称 查询 Invoker 列表，具体的使用场景暂时没想到
                invokers = localMethodInvokerMap.get(methodName + "." + args[0]); // The routing can be enumerated according to the first parameter
            }
            if (invokers == null) {
                // 通过方法名获取 Invoker 列表
                invokers = localMethodInvokerMap.get(methodName);
            }
            if (invokers == null) {
                // 通过 * 获取 Invoker 列表
                invokers = localMethodInvokerMap.get(Constants.ANY_VALUE);
            }
            // 冗余逻辑，pull request #2861 移除了下面的 if 分支代码
            if (invokers == null) {
                Iterator<List<Invoker<T>>> iterator = localMethodInvokerMap.values().iterator();
                if (iterator.hasNext()) {
                    invokers = iterator.next();
                }
            }
        }
        // 返回 Invoker 列表
        return invokers == null ? new ArrayList<Invoker<T>>(0) : invokers;
    }

    @Override
    public Class<T> getInterface() {
        return serviceType;
    }

    @Override
    public URL getUrl() {
        return this.overrideDirectoryUrl;
    }

    public URL getRegisteredConsumerUrl() {
        return registeredConsumerUrl;
    }

    public void setRegisteredConsumerUrl(URL registeredConsumerUrl) {
        this.registeredConsumerUrl = registeredConsumerUrl;
    }

    @Override
    public boolean isAvailable() {
        if (isDestroyed()) {
            return false;
        }
        Map<String, Invoker<T>> localUrlInvokerMap = urlInvokerMap;
        if (localUrlInvokerMap != null && localUrlInvokerMap.size() > 0) {
            for (Invoker<T> invoker : new ArrayList<Invoker<T>>(localUrlInvokerMap.values())) {
                if (invoker.isAvailable()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Haomin: added for test purpose
     */
    public Map<String, Invoker<T>> getUrlInvokerMap() {
        return urlInvokerMap;
    }

    /**
     * Haomin: added for test purpose
     */
    public Map<String, List<Invoker<T>>> getMethodInvokerMap() {
        return methodInvokerMap;
    }

    private static class InvokerComparator implements Comparator<Invoker<?>> {

        private static final InvokerComparator comparator = new InvokerComparator();

        private InvokerComparator() {
        }

        public static InvokerComparator getComparator() {
            return comparator;
        }

        @Override
        public int compare(Invoker<?> o1, Invoker<?> o2) {
            return o1.getUrl().toString().compareTo(o2.getUrl().toString());
        }

    }

    /**
     * The delegate class, which is mainly used to store the URL address sent by the registry,and can be reassembled on the basis of providerURL queryMap overrideMap for re-refer.
     *
     * @param <T>
     */
    private static class InvokerDelegate<T> extends InvokerWrapper<T> {
        /**
         * 提供者url
         */
        private URL providerUrl;

        public InvokerDelegate(Invoker<T> invoker, URL url, URL providerUrl) {
            super(invoker, url);
            this.providerUrl = providerUrl;
        }

        public URL getProviderUrl() {
            return providerUrl;
        }
    }
}
