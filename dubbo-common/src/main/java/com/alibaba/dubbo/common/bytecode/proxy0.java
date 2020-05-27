package com.alibaba.dubbo.common.bytecode;

/**
 * @description: {@link Proxy#getProxy(java.lang.ClassLoader, java.lang.Class[])}生成的代理类
 * @author: hzf
 * @create: 2020/5/12 10:43 PM
 */
public class proxy0 implements DemoService {

    public static java.lang.reflect.Method[] methods;

    private java.lang.reflect.InvocationHandler handler;

    public proxy0() {
    }

    /**
     *
     * @param arg0 {@link com.alibaba.dubbo.rpc.proxy.InvokerInvocationHandler}实例
     */
    public proxy0(java.lang.reflect.InvocationHandler arg0) {
        handler = arg0;
    }

    public java.lang.String sayHello(java.lang.String arg0) throws Throwable {
        Object[] args = new Object[1];
        args[0] =  arg0;
        Object ret = handler.invoke(this, methods[0], args);
        return (java.lang.String) ret;
    }
}
