package test;

import java.util.ArrayList;
import java.util.List;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TMultiplexedProtocol;
import org.apache.thrift.transport.TSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import shared.SharedStruct;
import tutorial.Calculator;
import tutorial.InvalidOperation;
import tutorial.Operation;
import tutorial.Work;

import com.facebook.nifty.test.LogEntry;
import com.facebook.nifty.test.scribe;

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
}