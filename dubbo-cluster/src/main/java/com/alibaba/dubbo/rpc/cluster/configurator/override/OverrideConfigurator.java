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
package com.alibaba.dubbo.rpc.cluster.configurator.override;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.cluster.configurator.AbstractConfigurator;

/**
 * AbsentConfigurator
 * 这种是覆盖添加。是目前在用的配置方式。
 * override://127.0.0.1:20880/com.test.DemoService?category=configurators&dynamic=true&enabled=true&application=test&timeout=1000
 * 1.override://代表override协议，必填。
 * 2.127.0.0.1:20880表示只有此提供者生效，必填。
 * 3.com.test.DemoService标傲世只对指定的服务生效，必填。
 * 4.category=configurators表示这个参数是动态配置类型的，必填。
 * 5.dynamic=true表示是否持久化，false则为持久化数据。注册方退出，数据依旧会保存在注册中心。必填。
 * 6.enabled=true表示覆盖规则是否生效，默认生效，可以不填。
 * 7.application=test表示只对某个应用生效，不填则对所有应用生效。可以不填
 * 8.timeout=1000表示满足前面条件的timeout参数覆盖为1000
 *
 */
public class OverrideConfigurator extends AbstractConfigurator {

    public OverrideConfigurator(URL url) {
        super(url);
    }

    @Override
    public URL doConfigure(URL currentUrl, URL configUrl) {
        // 覆盖添加
        return currentUrl.addParameters(configUrl.getParameters());
    }

}
