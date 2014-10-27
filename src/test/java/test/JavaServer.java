package com.nhnent.nettythrift;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

import java.util.List;

import org.apache.thrift.TException;
import org.apache.thrift.TMultiplexedProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tutorial.Calculator;

import com.facebook.nifty.core.NiftyDispatcher;
import com.facebook.nifty.core.ThriftFrameDecoder;
import com.facebook.nifty.core.ThriftServerDef;
import com.facebook.nifty.core.ThriftServerDefBuilder;
import com.facebook.nifty.test.LogEntry;
import com.facebook.nifty.test.ResultCode;
import com.facebook.nifty.test.scribe;

public class JavaServer {

	private static final Logger log = LoggerFactory.getLogger(JavaServer.class);
	static final String HOST = System.getProperty("host", "127.0.0.1");

	public static void main(String[] args) throws Exception {

		EventLoopGroup workerGroup = new NioEventLoopGroup();
		EventLoopGroup bossGroup = new NioEventLoopGroup();

		try {
			ServerBootstrap b = new ServerBootstrap();
			b.group(bossGroup, workerGroup);
			b.channel(NioServerSocketChannel.class);
			b.handler(new LoggingHandler(LogLevel.DEBUG));
			b.childHandler(new ChannelInitializer<SocketChannel>() {

				@Override
				public void initChannel(SocketChannel ch) throws Exception {

					TMultiplexedProcessor multiprocessor = new TMultiplexedProcessor();

					multiprocessor.registerProcessor("Calculator", new Calculator.Processor(new CalculatorHandler()));

					multiprocessor.registerProcessor("Scribe", new scribe.Processor<scribe.Iface>(new scribe.Iface() {
						@Override
						public ResultCode Log(List<LogEntry> messages) throws TException {
							for (LogEntry message : messages) {
								log.info("{}: {}", message.getCategory(), message.getMessage());
							}
							return ResultCode.OK;
						}
					}));

					ThriftServerDef def = new ThriftServerDefBuilder().withProcessor(multiprocessor).build();

					ChannelPipeline pipeline = ch.pipeline();

					pipeline.addLast("frameDecoder", new ThriftFrameDecoder(def.getMaxFrameSize(), def.getInProtocolFactory()));

					pipeline.addLast("dispatcher", new NiftyDispatcher(def));

				}
			});
			b.option(ChannelOption.SO_BACKLOG, 128);
			b.childOption(ChannelOption.SO_KEEPALIVE, true);
			log.debug("configuration serverBootstrap");

			if (log.isInfoEnabled()) {
				log.info("Start server with port: {} ", 9090);
			} else if (log.isWarnEnabled()) {
				log.warn("Start server with port: {} ", 9090);
			} else if (log.isErrorEnabled()) {
				log.error("Start server with port: {} ", 9090);
			}
			Channel serverChannel = b.bind(9090).sync().channel().closeFuture().sync().channel();

		} finally {
			workerGroup.shutdownGracefully();
			bossGroup.shutdownGracefully();
		}
	}
}
