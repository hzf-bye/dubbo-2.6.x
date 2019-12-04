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
package com.alibaba.dubbo.registry.status;

import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.common.status.Status;
import com.alibaba.dubbo.common.status.StatusChecker;
import com.alibaba.dubbo.registry.Registry;
import com.alibaba.dubbo.registry.support.AbstractRegistryFactory;

import java.util.Collection;

/**
 * RegistryStatusChecker
 * 该类实现了StatusChecker，StatusChecker是一个状态校验的接口，RegistryStatusChecker是它的扩展类，
 * 做了一些跟注册中心有关的状态检查和设置
 *
 * 第一个关注点就是@Activate注解，也就是RegistryStatusChecker类会自动激活加载。
 * 该类就实现了接口的check方法，作用就是给注册中心进行状态检查，并且返回检查结果。
 */
@Activate
public class RegistryStatusChecker implements StatusChecker {

    @Override
    public Status check() {

        // 获得所有的注册中心对象
        Collection<Registry> registries = AbstractRegistryFactory.getRegistries();
        if (registries.isEmpty()) {
            return new Status(Status.Level.UNKNOWN);
        }
        Status.Level level = Status.Level.OK;
        StringBuilder buf = new StringBuilder();
        // 拼接注册中心url中的地址
        for (Registry registry : registries) {
            if (buf.length() > 0) {
                buf.append(",");
            }
            buf.append(registry.getUrl().getAddress());
            // 如果注册中心的节点不可用，则拼接disconnected，并且状态设置为error
            if (!registry.isAvailable()) {
                level = Status.Level.ERROR;
                buf.append("(disconnected)");
            } else {
                buf.append("(connected)");
            }
        }
        // 返回状态检查结果
        return new Status(level, buf.toString());
    }

}
