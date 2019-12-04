package com.alibaba.dubbo.config;

import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.rpc.RpcException;

/**
 * @description: 动态生成的Protocol实例
 * @author: hzf
 * @create: 2019-11-13 22:20
 */
public class Protocol$Adaptive implements com.alibaba.dubbo.rpc.Protocol {
    public com.alibaba.dubbo.rpc.Invoker refer(java.lang.Class arg0, com.alibaba.dubbo.common.URL arg1) throws com.alibaba.dubbo.rpc.RpcException {
        if ( arg1 == null )
            throw new IllegalArgumentException("url == null");

        com.alibaba.dubbo.common.URL url = arg1;
        String extName = (url.getProtocol() == null ? "dubbo" : url.getProtocol());
        if ( extName == null )
            throw new IllegalStateException("Fail to get extension(com.alibaba.dubbo.rpc.Protocol) name from url(" + url.toString() + ") use keys([protocol])");

        com.alibaba.dubbo.rpc.Protocol extension = (com.alibaba.dubbo.rpc.Protocol) ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.rpc.Protocol.class).getExtension(extName);

        return extension.refer(arg0, arg1);
    }

    public com.alibaba.dubbo.rpc.Exporter export(com.alibaba.dubbo.rpc.Invoker arg0) throws com.alibaba.dubbo.rpc.RpcException {
        if ( arg0 == null )
            throw new IllegalArgumentException("com.alibaba.dubbo.rpc.Invoker argument == null");

        if ( arg0.getUrl() == null )
            throw new IllegalArgumentException("com.alibaba.dubbo.rpc.Invoker argument getUrl() == null");
        com.alibaba.dubbo.common.URL url = arg0.getUrl();
        //根据URL配置信息获取Protocol协议，默认是dubbo
        String extName = (url.getProtocol() == null ? "dubbo" : url.getProtocol());
        if ( extName == null )
            throw new IllegalStateException("Fail to get extension(com.alibaba.dubbo.rpc.Protocol) name from url(" + url.toString() + ") use keys([protocol])");
        //根据协议名，获取Protocol的实现
        //获得Protocol的实现过程中，会对Protocol先进行依赖注入，然后进行Wrapper包装，最后返回被修改过的Protocol
        //包装经过了ProtocolListenerWrapper->ProtocolFilterWrapper->RegistryProtocol
        com.alibaba.dubbo.rpc.Protocol extension = (com.alibaba.dubbo.rpc.Protocol) ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.rpc.Protocol.class).getExtension(extName);

        return extension.export(arg0);
    }

    public void destroy() {
        throw new UnsupportedOperationException("method public abstract void com.alibaba.dubbo.rpc.Protocol.destroy() of interface com.alibaba.dubbo.rpc.Protocol is not adaptive method!");
    }


    public int getDefaultPort() {
        throw new UnsupportedOperationException("method public abstract int com.alibaba.dubbo.rpc.Protocol.getDefaultPort() of interface com.alibaba.dubbo.rpc.Protocol is not adaptive method!");
    }

}