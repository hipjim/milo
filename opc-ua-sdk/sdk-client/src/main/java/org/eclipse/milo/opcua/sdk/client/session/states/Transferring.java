/*
 * Copyright (c) 2017 Kevin Herron
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 *
 * The Eclipse Public License is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *   http://www.eclipse.org/org/documents/edl-v10.html.
 */

package org.eclipse.milo.opcua.sdk.client.session.states;

import java.util.concurrent.CompletableFuture;

import org.eclipse.milo.opcua.sdk.client.OpcUaSession;
import org.eclipse.milo.opcua.sdk.client.session.Fsm;
import org.eclipse.milo.opcua.sdk.client.session.events.ChannelInactiveEvent;
import org.eclipse.milo.opcua.sdk.client.session.events.CloseSessionEvent;
import org.eclipse.milo.opcua.sdk.client.session.events.CreateSessionEvent;
import org.eclipse.milo.opcua.sdk.client.session.events.Event;
import org.eclipse.milo.opcua.sdk.client.session.events.TransferFailureEvent;
import org.eclipse.milo.opcua.sdk.client.session.events.TransferSuccessEvent;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.milo.opcua.stack.core.util.FutureUtils.complete;

public class Transferring extends AbstractSessionState implements SessionState {

    private static final Logger LOGGER = LoggerFactory.getLogger(Transferring.class);

    private CompletableFuture<OpcUaSession> sessionFuture;

    @Override
    public CompletableFuture<OpcUaSession> getSessionFuture() {
        return sessionFuture;
    }

    @Override
    public void onExternalTransition(Fsm fsm, SessionState from, Event event) {
        sessionFuture = from.getSessionFuture();
    }

    @Override
    public void onInternalTransition(Fsm fsm, Event event) {
        if (event instanceof CreateSessionEvent) {
            // Another call to SessionFsm.create() results in an internal transition; we need to ensure
            // the sessionFuture in this event is completed with the result of the one that originally
            // started the create session process.
            CreateSessionEvent e = (CreateSessionEvent) event;

            complete(e.getSessionFuture()).with(sessionFuture);
        }
    }

    @Override
    public SessionState execute(Fsm fsm, Event event) {
        if (event instanceof TransferSuccessEvent) {
            OpcUaSession session = ((TransferSuccessEvent) event).getSession();

            fsm.getClient().getConfig().getExecutor()
                .submit(() -> sessionFuture.complete(session));

            return new Active();
        } else if (event instanceof TransferFailureEvent) {
            TransferFailureEvent e = (TransferFailureEvent) event;

            Closing closing = new Closing();

            closeSessionAsync(fsm, e.getSession(), closing.getCloseFuture(), e.getSessionFuture());

            return closing;
        } else if (event instanceof CloseSessionEvent) {
            // CloseSessionEvent preempted our receipt of a TransferFailureEvent or TransferSuccessEvent.
            // Closing state will receive one of those events and execute the appropriate action.
            return new Closing();
        } else if (event instanceof ChannelInactiveEvent) {
            sessionFuture.completeExceptionally(
                new UaException(StatusCodes.Bad_ConnectionClosed));

            return new Inactive();
        } else {
            return this;
        }
    }

}
