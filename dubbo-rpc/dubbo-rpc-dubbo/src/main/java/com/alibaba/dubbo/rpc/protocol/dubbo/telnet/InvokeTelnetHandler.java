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
package com.alibaba.dubbo.rpc.protocol.dubbo.telnet;

import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.common.utils.PojoUtils;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.telnet.TelnetHandler;
import com.alibaba.dubbo.remoting.telnet.support.Help;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.protocol.dubbo.DubboProtocol;
import com.alibaba.fastjson.JSON;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * InvokeTelnetHandler
 */
@Activate
@Help(parameter = "[service.]method(args)", summary = "Invoke the service method.", detail = "Invoke the service method.")
public class InvokeTelnetHandler implements TelnetHandler {

    private static Method findMethod(Exporter<?> exporter, String method, List<Object> args) {
        Invoker<?> invoker = exporter.getInvoker();
        Method[] methods = invoker.getInterface().getMethods();
        for (Method m : methods) {
            if (m.getName().equals(method) && isMatch(m.getParameterTypes(), args)) {
                return m;
            }
        }
        return null;
    }

    private static boolean isMatch(Class<?>[] types, List<Object> args) {
        if (types.length != args.size()) {
            return false;
        }
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            Object arg = args.get(i);

            if (arg == null) {
                // if the type is primitive, the method to invoke will cause NullPointerException definitely
                // so we can offer a specified error message to the invoker in advance and avoid unnecessary invoking
                if (type.isPrimitive()) {
                    throw new NullPointerException(String.format(
                            "The type of No.%d parameter is primitive(%s), but the value passed is null.", i + 1, type.getName()));
                }

                // if the type is not primitive, we choose to believe what the invoker want is a null value
                continue;
            }

            if (ReflectUtils.isPrimitive(arg.getClass())) {
                if (!ReflectUtils.isPrimitive(type)) {
                    return false;
                }
            } else if (arg instanceof Map) {
                String name = (String) ((Map<?, ?>) arg).get("class");
                Class<?> cls = arg.getClass();
                if (name != null && name.length() > 0) {
                    cls = ReflectUtils.forName(name);
                }
                if (!type.isAssignableFrom(cls)) {
                    return false;
                }
            } else if (arg instanceof Collection) {
                if (!type.isArray() && !type.isAssignableFrom(arg.getClass())) {
                    return false;
                }
            } else {
                if (!type.isAssignableFrom(arg.getClass())) {
                    return false;
                }
            }
        }
        return true;
    }

    public static void main(String[] args) {

        //{"class":"com.xxx.xxx.xxxRequest","paranName1":"paramValue1","paranName2":"paramValue2"}
        List<Object> list = JSON.parseArray("[" + "{\"class\":\"com.xxx.xxx.xxxRequest\",\"paranName1\":\"paramValue1\",\"paranName2\":\"paramValue2\"}" + "]", Object.class);
        Object arg = list.get(0);
        System.out.println(arg instanceof Map);
        String name = (String) ((Map<?, ?>) arg).get("class");
        Class<?> cls = arg.getClass();
        cls = ReflectUtils.forName(name);
        System.out.println();


    }

    /**
     * 例如invoke命令为:invoke com.xxx.xxx.xxxService.methodName({"class":"com.xxx.xxx.xxxRequest","paranName1":"paramValue1","paranName2":"paramValue2"})
     * 那么message为:com.xxx.xxx.xxxService.methodName({"class":"com.xxx.xxx.xxxRequest","paranName1":"paramValue1","paranName2":"paramValue2"})
     */
    @Override
    @SuppressWarnings("unchecked")
    public String telnet(Channel channel, String message) {
        if (message == null || message.length() == 0) {
            return "Please input method name, eg: \r\ninvoke xxxMethod(1234, \"abcd\", {\"prop\" : \"value\"})\r\ninvoke XxxService.xxxMethod(1234, \"abcd\", {\"prop\" : \"value\"})\r\ninvoke com.xxx.XxxService.xxxMethod(1234, \"abcd\", {\"prop\" : \"value\"})";
        }
        StringBuilder buf = new StringBuilder();
        String service = (String) channel.getAttribute(ChangeTelnetHandler.SERVICE_KEY);
        if (service != null && service.length() > 0) {
            buf.append("Use default service " + service + ".\r\n");
        }
        int i = message.indexOf("(");
        if (i < 0 || !message.endsWith(")")) {
            return "Invalid parameters, format: service.method(args)";
        }
        //提取调用方法（由接口名.方法名组成）
        //com.xxx.xxx.xxxService.methodName
        String method = message.substring(0, i).trim();
        //提取调用方法参数值
        //{"class":"com.xxx.xxx.xxxRequest","paranName1":"paramValue1","paranName2":"paramValue2"}
        String args = message.substring(i + 1, message.length() - 1).trim();
        i = method.lastIndexOf(".");
        if (i >= 0) {
            //提取接口名称
            //com.xxx.xxx.xxxService
            service = method.substring(0, i).trim();
            //提取方法名称
            //methodName
            method = method.substring(i + 1).trim();
        }
        List<Object> list;
        try {
            //将参数JSON传转化为JSON对象
            list = JSON.parseArray("[" + args + "]", Object.class);
        } catch (Throwable t) {
            return "Invalid json argument, cause: " + t.getMessage();
        }
        Invoker<?> invoker = null;
        Method invokeMethod = null;
        //遍历所有服务提供者
        //这里获取到的 DubboProtocol.getDubboProtocol().getExporters()获取到的exporterMap
        //在服务初始化的时候赋值，DubboProtocol#export
        for (Exporter<?> exporter : DubboProtocol.getDubboProtocol().getExporters()) {
            //如果命令没有指定接口名称，那么就遍历提供者的所有方法，根据方法名+参数类型去找到一个匹配的方法
            //只要找到了一个则返回，因此这种方式可能不能精确的匹配到想到调用的方法
            if (service == null || service.length() == 0) {
                invokeMethod = findMethod(exporter, method, list);
                if (invokeMethod != null) {
                    invoker = exporter.getInvoker();
                    break;
                }
            } else {
                //指定了接口名称那么在上面的基础上再加上接口名的判断条件
                if (service.equals(exporter.getInvoker().getInterface().getSimpleName())
                        || service.equals(exporter.getInvoker().getInterface().getName())
                        || service.equals(exporter.getInvoker().getUrl().getPath())) {
                    invokeMethod = findMethod(exporter, method, list);
                    invoker = exporter.getInvoker();
                    break;
                }
            }
        }
        if (invoker != null) {
            if (invokeMethod != null) {
                try {
                    //将JSON参数值转化为java对象值
                    Object[] array = PojoUtils.realize(list.toArray(), invokeMethod.getParameterTypes(), invokeMethod.getGenericParameterTypes());
                    RpcContext.getContext().setLocalAddress(channel.getLocalAddress()).setRemoteAddress(channel.getRemoteAddress());
                    long start = System.currentTimeMillis();
                    //根据查找到的Invoker，构造RpcInvocation进行方法调用
                    //返回RpcResult或者RpcException
                    //com.alibaba.dubbo.rpc.proxy.javassist.JavassistProxyFactory.getInvoker
                    Object result = invoker.invoke(new RpcInvocation(invokeMethod, array)).recreate();
                    long end = System.currentTimeMillis();
                    buf.append(JSON.toJSONString(result));
                    buf.append("\r\nelapsed: ");
                    buf.append(end - start);
                    buf.append(" ms.");
                } catch (Throwable t) {
                    return "Failed to invoke method " + invokeMethod.getName() + ", cause: " + StringUtils.toString(t);
                }
            } else {
                buf.append("No such method " + method + " in service " + service);
            }
        } else {
            buf.append("No such service " + service);
        }
        return buf.toString();
    }

}
