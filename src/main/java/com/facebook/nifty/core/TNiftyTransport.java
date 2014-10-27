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

import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;

/**
 * Wraps incoming channel buffer into TTransport and provides a output buffer.
 */
public class TNiftyTransport extends TTransport {
	private final Channel channel;
	private final ThriftMessage in;
	private final ByteBuf out;
	private static final int DEFAULT_OUTPUT_BUFFER_SIZE = 1024;

	public TNiftyTransport(Channel channel, ByteBuf in) {
		this(channel, new ThriftMessage(in, ThriftTransportType.UNKNOWN));
	}

	public TNiftyTransport(Channel channel, ThriftMessage in) {
		this.channel = channel;
		this.in = in;
		this.out = channel.alloc().heapBuffer(DEFAULT_OUTPUT_BUFFER_SIZE);

		in.getBuffer().retain();
		out.retain();
	}

	@Override
	public boolean isOpen() {
		return channel.isOpen();
	}

	@Override
	public void open() throws TTransportException {
		// no-op
	}

	@Override
	public void close() {
		// no-op
		channel.close();
	}

	@Override
	public int read(byte[] bytes, int offset, int length) throws TTransportException {
		int _read = Math.min(in.getBuffer().readableBytes(), length);
		in.getBuffer().readBytes(bytes, offset, _read);
		return _read;
	}

	@Override
	public int readAll(byte[] bytes, int offset, int length) throws TTransportException {
		in.getBuffer().readBytes(bytes, offset, length);
		return length;
	}

	@Override
	public void write(byte[] bytes, int offset, int length) throws TTransportException {
		out.writeBytes(bytes, offset, length);
	}

	public ByteBuf getOutputBuffer() {
		return out;
	}

	public ThriftTransportType getTransportType() {
		return in.getTransportType();
	}

	@Override
	public void flush() throws TTransportException {
		// Flush is a no-op: NiftyDispatcher will write the response to the Channel, in order to
		// guarantee ordering of responses when required.
	}

	public void release() {
		in.getBuffer().release();
		out.release();
	}
}
