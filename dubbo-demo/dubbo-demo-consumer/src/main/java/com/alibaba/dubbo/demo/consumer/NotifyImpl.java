package com.alibaba.dubbo.demo.consumer;

import java.util.HashMap;
import java.util.Map;

/**
 * @description:
 * @author: hzf
 * @create: 2020/3/17 10:36 AM
 */
public class NotifyImpl implements Notify{

    public Map<String, String> ret = new HashMap<String, String>();

    @Override
    public String onreturn(String name, String id) {
        ret.put(id, name);
        System.out.println("onreturn: " + name);
        return name + id;
    }

    @Override
    public void onthrow(Throwable ex, String name, String id) {
        System.out.println("onthrow: " + name);
    }
}
