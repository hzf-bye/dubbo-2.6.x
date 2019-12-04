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
package com.alibaba.dubbo.common.extension.factory;

import com.alibaba.dubbo.common.extension.ExtensionFactory;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.extension.SPI;

/**
 * SpiExtensionFactory
 */
public class SpiExtensionFactory implements ExtensionFactory {

    /**
     * 拿type为ExtensionFactory.class举例
     * @param type object type.
     * @param name object name.
     * @param <T>
     * @return
     */
    @Override
    public <T> T getExtension(Class<T> type, String name) {
        //必须是接口且有spi注解
        if (type.isInterface() && type.isAnnotationPresent(SPI.class)) {
            ExtensionLoader<T> loader = ExtensionLoader.getExtensionLoader(type);
            //如果缓存的扩展点不空，则直接返回Adaptive实例
            if (!loader.getSupportedExtensions().isEmpty()) {
                /*
                 * 其实这里返回的实例还是AdaptiveExtensionFactory，因为AdaptiveExtensionFactory
                 * 中有@Adaptive注解，
                 * 因此AdaptiveExtensionFactory的实例早已经被缓存ExtensionFactory
                 * 对应的ExtensionLoader实例的cachedAdaptiveInstance属性中
                 */
                return loader.getAdaptiveExtension();
            }
        }
        return null;
    }

}
