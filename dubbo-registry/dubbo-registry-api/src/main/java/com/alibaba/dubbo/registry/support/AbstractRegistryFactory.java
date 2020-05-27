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
import com.alibaba.dubbo.registry.Registry;
import com.alibaba.dubbo.registry.RegistryFactory;
import com.alibaba.dubbo.registry.RegistryService;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * AbstractRegistryFactory. (SPI, Singleton, ThreadSafe)
 *
 * @see com.alibaba.dubbo.registry.RegistryFactory
 */
public abstract class AbstractRegistryFactory implements RegistryFactory {

    // Log output
    /**
     * 日志记录
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractRegistryFactory.class);

    // The lock for the acquisition process of the registry
    /**
     * 锁，对REGISTRIES访问对竞争控制
     */
    private static final ReentrantLock LOCK = new ReentrantLock();

    // Registry Collection Map<RegistryAddress, Registry>
    /**
     * Registry 集合
     * key : {@link URL#toServiceStringWithoutResolving()} 生成的，例如 zookeeper://127.0.0.1:2181/com.alibaba.dubbo.registry.RegistryService
     * value : {@link com.alibaba.dubbo.registry.zookeeper.ZookeeperRegistry}
     */
    private static final Map<String, Registry> REGISTRIES = new ConcurrentHashMap<String, Registry>();

    /**
     * Get all registries
     *
     * @return all registries
     */
    public static Collection<Registry> getRegistries() {
        return Collections.unmodifiableCollection(REGISTRIES.values());
    }

    /**
     * Close all created registries
     * 该方法作用是销毁所有的Registry对象，并且清除内存缓存，逻辑比较简单，关键就是对REGISTRIES进行同步的操作。
     */
    // TODO: 2017/8/30 to move somewhere else better
    public static void destroyAll() {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Close all registries " + getRegistries());
        }
        // Lock up the registry shutdown process
        // 获得锁
        LOCK.lock();
        try {
            for (Registry registry : getRegistries()) {
                try {
                    // 销毁
                    registry.destroy();
                } catch (Throwable e) {
                    LOGGER.error(e.getMessage(), e);
                }
            }
            // 清空缓存
            REGISTRIES.clear();
        } finally {
            // Release the lock
            // 释放锁
            LOCK.unlock();
        }
    }

    /**
     * 主要完成了加锁，以及调用抽象模板方法createRegistry，创建具体的实现等操作，并在缓存在内存中。
     * 抽象模板方法会由具体的子类继承并实现
     *
     * 每种注册中心都有自己的实现类，但是在什么地方判断，应该调用哪个工厂类实现呢？
     * 答案就在RegistryFactory接口中，该接口getRegistry方法有一个@Adaptive({"protocol"})注解
     * 根据其值来调用表不同的工厂类，例如当url.protocol=redis时，获取RedisRegistryFactory实现类。
     *
     *
     * 该方法是实现了RegistryFactory接口中的方法，
     * 这里最要注意的是createRegistry，因为AbstractRegistryFactory类把这个方法抽象出来，
     * 为了让子类只要关注该方法，比如说redis实现的注册中心和zookeeper实现的注册中心创建方式肯定不同，
     * 而他们相同的一些操作都已经在AbstractRegistryFactory中实现。所以只要关注并且实现该抽象方法即可。
     */
    @Override
    public Registry getRegistry(URL url) {
        // 修改url
        url = url.setPath(RegistryService.class.getName())
                .addParameter(Constants.INTERFACE_KEY, RegistryService.class.getName())
                .removeParameters(Constants.EXPORT_KEY, Constants.REFER_KEY);
        // 计算key值
        //这里key为：
        //zookeeper://127.0.0.1:2181/com.alibaba.dubbo.registry.RegistryService
        String key = url.toServiceStringWithoutResolving();
        // Lock the registry access process to ensure a single instance of the registry
        // 锁定注册中心获取过程，保证注册中心单一实例
        LOCK.lock();
        try {
            //先从缓存中获取
            Registry registry = REGISTRIES.get(key);
            if (registry != null) {
                return registry;
            }
            //创建registry，会直接new一个ZookeeperRegistry返回
            //具体创建实例是子类来实现的
            registry = createRegistry(url);
            if (registry == null) {
                throw new IllegalStateException("Can not create registry " + url);
            }
            //放入缓存中
            REGISTRIES.put(key, registry);
            return registry;
        } finally {
            // Release the lock
            LOCK.unlock();
        }
    }

    protected abstract Registry createRegistry(URL url);

}
