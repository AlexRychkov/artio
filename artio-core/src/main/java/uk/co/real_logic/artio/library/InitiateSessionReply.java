/*
 * Copyright 2015-2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.library;

import org.agrona.collections.IntArrayList;
import uk.co.real_logic.artio.FixGatewayException;
import uk.co.real_logic.artio.messages.GatewayError;
import uk.co.real_logic.artio.session.InternalSession;
import uk.co.real_logic.artio.session.Session;

import java.util.List;

import static uk.co.real_logic.artio.messages.GatewayError.UNABLE_TO_CONNECT;

/**
 * .
 */
class InitiateSessionReply extends LibraryReply<Session>
{
    private final SessionConfiguration configuration;

    private int addressIndex = 0;
    private boolean requiresResend;

    InitiateSessionReply(
        final LibraryPoller libraryPoller,
        final long latestReplyArrivalTime,
        final SessionConfiguration configuration)
    {
        super(libraryPoller, latestReplyArrivalTime);
        this.configuration = configuration;
        if (libraryPoller.isConnected())
        {
            sendMessage();
        }
    }

    private void sendMessage()
    {
        final List<String> hosts = configuration.hosts();
        final IntArrayList ports = configuration.ports();
        final int size = hosts.size();
        if (addressIndex >= size)
        {
            onError(new FixGatewayException("Unable to connect to any of the addresses specified"));
            return;
        }

        final String host = hosts.get(addressIndex);
        final int port = ports.getInt(addressIndex);

        final long position = libraryPoller.saveInitiateConnection(host, port, correlationId, configuration);

        requiresResend = position < 0;
    }

    void onError(final GatewayError errorType, final String errorMessage)
    {
        if (errorType == UNABLE_TO_CONNECT)
        {
            addressIndex++;
            register();
            sendMessage();
        }
        else
        {
            onError(new FixGatewayException(String.format("%s: %s", errorType, errorMessage)));
        }
    }

    void onComplete(final Session result)
    {
        final String host = configuration.hosts().get(addressIndex);
        final int port = configuration.ports().getInt(addressIndex);
        ((InternalSession)result).address(host, port);
        super.onComplete(result);
    }

    boolean poll(final long timeInMs)
    {
        if (requiresResend)
        {
            sendMessage();
        }

        return super.poll(timeInMs);
    }

    protected boolean onTimeout()
    {
        if (libraryPoller.saveMidConnectionDisconnect(correlationId) > 0)
        {
            super.onTimeout();
        }

        return false;
    }

    SessionConfiguration configuration()
    {
        return configuration;
    }
}
