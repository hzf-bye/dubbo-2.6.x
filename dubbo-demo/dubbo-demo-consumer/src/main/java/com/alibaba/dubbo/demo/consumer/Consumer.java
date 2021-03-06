/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.demo.consumer;

import com.alibaba.dubbo.demo.DemoService;
import com.alibaba.dubbo.remoting.exchange.ResponseCallback;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.protocol.dubbo.FutureAdapter;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class Consumer {

    public static void main(String[] args) throws Exception {
        //Prevent to get IPV6 address,this way only work in debug mode
        //But you can pass use -Djava.net.preferIPv4Stack=true,then it work well whether in debug mode or not
        System.setProperty("java.net.preferIPv4Stack", "true");
        System.setProperty("dubbo.registry.retry.period", "111111");
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext(new String[]{"META-INF/spring/dubbo-demo-consumer.xml"});
        context.start();
        RpcContext.getContext().setAttachment("name", "manba");
        DemoService demoService = (DemoService) context.getBean("demoService"); // get remote service proxy

        System.out.println(demoService.sayHello("manba"));
        ((FutureAdapter)RpcContext.getContext().getFuture()).getFuture().setCallback(new ResponseCallback() {

            @Override
            public void done(Object response) {
                System.out.println("result:" + response);
            }

            @Override
            public void caught(Throwable exception) {
                System.out.println("exception:" + exception.getMessage());
            }
        });
//        String hello = demoService.sayHello("world"); // call remote method
//        System.out.println("say hello" + hello);
//        Future<String> future = RpcContext.getContext().getFuture();
//
//        HzfService hzfService = (HzfService) context.getBean("hzfService"); // get remote service proxy
//        String result1 = hzfService.hello("mamba"); // call remote method
//        System.out.println(result1);


//        EchoService echoService = (EchoService) context.getBean("echoService");
//
//        String message = echoService.echo("hello world"); // call remote method
//        System.out.println(message);
        System.out.println(System.in.read());


    }




//        while (true) {
//            try {
////                Thread.sleep(1000);
//                String hello = demoService.sayHello("world"); // call remote method
//                System.out.println(hello); // get result
//
//            } catch (Throwable throwable) {
//                throwable.printStackTrace();
//            }
//        }

}
