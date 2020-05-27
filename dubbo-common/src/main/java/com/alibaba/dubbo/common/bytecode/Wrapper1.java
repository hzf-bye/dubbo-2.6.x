package com.alibaba.dubbo.common.bytecode;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;

/**
 * 以{@link HelloServiceImpl}
 * 生成的wrapper类其他方法忽略，主要看invokeMethod
 */
public class Wrapper1 extends Wrapper implements ClassGenerator.DC {
    public static String[] pns;
    public static Map pts;
    public static String[] mns;
    public static String[] dmns;
    public static Class[] mts0;
    public static Class[] mts1;

    public Class getPropertyType(String string) {
        return (Class)pts.get(string);
    }

    public Object invokeMethod(Object object, String string, Class[] arrclass, Object[] arrobject) throws InvocationTargetException {
        DemoServiceImpl demoServiceImpl;
        try {
            demoServiceImpl = (DemoServiceImpl)object;
        }
        catch (Throwable throwable) {
            throw new IllegalArgumentException(throwable);
        }
        try {
            if ("sayHello".equals(string) && arrclass.length == 1 && arrclass[0].getName().equals("java.lang.String")) {
                return demoServiceImpl.sayHello((String)arrobject[0]);
            }
            if ("sayHello".equals(string) && arrclass.length == 0) {
                return demoServiceImpl.sayHello();
            }
        }
        catch (Throwable throwable) {
            throw new InvocationTargetException(throwable);
        }
        throw new NoSuchMethodException(new StringBuffer().append("Not found method \"").append(string).append("\" in class com.alibaba.dubbo.demo.provider.DemoServiceImpl.").toString());
    }

    public Object getPropertyValue(Object object, String string) {
        try {
            DemoServiceImpl demoServiceImpl = (DemoServiceImpl)object;
        }
        catch (Throwable throwable) {
            throw new IllegalArgumentException(throwable);
        }
        throw new NoSuchPropertyException(new StringBuffer().append("Not found property \"").append(string).append("\" filed or setter method in class com.alibaba.dubbo.demo.provider.DemoServiceImpl.").toString());
    }

    public void setPropertyValue(Object object, String string, Object object2) {
        try {
            DemoServiceImpl demoServiceImpl = (DemoServiceImpl)object;
        }
        catch (Throwable throwable) {
            throw new IllegalArgumentException(throwable);
        }
        throw new NoSuchPropertyException(new StringBuffer().append("Not found property \"").append(string).append("\" filed or setter method in class com.alibaba.dubbo.demo.provider.DemoServiceImpl.").toString());
    }

    public String[] getMethodNames() {
        return mns;
    }

    public String[] getDeclaredMethodNames() {
        return dmns;
    }

    public String[] getPropertyNames() {
        return pns;
    }

    public boolean hasProperty(String string) {
        return pts.containsKey(string);
    }
}
