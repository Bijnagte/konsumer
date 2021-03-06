package smartthings.konsumer.util;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class SameThreadExecutionService extends AbstractExecutorService {

	private AtomicBoolean terminated = new AtomicBoolean(false);

	@Override
	public void shutdown() {
		terminated.set(true);
	}

	@Override
	public List<Runnable> shutdownNow() {
		terminated.set(true);
		return Collections.emptyList();
	}

	@Override
	public boolean isShutdown() {
		return terminated.get();
	}

	@Override
	public boolean isTerminated() {
		return terminated.get();
	}

	@Override
	public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
		return terminated.get();
	}

	@Override
	public void execute(Runnable command) {
		command.run();
	}
}
