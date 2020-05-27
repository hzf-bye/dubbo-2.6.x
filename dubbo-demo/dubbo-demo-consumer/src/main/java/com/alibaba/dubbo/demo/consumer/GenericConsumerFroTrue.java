package com.alibaba.dubbo.demo.consumer;

import com.alibaba.dubbo.config.ApplicationConfig;
import com.alibaba.dubbo.config.ReferenceConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import com.alibaba.dubbo.config.utils.ReferenceConfigCache;
import com.alibaba.dubbo.rpc.service.GenericService;
import com.alibaba.fastjson.JSON;

/**
 * @description: generic=true方式泛化调用
 * @author: hzf
 * @create: 2020/5/20 9:27 PM
 */
public class GenericConsumerFroTrue {

    public static void main(String[] args) {


        ReferenceConfig<GenericService> reference = new ReferenceConfig<GenericService>();
        reference.setApplication(new ApplicationConfig("first-dubbo-generic"));
        reference.setRegistry(new RegistryConfig("zookeeper://127.0.0.1:2181"));
        reference.setInterface("com.alibaba.dubbo.demo.DemoService");
        reference.setVersion("*");//1.0_local
        //设置泛化调用类型为 true
        reference.setGeneric(true);
        ReferenceConfigCache cache = ReferenceConfigCache.getCache();
        GenericService genericService = cache.get(reference);

        try {
            Object result = genericService.$invoke("sayHello", new String[]{"java.lang.String"}, new Object[]{"mamba"});
            System.out.println(JSON.toJSONString(result));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
