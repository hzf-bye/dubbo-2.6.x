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

package com.alibaba.dubbo.remoting.transport;

import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.Decodeable;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.Response;
import com.alibaba.dubbo.remoting.exchange.codec.ExchangeCodec;
import com.alibaba.dubbo.remoting.exchange.support.header.HeaderExchangeHandler;
import com.alibaba.dubbo.remoting.exchange.support.header.HeartbeatHandler;

public class DecodeHandler extends AbstractChannelHandlerDelegate {

    private static final Logger log = LoggerFactory.getLogger(DecodeHandler.class);

    public DecodeHandler(ChannelHandler handler) {
        /**
         * @see HeaderExchangeHandler#HeaderExchangeHandler(com.alibaba.dubbo.remoting.exchange.ExchangeHandler)实例
         */
        super(handler);
    }

    /**
     * 可以看到做了三次判断，根据消息的不同会对消息的不同数据做解码。
     * 可以看到，这里用到装饰模式后，在处理消息的前面做了解码的处理，
     * 并且还能继续委托给handler来处理消息，通过组合做到了功能的叠加。
     * 到这里的message是已经经过
     * @see ExchangeCodec#decode(com.alibaba.dubbo.remoting.Channel, com.alibaba.dubbo.remoting.buffer.ChannelBuffer)
     * @see com.alibaba.dubbo.rpc.protocol.dubbo.DubboCodec#decodeBody(com.alibaba.dubbo.remoting.Channel, java.io.InputStream, byte[])
     */
    @Override
    public void received(Channel channel, Object message) throws RemotingException {
        // 如果是Decodeable类型的消息，则对整个消息解码
        if (message instanceof Decodeable) {
            decode(message);
        }

        // 如果是Request请求类型消息，则对请求中对请求数据解码
        if (message instanceof Request) {
            decode(((Request) message).getData());
        }

        // 如果是Response返回类型的消息，则对返回消息中对结果进行解码
        if (message instanceof Response) {
            decode(((Response) message).getResult());
        }

        // 继续将消息委托给handler，继续处理
        handler.received(channel, message);
    }

    /**
     * 如果是心跳事件消息或者只读事件请求消息那么message为 Request Response
     * @see HeartbeatHandler#received(com.alibaba.dubbo.remoting.Channel, java.lang.Object)
     * @see com.alibaba.dubbo.remoting.exchange.support.header.HeartBeatTask#run()
     * 正常的请求响应消息 message为DecodeableRpcInvocation与DecodeableRpcResult 再次调用其解码方法即可。
     * @see com.alibaba.dubbo.rpc.protocol.dubbo.DubboCodec#decodeBody(com.alibaba.dubbo.remoting.Channel, java.io.InputStream, byte[])
     */
    private void decode(Object message) {
        // 如果消息类型是Decodeable，进一步调用Decodeable的decode来解码
        if (message != null && message instanceof Decodeable) {
            try {
                ((Decodeable) message).decode();
                if (log.isDebugEnabled()) {
                    log.debug("Decode decodeable message " + message.getClass().getName());
                }
            } catch (Throwable e) {
                if (log.isWarnEnabled()) {
                    log.warn("Call Decodeable.decode failed: " + e.getMessage(), e);
                }
            } // ~ end of catch
        } // ~ end of if
    } // ~ end of method decode

}
