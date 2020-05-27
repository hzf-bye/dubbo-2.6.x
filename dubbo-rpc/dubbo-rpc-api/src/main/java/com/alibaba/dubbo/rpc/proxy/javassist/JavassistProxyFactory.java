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
package com.alibaba.dubbo.rpc.proxy.javassist;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.bytecode.Proxy;
import com.alibaba.dubbo.common.bytecode.Wrapper;
import com.alibaba.dubbo.common.bytecode.Wrapper1;
import com.alibaba.dubbo.common.bytecode.proxy0;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.proxy.AbstractProxyFactory;
import com.alibaba.dubbo.rpc.proxy.AbstractProxyInvoker;
import com.alibaba.dubbo.rpc.proxy.InvokerInvocationHandler;

/**
 * JavaassistRpcProxyFactory
 * dubbo使用JavassistProxyFactory减少反射调用开销。
 *
 * 1.
 * @see JavassistProxyFactory#getProxy(com.alibaba.dubbo.rpc.Invoker, java.lang.Class[])
 * 创建一个代理类，代理类{@link proxy0}
 * 当调用代理的invoke方法时间隔调用{@link InvokerInvocationHandler#invoke(java.lang.Object, java.lang.reflect.Method, java.lang.Object[])}方法
 * 最终调用目标Invoker的方法
 *
 * 2.
 * @see JavassistProxyFactory#getInvoker(java.lang.Object, java.lang.Class, com.alibaba.dubbo.common.URL)
 * 方法中生成的Wrapper类{@link Wrapper1}
 * 最终调动的还是 proxy 的 methodName方法，
 * 而Wrapper1实例是在dubbo启动的时候生成的，所以不会在运行时带来开销。
 *
 *
 *
 */
public class JavassistProxyFactory extends AbstractProxyFactory {

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProxy(Invoker<T> invoker, Class<?>[] interfaces) {
        return (T) Proxy.getProxy(interfaces).newInstance(new InvokerInvocationHandler(invoker));
    }

    @Override
    public <T> Invoker<T> getInvoker(T proxy, Class<T> type, URL url) {
        // TODO Wrapper cannot handle this scenario correctly: the classname contains '$'
        // 为目标类创建 Wrapper
        //如果类中有$，就使用接口类型获取，其他的使用实现类获取
        /**
         * 生成的Wrapper类
         * @see Wrapper1
         */
        final Wrapper wrapper = Wrapper.getWrapper(proxy.getClass().getName().indexOf('$') < 0 ? proxy.getClass() : type);
        // 创建匿名 Invoker 类对象，并实现 doInvoke 方法。
        //可以看到Invoker执行方法的时候，会调用Wrapper的invokeMethod，这个方法中会有真实的实现类调用真实方法的代码
        return new AbstractProxyInvoker<T>(proxy, type, url) {
            @Override
            protected Object doInvoke(T proxy, String methodName,
                                      Class<?>[] parameterTypes,
                                      Object[] arguments) throws Throwable {
                // 调用 Wrapper 的 invokeMethod 方法，invokeMethod 最终会调用目标方法
                return wrapper.invokeMethod(proxy, methodName, parameterTypes, arguments);
            }
        };
    }

}
