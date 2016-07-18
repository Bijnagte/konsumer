package smartthings.konsumer.filterchain;

import kafka.message.MessageAndMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smartthings.konsumer.MessageProcessor;
import smartthings.konsumer.circuitbreaker.CircuitBreaker;
import smartthings.konsumer.event.KonsumerEvent;
import smartthings.konsumer.event.KonsumerEventListener;

import java.util.*;

public class MessageFilterChain {
	private final static Logger log = LoggerFactory.getLogger(MessageFilterChain.class);

	private final MessageProcessor processor;
	private final List<MessageFilter> filters;

	public MessageFilterChain(final MessageProcessor processor, final MessageFilter... messageFilters) {
		this.processor = processor;
		this.filters = Collections.unmodifiableList(new ArrayList<MessageFilter>(Arrays.asList(messageFilters)));
	}

	public void handle(MessageAndMetadata<byte[], byte[]> originalMessageAndMetadata,
					   final CircuitBreaker circuitBreaker) throws Exception {

		MessageContext context = new MessageContext() {
			private int counter = 0;

			@Override
			public void next(MessageAndMetadata<byte[], byte[]> messageAndMetadata) throws Exception {
				if (filters.size() > counter) {
					MessageFilter filter = filters.get(counter);
					log.debug("Calling filterchain # {} - {}", counter, filter.getClass().toString());
					counter++;
					try {
						filter.handleMessage(messageAndMetadata, this);
					} finally {
						counter--;
					}
				} else {
					log.debug("Calling processor # {} - {}", counter, processor.getClass().toString());
					processor.processMessage(messageAndMetadata);
				}
			}

			@Override
			public CircuitBreaker getCircuitBreaker() {
				return circuitBreaker;
			}
		};

		context.next(originalMessageAndMetadata);

	}

	private void invokeFilterLifecycleCallbacks(KonsumerEvent event) {
		for (MessageFilter filter : filters) {
			if (event == KonsumerEvent.STARTED) {
				filter.init();
			} else if (event == KonsumerEvent.STOPPED) {
				filter.destroy();
			} else if (event == KonsumerEvent.SUSPENDED) {
				filter.suspended();
			} else if (event == KonsumerEvent.RESUMED) {
				filter.resumed();
			}
		}
	}

	public KonsumerEventListener getKonsumerEventListener() {
		return new KonsumerEventListener() {
			@Override
			public void eventNotification(KonsumerEvent event) {
				invokeFilterLifecycleCallbacks(event);
			}
		};
	}

}
