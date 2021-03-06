/*
 * Copyright 2002-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.mthizo247.cloud.netflix.zuul.web.socket;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandler;
import org.springframework.util.ErrorHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.ConnectionManagerSupport;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * A web socket connection manager bridge between client and backend server via zuul
 * reverse proxy
 *
 * @author Ronald Mthombeni
 * @author Salman Noor
 */
public class ProxyWebSocketConnectionManager extends ConnectionManagerSupport
		implements StompSessionHandler {
	private final WebSocketStompClient stompClient;
	private StompSession serverSession;
	private Map<String, StompSession.Subscription> subscriptions = new ConcurrentHashMap<>();
	private ErrorHandler errorHandler;
	private SimpMessagingTemplate messagingTemplate;
	private String uri;

	public ProxyWebSocketConnectionManager(SimpMessagingTemplate messagingTemplate,
			WebSocketStompClient stompClient, String uri) {
		super(uri);
		this.messagingTemplate = messagingTemplate;
		this.stompClient = stompClient;
		this.uri = uri;
	}

	public String getUrl(){
		return this.uri;
	}
	
	public void errorHandler(ErrorHandler errorHandler) {
		this.errorHandler = errorHandler;
	}

	private WebSocketHttpHeaders buildWebSocketHttpHeaders() {
		return new WebSocketHttpHeaders();
	}

	@Override
	protected void openConnection() {
		connect();
	}

	public void connect() {
		try {
			serverSession = stompClient
					.connect(
						getUri().toString(),
						buildWebSocketHttpHeaders(),
						this)
					.get();
		}
		catch (Exception e) {
			logger.error("Error connecting to web socket uri " + getUri(), e);
			throw new RuntimeException(e);
		}
	}

	public void reconnect(final long delay) {
		if (delay > 0) {
			logger.warn("Connection lost or refused, will attempt to reconnect after "
					+ delay + " millis");
			try {
				Thread.sleep(delay);
			}
			catch (InterruptedException e) {
				//
			}
		}

		Set<String> destinations = new HashSet<>(subscriptions.keySet());

		connect();

		for (String destination : destinations) {
			try {
				subscribe(destination);
			}
			catch (Exception ignored) {
				// nothing
			}
		}
	}

	@Override
	protected void closeConnection() throws Exception {
		if (isConnected()) {
			this.serverSession.disconnect();
		}
	}

	@Override
	protected boolean isConnected() {
		return (this.serverSession != null && this.serverSession.isConnected());
	}

	@Override
	public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
		if (logger.isDebugEnabled()) {
			logger.debug("Proxied target now connected " + session);
		}
	}

	@Override
	public void handleException(StompSession session, StompCommand command,
			StompHeaders headers, byte[] payload, Throwable ex) {
		if (errorHandler != null) {
			errorHandler.handleError(new ProxySessionException(this, session, ex));
		}
	}

	@Override
	public void handleTransportError(StompSession session, Throwable ex) {
		if (errorHandler != null) {
			errorHandler.handleError(new ProxySessionException(this, session, ex));
		}
	}

	@Override
	public Type getPayloadType(StompHeaders headers) {
		return Object.class;
	}

	public void sendMessage(final String destination, final Object msg) {
		if (msg instanceof String) { // in case of a json string to avoid double
										// converstion by the converters
			serverSession.send(destination, ((String) msg).getBytes());
			return;
		}

		serverSession.send(destination, msg);
	}

	@Override
	public void handleFrame(StompHeaders headers, Object payload) {
		if (headers.getDestination() != null) {
			String destination = headers.getDestination();
			if (logger.isDebugEnabled()) {
				logger.debug("Received " + payload + ", To " + headers.getDestination());
			}
			messagingTemplate.convertAndSend(destination, payload,
					copyHeaders(headers.toSingleValueMap()));
		}
	}

	private Map<String, Object> copyHeaders(Map<String, String> original) {
		Map<String, Object> copy = new HashMap<>();
		for (String key : original.keySet()) {
			copy.put(key, original.get(key));
		}

		return copy;
	}

	private void connectIfNecessary() {
		if (!isConnected()) {
			connect();
		}
	}

	public void subscribe(String destination) throws Exception {
		connectIfNecessary();
		if(!subscriptions.containsKey(destination)){
			StompSession.Subscription subscription = serverSession.subscribe(destination, this);
			subscriptions.put(destination, subscription);
		}
	}

	public void unsubscribe(String destination) {
		StompSession.Subscription subscription = subscriptions.remove(destination);
		if (subscription != null) {
			connectIfNecessary();
			subscription.unsubscribe();
		}
	}

	public void disconnect() {
		try {
			closeConnection();
		}
		catch (Exception e) {
			// nothing
		}
	}
}
