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
package com.alibaba.dubbo.remoting.exchange.codec;

import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.io.Bytes;
import com.alibaba.dubbo.common.io.StreamUtils;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.serialize.Cleanable;
import com.alibaba.dubbo.common.serialize.ObjectInput;
import com.alibaba.dubbo.common.serialize.ObjectOutput;
import com.alibaba.dubbo.common.serialize.Serialization;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.buffer.ChannelBuffer;
import com.alibaba.dubbo.remoting.buffer.ChannelBufferInputStream;
import com.alibaba.dubbo.remoting.buffer.ChannelBufferOutputStream;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.Response;
import com.alibaba.dubbo.remoting.exchange.support.DefaultFuture;
import com.alibaba.dubbo.remoting.telnet.codec.TelnetCodec;
import com.alibaba.dubbo.remoting.transport.CodecSupport;
import com.alibaba.dubbo.remoting.transport.ExceedPayloadLimitException;

import java.io.IOException;
import java.io.InputStream;

/**
 * ExchangeCodec.
 *
 * dubbo协议头由16字节组成
 * 第1,2字节 存储0xdabb 魔法树
 * 第3个字节(16-23位)
 *      第16位：是否为双向的rpc调用（比如方法拥有返回值），0为Response，1为Request
 *      第17位：仅在第15为被设置为1时有效，0为单向调用，1为双向调用；比如在优雅停机时服务端发送readonly不需要双向调用，这里标志位就为0
 *      第18位：事件标识，0为当前数据包是请求或者响应包，1为当前数据包是心跳包（框架为了保活TCP连接，每次客户端和服务端互相发送心跳包时这个标志位就被设定，设置了心跳报文不会透传到业务方法调用，仅用于框架内部保活机制）
 *      第19-23位：序列化器编码，2-Hessian2Serialization,3-JavaSerialization,4-CompactedJavaSerialization,6-FastJsonSerialization,7-NativeJavaSerialization,8-KryoSerialization,9-FstSerialization
 * 第4字节(24-31位)
 *      状态：20-OK,30-CLIENT_TIMEOUT,31-SERVER_TIMEOUT,40-BAD_REQUEST,50-BAD_RESPONSE,60-SERVICE_NOT_FOUND,70-SERVER_ERROR,80-SERVER_ERROR,90-CLIENT_ERROR,100-SERVER_THREADPOOL_EXHAUSTED_ERROR(服务端线程池满拒绝执行)
 * 第5-12字节
 *      请求编号，这8个字节存储RPC请求的唯一id，用来将请求和响应做关联
 * 第13-16字节
 *      占用的4个字节存储消息体长度。在一次RPC请求过程中，消息体中一次会存储7部分内容，分别是：dubbo版本号，服务接口名，服务接口版本，方法名，参数类型，方法参数值，请求额外参数（attachment）
 *
 */
public class ExchangeCodec extends TelnetCodec {

    // header length.
    /**
     * 协议头长度：16字节 = 128Bits
     */
    protected static final int HEADER_LENGTH = 16;
    // magic header.
    /**
     * 魔法数
     * MAGIC二进制：1101101010111011，十进制：55995
     * 用来判断是不是dubbo协议的数据包
     * 我们都知道网络通信中(基于TCP)需要解决网络粘包/解包的问题，一些常用的办法比如回车、换行、固定长度和特殊分隔符等进行处理。
     * Dubbo其实就是用特殊符号0xdabb魔法数来分割处理粘包的问题
     */
    protected static final short MAGIC = (short) 0xdabb;
    /**
     * Magic High，也就是0-7位：11011010
     */
    protected static final byte MAGIC_HIGH = Bytes.short2bytes(MAGIC)[0];
    /**
     * Magic Low  8-15位 ：10111011
     */
    protected static final byte MAGIC_LOW = Bytes.short2bytes(MAGIC)[1];
    // message flag.
    /**
     * 128 二进制：10000000
     */
    protected static final byte FLAG_REQUEST = (byte) 0x80;

    /**
     * 64 二进制：1000000
     */
    protected static final byte FLAG_TWOWAY = (byte) 0x40;
    /**
     * 32 二进制：100000
     */
    protected static final byte FLAG_EVENT = (byte) 0x20;
    /**
     * 31 二进制：11111
     */
    protected static final int SERIALIZATION_MASK = 0x1f;
    private static final Logger logger = LoggerFactory.getLogger(ExchangeCodec.class);

    public Short getMagicCode() {
        return MAGIC;
    }

    /**
     * 该方法是根据消息的类型来分别进行编码，分为三种情况：Request类型、Response类型以及其他
     */
    @Override
    public void encode(Channel channel, ChannelBuffer buffer, Object msg) throws IOException {
        if (msg instanceof Request) {
            // 如果消息是Request类型，对请求消息编码
            encodeRequest(channel, buffer, (Request) msg);
        } else if (msg instanceof Response) {
            // 如果消息是Response类型，对响应消息编码
            encodeResponse(channel, buffer, (Response) msg);
        } else {
            // 直接让父类( Telnet ) 处理，目前是 Telnet 命令的结果。
            super.encode(channel, buffer, msg);
        }
    }

    @Override
    public Object decode(Channel channel, ChannelBuffer buffer) throws IOException {
        int readable = buffer.readableBytes();
        //最多读取16个字节并分配存储空间
        byte[] header = new byte[Math.min(readable, HEADER_LENGTH)];
        buffer.readBytes(header);
        return decode(channel, buffer, readable, header);
    }

    /**
     * 该方法就是解码前的一些核对过程，包括检测是否为dubbo协议，是否有拆包现象等，具体的解码在decodeBody方法
     * DubboCodec重写了decodeBody方法
     */
    @Override
    protected Object decode(Channel channel, ChannelBuffer buffer, int readable, byte[] header) throws IOException {
        // check magic number.
        // 核对魔数（该数字固定）
        //处理流起始处不是dubbo魔法数0xdabb的场景
        if (readable > 0 && header[0] != MAGIC_HIGH
                || readable > 1 && header[1] != MAGIC_LOW) {
            int length = header.length;
            //流中还有数据可以读取
            if (header.length < readable) {
                //为header重新分配空间，用来存储流中所有可读字节
                header = Bytes.copyOf(header, readable);
                //将流中剩余字节读取到header中
                buffer.readBytes(header, length, readable - length);
            }
            for (int i = 1; i < header.length - 1; i++) {
                //将dubbo读索引指向dubbo报文开始处(0xdabb)
                if (header[i] == MAGIC_HIGH && header[i + 1] == MAGIC_LOW) {
                    buffer.readerIndex(buffer.readerIndex() - header.length + i);
                    //将流起始处至下一个dubbo报文之间的数据读取到header中
                    header = Bytes.copyOf(header, i);
                    break;
                }
            }
            //主要用于解析header数据，比如用于telnet
            return super.decode(channel, buffer, readable, header);
        }
        // check length.
        //如果读取数据长度小于16个字节，则期待更多数据，解决拆包现象
        if (readable < HEADER_LENGTH) {
            return DecodeResult.NEED_MORE_INPUT;
        }

        // get data length.
        //读取头部存储的报文长度，并校验长度是否超过限制
        int len = Bytes.bytes2int(header, 12);
        checkPayload(channel, len);

        int tt = len + HEADER_LENGTH;
        //校验是否可以读取完整Dubbo报文，否则期待更多数据，解决拆包现象
        if (readable < tt) {
            return DecodeResult.NEED_MORE_INPUT;
        }

        // limit input stream.
        ChannelBufferInputStream is = new ChannelBufferInputStream(buffer, len);

        try {
            //解码消息体，is流是完整的RPC调用报文
            return decodeBody(channel, is, header);
        } finally {
            //如果解码过程有问题则跳过此次RPC调用报文
            if (is.available() > 0) {
                try {
                    if (logger.isWarnEnabled()) {
                        logger.warn("Skip input stream " + is.available());
                    }
                    StreamUtils.skipUnusedStream(is);
                } catch (IOException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
    }

    protected Object decodeBody(Channel channel, InputStream is, byte[] header) throws IOException {
        byte flag = header[2], proto = (byte) (flag & SERIALIZATION_MASK);
        // get request id.
        long id = Bytes.bytes2long(header, 4);
        if ((flag & FLAG_REQUEST) == 0) {
            // decode response.
            Response res = new Response(id);
            if ((flag & FLAG_EVENT) != 0) {
                res.setEvent(Response.HEARTBEAT_EVENT);
            }
            // get status.
            byte status = header[3];
            res.setStatus(status);
            try {
                ObjectInput in = CodecSupport.deserialize(channel.getUrl(), is, proto);
                if (status == Response.OK) {
                    Object data;
                    if (res.isHeartbeat()) {
                        data = decodeHeartbeatData(channel, in);
                    } else if (res.isEvent()) {
                        data = decodeEventData(channel, in);
                    } else {
                        data = decodeResponseData(channel, in, getRequestData(id));
                    }
                    res.setResult(data);
                } else {
                    res.setErrorMessage(in.readUTF());
                }
            } catch (Throwable t) {
                res.setStatus(Response.CLIENT_ERROR);
                res.setErrorMessage(StringUtils.toString(t));
            }
            return res;
        } else {
            // decode request.
            Request req = new Request(id);
            req.setVersion(Version.getProtocolVersion());
            req.setTwoWay((flag & FLAG_TWOWAY) != 0);
            if ((flag & FLAG_EVENT) != 0) {
                req.setEvent(Request.HEARTBEAT_EVENT);
            }
            try {
                ObjectInput in = CodecSupport.deserialize(channel.getUrl(), is, proto);
                Object data;
                if (req.isHeartbeat()) {
                    data = decodeHeartbeatData(channel, in);
                } else if (req.isEvent()) {
                    data = decodeEventData(channel, in);
                } else {
                    data = decodeRequestData(channel, in);
                }
                req.setData(data);
            } catch (Throwable t) {
                // bad request
                req.setBroken(true);
                req.setData(t);
            }
            return req;
        }
    }

    protected Object getRequestData(long id) {
        DefaultFuture future = DefaultFuture.getFuture(id);
        if (future == null)
            return null;
        Request req = future.getRequest();
        if (req == null)
            return null;
        return req.getData();
    }

    protected void encodeRequest(Channel channel, ChannelBuffer buffer, Request req) throws IOException {
        //获取指定或者默认的序列化协议(Hessian2)
        Serialization serialization = getSerialization(channel);
        // header.
        // 创建16字节的字节数组，报文头
        byte[] header = new byte[HEADER_LENGTH];
        // set magic number.
        // 设置前16位数据，也就是设置header[0]和header[1]的数据为Magic High和Magic Low
        // 即暂用两个字节存储魔法数
        Bytes.short2bytes(MAGIC, header);

        // set request and serialization flag.
        // 16-23位为serialization编号，用到或运算10000000|serialization编号，例如serialization编号为10，则为10000010
        // 第16位表示请求标志，这里因为是为Request所以为1；19-23：序列化协议序号，比如Hessian2协议（Hessian2Serialization）值为2
        header[2] = (byte) (FLAG_REQUEST | serialization.getContentTypeId());

        //设置第17位与第18位的值
        if (req.isTwoWay()) header[2] |= FLAG_TWOWAY;
        if (req.isEvent()) header[2] |= FLAG_EVENT;

        /*
         * 比如最后得到head[2] = 11000010
         * 从左往右代表 16-23位
         * 那么就是 数据包类型为request，调用方式为双向调用，事件类型是请求包，序列化方式为Hessian2
         */

        // set request id.
        // 设置32-95位请求id
        Bytes.long2bytes(req.getId(), header, 4);

        // encode request data.
        // 编码 `Request.data` 到 Body ，并写入到 Buffer
        int savedWriteIndex = buffer.writerIndex();
        //跳过buffer头部16个字节，用于序列化消息体
        buffer.writerIndex(savedWriteIndex + HEADER_LENGTH);
        ChannelBufferOutputStream bos = new ChannelBufferOutputStream(buffer);
        // 对body数据序列化
        ObjectOutput out = serialization.serialize(channel.getUrl(), bos);
        if (req.isEvent()) {
            // 特殊事件编码
            encodeEventData(channel, out, req.getData());
        } else {
            //序列化请求调用，data一般是RpcInvocation
            //DubboCodec#encodeRequestData
            //按照dubbo版本号，服务接口名，服务接口版本，方法名，参数类型，方法参数值，请求额外参数（attachment）顺序序列化
            encodeRequestData(channel, out, req.getData(), req.getVersion());
        }
        // 释放资源
        out.flushBuffer();
        if (out instanceof Cleanable) {
            ((Cleanable) out).cleanup();
        }
        bos.flush();
        bos.close();
        int len = bos.writtenBytes();
        //检查是否超过默认8MB大小
        checkPayload(channel, len);
        //头部第96-127位写入消息体长度
        Bytes.int2bytes(len, header, 12);

        // write
        // 定位指针到报文头部开始位置
        buffer.writerIndex(savedWriteIndex);
        //写入完整报文头部到buffer
        buffer.writeBytes(header);
        //重新设置index为savedWriteIndex起始位置+头部16字节+消息体字节大小
        buffer.writerIndex(savedWriteIndex + HEADER_LENGTH + len);
    }

    protected void encodeResponse(Channel channel, ChannelBuffer buffer, Response res) throws IOException {
        int savedWriteIndex = buffer.writerIndex();
        try {
            //获取指定或者默认的序列化协议(Hessian2)
            Serialization serialization = getSerialization(channel);
            // header.
            // 创建16字节大小的字节数组
            byte[] header = new byte[HEADER_LENGTH];
            // set magic number.
            //设置魔法数 两个字节
            Bytes.short2bytes(MAGIC, header);
            // set request and serialization flag.
            // 19-23位设置响应标志，
            header[2] = serialization.getContentTypeId();
            // 如果是心跳事件，则设置第18位为1
            if (res.isHeartbeat()) header[2] |= FLAG_EVENT;
            // set response status.
            // 设置24-31位为状态码
            byte status = res.getStatus();
            header[3] = status;
            // set request id.
            // 设置32-95位为请求id
            Bytes.long2bytes(res.getId(), header, 4);

            // 空出16字节头部用于存储响应体报文
            buffer.writerIndex(savedWriteIndex + HEADER_LENGTH);
            ChannelBufferOutputStream bos = new ChannelBufferOutputStream(buffer);
            // 对body进行序列化
            ObjectOutput out = serialization.serialize(channel.getUrl(), bos);
            // encode response data or error message.
            if (status == Response.OK) {
                if (res.isHeartbeat()) {
                    // 对心跳事件编码
                    encodeHeartbeatData(channel, out, res.getResult());
                } else {
                    // 对普通响应编码
                    //序列化响应调用，
                    encodeResponseData(channel, out, res.getResult(), res.getVersion());
                }
            } else out.writeUTF(res.getErrorMessage());
            out.flushBuffer();
            if (out instanceof Cleanable) {
                ((Cleanable) out).cleanup();
            }
            bos.flush();
            bos.close();

            int len = bos.writtenBytes();
            //检查是否超过默认8MB大小
            checkPayload(channel, len);
            //头部第96-127位写入消息体长度
            Bytes.int2bytes(len, header, 12);
            // write
            // 定位指针到报文头部开始位置
            buffer.writerIndex(savedWriteIndex);
            //写入完整报文头部到buffer
            buffer.writeBytes(header);
            //重新设置index为savedWriteIndex起始位置+头部16字节+消息体字节大小
            buffer.writerIndex(savedWriteIndex + HEADER_LENGTH + len);
        } catch (Throwable t) {
            // clear buffer
            //如果编码失败则复位buffer
            buffer.writerIndex(savedWriteIndex);
            // send error message to Consumer, otherwise, Consumer will wait till timeout.
            // 将编码响应异常发送给consumer，否则只能等待超时
            if (!res.isEvent() && res.getStatus() != Response.BAD_RESPONSE) {
                Response r = new Response(res.getId(), res.getVersion());
                r.setStatus(Response.BAD_RESPONSE);

                if (t instanceof ExceedPayloadLimitException) {
                    logger.warn(t.getMessage(), t);
                    try {
                        //告诉客户端数据包长度超过限制
                        r.setErrorMessage(t.getMessage());
                        channel.send(r);
                        return;
                    } catch (RemotingException e) {
                        logger.warn("Failed to send bad_response info back: " + t.getMessage() + ", cause: " + e.getMessage(), e);
                    }
                } else {
                    // FIXME log error message in Codec and handle in caught() of IoHanndler?
                    logger.warn("Fail to encode response: " + res + ", send bad_response info instead, cause: " + t.getMessage(), t);
                    try {
                        r.setErrorMessage("Failed to send response: " + res + ", cause: " + StringUtils.toString(t));
                        channel.send(r);
                        return;
                    } catch (RemotingException e) {
                        logger.warn("Failed to send bad_response info back: " + res + ", cause: " + e.getMessage(), e);
                    }
                }
            }

            // Rethrow exception
            if (t instanceof IOException) {
                throw (IOException) t;
            } else if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            } else if (t instanceof Error) {
                throw (Error) t;
            } else {
                throw new RuntimeException(t.getMessage(), t);
            }
        }
    }

    @Override
    protected Object decodeData(ObjectInput in) throws IOException {
        return decodeRequestData(in);
    }

    @Deprecated
    protected Object decodeHeartbeatData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    protected Object decodeRequestData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    protected Object decodeResponseData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    @Override
    protected void encodeData(ObjectOutput out, Object data) throws IOException {
        encodeRequestData(out, data);
    }

    private void encodeEventData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    @Deprecated
    protected void encodeHeartbeatData(ObjectOutput out, Object data) throws IOException {
        encodeEventData(out, data);
    }

    protected void encodeRequestData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    protected void encodeResponseData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    @Override
    protected Object decodeData(Channel channel, ObjectInput in) throws IOException {
        return decodeRequestData(channel, in);
    }

    protected Object decodeEventData(Channel channel, ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    @Deprecated
    protected Object decodeHeartbeatData(Channel channel, ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    protected Object decodeRequestData(Channel channel, ObjectInput in) throws IOException {
        return decodeRequestData(in);
    }

    protected Object decodeResponseData(Channel channel, ObjectInput in) throws IOException {
        return decodeResponseData(in);
    }

    protected Object decodeResponseData(Channel channel, ObjectInput in, Object requestData) throws IOException {
        return decodeResponseData(channel, in);
    }

    @Override
    protected void encodeData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(channel, out, data);
    }

    private void encodeEventData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeEventData(out, data);
    }

    @Deprecated
    protected void encodeHeartbeatData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeHeartbeatData(out, data);
    }

    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(out, data);
    }

    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeResponseData(out, data);
    }

    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        encodeRequestData(out, data);
    }

    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        encodeResponseData(out, data);
    }


}
