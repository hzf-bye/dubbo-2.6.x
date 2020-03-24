package com.alibaba.dubbo.demo.consumer;

/**
 * @description:
 * @author: hzf
 * @create: 2020/3/17 10:36 AM
 */
public interface Notify {

    String onreturn(String name, String id);

    void onthrow(Throwable ex, String name, String id);
}
