package smartthings.konsumer.stream

import kafka.consumer.ConsumerIterator
import kafka.consumer.KafkaStream
import kafka.message.MessageAndMetadata
import smartthings.konsumer.ListenerConfig
import smartthings.konsumer.MessageProcessor
import smartthings.konsumer.circuitbreaker.CircuitBreaker
import smartthings.konsumer.filterchain.MessageFilterChain
import spock.lang.Specification
import spock.lang.Unroll

import java.util.concurrent.Executor

class ThreadedMessageConsumerSpec extends Specification {

	Executor currentTreadExecutor = new Executor() {
		@Override
		void execute(Runnable command) {
			command.run()
		}
	}

	def 'should verify circuit breaker state before consuming a message'() {
		given:
		KafkaStream<byte[], byte[]> stream = Mock()
		ConsumerIterator<byte[], byte[]> streamIterator = Mock()
		MessageFilterChain filterChain = Mock()
		CircuitBreaker circuitBreaker = Mock()
		MessageAndMetadata<byte[], byte[]> messageAndMetadata = Mock()
		int cnt = 0
		ListenerConfig config = ListenerConfig.builder().processingThreads(1).build()
		ThreadedMessageConsumer consumer = new ThreadedMessageConsumer(stream, currentTreadExecutor, config,
				filterChain, circuitBreaker)

		when:
		consumer.run()

		then:
		1 * stream.iterator() >> streamIterator
		2 * streamIterator.hasNext() >> { cnt += 1; return cnt == 1 }
		1 * circuitBreaker.blockIfOpen()
		1 * streamIterator.next() >> messageAndMetadata
		1 * filterChain.handle(messageAndMetadata, circuitBreaker)
		0 * _
	}

	def 'should process every message'() {
		given:
		KafkaStream<byte[], byte[]> stream = Mock()
		ConsumerIterator<byte[], byte[]> streamIterator = Mock()
		MessageFilterChain filterChain = Mock()
		CircuitBreaker circuitBreaker = Mock()
		MessageAndMetadata<byte[], byte[]> messageAndMetadata = Mock()
		int cnt = 0
		int numMessages = 5
		ListenerConfig config = ListenerConfig.builder().processingThreads(1).build()
		ThreadedMessageConsumer consumer = new ThreadedMessageConsumer(stream, currentTreadExecutor, config,
				filterChain, circuitBreaker)

		when:
		consumer.run()

		then:
		1 * stream.iterator() >> streamIterator
		(numMessages + 1) * streamIterator.hasNext() >> { cnt += 1; return cnt <= numMessages }
		numMessages * circuitBreaker.blockIfOpen()
		numMessages * streamIterator.next() >> messageAndMetadata
		numMessages * filterChain.handle(messageAndMetadata, circuitBreaker)
		0 * _
	}

	def 'should process messages with a decoder'() {
		given:
		KafkaStream<String, String> stream = Mock()
		ConsumerIterator<String, String> streamIterator = Mock()
		MessageFilterChain filterChain = Mock()
		CircuitBreaker circuitBreaker = Mock()
		MessageAndMetadata<String, String> messageAndMetadata = Mock()
		ListenerConfig config = ListenerConfig.builder().processingThreads(1).build()
		ThreadedMessageConsumer<String, String, Void> consumer = new ThreadedMessageConsumer<>(stream,
			currentTreadExecutor, config, filterChain, circuitBreaker)

		when:
		consumer.run()

		then:
		1 * stream.iterator() >> streamIterator
		2 * streamIterator.hasNext() >>> [true, false]
		1 * circuitBreaker.blockIfOpen()
		1 * streamIterator.next() >> messageAndMetadata
		1 * filterChain.handle(messageAndMetadata, circuitBreaker)
		0 * _
	}

}
