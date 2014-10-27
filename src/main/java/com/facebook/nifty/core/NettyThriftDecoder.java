/*
 * Copyright (C) 2012-2013 Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.nifty.core;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

import org.apache.thrift.transport.TTransport;

/**
 * Converts ByteBuf into TNiftyTransport.
 */
public class NettyThriftDecoder extends ByteToMessageDecoder {

	protected TTransport getTransport(Channel channel, ByteBuf cb) {
		ThriftTransportType type = (cb.getUnsignedByte(0) < 0x80) ? ThriftTransportType.FRAMED : ThriftTransportType.UNFRAMED;
		if (type == ThriftTransportType.FRAMED) {
			cb.skipBytes(4);
		}
		return new TNiftyTransport(channel, new ThriftMessage(cb, type));
	}

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
		out.add(getTransport(ctx.channel(), in));
	}

}
