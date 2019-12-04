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
package com.alibaba.dubbo.remoting;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.SPI;
import com.alibaba.dubbo.remoting.buffer.ChannelBuffer;

import java.io.IOException;

/**
 * Codec2是编解码器，那么什么叫做编解码器，在网络中只是讲数据看成是原始的字节序列，
 * 但是我们的应用程序会把这些字节组织成有意义的信息，那么网络字节流和数据间的转化就是很常见的任务。
 * 而编码器是讲应用程序的数据转化为网络格式，解码器则是讲网络格式转化为应用程序，
 * 同时具备这两种功能的单一组件就叫编解码器。在dubbo中Codec是老的编解码器接口，
 * 而Codec2是新的编解码器接口，并且dubbo已经用CodecAdapter把Codec适配成Codec2了。
 * 所以在这里我就介绍Codec2接口，毕竟人总要往前看。
 */
@SPI
public interface Codec2 {

    /**
     * 编码
     */
    @Adaptive({Constants.CODEC_KEY})
    void encode(Channel channel, ChannelBuffer buffer, Object message) throws IOException;

    /**
     * 解码
     */
    @Adaptive({Constants.CODEC_KEY})
    Object decode(Channel channel, ChannelBuffer buffer) throws IOException;


    /**
     * 该接口中有个枚举类型DecodeResult，因为解码过程中，需要解决 TCP 拆包、粘包的场景，所以增加了这两种解码结果。
     */
    enum DecodeResult {
        // 需要更多输入和忽略一些输入
        NEED_MORE_INPUT, SKIP_SOME_INPUT
    }

}

