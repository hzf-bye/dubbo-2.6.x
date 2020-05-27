package com.alibaba.dubbo.demo;

/**
 * @description:
 * @author: hzf
 * @create: 2020/5/13 11:12 PM
 */
public class DemoServiceMock implements DemoService {
    @Override
    public String sayHello(String name) {
        return "mock " + name;
    }

    @Override
    public String sayHello() {
        return null;
    }
}
