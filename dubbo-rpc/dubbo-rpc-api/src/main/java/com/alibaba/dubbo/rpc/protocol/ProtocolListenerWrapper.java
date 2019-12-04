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
package com.alibaba.dubbo.rpc.protocol;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.ExporterListener;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.InvokerListener;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.listener.ListenerExporterWrapper;
import com.alibaba.dubbo.rpc.listener.ListenerInvokerWrapper;

import java.util.Collections;

/**
 * ListenerProtocol
 *
 * 因为ProtocolListenerWrapper构造方法中又有Protocol类型的参数，
 * 因此会将ProtocolListenerWrapper的Class对象保存在
 * {@link com.alibaba.dubbo.common.extension.ExtensionLoader#cachedWrapperClasses}
 * 详见com.alibaba.dubbo.common.extension.ExtensionLoader#loadClass(java.util.Map, java.net.URL, java.lang.Class, java.lang.String)
 *
 * ProtocolFilterWrapper同理，在文件META-INF/dubbo/internal/com.alibaba.dubbo.rpc.Protocol中定义了
 * ProtocolFilterWrapper,ProtocolListenerWrapper
 *
 * cachedWrapperClasses是set类型，因此添加进set后的顺序是ProtocolListenerWrapper,ProtocolFilterWrapper
 *
 * 因此ProtocolListenerWrapper中持有的Protocol实例就是DubboProtocol(DubboProtocol是默认的Protocol)
 * 详见方法
 * @see com.alibaba.dubbo.common.extension.ExtensionLoader#createExtension(String) 对于cachedWrapperClasses的处理
 *
 * 所以在调用com.alibaba.dubbo.common.extension.ExtensionLoader#getExtension("dubbo")方法时。
 * 返回的Protocol实例会是ProtocolFilterWrapper
 * 所以RegistryPtotocol中的protocol.export()实际上调用的ProtocolFilterWrapper的export方法，
 * 然后ProtocolFilterWrapper的protocol.export方法调用的是ProtocolListenerWrapper的export方法
 * 然后ProtocolListenerWrapper的protocol.export方法调用的是DubboProtocol的export方法
 * 因此整个调用链如上
 *
 *
 *
 *
 */
public class ProtocolListenerWrapper implements Protocol {

    private final Protocol protocol;

    public ProtocolListenerWrapper(Protocol protocol) {
        if (protocol == null) {
            throw new IllegalArgumentException("protocol == null");
        }
        this.protocol = protocol;
    }

    @Override
    public int getDefaultPort() {
        return protocol.getDefaultPort();
    }

    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        //registry类型的Invoker，不需要做处理
        if (Constants.REGISTRY_PROTOCOL.equals(invoker.getUrl().getProtocol())) {
            return protocol.export(invoker);
        }
        //其他具体协议类型的Invoker
        //先进行导出protocol.export(invoker)
        //然后获取自适应的监听器
        //最后返回的是包装了监听器的Exporter
        //这里监听器的获取是getActivateExtension，如果指定了listener就加载实现，没有指定就不加载
        return new ListenerExporterWrapper<T>(protocol.export(invoker),
                Collections.unmodifiableList(ExtensionLoader.getExtensionLoader(ExporterListener.class)
                        .getActivateExtension(invoker.getUrl(), Constants.EXPORTER_LISTENER_KEY)));
    }

    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        if (Constants.REGISTRY_PROTOCOL.equals(url.getProtocol())) {
            return protocol.refer(type, url);
        }
        return new ListenerInvokerWrapper<T>(protocol.refer(type, url),
                Collections.unmodifiableList(
                        ExtensionLoader.getExtensionLoader(InvokerListener.class)
                                .getActivateExtension(url, Constants.INVOKER_LISTENER_KEY)));
    }

    @Override
    public void destroy() {
        protocol.destroy();
    }

}
