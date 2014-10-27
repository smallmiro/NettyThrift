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
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.thrift.TException;
import org.apache.thrift.TProcessorFactory;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatch TNiftyTransport to the TProcessor and write output back.
 *
 * Note that all current async thrift clients are capable of sending multiple requests at once
 * but not capable of handling out-of-order responses to those requests, so this dispatcher
 * sends the requests in order. (Eventually this will be conditional on a flag in the thrift
 * message header for future async clients that can handle out-of-order responses).
 */
public class NiftyDispatcher extends ChannelInboundHandlerAdapter {
	private static final Logger log = LoggerFactory.getLogger(NiftyDispatcher.class);

	private final TProcessorFactory processorFactory;
	private final TProtocolFactory inProtocolFactory;
	private final TProtocolFactory outProtocolFactory;
	private final Executor exe;
	private final int queuedResponseLimit;
	private final Map<Integer, ByteBuf> responseMap = new HashMap<Integer, ByteBuf>();
	private final AtomicInteger dispatcherSequenceId = new AtomicInteger(0);
	private final AtomicInteger lastResponseWrittenId = new AtomicInteger(0);

	public NiftyDispatcher(ThriftServerDef def) {
		this.processorFactory = def.getProcessorFactory();
		this.inProtocolFactory = def.getInProtocolFactory();
		this.outProtocolFactory = def.getOutProtocolFactory();
		this.queuedResponseLimit = def.getQueuedResponseLimit();
		this.exe = def.getExecutor();
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		processRequest(ctx, (TNiftyTransport)msg);
	}

	private void processRequest(final ChannelHandlerContext ctx, final TNiftyTransport messageTransport) {
		// Remember the ordering of requests as they arrive, used to enforce an order on the
		// responses.
		final int requestSequenceId = dispatcherSequenceId.incrementAndGet();

		synchronized (responseMap) {
			// Limit the number of pending responses (responses which finished out of order, and are
			// waiting for previous requests to be finished so they can be written in order), by
			// blocking further channel reads. Due to the way Netty frame decoders work, this is more
			// of an estimate than a hard limit. Netty may continue to decode and process several
			// more requests that were in the latest read, even while further reads on the channel
			// have been blocked.
			if ((requestSequenceId > lastResponseWrittenId.get() + queuedResponseLimit) && !isChannelReadBlocked(ctx)) {
				blockChannelReads(ctx);
			}
		}

		exe.execute(new Runnable() {
			@Override
			public void run() {
				TProtocol inProtocol = inProtocolFactory.getProtocol(messageTransport);
				TProtocol outProtocol = outProtocolFactory.getProtocol(messageTransport);
				try {
					processorFactory.getProcessor(messageTransport).process(inProtocol, outProtocol);
					writeResponse(ctx, messageTransport, requestSequenceId);
					messageTransport.release();
				} catch (TException e1) {
					log.error("Exception while invoking!", e1);
					closeChannel(ctx);
				}
			}

		});
	}

	private void writeResponse(ChannelHandlerContext ctx, TNiftyTransport messageTransport, int responseSequenceId) {
		// Ensure responses to requests are written in the same order the requests
		// were received.
		synchronized (responseMap) {
			ByteBuf response = messageTransport.getOutputBuffer();
			ThriftTransportType transportType = messageTransport.getTransportType();
			int currentResponseId = lastResponseWrittenId.get() + 1;
			if (responseSequenceId != currentResponseId) {
				// This response is NOT next in line of ordered responses, save it to
				// be sent later, after responses to all earlier requests have been
				// sent.
				responseMap.put(responseSequenceId, response);
			} else {
				// This response was next in line, write this response now, and see if
				// there are others next in line that should be sent now as well.
				do {
					if (response.isReadable()) {
						response = addFraming(ctx, response, transportType);
						//ctx.nextOutboundByteBuffer().writeBytes(response);
						ctx.channel().write(response);
						//response.release();
					}
					lastResponseWrittenId.incrementAndGet();
					++currentResponseId;
					response = responseMap.remove(currentResponseId);
				} while (null != response);

				ctx.channel().flush();

				// Now that we've written some responses, check if reads should be unblocked
				if (isChannelReadBlocked(ctx)) {
					int lastRequestSequenceId = dispatcherSequenceId.get();
					if (lastRequestSequenceId <= lastResponseWrittenId.get() + queuedResponseLimit) {
						unblockChannelReads(ctx);
					}
				}
			}
		}
	}

	private ByteBuf addFraming(ChannelHandlerContext ctx, ByteBuf response, ThriftTransportType transportType) {
		if (!response.isReadable()) {
			// Empty response from one-way message, don't frame it
			return response;
		}

		if (transportType == ThriftTransportType.UNFRAMED) {
			return response;
		} else if (transportType == ThriftTransportType.FRAMED) {
			ByteBuf frameSizeBuffer = ctx.alloc().buffer(4);
			frameSizeBuffer.writeInt(response.readableBytes());
			return Unpooled.wrappedBuffer(frameSizeBuffer, response);
		} else {
			throw new UnsupportedOperationException("Header protocol is not supported");
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		// Any out of band exception are caught here and we tear down the socket
		ctx.close();
		super.exceptionCaught(ctx, cause);
	}

	private void closeChannel(ChannelHandlerContext ctx) {
		if (ctx.channel().isOpen()) {
			ctx.channel().close();
		}
	}

	private enum ReadBlockedState {
		NOT_BLOCKED, BLOCKED,
	}

	private static final AttributeKey<ReadBlockedState> READ_BLOCKED_STATE_KEY = new AttributeKey<ReadBlockedState>("ReadBlockedState");

	@Override
	public void channelActive(ChannelHandlerContext ctx) throws Exception {
		ctx.attr(READ_BLOCKED_STATE_KEY).set(ReadBlockedState.NOT_BLOCKED);
		super.channelActive(ctx);
	}

	private boolean isChannelReadBlocked(ChannelHandlerContext ctx) {
		return ctx.attr(READ_BLOCKED_STATE_KEY).get() == ReadBlockedState.BLOCKED;
	}

	private void blockChannelReads(ChannelHandlerContext ctx) {
		// Remember that reads are blocked (there is no Channel.getReadable())
		ctx.attr(READ_BLOCKED_STATE_KEY).set(ReadBlockedState.BLOCKED);

		// NOTE: this shuts down reads, but isn't a 100% guarantee we won't get any more messages.
		// It sets up the channel so that the polling loop will not report any new read events
		// and netty won't read any more data from the socket, but any messages already fully read
		// from the socket before this ran may still be decoded and arrive at this handler. Thus
		// the limit on queued messages before we block reads is more of a guidance than a hard
		// limit.
		ctx.channel().config().setAutoRead(false);
	}

	private void unblockChannelReads(ChannelHandlerContext ctx) {
		// Remember that reads are unblocked (there is no Channel.getReadable())
		ctx.attr(READ_BLOCKED_STATE_KEY).set(ReadBlockedState.NOT_BLOCKED);
		ctx.channel().config().setAutoRead(true);
	}
}
