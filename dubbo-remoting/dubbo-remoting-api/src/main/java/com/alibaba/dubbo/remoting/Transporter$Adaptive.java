package com.alibaba.dubbo.remoting;

import com.alibaba.dubbo.common.extension.ExtensionLoader;

/**
 * @description: ExtensionLoader会通过createAdaptiveExtensionClassCode方法动态生成一个Transporter$Adaptive类
 * @author: hzf
 * @create: 2019-11-06 21:25
 */
public class Transporter$Adaptive implements com.alibaba.dubbo.remoting.Transporter {


    /**
     * 所有扩展点都通过传递URL携带配置信息，所以适配器中的方法必须携带URL参数，
     * 才能根据URL中的配置来选择对应的扩展实现。@Adaptive注解中有一些key值，比如connect方法的注解中有两个key，
     * 分别为“client”和“transporter”，URL会首先去取client对应的value来作为我上述（一）注解@SPI中写到的key值，
     * 如果为空，则去取transporter对应的value，如果还是为空，则会取Transporter类@SPI注解的value属性的值，在这里也就是netty去调用扩展的实现类，
     * 如果@SPI没有设定默认值，则会抛出IllegalStateException异常。
     */
    public com.alibaba.dubbo.remoting.Client connect(com.alibaba.dubbo.common.URL arg0, com.alibaba.dubbo.remoting.ChannelHandler arg1) throws com.alibaba.dubbo.remoting.RemotingException {
        //URL参数为空则抛出异常。
        if (arg0 == null)
            throw new IllegalArgumentException("url == null");

        com.alibaba.dubbo.common.URL url = arg0;
        //这里的getParameter方法可以在源码中具体查看
        String extName = url.getParameter("client", url.getParameter("transporter", "netty"));
        if(extName == null)
            throw new IllegalStateException("Fail to get extension(com.alibaba.dubbo.remoting.Transporter) name from url(" + url.toString() + ") use keys([client, transporter])");
        //这里我在后面会有详细介绍
        com.alibaba.dubbo.remoting.Transporter extension = (com.alibaba.dubbo.remoting.Transporter) ExtensionLoader.getExtensionLoader

                (com.alibaba.dubbo.remoting.Transporter.class).getExtension(extName);
        return extension.connect(arg0, arg1);
    }
    public com.alibaba.dubbo.remoting.Server bind(com.alibaba.dubbo.common.URL arg0, com.alibaba.dubbo.remoting.ChannelHandler arg1) throws com.alibaba.dubbo.remoting.RemotingException {
        if (arg0 == null)
            throw new IllegalArgumentException("url == null");
        com.alibaba.dubbo.common.URL url = arg0;
        String extName = url.getParameter("server", url.getParameter("transporter", "netty"));
        if(extName == null)
            throw new IllegalStateException("Fail to get extension(com.alibaba.dubbo.remoting.Transporter) name from url(" + url.toString() + ") use keys([server, transporter])");
        com.alibaba.dubbo.remoting.Transporter extension = (com.alibaba.dubbo.remoting.Transporter)ExtensionLoader.getExtensionLoader
                (com.alibaba.dubbo.remoting.Transporter.class).getExtension(extName);

        return extension.bind(arg0, arg1);
    }
}