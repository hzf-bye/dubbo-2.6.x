package com.alibaba.dubbo.demo;

/**
 * @description:
 * @author: hzf
 * @create: 2020/5/14 10:09 PM
 */
public class HzfServiceMock implements HzfService {
    @Override
    public String hello(String name) {
        return "mock " + name;
    }
}
