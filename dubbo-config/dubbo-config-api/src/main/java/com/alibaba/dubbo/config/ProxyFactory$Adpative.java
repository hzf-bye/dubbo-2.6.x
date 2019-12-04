package com.alibaba.dubbo.config;

import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.rpc.Invoker;

/**
 * @description:
 * @author: hzf
 * @create: 2019-11-13 22:26
 */
public class ProxyFactory$Adpative implements com.alibaba.dubbo.rpc.ProxyFactory {

    public com.alibaba.dubbo.rpc.Invoker getInvoker(java.lang.Object arg0, java.lang.Class arg1, com.alibaba.dubbo.common.URL arg2) throws com.alibaba.dubbo.rpc.RpcException {
        if (arg2 == null)
            throw new IllegalArgumentException("url == null");

        com.alibaba.dubbo.common.URL url = arg2;
        //没有proxy参数配置，默认使用javassist
        String extName = url.getParameter("proxy", "javassist");
        if(extName == null)
            throw new IllegalStateException("Fail to get extension(com.alibaba.dubbo.rpc.ProxyFactory) name from url(" + url.toString() + ") use keys([proxy])");

        //这一步就使用javassist来获取ProxyFactory的实现类JavassistProxyFactory
        com.alibaba.dubbo.rpc.ProxyFactory extension = (com.alibaba.dubbo.rpc.ProxyFactory) ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.rpc.ProxyFactory.class).getExtension(extName);

        return extension.getInvoker(arg0, arg1, arg2);
    }

    public java.lang.Object getProxy(com.alibaba.dubbo.rpc.Invoker arg0)  throws com.alibaba.dubbo.rpc.RpcException {
        if (arg0 == null)
            throw new IllegalArgumentException("com.alibaba.dubbo.rpc.Invoker argument == null");

        if (arg0.getUrl() == null)
            throw new IllegalArgumentException("com.alibaba.dubbo.rpc.Invoker argument getUrl() == null");com.alibaba.dubbo.common.URL url = arg0.getUrl();

        String extName = url.getParameter("proxy", "javassist");
        if(extName == null)
            throw new IllegalStateException("Fail to get extension(com.alibaba.dubbo.rpc.ProxyFactory) name from url(" + url.toString() + ") use keys([proxy])");

        com.alibaba.dubbo.rpc.ProxyFactory extension = (com.alibaba.dubbo.rpc.ProxyFactory)ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.rpc.ProxyFactory.class).getExtension(extName);

        return extension.getProxy(arg0);
    }

    @Override
    public <T> T getProxy(Invoker<T> invoker, boolean generic) throws com.alibaba.dubbo.rpc.RpcException {
        return null;
    }
}