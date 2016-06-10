package smartthings.konsumer;

import smartthings.konsumer.circuitbreaker.CircuitBreaker;
import smartthings.konsumer.circuitbreaker.CircuitBreakerListener;
import smartthings.konsumer.circuitbreaker.SimpleCircuitBreaker;
import smartthings.konsumer.event.KonsumerEvent;
import smartthings.konsumer.event.KonsumerEventListener;
import smartthings.konsumer.filterchain.MessageFilter;
import smartthings.konsumer.filterchain.MessageFilterChain;
import smartthings.konsumer.stream.StreamProcessor;
import smartthings.konsumer.stream.StreamProcessorFactory;
import smartthings.konsumer.util.QuietCallable;
import smartthings.konsumer.util.RunUtil;
import smartthings.konsumer.util.ThreadFactoryBuilder;
import kafka.consumer.Consumer;
import kafka.consumer.KafkaStream;
import kafka.javaapi.consumer.ConsumerConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EventListener;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

public class KafkaListener {
	private final static Logger log = LoggerFactory.getLogger(KafkaListener.class);

	private final ConsumerConnector consumer;
	private final ExecutorService partitionExecutor;
	private final StreamProcessor streamProcessor;
	private final String topic;
	private final ListenerConfig config;
	private final CircuitBreaker circuitBreaker;
	private final List<KonsumerEventListener> listeners = new CopyOnWriteArrayList<>();

	public KafkaListener(ListenerConfig config) {
		this.config = config;
		// Build custom executor so we control the factory and backing queue
		// and get better thread names for logging
		partitionExecutor = buildPartitionExecutor();

		consumer = Consumer.createJavaConsumerConnector(config.getConsumerConfig());
		topic = config.getTopic();
		streamProcessor = new StreamProcessorFactory(config).getProcessor();
		circuitBreaker = new SimpleCircuitBreaker();
	}

	private ExecutorService buildPartitionExecutor() {
		ThreadFactory threadFactory = new ThreadFactoryBuilder()
				.setNameFormat("KafkaPartition-" + config.getTopic() + "-%d")
				.setDaemon(config.useDaemonThreads())
				.build();
		return Executors.newFixedThreadPool(config.getPartitionThreads(), threadFactory);
	}

	public void shutdown() {
		consumer.shutdown();
		partitionExecutor.shutdown();
		try {
			boolean completed = partitionExecutor.awaitTermination(config.getShutdownAwaitSeconds(), TimeUnit.SECONDS);
			if (completed) {
				log.info("Shutdown partition consumers of topic {} all messages processed", topic);
			} else {
				log.warn("Shutdown partition consumers of topic {}. Some messages left unprocessed.", topic);
			}
		} catch (InterruptedException e) {
			log.error("Interrupted while waiting for shutdown of topic {}", topic, e);
		}
		streamProcessor.shutdown();
		notifyEventListeners(KonsumerEvent.STOPPED);
	}

	public void run(MessageProcessor processor, MessageFilter... filters) {
		MessageFilterChain filterChain = new MessageFilterChain(processor, filters);
		registerEventListener(filterChain.getKonsumerEventListener());
		Map<String, Integer> topicCountMap = new HashMap<>();
		topicCountMap.put(topic, config.getPartitionThreads());
		Map<String, List<KafkaStream<byte[], byte[]>>> consumerMap = consumer.createMessageStreams(topicCountMap);
		List<KafkaStream<byte[], byte[]>> streams = consumerMap.get(topic);
		circuitBreaker.init(new CircuitBreakerListener() {
			@Override
			public void opened() {
				notifyEventListeners(KonsumerEvent.SUSPENDED);
			}

			@Override
			public void closed() {
				notifyEventListeners(KonsumerEvent.RESUMED);
			}
		});
		notifyEventListeners(KonsumerEvent.STARTED);

		log.info("Listening to kafka with {} partition threads", config.getPartitionThreads());
		for (KafkaStream<byte[], byte[]> stream : streams) {
			try {
				partitionExecutor.submit(streamProcessor.buildConsumer(stream, filterChain, circuitBreaker));
			} catch (RejectedExecutionException e) {
				log.error("Error submitting job to partition executor");
				throw e;
			}
		}
	}

	private void notifyEventListeners(KonsumerEvent event) {
		for (KonsumerEventListener listener : listeners) {
			listener.eventNotification(event);
		}
	}

	public void registerEventListener(KonsumerEventListener listener) {
		listeners.add(listener);
	}

	public void suspend() {
		circuitBreaker.open(this.getClass().getCanonicalName());
	}

	public boolean isSuspended() {
		return circuitBreaker.isOpen();
	}

	public void resume() {
		circuitBreaker.conditionalClose(this.getClass().getCanonicalName());
	}

	/**
	 * Run and then block the calling thread until shutdown. In place to make it easy to
	 * consume in a main method and still exit cleanly.
	 * @param processor The handler that will be handle all messages consumed.
	 * @param filters An ordered list of the filters that will be applied to all messages consumed before they
	 *                are passed to the processor.
	 */
	public void runAndBlock(MessageProcessor processor, MessageFilter... filters) {
		run(processor, filters);
		RunUtil.blockForShutdown(new QuietCallable() {
			@Override
			public void call() {
				shutdown();
			}
		});
	}
}
