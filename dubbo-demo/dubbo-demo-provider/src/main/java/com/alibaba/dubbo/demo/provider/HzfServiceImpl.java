package com.alibaba.dubbo.demo.provider;

import com.alibaba.dubbo.demo.HzfService;

/**
 * @description:
 * @author: hzf
 * @create: 2020/5/14 10:01 PM
 */
public class HzfServiceImpl implements HzfService {
    @Override
    public String hello(String name) {
        return "hello " + name;
    }
}
