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
package com.alibaba.dubbo.registry.zookeeper;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.registry.NotifyListener;
import com.alibaba.dubbo.registry.support.FailbackRegistry;
import com.alibaba.dubbo.remoting.zookeeper.ChildListener;
import com.alibaba.dubbo.remoting.zookeeper.StateListener;
import com.alibaba.dubbo.remoting.zookeeper.ZookeeperClient;
import com.alibaba.dubbo.remoting.zookeeper.ZookeeperTransporter;
import com.alibaba.dubbo.rpc.RpcException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * ZookeeperRegistry
 * 该类继承了FailbackRegistry类，该类就是针对注册中心核心的功能注册、订阅、取消注册、取消订阅，查询注册列表进行展开，
 * 基于zookeeper来实现。
 *
 * 因为调用了zookeeper服务组件，很多的逻辑不需要在dubbo中自己去实现
 *
 * 1. dubbo的Root层是根目录，通过<dubbo:registry group="dubbo" />的“group”来设置zookeeper的根节点，缺省值是“dubbo”。
 * 2. Service层是服务接口的全名。 例如:com.alibaba.dubbo.samples.echo.api.EchoService
 * 3. Type层是分类，一共有四种分类，分别是providers（服务提供者列表）、consumers（服务消费者列表）、routes（路由规则列表）、configurations（配置规则列表）。
 * 4. URL层：根据不同的Type目录：可以有服务提供者 URL 、服务消费者 URL 、路由规则 URL 、配置规则 URL 。不同的Type关注的URL不同。
 *
 * 所以比如有个com.alibaba.dubbo.samples.echo.api.EchoService的提供者，且有消费者消费此类
 * 那么zk中会有这样的目录
 * 1./dubbo/com.alibaba.dubbo.samples.echo.api.EchoService/providers
 *   /dubbo是根目录，
 *   /com.alibaba.dubbo.samples.echo.api.EchoService是service层
 *   /providers是type层之一
 *   url层的值为:dubbo%3A%2F%2F192.168.0.105%3A20880%2Fcom.alibaba.dubbo.samples.echo.api.EchoService%3Fanyhost%3Dtrue%26application%3Decho-provider%26dubbo%3D2.0.2%26generic%3Dfalse%26interface%3Dcom.alibaba.dubbo.samples.echo.api.EchoService%26methods%3Decho1%2Cecho%26pid%3D15700%26side%3Dprovider%26timestamp%3D1572966656788
 *   解码后为:dubbo://192.168.0.105:20880/com.alibaba.dubbo.samples.echo.api.EchoService?anyhost=true&application=echo-provider&dubbo=2.0.2&generic=false&interface=com.alibaba.dubbo.samples.echo.api.EchoService&methods=echo1,echo&pid=15700&side=provider&timestamp=1572966656788
 *   可通过此url信息构造出URL对象
 * 2./dubbo/com.alibaba.dubbo.samples.echo.api.EchoService/consumers
 *   同上
 *   url层的值为:consumer%3A%2F%2F192.168.0.105%2Fcom.alibaba.dubbo.samples.echo.api.EchoService%3Fapplication%3Decho-consumer%26category%3Dconsumers%26check%3Dfalse%26dubbo%3D2.0.2%26interface%3Dcom.alibaba.dubbo.samples.echo.api.EchoService%26methods%3Decho1%2Cecho%26pid%3D15717%26side%3Dconsumer%26timestamp%3D1572966892358
 *   解码后为:consumer://192.168.0.105/com.alibaba.dubbo.samples.echo.api.EchoService?application=echo-consumer&category=consumers&check=false&dubbo=2.0.2&interface=com.alibaba.dubbo.samples.echo.api.EchoService&methods=echo&pid=4839&side=consumer&timestamp=1572684882365
 *   可通过此url信息构造出URL对象
 *   @see URL#valueOf(String) 构造URL对象
 */
public class ZookeeperRegistry extends FailbackRegistry {

    /**
     * 日志记录
     */
    private final static Logger logger = LoggerFactory.getLogger(ZookeeperRegistry.class);

    /**
     * 默认的zookeeper端口
     */
    private final static int DEFAULT_ZOOKEEPER_PORT = 2181;

    /**
     * 默认zookeeper根节点
     */
    private final static String DEFAULT_ROOT = "dubbo";

    /**
     * zookeeper根节点
     */
    private final String root;

    /**
     * 服务接口集合
     */
    private final Set<String> anyServices = new ConcurrentHashSet<String>();

    /**
     * 监听器集合
     * key为消费者或者提供者url
     * @see ZookeeperRegistry#doSubscribe(com.alibaba.dubbo.common.URL, com.alibaba.dubbo.registry.NotifyListener)
     *
     */
    private final ConcurrentMap<URL, ConcurrentMap<NotifyListener, ChildListener>> zkListeners = new ConcurrentHashMap<URL, ConcurrentMap<NotifyListener, ChildListener>>();

    /**
     * zookeeper客户端实例
     * CuratorZookeeperClient默认值
     */
    private final ZookeeperClient zkClient;

    /**
     * 1、参数中ZookeeperTransporter是一个接口，并且在dubbo中有ZkclientZookeeperTransporter和CuratorZookeeperTransporter两个实现类，ZookeeperTransporter还是一个可扩展的接口，
     *      基于 Dubbo SPI Adaptive 机制，会根据url中携带的参数去选择用哪个实现类。
     * 2、上面我说明了dubbo在zookeeper节点层级有一层是root层，该层是通过group属性来设置的。
     * 3、给客户端添加一个监听器，当状态为重连的时候调用FailbackRegistry的恢复方法
     */
    public ZookeeperRegistry(URL url, ZookeeperTransporter zookeeperTransporter) {
        super(url);
        if (url.isAnyHost()) {
            throw new IllegalStateException("registry address == null");
        }
        // 获得url携带的分组配置,默认为dubbo，并且作为zookeeper的根节点
        String group = url.getParameter(Constants.GROUP_KEY, DEFAULT_ROOT);
        if (!group.startsWith(Constants.PATH_SEPARATOR)) {
            group = Constants.PATH_SEPARATOR + group;
        }
        //注册到注册中心的节点
        this.root = group;
        // 创建zookeeper client
        //使用zookeeperTansporter去连接
        //ZookeeperTransport这里是生成的自适应实现，默认使用ZkClientZookeeperTransporter
        //ZkClientZookeeperTransporter的connect去实例化一个ZkClient实例
        //并且订阅状态变化的监听器subscribeStateChanges
        //然后返回一个CuratorZookeeperClient实例（默认值）
        zkClient = zookeeperTransporter.connect(url);
        // 添加状态监听器，当状态为重连的时候调用恢复方法
        //CuratorZookeeperClient添加状态改变监听器
        zkClient.addStateListener(new StateListener() {
            @Override
            public void stateChanged(int state) {
                if (state == RECONNECTED) {
                    try {
                        // 恢复
                        recover();
                    } catch (Exception e) {
                        logger.error(e.getMessage(), e);
                    }
                }
            }
        });
    }

    /**
     * 该方法是拼接使用默认的zookeeper端口，就是当地址本身没有端口的时候才使用默认端口。
     */
    static String appendDefaultPort(String address) {
        if (address != null && address.length() > 0) {
            int i = address.indexOf(':');
            // 如果地址本身没有端口，则使用默认端口2181
            if (i < 0) {
                return address + ":" + DEFAULT_ZOOKEEPER_PORT;
            } else if (Integer.parseInt(address.substring(i + 1)) == 0) {
                return address.substring(0, i + 1) + DEFAULT_ZOOKEEPER_PORT;
            }
        }
        return address;
    }

    /**
     * isAvailable与两个方法分别是检测zookeeper是否连接以及销毁连接，很简单，都是调用了zookeeper客户端封装好的方法。
     */
    @Override
    public boolean isAvailable() {
        return zkClient.isConnected();
    }

    @Override
    public void destroy() {
        super.destroy();
        try {
            zkClient.close();
        } catch (Exception e) {
            logger.warn("Failed to close zookeeper client " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    protected void doRegister(URL url) {
        try {
            //创建目录源码
            /*
             * 1. 如果是提供者，且提供者类为org.apache.dubbo.demo.DemoService
             * 那么会在zk中创建目录dubbo/org.apache.dubbo.demo.DemoService/providers/
             *
             * toUrlPath(url)获取到的值
             * dubbo/org.apache.dubbo.demo.DemoService/providers/dubbo%3A%2F%2F192.168.0.105%3A20880%2Forg.apache.dubbo.demo.DemoService%3Fanyhost%3Dtrue%26application%3Decho-provider%26dubbo%3D2.0.2%26generic%3Dfalse%26interface%3Dcom.alibaba.dubbo.samples.echo.api.EchoService%26methods%3Decho1%2Cecho%26pid%3D15700%26side%3Dprovider%26timestamp%3D1572966656788
             *
             * 2. 如果是消费者，且消费类为org.apache.dubbo.demo.DemoService
             * 那么会在zk中创建目录dubbo/org.apache.dubbo.demo.DemoService/consumers/
             *
             * // 创建URL节点，也就是URL层的节点
             */
            zkClient.create(toUrlPath(url), url.getParameter(Constants.DYNAMIC_KEY, true));
        } catch (Throwable e) {
            throw new RpcException("Failed to register " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    protected void doUnregister(URL url) {
        try {
            //删除目录
            zkClient.delete(toUrlPath(url));
        } catch (Throwable e) {
            throw new RpcException("Failed to unregister " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * 这个方法是订阅，逻辑实现比较多，可以分两段来看，
     * 这里的实现把所有Service层发起的订阅以及指定的Service层发起的订阅分开处理。
     * 所有Service层类似于监控中心发起的订阅。指定的Service层发起的订阅可以看作是服务消费者的订阅。订阅的大致逻辑类似，不过还是有几个区别：
     * 1.所有Service层发起的订阅中的ChildListener是在在 Service 层发生变更时，才会做出解码，
     *  用anyServices属性判断是否是新增的服务，最后调用父类的subscribe订阅。
     *  而指定的Service层发起的订阅是在URL层发生变更的时候，调用notify，回调NotifyListener的逻辑，做到通知服务变更。
     * 2.所有Service层发起的订阅中客户端创建的节点是Service节点，该节点为持久节点，
     *  而指定的Service层发起的订阅中创建的节点是Type节点，该节点也是持久节点。
     *  这里补充一下zookeeper的持久节点是节点创建后，就一直存在，直到有删除操作来主动清除这个节点，
     *  不会因为创建该节点的客户端会话失效而消失。而临时节点的生命周期和客户端会话绑定。
     *  也就是说，如果客户端会话失效，那么这个节点就会自动被清除掉。注意，这里提到的是会话失效，而非连接断开。
     *  另外，在临时节点下面不能创建子节点。
     * 3.指定的Service层发起的订阅中调用了两次notify，
     *  第一次是增量的通知，也就是只是通知这次增加的服务节点，
     *  而第二个是全量的通知。
     *
     */
    @Override
    protected void doSubscribe(final URL url, final NotifyListener listener) {
        try {
            // 处理所有Service层发起的订阅，例如监控中心的订阅
            if (Constants.ANY_VALUE.equals(url.getServiceInterface())) {
                // 获得根目录
                String root = toRootPath();
                // 获得url对应的监听器集合
                ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
                //listeners为空说明缓存中没有，这里把listeners放入缓存中
                if (listeners == null) {
                    zkListeners.putIfAbsent(url, new ConcurrentHashMap<NotifyListener, ChildListener>());
                    listeners = zkListeners.get(url);
                }
                ChildListener zkListener = listeners.get(listener);
                //zkListener为空说明是第一次创建，新建一个listener
                if (zkListener == null) {
                    //创建一个匿名类。childChanged的实现如下，并不会立即执行，只会在触发变更通知时执行
                    listeners.putIfAbsent(listener, new ChildListener() {
                        @Override
                        public void childChanged(String parentPath, List<String> currentChilds) {
                            //如果子节点有变化则会接受到通知，遍历所有子节点
                            // 遍历现有的节点，如果现有的服务集合中没有该节点，则加入该节点，然后订阅该节点
                            for (String child : currentChilds) {
                                // 解码
                                child = URL.decode(child);
                                if (!anyServices.contains(child)) {
                                    //如果存在子节点还未被订阅，说明是新的节点，则订阅
                                    anyServices.add(child);
                                    //如果consumer的interface为*，会订阅每一个url，会触发另一个分支的逻辑
                                    //这里是用来对/dubbo下面提供者新增时的回调，相当于增量
                                    subscribe(url.setPath(child).addParameters(Constants.INTERFACE_KEY, child,
                                            Constants.CHECK_KEY, String.valueOf(false)), listener);
                                }
                            }
                        }
                    });
                    // 重新获取，为了保证一致性
                    zkListener = listeners.get(listener);
                }
                //创建持久化节点，接下来订阅持久化节点的直接子节点
                zkClient.create(root, false);
                //添加监听器会返回子节点集合
                // 向zookeeper的service节点发起订阅，获得Service接口全名数组
                List<String> services = zkClient.addChildListener(root, zkListener);
                if (services != null && !services.isEmpty()) {
                    //然后遍历所有子节点进行订阅
                    // 遍历Service接口全名数组
                    for (String service : services) {
                        service = URL.decode(service);
                        anyServices.add(service);
                        //增加当前节点的订阅并且会返回该节点下所有子节点列表
                        //如果consumer的interface为*，会订阅每一个url，会触发另一个分支的逻辑
                        //这里的逻辑只执行一次，一次全量
                        // 发起该service层的订阅
                        subscribe(url.setPath(service).addParameters(Constants.INTERFACE_KEY, service,
                                Constants.CHECK_KEY, String.valueOf(false)), listener);
                    }
                }
            } else {
                // 处理指定 Service 层的发起订阅，例如服务消费者的订阅
                List<URL> urls = new ArrayList<URL>();
                // 遍历分类数组
                //比如此时path为 /dubbo/org.apache.dubbo.demo.HzfService/providers

                /*
                 * 如若是消费者订阅那么会创建三个节点
                 * 详见com.alibaba.dubbo.registry.integration.RegistryProtocol.doRefer
                 * /dubbo/dubbo.common.hello.service.HelloService/providers/
                 * /dubbo/dubbo.common.hello.service.HelloService/configurators/
                 * /dubbo/dubbo.common.hello.service.HelloService/routers/
                 *
                 * 三个路径会被消费者端监听，当提供者，配置，路由发生变化之后，
                 * 注册中心会通知消费者刷新本地缓存。
                 */
                for (String path : toCategoriesPath(url)) {
                    // 获得监听器集合
                    ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
                    if (listeners == null) {
                        zkListeners.putIfAbsent(url, new ConcurrentHashMap<NotifyListener, ChildListener>());
                        listeners = zkListeners.get(url);
                    }
                    ChildListener zkListener = listeners.get(listener);
                    if (zkListener == null) {
                        listeners.putIfAbsent(listener, new ChildListener() {
                            // 通知服务变化 回调NotifyListener
                            @Override
                            public void childChanged(String parentPath, List<String> currentChilds) {
                                ZookeeperRegistry.this.notify(url, listener, toUrlsWithEmpty(url, parentPath, currentChilds));
                            }
                        });
                        // 重新获取节点监听器，保证一致性
                        zkListener = listeners.get(listener);
                    }
                    //创建type节点，该节点为持久节点
                    zkClient.create(path, false);
                    // 向zookeeper的path节点发起订阅，并且返回当前节点下的子节点
                    /**
                     *
                     * 此时子节点内容为（URL#encode编码后）：
                     * dubbo%3A%2F%2F172.21.1.40%3A20880%2Fcom.alibaba.dubbo.demo.DemoService%3Fanyhost%3Dtrue%26application%3Ddemo-provider%26bean.name%3Dcom.alibaba.dubbo.demo.DemoService%26dubbo%3D2.0.2%26generic%3Dfalse%26interface%3Dcom.alibaba.dubbo.demo.DemoService%26methods%3DsayHello%26pid%3D24274%26revision%3D1.0_local%26side%3Dprovider%26timestamp%3D1574925853617%26version%3D1.0_local
                     * 如果子节点不存在数据或者存在数据但是不符合此消费者url，那么就返回一个empty://的URL
                     * 且此URL只是将protocol设置为empty且category设置为path对应的值，其它属性都与consumer相同
                     *
                     * children大小就代表有几个子节点
                     * 然后校验当前URL与 zk中的子节点是否匹配，匹配则加入到urls中，
                     *
                     * 打比方当前消费者订阅了三个子节点
                     * dubbo/dubbo.common.hello.service.HelloService/providers/
                     * dubbo/dubbo.common.hello.service.HelloService/configurators/
                     * dubbo/dubbo.common.hello.service.HelloService/routers/
                     *
                     * 若zk中此时存在对应的子节点，则说明存在订阅的数据，那么就会加入到urls中，通知该节点
                     * 若无对应的子节点则返回empty://
                     */
                    List<String> children = zkClient.addChildListener(path, zkListener);
                    if (children != null) {
                        urls.addAll(toUrlsWithEmpty(url, path, children));
                    }
                }
                //如果有子节点，直接进行触发一次，对应AbstractRegsitry的lookup方法
                //意思就是第一次订阅，如果订阅目录存在子节点，直接会触发一次
                //所谓的子节点就是比如/dubbo/dubbo.common.hello.service.HelloService/providers/文件中有数据，
                // 且此提供者匹配此消费(toUrlsWithEmpty(url, path, children))

                //此时的urls就是满足此消费者url的提供者信息
                notify(url, listener, urls);
            }
        } catch (Throwable e) {
            throw new RpcException("Failed to subscribe " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * 该方法是取消订阅，也是分为两种情况，
     * 所有的Service发起的取消订阅还是指定的Service发起的取消订阅。
     * 可以看到所有的Service发起的取消订阅就直接移除了根目录下所有的监听器，
     * 而指定的Service发起的取消订阅是移除了该Service层下面的所有Type节点监听器
     */
    @Override
    protected void doUnsubscribe(URL url, NotifyListener listener) {
        // 获得监听器集合
        ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
        if (listeners != null) {
            // 获得子节点的监听器
            ChildListener zkListener = listeners.get(listener);
            if (zkListener != null) {
                // 如果为全部的服务接口，例如监控中心
                if (Constants.ANY_VALUE.equals(url.getServiceInterface())) {
                    // 获得根目录
                    String root = toRootPath();
                    // 移除监听器
                    zkClient.removeChildListener(root, zkListener);
                } else {
                    // 遍历分类数组进行移除监听器
                    for (String path : toCategoriesPath(url)) {
                        zkClient.removeChildListener(path, zkListener);
                    }
                }
            }
        }
    }

    /**
     * 该方法就是查询符合条件的已经注册的服务。调用了toUrlsWithoutEmpty方法
     */
    @Override
    public List<URL> lookup(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("lookup url == null");
        }
        try {
            List<String> providers = new ArrayList<String>();
            // 遍历分组类别
            for (String path : toCategoriesPath(url)) {
                // 获得子节点
                List<String> children = zkClient.getChildren(path);
                if (children != null) {
                    providers.addAll(children);
                }
            }
            // 获得 providers 中，和 consumer 匹配的 URL 数组
            return toUrlsWithoutEmpty(url, providers);
        } catch (Throwable e) {
            throw new RpcException("Failed to lookup " + url + " from zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    private String toRootDir() {
        if (root.equals(Constants.PATH_SEPARATOR)) {
            return root;
        }
        return root + Constants.PATH_SEPARATOR;
    }

    private String toRootPath() {
        return root;
    }

    /**
     * 该方法是获得服务路径，拼接规则：Root + Type
     */
    private String toServicePath(URL url) {
        String name = url.getServiceInterface();
        // 如果是包括所有服务，则返回根节点
        if (Constants.ANY_VALUE.equals(name)) {
            return toRootPath();
        }
        return toRootDir() + URL.encode(name);
    }

    /**
     * url携带的服务下的所有Type节点数组
     * Root + Service + Type
     */
    private String[] toCategoriesPath(URL url) {
        String[] categories;
        // 如果url携带的分类配置为*，则创建包括所有分类的数组
        if (Constants.ANY_VALUE.equals(url.getParameter(Constants.CATEGORY_KEY))) {
            categories = new String[]{Constants.PROVIDERS_CATEGORY, Constants.CONSUMERS_CATEGORY,
                    Constants.ROUTERS_CATEGORY, Constants.CONFIGURATORS_CATEGORY};
        } else {
            // 返回url携带的分类配置，默认值为providers
            categories = url.getParameter(Constants.CATEGORY_KEY, new String[]{Constants.DEFAULT_CATEGORY});
        }
        String[] paths = new String[categories.length];
        for (int i = 0; i < categories.length; i++) {
            // 加上服务路径
            paths[i] = toServicePath(url) + Constants.PATH_SEPARATOR + categories[i];
        }
        return paths;
    }

    /**
     * Root + Service + Type
     */
    private String toCategoryPath(URL url) {
        return toServicePath(url) + Constants.PATH_SEPARATOR + url.getParameter(Constants.CATEGORY_KEY, Constants.DEFAULT_CATEGORY);
    }

    /**
     * 该方法是获得URL路径，拼接规则是Root + Service + Type + URL
     */
    private String toUrlPath(URL url) {
        return toCategoryPath(url) + Constants.PATH_SEPARATOR + URL.encode(url.toFullString());
    }

    /**
     * 获得 providers 中，和 consumer 匹配的 URL 数组
     * @param consumer url
     * @param providers e.g. dubbo%3A%2F%2F192.168.0.105%3A20880%2Fcom.alibaba.dubbo.samples.echo.api.EchoService%3Fanyhost%3Dtrue%26application%3Decho-provider%26dubbo%3D2.0.2%26generic%3Dfalse%26interface%3Dcom.alibaba.dubbo.samples.echo.api.EchoService%26methods%3Decho%26pid%3D15679%26side%3Dprovider%26timestamp%3D1572966229731
     *                  decode后 dubbo://192.168.0.105:20880/com.alibaba.dubbo.samples.echo.api.EchoService?anyhost=true&application=echo-provider&dubbo=2.0.2&generic=false&interface=com.alibaba.dubbo.samples.echo.api.EchoService&methods=echo&pid=4835&side=provider&timestamp=1572684815739
     * @return
     */
    private List<URL> toUrlsWithoutEmpty(URL consumer, List<String> providers) {
        List<URL> urls = new ArrayList<URL>();
        if (providers != null && !providers.isEmpty()) {
            // 遍历服务提供者
            for (String provider : providers) {
                // 解码
                provider = URL.decode(provider);
                if (provider.contains("://")) {
                    // 把服务转化成url的形式
                    URL url = URL.valueOf(provider);
                    // 判断是否匹配，如果匹配， 则加入到集合中
                    if (UrlUtils.isMatch(consumer, url)) {
                        urls.add(url);
                    }
                }
            }
        }
        return urls;
    }

    /**
     * toUrlsWithEmpty方法是调用了第一个方法后增加了若不存在匹配，
     *  则创建 empty:// 的 URL返回。通过这样的方式，可以处理类似服务提供者为空的情况。
     *
     * @param consumer url
     * @param path e.g. /dubbo/com.alibaba.dubbo.samples.echo.api.EchoService/providers
     * @param providers e.g. dubbo%3A%2F%2F192.168.0.105%3A20880%2Fcom.alibaba.dubbo.samples.echo.api.EchoService%3Fanyhost%3Dtrue%26application%3Decho-provider%26dubbo%3D2.0.2%26generic%3Dfalse%26interface%3Dcom.alibaba.dubbo.samples.echo.api.EchoService%26methods%3Decho%26pid%3D15679%26side%3Dprovider%26timestamp%3D1572966229731
     * @return
     */
    private List<URL> toUrlsWithEmpty(URL consumer, String path, List<String> providers) {
        // 返回和服务消费者匹配的服务提供者url
        List<URL> urls = toUrlsWithoutEmpty(consumer, providers);
        // 如果不存在，则创建`empty://` 的 URL返回
        if (urls == null || urls.isEmpty()) {
            int i = path.lastIndexOf('/');
            String category = i < 0 ? path : path.substring(i + 1);
            URL empty = consumer.setProtocol(Constants.EMPTY_PROTOCOL).addParameter(Constants.CATEGORY_KEY, category);
            urls.add(empty);
        }
        return urls;
    }

}
