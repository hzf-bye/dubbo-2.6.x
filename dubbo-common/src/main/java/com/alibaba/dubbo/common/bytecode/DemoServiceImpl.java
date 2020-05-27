package com.alibaba.dubbo.common.bytecode;

public class DemoServiceImpl implements DemoService {

    @Override
    public String sayHello(String name) {
        return "Hello " + name ;
    }

    @Override
    public String sayHello() {
        return "sayHello";
    }
}
