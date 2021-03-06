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
package com.alibaba.dubbo.registry.support;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.registry.NotifyListener;
import com.alibaba.dubbo.registry.Registry;
import com.alibaba.dubbo.registry.integration.RegistryDirectory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AbstractRegistry. (SPI, Prototype, ThreadSafe)
 *
 */
public abstract class AbstractRegistry implements Registry {

    // URL address separator, used in file cache, service provider URL separation
    /**
     * URL的地址分隔符，在缓存文件中使用，服务提供者的URL分隔
     */
    private static final char URL_SEPARATOR = ' ';
    // URL address separated regular expression for parsing the service provider URL list in the file cache
    /**
     * URL地址分隔正则表达式，用于解析文件缓存中服务提供者URL列表
     * 匹配任意空白字符
     */
    private static final String URL_SPLIT = "\\s+";
    // Log output
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    // Local disk cache, where the special key value.registies records the list of registry centers, and the others are the list of notified service providers
    // 本地磁盘缓存，有一个特殊的key值为registies，记录的是注册中心列表，其他记录的都是服务提供者列表
    /**
     * 保存了所有服务提供者的URL，使用URL#getServiceKey作为key，提供者列表、路由规则列表、配置规则列表等作为value。
     * 由于value是列表，当存在多个的时候使用空格隔开。还有一个特殊的key.registies，保存的注册中心的地址。
     * 如果应用在启动过程中，注册中心无法连接或者宕机，dubbo会自动通过本地缓存加载Invokers。
     *
     *
     * 消费者从注册中心订阅节点失败后则从本地缓存中获取
     * @see FailbackRegistry#subscribe(com.alibaba.dubbo.common.URL, com.alibaba.dubbo.registry.NotifyListener)
     * @see AbstractRegistry#getCacheUrls(com.alibaba.dubbo.common.URL)
     *
     * e.q.
     * key:com.alibaba.dubbo.demo.DemoService:1.0_local
     * value: empty://192.168.0.107/com.alibaba.dubbo.demo.DemoService?application=demo-consumer&category=configurators&check=false&dubbo=2.0.2&interface=com.alibaba.dubbo.demo.DemoService&methods=sayHello&pid=1999&qos.port=33333&retries=0&revision=1.0_local&sayHello.async=true&side=consumer&timeout=4000000&timestamp=1589122785691&version=1.0_local empty://192.168.0.107/com.alibaba.dubbo.demo.DemoService?application=demo-consumer&category=routers&check=false&dubbo=2.0.2&interface=com.alibaba.dubbo.demo.DemoService&methods=sayHello&pid=1999&qos.port=33333&retries=0&revision=1.0_local&sayHello.async=true&side=consumer&timeout=4000000&timestamp=1589122785691&version=1.0_local dubbo://192.168.0.107:20880/com.alibaba.dubbo.demo.DemoService?anyhost=true&application=demo-provider&bean.name=com.alibaba.dubbo.demo.DemoService&dubbo=2.0.2&generic=false&interface=com.alibaba.dubbo.demo.DemoService&methods=sayHello&pid=1931&revision=1.0_local&side=provider&timeout=30000&timestamp=1589120898309&version=1.0_local empty://192.168.0.107/com.alibaba.dubbo.demo.DemoService?application=demo-consumer&category=providers,configurators,routers&check=false&dubbo=2.0.2&interface=com.alibaba.dubbo.demo.DemoService&methods=sayHello&pid=1999&qos.port=33333&retries=0&revision=1.0_local&sayHello.async=true&side=consumer&timeout=4000000&timestamp=1589122785691&version=1.0_local
     * 空格分隔后就是
     * empty://192.168.0.107/com.alibaba.dubbo.demo.DemoService?application=demo-consumer&category=configurators&check=false&dubbo=2.0.2&interface=com.alibaba.dubbo.demo.DemoService&methods=sayHello&pid=1999&qos.port=33333&retries=0&revision=1.0_local&sayHello.async=true&side=consumer&timeout=4000000&timestamp=1589122785691&version=1.0_local
     * empty://192.168.0.107/com.alibaba.dubbo.demo.DemoService?application=demo-consumer&category=routers&check=false&dubbo=2.0.2&interface=com.alibaba.dubbo.demo.DemoService&methods=sayHello&pid=1999&qos.port=33333&retries=0&revision=1.0_local&sayHello.async=true&side=consumer&timeout=4000000&timestamp=1589122785691&version=1.0_local
     * dubbo://192.168.0.107:20880/com.alibaba.dubbo.demo.DemoService?anyhost=true&application=demo-provider&bean.name=com.alibaba.dubbo.demo.DemoService&dubbo=2.0.2&generic=false&interface=com.alibaba.dubbo.demo.DemoService&methods=sayHello&pid=1931&revision=1.0_local&side=provider&timeout=30000&timestamp=1589120898309&version=1.0_local
     * empty://192.168.0.107/com.alibaba.dubbo.demo.DemoService?application=demo-consumer&category=providers,configurators,routers&check=false&dubbo=2.0.2&interface=com.alibaba.dubbo.demo.DemoService&methods=sayHello&pid=1999&qos.port=33333&retries=0&revision=1.0_local&sayHello.async=true&side=consumer&timeout=4000000&timestamp=1589122785691&version=1.0_local
     * 即缓存的是当前key 在注册中心订阅的所有节点的信息
     * @see AbstractRegistry#loadProperties()
     * 当创建注册中心实例时会从本地加载持久化的注册信息至properties中
     */
    private final Properties properties = new Properties();
    // File cache timing writing
    /**
     * 缓存写入执行器
     */
    private final ExecutorService registryCacheExecutor = Executors.newFixedThreadPool(1, new NamedThreadFactory("DubboSaveRegistryCache", true));
    // Is it synchronized to save the file
    /**
     * 是否同步保存文件标志
     */
    private final boolean syncSaveFile;
    //数据版本号
    /**
     * 因为每次写入file都是全部覆盖的写入，不是增量的去写入到文件，所以需要有这个版本号来避免老版本覆盖新版本。
     */
    private final AtomicLong lastCacheChanged = new AtomicLong();

    /**
     * 已注册 URL 集合
     * 注册的 URL 不仅仅可以是服务提供者的，也可以是服务消费者的
     */
    private final Set<URL> registered = new ConcurrentHashSet<URL>();
    /**
     * 订阅URL的监听器集合
     *
     * key为消费者Url，value:{@link RegistryDirectory}
     */
    private final ConcurrentMap<URL, Set<NotifyListener>> subscribed = new ConcurrentHashMap<URL, Set<NotifyListener>>();
    /**
     * 内存中的服务缓存对象
     * key 消费者或者提供者的URL，例如消费者订阅量providers、consumers、routes、configurators四个节点，提供者订阅量configurators一个节点
     * 内层map key是分类，包含providers、consumers、routes、configurators四种
     * 内层map value则是对应的服务列表，对于没有服务提供者提供服务的URL，它会以特殊的empty://前缀开头
     */
    /**
     * 跟properties的区别，第一是数据来源不是文件，而是从注册中心中读取，第二个是notified根据分类把同一类的值做了聚合。
     * @see AbstractRegistry#saveProperties(com.alibaba.dubbo.common.URL)
     * 中将notified中的数据保存至properties中随后持久化至本地文件中
     */
    private final ConcurrentMap<URL, Map<String, List<URL>>> notified = new ConcurrentHashMap<URL, Map<String, List<URL>>>();
    /**
     * 注册中心 URL
     */
    private URL registryUrl;
    // Local disk cache file
    /**
     * 磁盘文件服务缓存对象
     * 本地磁盘缓存文件，缓存注册中心的数据
     */
    private File file;

    /**
     * 在创建ZookeeperRegistry实例时会先来这儿
     * @param url
     */
    public AbstractRegistry(URL url) {
        // 把url放到registryUrl中
        setUrl(url);
        // Start file save timer
        // 从url中读取是否同步保存文件的配置，如果没有值默认用异步保存文件
        syncSaveFile = url.getParameter(Constants.REGISTRY_FILESAVE_SYNC_KEY, false);
        // 获得file路径
        //优先选择URL上的配置，如果没有相关的配置，再选用默认配置
        //例如本机mac 保存的文件为：
        // /Users/hzf/.dubbo/dubbo-registry-127.0.0.1.cache
        String filename = url.getParameter(Constants.FILE_KEY, System.getProperty("user.home") + "/.dubbo/dubbo-registry-" + url.getParameter(Constants.APPLICATION_KEY) + "-" + url.getAddress() + ".cache");
        File file = null;
        if (ConfigUtils.isNotEmpty(filename)) {
            //创建文件
            file = new File(filename);
            if (!file.exists() && file.getParentFile() != null && !file.getParentFile().exists()) {
                if (!file.getParentFile().mkdirs()) {
                    throw new IllegalArgumentException("Invalid registry store file " + file + ", cause: Failed to create directory " + file.getParentFile() + "!");
                }
            }
        }
        this.file = file;
        // 把文件里面的数据写入properties
        loadProperties();
        // 通知监听器，URL 变化结果
        // 获取backup url,例如注册中心的地址为 zookeeper://127.0.0.1:2181?backup=127.0.0.1:2182
        notify(url.getBackupUrls());
    }

    /**
     * 就是判断url集合是否为空，如果为空，则把url中key为empty的值加入到集合。
     * 该方法只有在notify方法中用到，为了防止通知的URL变化结果为空
     */
    protected static List<URL> filterEmpty(URL url, List<URL> urls) {
        if (urls == null || urls.isEmpty()) {
            List<URL> result = new ArrayList<URL>(1);
            result.add(url.setProtocol(Constants.EMPTY_PROTOCOL));
            return result;
        }
        return urls;
    }

    @Override
    public URL getUrl() {
        return registryUrl;
    }

    protected void setUrl(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("registry url == null");
        }
        this.registryUrl = url;
    }

    public Set<URL> getRegistered() {
        return registered;
    }

    public Map<URL, Set<NotifyListener>> getSubscribed() {
        return subscribed;
    }

    public Map<URL, Map<String, List<URL>>> getNotified() {
        return notified;
    }

    public File getCacheFile() {
        return file;
    }

    public Properties getCacheProperties() {
        return properties;
    }

    public AtomicLong getLastCacheChanged() {
        return lastCacheChanged;
    }

    /**
     * 该方法主要是将内存缓存properties中的数据存储到文件中，并且在里面做了版本号的控制，防止老的版本数据覆盖了新版本数据。
     * 数据流向是跟loadProperties方法相反。
     */
    public void doSaveProperties(long version) {
        if (version < lastCacheChanged.get()) {
            return;
        }
        if (file == null) {
            return;
        }
        // Save
        try {
            File lockfile = new File(file.getAbsolutePath() + ".lock");
            if (!lockfile.exists()) {
                lockfile.createNewFile();
            }
            RandomAccessFile raf = new RandomAccessFile(lockfile, "rw");
            try {
                FileChannel channel = raf.getChannel();
                try {
                    FileLock lock = channel.tryLock();
                    if (lock == null) {
                        throw new IOException("Can not lock the registry cache file " + file.getAbsolutePath() + ", ignore and retry later, maybe multi java process use the file, please config: dubbo.registry.file=xxx.properties");
                    }
                    // Save
                    try {
                        if (!file.exists()) {
                            file.createNewFile();
                        }
                        FileOutputStream outputFile = new FileOutputStream(file);
                        try {
                            properties.store(outputFile, "Dubbo Registry Cache");
                        } finally {
                            outputFile.close();
                        }
                    } finally {
                        lock.release();
                    }
                } finally {
                    channel.close();
                }
            } finally {
                raf.close();
            }
        } catch (Throwable e) {
            if (version < lastCacheChanged.get()) {
                return;
            } else {
                registryCacheExecutor.execute(new SaveProperties(lastCacheChanged.incrementAndGet()));
            }
            logger.warn("Failed to save registry store file, cause: " + e.getMessage(), e);
        }
    }

    private void loadProperties() {
        if (file != null && file.exists()) {
            InputStream in = null;
            try {
                //读取磁盘上的文件
                in = new FileInputStream(file);
                //把持久化的注册信息加载到properties对象里
                properties.load(in);
                if (logger.isInfoEnabled()) {
                    logger.info("Load registry store file " + file + ", data: " + properties);
                }
            } catch (Throwable e) {
                logger.warn("Failed to load registry store file " + file, e);
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
            }
        }
    }

    public List<URL> getCacheUrls(URL url) {
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            // key为某个分类，例如服务提供者分类
            String key = (String) entry.getKey();
            // value为某个分类的列表，例如服务提供者列表
            String value = (String) entry.getValue();
            if (key != null && key.length() > 0 && key.equals(url.getServiceKey())
                    && (Character.isLetter(key.charAt(0)) || key.charAt(0) == '_')
                    && value != null && value.length() > 0) {
                //分割出列表的每个值，然后创建URl实例
                String[] arr = value.trim().split(URL_SPLIT);
                List<URL> urls = new ArrayList<URL>();
                for (String u : arr) {
                    urls.add(URL.valueOf(u));
                }
                return urls;
            }
        }
        return null;
    }

    /**
     * 该方法是实现了RegistryService接口的方法，作用是获得消费者url订阅的服务URL列表
     * URL可能是消费者URL，也可能是注册在注册中心的服务URL，我在注释中在URL加了修饰，为了能更明白的区分。
     * 订阅了的服务URL一定是在注册中心中注册了的。
     * 关于订阅服务subscribe方法和通知监听器NotifyListener，会在下面解释。
     */
    @Override
    public List<URL> lookup(URL url) {
        List<URL> result = new ArrayList<URL>();
        // 获得该消费者url订阅的 所有被通知的 服务URL集合
        Map<String, List<URL>> notifiedUrls = getNotified().get(url);
        // 判断该消费者是否订阅服务
        if (notifiedUrls != null && notifiedUrls.size() > 0) {
            for (List<URL> urls : notifiedUrls.values()) {
                for (URL u : urls) {
                    // 判断协议是否为空
                    if (!Constants.EMPTY_PROTOCOL.equals(u.getProtocol())) {
                        // 添加 该消费者订阅的服务URL
                        result.add(u);
                    }
                }
            }
        } else {
            // 原子类 避免在获取注册在注册中心的服务url时能够保证是最新的url集合
            final AtomicReference<List<URL>> reference = new AtomicReference<List<URL>>();
            // 通知监听器。当收到服务变更通知时触发
            NotifyListener listener = new NotifyListener() {
                @Override
                public void notify(List<URL> urls) {
                    reference.set(urls);
                }
            };
            // 订阅服务，就是消费者url订阅已经 注册在注册中心的服务（也就是添加该服务的监听器）
            subscribe(url, listener); // Subscribe logic guarantees the first notify to return
            List<URL> urls = reference.get();
            if (urls != null && !urls.isEmpty()) {
                for (URL u : urls) {
                    if (!Constants.EMPTY_PROTOCOL.equals(u.getProtocol())) {
                        result.add(u);
                    }
                }
            }
        }
        return result;
    }

    @Override
    public void register(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("register url == null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("Register: " + url);
        }
        registered.add(url);
    }

    @Override
    public void unregister(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("unregister url == null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("Unregister: " + url);
        }
        registered.remove(url);
    }

    /**
     * 订阅
     * 此时url为consumer://192.168.1.100/dubbo.common.hello.service.HelloService?application=dubbo-consumer&
     * category=providers,configurators,routers&dubbo=2.5.3&interface=dubbo.common.hello.service.HelloService&methods=
     * sayHello&pid=28819&side=consumer&timeout=100000&timestamp=1489332839677
     *
     * listener为 RegistryDirectory
     * com.alibaba.dubbo.registry.integration.RegistryProtocol#doRefer(com.alibaba.dubbo.rpc.cluster.Cluster, com.alibaba.dubbo.registry.Registry, java.lang.Class, com.alibaba.dubbo.common.URL)
     * 中初始化RegistryDirectory
     */
    @Override
    public void subscribe(URL url, NotifyListener listener) {
        if (url == null) {
            throw new IllegalArgumentException("subscribe url == null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("subscribe listener == null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("Subscribe: " + url);
        }
        // 获得该消费者url 已经订阅的服务 的监听器集合
        Set<NotifyListener> listeners = subscribed.get(url);
        //没有监听器，就创建，并添加进去
        if (listeners == null) {
            subscribed.putIfAbsent(url, new ConcurrentHashSet<NotifyListener>());
            listeners = subscribed.get(url);
        }
        // 添加某个服务的监听器
        listeners.add(listener);
    }

    /**
     * 取消订阅
     */
    @Override
    public void unsubscribe(URL url, NotifyListener listener) {
        if (url == null) {
            throw new IllegalArgumentException("unsubscribe url == null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("unsubscribe listener == null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("Unsubscribe: " + url);
        }
        Set<NotifyListener> listeners = subscribed.get(url);
        if (listeners != null) {
            listeners.remove(listener);
        }
    }

    /**
     * 恢复方法，在注册中心断开，重连成功的时候，会恢复注册和订阅。
     */
    protected void recover() throws Exception {
        // register
        //把内存缓存中的registered取出来遍历进行注册
        Set<URL> recoverRegistered = new HashSet<URL>(getRegistered());
        if (!recoverRegistered.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Recover register url " + recoverRegistered);
            }
            for (URL url : recoverRegistered) {
                //调用子类的register方法
                register(url);
            }
        }
        // subscribe
        //把内存缓存中的subscribed取出来遍历进行订阅
        Map<URL, Set<NotifyListener>> recoverSubscribed = new HashMap<URL, Set<NotifyListener>>(getSubscribed());
        if (!recoverSubscribed.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Recover subscribe url " + recoverSubscribed.keySet());
            }
            for (Map.Entry<URL, Set<NotifyListener>> entry : recoverSubscribed.entrySet()) {
                URL url = entry.getKey();
                for (NotifyListener listener : entry.getValue()) {
                    //调用子类的subscribe方法
                    subscribe(url, listener);
                }
            }
        }
    }

    /**
     * 当创建注册中心实例时调用此方法，
     * @param urls 注册中心URL集合
     *
     *
     */
    protected void notify(List<URL> urls) {
        if (urls == null || urls.isEmpty()) return;

        // 遍历订阅URL的监听器集合，通知他们
        //getSubscribed()方法获取订阅者列表
        //订阅者Entry里每个URL都对应着n个NotifyListener
        for (Map.Entry<URL, Set<NotifyListener>> entry : getSubscribed().entrySet()) {
            URL url = entry.getKey();

            // 判断是否匹配
            if (!UrlUtils.isMatch(url, urls.get(0))) {
                continue;
            }

            // 遍历监听器集合，通知他们
            Set<NotifyListener> listeners = entry.getValue();
            if (listeners != null) {
                for (NotifyListener listener : listeners) {
                    try {
                        //调用子类的notify方法
                        notify(url, listener, filterEmpty(url, urls));
                    } catch (Throwable t) {
                        logger.error("Failed to notify registry event, urls: " + urls + ", cause: " + t.getMessage(), t);
                    }
                }
            }
        }
    }

    /**
     * 这个方法不会直接触发，被FailbackRegistry重载
     * AilbackRegistry增加failback逻辑后，还是会调用这个方法
     *
     * notify方法是通知监听器，url的变化结果，不过变化的是全量数据，
     * 全量数据意思就是是以服务接口和数据类型为维度全量通知，
     * 即不会通知一个服务的同类型的部分数据，用户不需要对比上一次通知结果。
     *
     * 1、发起订阅后，会获取全量数据，此时会调用notify方法。即Registry 获取到了全量数据
     * 2、每次注册中心发生变更时会调用notify方法虽然变化是增量，调用这个方法的调用方，已经进行处理，传入的urls依然是全量的。
     * 3、listener.notify，通知监听器，例如，有新的服务提供者启动时，被通知，创建新的 Invoker 对象。
     * @param url 之前往zk中注册的URL，可以是消费者也可以是提供者
     * @param listener 当url订阅的子节点变化是所通知的监听器
     * @param urls  url订阅的所有的子节点的对应的url
     */
    protected void notify(URL url, NotifyListener listener, List<URL> urls) {
        if (url == null) {
            throw new IllegalArgumentException("notify url == null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("notify listener == null");
        }
        if ((urls == null || urls.isEmpty())
                && !Constants.ANY_VALUE.equals(url.getServiceInterface())) {
            logger.warn("Ignore empty notify urls for subscribe url " + url);
            return;
        }
        if (logger.isInfoEnabled()) {
            logger.info("Notify urls for subscribe url " + url + ", urls: " + urls);
        }
        //key存储的是category，例如providers，consumers，routersconfigurators
        //value 是对应的URL
        Map<String, List<URL>> result = new HashMap<String, List<URL>>();
        //根据url的category进行分类
        for (URL u : urls) {
            //不同类型的数据分开通知，providers，consumers，routers，overrides
            //允许只通知其中一种类型，但该类型的数据必须是全量的，不是增量的。
            if (UrlUtils.isMatch(url, u)) {
                // 按照url中key为category对应的值进行分类
                //providers、consumers、routers、configurators
                String category = u.getParameter(Constants.CATEGORY_KEY, Constants.DEFAULT_CATEGORY);
                List<URL> categoryList = result.get(category);
                if (categoryList == null) {
                    categoryList = new ArrayList<URL>();
                    result.put(category, categoryList);
                }
                categoryList.add(u);
            }
        }
        if (result.size() == 0) {
            return;
        }
        //下面操作notified缓存
        // 获得某一个消费者被通知的url集合（通知的 URL 变化结果）
        Map<String, List<URL>> categoryNotified = notified.get(url);
        if (categoryNotified == null) {
            // 添加该消费者对应的url
            notified.putIfAbsent(url, new ConcurrentHashMap<String, List<URL>>());
            categoryNotified = notified.get(url);
        }
        // 处理通知监听器URL 变化结果
        for (Map.Entry<String, List<URL>> entry : result.entrySet()) {
            String category = entry.getKey();
            List<URL> categoryList = entry.getValue();
            //对notified内容进行覆盖，相当于会保存上一次的通知
            // 把分类标识和分类后的列表放入notified的value中
            // 覆盖到 `notified`
            // 当某个分类的数据为空时，会依然有 urls 。其中 `urls[0].protocol = empty` ，
            // 通过这样的方式，处理所有服务提供者为空的情况。
            categoryNotified.put(category, categoryList);
            //每次通知后会刷新本地缓存
            saveProperties(url);
            //进行listener回调，每种category的url分别回调一次
            //这里的listener是RegistryDirectory
            listener.notify(categoryList);
        }
    }

    /**
     * 缓存的保存与更新
     * 该方法是单个消费者url对应在notified中的数据，保存在到文件，而保存到文件的操作是调用了doSaveProperties方法，
     * 该方法跟doSaveProperties的区别是doSaveProperties方法将properties数据全部覆盖性的保存到文件，
     * 而saveProperties只是保存单个消费者url的数据到properties中。
     */
    private void saveProperties(URL url) {
        if (file == null) {
            return;
        }

        try {
            // 拼接url
            StringBuilder buf = new StringBuilder();
            Map<String, List<URL>> categoryNotified = notified.get(url);
            if (categoryNotified != null) {
                for (List<URL> us : categoryNotified.values()) {
                    for (URL u : us) {
                        if (buf.length() > 0) {
                            buf.append(URL_SEPARATOR);
                        }
                        buf.append(u.toFullString());
                    }
                }
            }
            properties.setProperty(url.getServiceKey(), buf.toString());
            // 增加版本号
            long version = lastCacheChanged.incrementAndGet();
            if (syncSaveFile) {
                //同步保存
                doSaveProperties(version);
            } else {
                //异步保存，放入线程池，会传入一个AtomicLong的版本号，保证数据是最新的
                registryCacheExecutor.execute(new SaveProperties(version));
            }
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    /**
     * 该方法在JVM关闭时调用，进行取消注册和订阅的操作。
     * 具体逻辑就是调用了unregister和unsubscribe方法。
     * @see com.alibaba.dubbo.config.DubboShutdownHook#destroyAll()
     */
    @Override
    public void destroy() {
        if (logger.isInfoEnabled()) {
            logger.info("Destroy registry:" + getUrl());
        }
        Set<URL> destroyRegistered = new HashSet<URL>(getRegistered());
        if (!destroyRegistered.isEmpty()) {
            for (URL url : new HashSet<URL>(getRegistered())) {
                if (url.getParameter(Constants.DYNAMIC_KEY, true)) {
                    try {
                        unregister(url);
                        if (logger.isInfoEnabled()) {
                            logger.info("Destroy unregister url " + url);
                        }
                    } catch (Throwable t) {
                        logger.warn("Failed to unregister url " + url + " to registry " + getUrl() + " on destroy, cause: " + t.getMessage(), t);
                    }
                }
            }
        }
        Map<URL, Set<NotifyListener>> destroySubscribed = new HashMap<URL, Set<NotifyListener>>(getSubscribed());
        if (!destroySubscribed.isEmpty()) {
            for (Map.Entry<URL, Set<NotifyListener>> entry : destroySubscribed.entrySet()) {
                URL url = entry.getKey();
                for (NotifyListener listener : entry.getValue()) {
                    try {
                        unsubscribe(url, listener);
                        if (logger.isInfoEnabled()) {
                            logger.info("Destroy unsubscribe url " + url);
                        }
                    } catch (Throwable t) {
                        logger.warn("Failed to unsubscribe url " + url + " to registry " + getUrl() + " on destroy, cause: " + t.getMessage(), t);
                    }
                }
            }
        }
    }

    @Override
    public String toString() {
        return getUrl().toString();
    }

    private class SaveProperties implements Runnable {
        private long version;

        private SaveProperties(long version) {
            this.version = version;
        }

        @Override
        public void run() {
            doSaveProperties(version);
        }
    }

}
