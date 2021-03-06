/*
 * Copyright 2017 Josue Gontijo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.joshworks.stream.client;

import io.joshworks.snappy.sse.SseBroadcaster;
import io.joshworks.stream.client.sse.EventData;
import io.joshworks.stream.client.sse.SSEConnection;
import io.joshworks.stream.client.sse.SseClientCallback;
import io.undertow.server.handlers.sse.ServerSentEventConnection;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.joshworks.snappy.SnappyServer.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Created by Josh Gontijo on 3/30/17.
 */
public class ServerSentEventTest {

    private AtomicReference<ServerSentEventConnection> serverConnectionRef = new AtomicReference<>();

    @Before
    public void init() {

        sse("/empty");

        sse("/simple", (connection, lastEventId) -> {
            connection.addCloseTask(channel -> System.out.println("Disconnected"));

            connection.send("1");
            connection.send("2");
            connection.send("3");
        });


        sse("/id", (connection, lastEventId) -> {

            int eventId = lastEventId == null ? 0 : Integer.parseInt(lastEventId);
            connection.addCloseTask(channel -> System.out.println("Disconnected"));

            connection.send("a", "event-type-A", "" + ++eventId, null);
            connection.send("b", "event-type-A", "" + ++eventId, null);
            connection.send("c", "event-type-A", "" + ++eventId, null);
        });


        sse("/serverClose", (connection, lastEventId) -> serverConnectionRef.set(connection));


        start();
    }

    @After
    public void stopServer() {
        stop();
    }

    @AfterClass
    public static void shutdown() {
        StreamClient.shutdown();
        stop();
    }


    @Test
    public void messageReceived() throws Exception {
        final CountDownLatch latch = new CountDownLatch(3);

        StreamClient.sse("http://localhost:9000/simple").onEvent(data -> {
            assertNotNull(data);
            latch.countDown();
        }).connect();

        if (!latch.await(10, TimeUnit.SECONDS)) {
            fail("No messages were received");
        }
    }

    @Test
    public void lastEventId() throws Exception {
        CountDownLatch firstConnection = new CountDownLatch(3);
        CountDownLatch secondConnection = new CountDownLatch(6);

        SSEConnection connection = StreamClient.connect("http://localhost:9000/id", new SseClientCallback() {
            @Override
            public void onEvent(EventData data) {
                System.out.println(data);
                firstConnection.countDown();
                secondConnection.countDown();
            }

            @Override
            public void onClose(String lastEventId) {
                System.out.println("Closed");
            }

            @Override
            public void onError(Exception e) {
                e.printStackTrace();
            }
        });

        if (!firstConnection.await(5, TimeUnit.SECONDS)) {
            fail("No messages were received");
        }
        String lastEventId = connection.close();
        assertEquals(3, Integer.parseInt(lastEventId));

        connection.connect();

        if (!secondConnection.await(10, TimeUnit.SECONDS)) {
            fail("No messages were received after trying to autoReconnect");
        }
        lastEventId = connection.close();
        assertEquals(6, Integer.parseInt(lastEventId));
    }

    @Test
    public void closedByTheServer() throws Exception {
        final CountDownLatch onClose = new CountDownLatch(1);
        final CountDownLatch connected = new CountDownLatch(1);

        SSEConnection sseConnection = StreamClient.sse("http://localhost:9000/serverClose")
                .onOpen(connected::countDown)
                .onClose(lastEventId -> {
                    System.out.println("Closing connection");
                    onClose.countDown();
                }).connect();

        if (!connected.await(10, TimeUnit.SECONDS)) {
            fail("Client did not connect");
        }

        //close server connection
        serverConnectionRef.get().close();

        if (!onClose.await(10, TimeUnit.SECONDS)) {
            fail("Client could not detect connection closed by the server");
        }

        assertFalse(sseConnection.isOpen());
    }

    @Test
    public void closedByTheServer_clientCallback() throws Exception {
        final CountDownLatch closed = new CountDownLatch(1);
        final CountDownLatch connected = new CountDownLatch(1);

        SSEConnection sseConnection = StreamClient.connect("http://localhost:9000/serverClose", new SseClientCallback() {

            @Override
            public void onOpen() {
                connected.countDown();
            }

            @Override
            public void onEvent(EventData event) {

            }

            @Override
            public void onClose(String lastEventId) {
                System.out.println("Closing connection");
                closed.countDown();
            }
        });

        if (!connected.await(10, TimeUnit.SECONDS)) {
            fail("Client did not connect");
        }

        //close server connection
        serverConnectionRef.get().close();


        if (!closed.await(10, TimeUnit.SECONDS)) {
            fail("Client could not detect connection closed by the server");
        }

        assertFalse(sseConnection.isOpen());
    }

    @Test
    public void isOpen() throws Exception {
        final CountDownLatch connected = new CountDownLatch(1);

        SSEConnection sseConnection = StreamClient.sse("http://localhost:9000/serverClose")
                .onOpen(connected::countDown)
                .connect();


        if (!connected.await(10, TimeUnit.SECONDS)) {
            fail("Client did not connect");
        }

        assertTrue(sseConnection.isOpen());
    }

    @Test
    public void onEvent() throws Exception {
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch onEvent = new CountDownLatch(3); //three events

        StreamClient.sse("http://localhost:9000/simple")
                .onOpen(connected::countDown)
                .onEvent(data -> onEvent.countDown())
                .connect();


        if (!connected.await(10, TimeUnit.SECONDS)) {
            fail("Client did not connect");
        }

        if (!onEvent.await(10, TimeUnit.SECONDS)) {
            fail("Event not received");
        }

    }

    @Test
    public void closedByTheClient() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final CountDownLatch messageLatch = new CountDownLatch(3);

        SSEConnection sseConnection = StreamClient.connect("http://localhost:9000/simple", new SseClientCallback() {
            @Override
            public void onEvent(EventData event) {
                messageLatch.countDown();
            }

            @Override
            public void onClose(String lastEventId) {
                System.out.println("Closing connection");
                latch.countDown();
            }

        });

        if (!messageLatch.await(10, TimeUnit.SECONDS)) {
            fail("Failed on waiting messages from the server");
        }

        sseConnection.close();

        if (!latch.await(10, TimeUnit.SECONDS)) {
            fail("Client could not detect connection closed by the server");
        }

        assertFalse(sseConnection.isOpen());
    }

    @Test
    public void emptyHandler() throws Exception {
        final CountDownLatch openLatch = new CountDownLatch(1);
        final CountDownLatch closeLatch = new CountDownLatch(1);
        final CountDownLatch messageLatch = new CountDownLatch(2);

        SSEConnection sseConnection = StreamClient.connect("http://localhost:9000/empty", new SseClientCallback() {
            @Override
            public void onOpen() {
                openLatch.countDown();
            }

            @Override
            public void onEvent(EventData event) {
                messageLatch.countDown();
            }

            @Override
            public void onClose(String lastEventId) {
                closeLatch.countDown();
            }

        });

        if (!openLatch.await(10, TimeUnit.SECONDS)) {
            fail("Could not tryConnect to the server");
        }

        SseBroadcaster.broadcast("message 1");
        SseBroadcaster.broadcast("message 2");

        if (!messageLatch.await(10, TimeUnit.SECONDS)) {
            fail("Failed on waiting messages from the server");
        }

        sseConnection.close();

        if (!closeLatch.await(10, TimeUnit.SECONDS)) {
            fail("Client could not detect connection closed by the server");
        }

        assertFalse(sseConnection.isOpen());
    }

    @Test
    public void autoReconnect() throws Exception {
        CountDownLatch firstConnection = new CountDownLatch(3);
        CountDownLatch secondConnection = new CountDownLatch(6);
        CountDownLatch onClose = new CountDownLatch(1);

        SSEConnection connect = StreamClient.sse("http://localhost:9000/id")
                .maxRetries(-1)
                .clientCallback(new SseClientCallback() {
                    @Override
                    public void onEvent(EventData data) {
                        System.out.println(data);
                        firstConnection.countDown();
                        secondConnection.countDown();
                    }

                    @Override
                    public void onClose(String lastEventId) {
                        System.out.println("Closed");
                        onClose.countDown();
                    }

                    @Override
                    public void onError(Exception e) {
                        e.printStackTrace();
                    }
                }).connect();

        if (!firstConnection.await(5, TimeUnit.SECONDS)) {
            fail("No messages were received");
        }

        stop(); //server dies
        init(); //reconfigure and autoReconnect

        if (!onClose.await(10, TimeUnit.SECONDS)) {
            fail("onClose wasn't called");
        }

        if (!secondConnection.await(10, TimeUnit.SECONDS)) {
            fail("No messages were received, or client did not tryConnect");
        }
        String lastEventId = connect.close();
        assertEquals(6, Integer.parseInt(lastEventId));
    }

    @Test
    public void onRetriesExceeded() throws Exception {
        stop(); //server not connected

        final CountDownLatch exceeded = new CountDownLatch(1);

        StreamClient.sse("http://localhost:9000/id")
                .maxRetries(1)
                .onRetriesExceeded(exceeded::countDown)
                .connect();

        if (!exceeded.await(10, TimeUnit.SECONDS)) {
            fail("onRetriesExceeded not called");
        }
    }

    @Test
    public void onFailedAttempt() throws Exception {
        stop(); //server not connected

        final int maxRetries = 2;
        final CountDownLatch failedAttempt = new CountDownLatch(maxRetries);

        StreamClient.sse("http://localhost:9000/id")
                .onFailedAttempt(failedAttempt::countDown)
                .maxRetries(2)
                .connect();

        if (!failedAttempt.await(10, TimeUnit.SECONDS)) {
            fail("onFailedAttempt not called " + maxRetries + " times");
        }
    }

    @Test
    public void connectionRetry() throws Exception {
        stop(); //server not connected

        CountDownLatch messageReceived = new CountDownLatch(1);
        CountDownLatch error = new CountDownLatch(1);

        StreamClient.sse("http://localhost:9000/id")
                .maxRetries(5)
                .onEvent(eventData -> {
                    System.out.println(eventData);
                    messageReceived.countDown();
                })
                .onError((e) -> error.countDown())
                .connect();


        if (!error.await(5, TimeUnit.SECONDS)) {
            fail("onError callback was not called");
        }

        init(); //server startup

        if (!messageReceived.await(10, TimeUnit.SECONDS)) {
            fail("No message was received after connection autoReconnect");
        }
    }

}
