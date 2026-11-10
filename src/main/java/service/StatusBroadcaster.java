package service;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Fan-out of "the Daemon's status just changed" to any number of stream subscribers.
 *
 * <p>Latest-wins by design: each subscriber holds at most <em>one</em> pending message, and a newer
 * publish replaces an unread older one. A slow or stalled client therefore can never build up a backlog
 * or slow the scheduler down; it just sees the newest state when it next reads. New subscribers are
 * seeded with the most recent status so a freshly connected GUI is correct immediately.
 */
public final class StatusBroadcaster implements AutoCloseable {

    private final Set<Subscription> subscribers = ConcurrentHashMap.newKeySet();
    private volatile String latest;
    private volatile boolean closed;

    /**
     * Called whenever the exported state changes. Never blocks.
     */
    public void publish(String text) {
        if (closed || text == null) return;
        latest = text;
        for (Subscription s : subscribers) s.offerLatest(text);
    }

    /**
     * Most recent status text, or null if none has been published yet.
     */
    public String latest() {
        return latest;
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    /**
     * New subscriber seeded with the latest status; {@code null} if {@code maxSubscribers} are already connected.
     */
    public Subscription subscribe(int maxSubscribers) {
        if (closed || subscribers.size() >= maxSubscribers) return null;
        Subscription s = new Subscription(this);
        subscribers.add(s);
        String seed = latest;
        if (seed != null) s.offerLatest(seed);
        return s;
    }

    /**
     * Ends every stream (their handler loops see {@code isClosed()} within one heartbeat).
     */
    @Override
    public void close() {
        closed = true;
        subscribers.clear();
    }

    /**
     * One connected stream.
     */
    public static final class Subscription implements AutoCloseable {
        private final StatusBroadcaster owner;
        private final BlockingQueue<String> pending = new ArrayBlockingQueue<>(1);
        private volatile boolean closed;

        private Subscription(StatusBroadcaster owner) {
            this.owner = owner;
        }

        private synchronized void offerLatest(String text) {
            pending.clear();
            pending.offer(text);
        }

        /**
         * Next status text, or {@code null} if none arrived within {@code timeoutMs} (time to send a heartbeat).
         */
        public String next(long timeoutMs) throws InterruptedException {
            return pending.poll(timeoutMs, TimeUnit.MILLISECONDS);
        }

        public boolean isClosed() {
            return closed || owner.closed;
        }

        @Override
        public void close() {
            closed = true;
            owner.subscribers.remove(this);
        }
    }
}
