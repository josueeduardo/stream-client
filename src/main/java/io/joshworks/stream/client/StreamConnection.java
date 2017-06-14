package io.joshworks.stream.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.XnioWorker;

import java.io.IOException;
import java.nio.channels.Channel;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Created by Josh Gontijo on 6/9/17.
 */
public abstract class StreamConnection {

    private static final Logger logger = LoggerFactory.getLogger(StreamConnection.class);

    protected final String url;
    protected final XnioWorker worker;
    protected final String uuid;
    protected final ConnectionMonitor monitor;
    private final ScheduledExecutorService scheduler;

    private final long reconnectInterval;
    private final int maxRetries;
    private final boolean autoReconnect;

    private final Runnable onFailedAttempt;
    private final Runnable onRetriesExceeded;

    protected boolean shuttingDown = false;
    private int retries = 0;

    public StreamConnection(ClientConfiguration clientConfiguration) {
        this.uuid = UUID.randomUUID().toString().substring(0, 8);
        this.url = clientConfiguration.url;
        this.scheduler = clientConfiguration.scheduler;
        this.monitor = clientConfiguration.monitor;
        this.reconnectInterval = clientConfiguration.retryInterval;
        this.maxRetries = clientConfiguration.maxRetries;
        this.autoReconnect = clientConfiguration.autoReconnect;
        this.worker = clientConfiguration.worker;
        this.onFailedAttempt = clientConfiguration.onFailedAttempt;
        this.onRetriesExceeded = clientConfiguration.onRetriesExceeded;
    }

    protected abstract void tryConnect() throws Exception;

    protected abstract void closeChannel();

    public void connect() {
        shuttingDown = false;
        this.tryConnect(false, 0);
    }

    protected static void closeChannel(Channel channel) {
        if (channel != null && channel.isOpen()) {
            try {
                channel.close();
            } catch (IOException e) {
                logger.error("Error while closing channel", e);
            }
        }
    }


    protected void retry() {
        if (maxRetries == 0) {
            onRetriesExceeded.run();
        }
        tryConnect(false, reconnectInterval);
    }

    protected void reconnect() {
        if (autoReconnect) {
            logger.info("Connection closed. Not reconnecting");
        }
        tryConnect(true, reconnectInterval);
    }

    protected void tryConnect(boolean isReconnection, long delay) {
        if (retries++ > maxRetries && maxRetries >= 0) {
            onRetriesExceeded.run();
            throw new MaxRetryExceeded("Max retries (" + maxRetries + ") exceeded, not reconnecting");
        }
        if (shuttingDown || (isReconnection && !autoReconnect)) {
            return;
        }

        logger.info("Trying to connect to {} in {}ms, autoReconnect {} of {}", url, reconnectInterval, retries, maxRetries);
        try {
            if (scheduler.isTerminated() || scheduler.isShutdown()) {
                logger.warn("Scheduler service shutdown, not reconnecting");
                return;
            }
            scheduler.schedule(() -> {
                try {
                    this.tryConnect();
                } catch (Exception e) {
                    onFailedAttempt.run();
                    closeChannel();
                    retry();
                }
                retries = 0;
            }, delay, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            logger.error("Error while scheduling reconnection", e);
        }
    }


}
