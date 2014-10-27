NettyThrift
===========

This project is an implementation of [Thrift](http://thrift.apache.org/) clients and servers on [Netty 4.0.4](http://netty.io/). It does not require Google inject

It is also the change implementation used by [nifty](https://github.com/facebook/nifty)


Example
===========

server source

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


client source


public class JavaClient {
	private static final Logger log = LoggerFactory.getLogger(JavaClient.class);
	public static void main(String[] args) {
		try {
			TSocket transport = new TSocket("localhost", 9090);
			transport.open();

			TBinaryProtocol protocol = new TBinaryProtocol(transport);

			TMultiplexedProtocol mp = new TMultiplexedProtocol(protocol, "Calculator");
			Calculator.Client client = new Calculator.Client(mp);

			TMultiplexedProtocol mp2 = new TMultiplexedProtocol(protocol, "Scribe");
			scribe.Client client2 = new scribe.Client(mp2);

			testCalculator(client);

			testScribe(client2);

			transport.close();
		} catch (TException x) {
			x.printStackTrace();
		}
	}

	private static void testScribe(scribe.Client client2) throws TException {
		List<LogEntry> messages = new ArrayList<LogEntry>();
		messages.add(new LogEntry("a", "b1"));
		messages.add(new LogEntry("a", "b2"));
		messages.add(new LogEntry("a", "b3"));
		messages.add(new LogEntry("a", "b4"));

		client2.Log(messages);
	}

	private static void testCalculator(Calculator.Client client) throws TException {
		client.ping();
		log.info("ping()");

		int sum = client.add(1, 1);
		log.info("1+1={}", sum);

		Work work = new Work();

		work.op = Operation.DIVIDE;
		work.num1 = 1;
		work.num2 = 0;
		try {
			int quotient = client.calculate(1, work);
			log.info("Whoa we can divide by 0");
		} catch (InvalidOperation io) {
			log.error("Invalid operation: {}", io.why, io);
		}

		work.op = Operation.SUBTRACT;
		work.num1 = 15;
		work.num2 = 10;
		try {
			int diff = client.calculate(1, work);
			log.info("15-10={}", diff);
		} catch (InvalidOperation io) {
			log.error("Invalid operation: {}", io.why, io);
		}

		SharedStruct log2 = client.getStruct(1);
		log.info("Check log: {}", log2.value);
	}
