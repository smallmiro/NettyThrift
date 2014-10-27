package com.facebook.nifty.core;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

import org.apache.thrift.transport.TTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ThriftBufferDecoder extends ByteToMessageDecoder {

	private Logger log = LoggerFactory.getLogger(ThriftBufferDecoder.class);

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
		out.add(getTransport(ctx.channel(), in));
	}

	protected TTransport getTransport(Channel channel, ByteBuf cb) {
		ThriftTransportType type = (cb.getUnsignedByte(0) < 0x80) ? ThriftTransportType.FRAMED : ThriftTransportType.UNFRAMED;
		log.debug("ThriftTransportType={}", type.name());

		if (type == ThriftTransportType.FRAMED) {
			cb.skipBytes(4);
		}
		return new TNiftyTransport(channel, new ThriftMessage(cb, type));
	}
}
