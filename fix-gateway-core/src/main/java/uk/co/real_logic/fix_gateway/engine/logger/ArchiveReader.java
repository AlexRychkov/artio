/*
 * Copyright 2015 Real Logic Ltd.
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
package uk.co.real_logic.fix_gateway.engine.logger;

import uk.co.real_logic.aeron.logbuffer.BlockHandler;
import uk.co.real_logic.aeron.logbuffer.FragmentHandler;
import uk.co.real_logic.aeron.logbuffer.Header;
import uk.co.real_logic.aeron.logbuffer.LogBufferDescriptor;
import uk.co.real_logic.agrona.IoUtil;
import uk.co.real_logic.agrona.collections.Int2ObjectCache;
import uk.co.real_logic.agrona.collections.Int2ObjectHashMap;
import uk.co.real_logic.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.fix_gateway.messages.ArchiveMetaDataDecoder;
import uk.co.real_logic.fix_gateway.replication.StreamIdentifier;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.util.function.IntFunction;

import static java.lang.Integer.numberOfTrailingZeros;
import static uk.co.real_logic.aeron.protocol.DataHeaderFlyweight.HEADER_LENGTH;

public class ArchiveReader implements AutoCloseable
{
    private final IntFunction<SessionReader> newSessionReader = this::newSessionReader;

    /**
     * Cannot read this fragment - your session id doesn't exist in the archive.
     */
    public static final int UNKNOWN_SESSION = -1;

    /**
     * Cannot read this fragment - your term doesn't exist in the archive for this session.
     */
    public static final int UNKNOWN_TERM = -2;

    /**
     * Cannot read this fragment - your session/term/position combination isn't the beginning of a message.
     */
    public static final long NO_MESSAGE = -3;

    private final Int2ObjectHashMap<SessionReader> aeronSessionIdToReader;
    private final ExistingBufferFactory archiveBufferFactory;
    private final ArchiveMetaData metaData;
    private final StreamIdentifier streamId;
    private final LogDirectoryDescriptor directoryDescriptor;
    private final int cacheNumSets;
    private final int cacheSetSize;

    public ArchiveReader(
        final ArchiveMetaData metaData,
        final int cacheNumSets,
        final int cacheSetSize,
        final StreamIdentifier streamId)
    {
        this.cacheNumSets = cacheNumSets;
        this.cacheSetSize = cacheSetSize;
        archiveBufferFactory = LoggerUtil::mapExistingFile;
        this.metaData = metaData;
        this.streamId = streamId;
        directoryDescriptor = metaData.directoryDescriptor();
        aeronSessionIdToReader = new Int2ObjectHashMap<>();
    }

    public void close()
    {
        metaData.close();
        aeronSessionIdToReader.values().forEach(SessionReader::close);
    }

    /**
     * Reads a message out of the log archive.
     *
     * @param aeronSessionId the session to read from
     * @param position the log position to start reading at
     * @param handler the handler to pass the data into
     * @return the position after the end of this message. If there's another message, then this is its start.
     */
    public long read(final int aeronSessionId, final long position, final FragmentHandler handler)
    {
        final SessionReader sessionReader = session(aeronSessionId);
        if (sessionReader == null)
        {
            return UNKNOWN_SESSION;
        }

        return sessionReader.read(position, handler);
    }

    /**
     * Reads a message out of the log archive.
     *
     * @param aeronSessionId the session to read from
     * @param beginPosition the log position to start reading at
     * @param endPosition the last start position of a message to stop reading at (NB: can read up to a fragment beyond)
     * @param handler the handler to pass the data into
     * @return the position after the end of this message. If there's another message, then this is its start.
     */
    public long readUpTo(
        final int aeronSessionId, final long beginPosition, final long endPosition, final FragmentHandler handler)
    {
        final SessionReader sessionReader = session(aeronSessionId);
        if (sessionReader == null)
        {
            return UNKNOWN_SESSION;
        }

        return sessionReader.readUpTo(beginPosition, endPosition, handler);
    }

    /**
     * Reads a block of bytes out of the log archive.
     *
     * A block will only be read if the archive contains the whole block.
     *
     * @param aeronSessionId the session to read from
     * @param position the log position to start reading at
     * @param length the length of data read
     * @param handler the handler to pass the data into
     * @return true if the message has been read, false otherwise
     */
    public boolean readBlock(
        final int aeronSessionId, final long position, final int length, final BlockHandler handler)
    {
        final SessionReader sessionReader = session(aeronSessionId);
        return sessionReader != null && sessionReader.readBlock(position, length, handler);
    }

    public SessionReader session(final int aeronSessionId)
    {
        return aeronSessionIdToReader.computeIfAbsent(aeronSessionId, newSessionReader);
    }

    private SessionReader newSessionReader(final int sessionId)
    {
        final ArchiveMetaDataDecoder streamMetaData = metaData.read(streamId, sessionId);
        if (streamMetaData == null)
        {
            return null;
        }

        return new SessionReader(sessionId, streamMetaData.initialTermId(), streamMetaData.termBufferLength());
    }

    public class SessionReader implements AutoCloseable
    {
        private final IntFunction<ByteBuffer> newBuffer = this::newBuffer;
        private final int sessionId;
        private final Int2ObjectCache<ByteBuffer> termIdToBuffer =
            new Int2ObjectCache<>(cacheNumSets, cacheSetSize, this::closeBuffer);
        private final UnsafeBuffer buffer = new UnsafeBuffer(0, 0);
        private final int initialTermId;
        private final int positionBitsToShift;
        private final Header header;

        SessionReader(final int sessionId, final int initialTermId, final int termBufferLength)
        {
            this.sessionId = sessionId;
            this.initialTermId = initialTermId;
            positionBitsToShift = numberOfTrailingZeros(termBufferLength);
            header = new Header(this.initialTermId, termBufferLength);
        }

        /**
         * Reads a message out of this session's log archive.
         *
         * @param position the log position to start reading at
         * @param handler the handler to pass the data into
         * @return the position after the end of this message. If there's another message, then this is its start.
         */
        public long read(final long position, final FragmentHandler handler)
        {
            final int termOffset = scan(position);
            if (termOffset == UNKNOWN_TERM)
            {
                return UNKNOWN_TERM;
            }

            final int frameLength = header.frameLength();
            if (frameLength == 0)
            {
                return NO_MESSAGE;
            }

            handler.onFragment(buffer, termOffset, frameLength - HEADER_LENGTH, header);

            return position + frameLength;
        }

        /**
         * Reads a message out of this session's log archive.
         *
         * @param beginPosition the log position to start reading at
         * @param endPosition the last start position of a message to stop reading at (NB: can read up to a fragment beyond)
         * @param handler the handler to pass the data into
         * @return the position after the end of this message. If there's another message, then this is its start.
         */
        public long readUpTo(final long beginPosition, final long endPosition, final FragmentHandler handler)
        {
            long position = beginPosition;
            while (position >= 0)
            {
                final int termOffset = scan(position);
                if (termOffset == UNKNOWN_TERM)
                {
                    return position;
                }

                final int frameLength = header.frameLength();
                if (frameLength == 0)
                {
                    return position;
                }

                final int bodyLength = frameLength - HEADER_LENGTH;
                final long messageEnd = position + bodyLength;
                if (messageEnd > endPosition)
                {
                    return position;
                }

                handler.onFragment(buffer, termOffset, bodyLength, header);

                position += frameLength;
            }

            return position;
        }

        /**
         * Reads a block of bytes out of this session's log archive.
         *
         * A block will only be read if the archive contains the whole block.
         *
         * @param position the log position to start reading at
         * @param handler the handler to pass the data into
         * @return true if the message has been read, false otherwise
         */
        public boolean readBlock(final long position, final int requestedLength, final BlockHandler handler)
        {
            final int termId = computeTermIdFromPosition(position);
            final ByteBuffer termBuffer = termIdToBuffer.computeIfAbsent(termId, newBuffer);
            if (termBuffer == null)
            {
                return false;
            }

            buffer.wrap(termBuffer);
            final int termOffset = computeTermOffsetFromPosition(position);
            final int remainder = termBuffer.capacity() - termOffset;
            final int length = Math.min(requestedLength, remainder);

            handler.onBlock(buffer, termOffset, length, sessionId, termId);

            return true;
        }

        private ByteBuffer newBuffer(final int termId)
        {
            final File logFile = directoryDescriptor.logFile(streamId, sessionId, termId);
            if (!logFile.exists())
            {
                return null;
            }

            return archiveBufferFactory.map(logFile);
        }

        private int scan(final long position)
        {
            final int termId = computeTermIdFromPosition(position);
            final ByteBuffer termBuffer = termIdToBuffer.computeIfAbsent(termId, newBuffer);
            if (termBuffer == null)
            {
                return UNKNOWN_TERM;
            }

            final int termOffset = computeTermOffsetFromPosition(position);
            final int headerOffset = termOffset - HEADER_LENGTH;
            buffer.wrap(termBuffer);
            header.buffer(buffer);
            header.offset(headerOffset);

            return termOffset;
        }

        private int computeTermOffsetFromPosition(final long position)
        {
            return LogBufferDescriptor.computeTermOffsetFromPosition(position, positionBitsToShift);
        }

        private int computeTermIdFromPosition(final long position)
        {
            return LogBufferDescriptor.computeTermIdFromPosition(position, positionBitsToShift, initialTermId);
        }

        public void close()
        {
            termIdToBuffer.clear();
        }

        private void closeBuffer(final ByteBuffer buffer)
        {
            if (buffer instanceof MappedByteBuffer)
            {
                IoUtil.unmap((MappedByteBuffer)buffer);
            }
        }
    }
}
