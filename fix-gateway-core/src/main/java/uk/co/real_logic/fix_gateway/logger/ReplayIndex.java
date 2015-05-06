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
package uk.co.real_logic.fix_gateway.logger;

import uk.co.real_logic.agrona.DirectBuffer;

import java.nio.MappedByteBuffer;
import java.util.function.Function;

/**
 * Builds an index of a composite key of session id and sequence number
 */
public class ReplayIndex implements Index
{
    private final Function<String, MappedByteBuffer> bufferFactory;

    public ReplayIndex(final Function<String, MappedByteBuffer> bufferFactory)
    {
        this.bufferFactory = bufferFactory;
    }

    public void close()
    {
    }

    public void indexRecord(final DirectBuffer buffer, final int offset, final int length)
    {
        final long value = 0L;
        //index.extractKey(buffer, offset, length);
    }
}
