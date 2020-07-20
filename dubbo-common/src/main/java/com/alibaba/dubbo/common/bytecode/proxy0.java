package com.alibaba.dubbo.common.bytecode;


import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * @description: {@link Proxy#getProxy(java.lang.ClassLoader, java.lang.Class[])}生成的代理类
 * @author: hzf
 * @create: 2020/5/12 10:43 PM
 */
public class proxy0 implements ClassGenerator.DC, DemoService {
    public static Method[] methods;
    private InvocationHandler handler;

    /**
     * @see com.alibaba.dubbo.rpc.proxy.InvokerInvocationHandler
     */
    public proxy0(InvocationHandler invocationHandler) {
        this.handler = invocationHandler;
    }

    public proxy0() {
    }

    public String sayHello(String string) {
        Object[] arrobject = new Object[]{string};
        Object object = null;
        try {
            object = this.handler.invoke(this, methods[0], arrobject);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
        return (String)object;
    }

    public String sayHello() {
        Object[] arrobject = new Object[]{};
        Object object = null;
        try {
            object = this.handler.invoke(this, methods[1], arrobject);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
        return (String)object;
    }
}
