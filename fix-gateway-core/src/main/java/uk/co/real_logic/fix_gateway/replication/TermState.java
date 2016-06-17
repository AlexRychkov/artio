/*
 * Copyright 2015-2016 Real Logic Ltd.
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
package uk.co.real_logic.fix_gateway.replication;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class TermState
{
    static final int NO_LEADER = 0;

    /** the aeron session id of the current leader */
    private AtomicInteger leaderSessionId = new AtomicInteger(NO_LEADER);

    /** The raft leader's current term number */
    private int leadershipTerm;

    /** The position within the current leadership term that we have read data on the session up to. */
    private long receivedPosition;

    /** The position within the current leadership term that we have applied to our state machine. */
    private long lastAppliedPosition;

    /** The position within the current leadership term that we can commit up to. */
    private final AtomicLong consensusPosition = new AtomicLong(0);

    private volatile LeaderPosition leaderPosition;

    public LeaderPosition leaderPosition()
    {
        return leaderPosition;
    }

    public TermState leaderPosition(final LeaderPosition leaderPosition)
    {
        this.leaderPosition = leaderPosition;
        return this;
    }

    public TermState leaderSessionId(int leadershipSessionId)
    {
        this.leaderSessionId.set(leadershipSessionId);
        return this;
    }

    public TermState noLeader()
    {
        leaderPosition = null;
        return this;
    }

    public TermState leadershipTerm(int leadershipTerm)
    {
        this.leadershipTerm = leadershipTerm;
        return this;
    }

    public TermState receivedPosition(final long receivedPosition)
    {
        this.receivedPosition = receivedPosition;
        return this;
    }

    public TermState lastAppliedPosition(final long lastAppliedPosition)
    {
        this.lastAppliedPosition = lastAppliedPosition;
        return this;
    }

    public TermState consensusPosition(final long consensusPosition)
    {
        consensusPosition().set(consensusPosition);
        return this;
    }

    public TermState allPositions(final long position)
    {
        receivedPosition(position);
        lastAppliedPosition(position);
        consensusPosition(position);
        return this;
    }

    public AtomicInteger leaderSessionId()
    {
        return leaderSessionId;
    }

    public boolean hasLeader()
    {
        return leaderSessionId.get() != NO_LEADER;
    }

    public int leadershipTerm()
    {
        return leadershipTerm;
    }

    public long receivedPosition()
    {
        return receivedPosition;
    }

    public long lastAppliedPosition()
    {
        return lastAppliedPosition;
    }

    public AtomicLong consensusPosition()
    {
        return consensusPosition;
    }

    public TermState reset()
    {
        noLeader();
        leaderSessionId(0);
        leadershipTerm(0);
        allPositions(0);
        return this;
    }

    @Override
    public String toString()
    {
        return "TermState{" +
            "leaderSessionId=" + leaderSessionId +
            ", leadershipTerm=" + leadershipTerm +
            ", receivedPosition=" + receivedPosition +
            ", lastAppliedPosition=" + lastAppliedPosition +
            ", consensusPosition=" + consensusPosition +
            '}';
    }
}
