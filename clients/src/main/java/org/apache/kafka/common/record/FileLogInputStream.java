/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.common.record;

import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.CorruptRecordException;
import org.apache.kafka.common.utils.Utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Iterator;

import static org.apache.kafka.common.record.Records.LOG_OVERHEAD;

/**
 * A log input stream which is backed by a {@link FileChannel}.
 */
public class FileLogInputStream implements LogInputStream<FileLogInputStream.FileChannelRecordBatch> {
    private int position;
    private final int end;
    private final FileChannel channel;
    private final int maxRecordSize;
    private final ByteBuffer logHeaderBuffer = ByteBuffer.allocate(LOG_OVERHEAD);

    /**
     * Create a new log input stream over the FileChannel
     * @param channel Underlying FileChannel
     * @param maxRecordSize Maximum size of records
     * @param start Position in the file channel to start from
     * @param end Position in the file channel not to read past
     */
    FileLogInputStream(FileChannel channel,
                       int maxRecordSize,
                       int start,
                       int end) {
        this.channel = channel;
        this.maxRecordSize = maxRecordSize;
        this.position = start;
        this.end = end;
    }

    @Override
    public FileChannelRecordBatch nextBatch() throws IOException {
        if (position + LOG_OVERHEAD >= end)
            return null;

        logHeaderBuffer.rewind();
        Utils.readFullyOrFail(channel, logHeaderBuffer, position, "log header");

        logHeaderBuffer.rewind();
        long offset = logHeaderBuffer.getLong();
        int size = logHeaderBuffer.getInt();

        if (size < LegacyRecord.RECORD_OVERHEAD_V0)
            throw new CorruptRecordException(String.format("Record size is smaller than minimum record overhead (%d).", LegacyRecord.RECORD_OVERHEAD_V0));

        if (size > maxRecordSize)
            throw new CorruptRecordException(String.format("Record size exceeds the largest allowable message size (%d).", maxRecordSize));

        if (position + LOG_OVERHEAD + size > end)
            return null;

        FileChannelRecordBatch batch = new FileChannelRecordBatch(offset, channel, position, size);
        position += batch.sizeInBytes();
        return batch;
    }

    /**
     * Log entry backed by an underlying FileChannel. This allows iteration over the record batches
     * without needing to read the record data into memory until it is needed. The downside
     * is that entries will generally no longer be readable when the underlying channel is closed.
     */
    public static class FileChannelRecordBatch extends AbstractRecordBatch {
        private final long offset;
        private final FileChannel channel;
        private final int position;
        private final int entrySize;
        private RecordBatch underlying;

        private FileChannelRecordBatch(long offset,
                                       FileChannel channel,
                                       int position,
                                       int entrySize) {
            this.offset = offset;
            this.channel = channel;
            this.position = position;
            this.entrySize = entrySize;
        }

        @Override
        public long baseOffset() {
            if (magic() >= RecordBatch.MAGIC_VALUE_V2)
                return offset;

            loadUnderlyingRecordBatch();
            return underlying.baseOffset();
        }

        @Override
        public CompressionType compressionType() {
            loadUnderlyingRecordBatch();
            return underlying.compressionType();
        }

        @Override
        public TimestampType timestampType() {
            loadUnderlyingRecordBatch();
            return underlying.timestampType();
        }

        @Override
        public long maxTimestamp() {
            loadUnderlyingRecordBatch();
            return underlying.maxTimestamp();
        }

        @Override
        public long lastOffset() {
            if (magic() < RecordBatch.MAGIC_VALUE_V2)
                return offset;
            else if (underlying != null)
                return underlying.lastOffset();

            try {
                // FIXME: This logic probably should be moved into DefaultRecordBatch somehow
                // maybe we just need two separate implementations

                byte[] offsetDelta = new byte[4];
                ByteBuffer buf = ByteBuffer.wrap(offsetDelta);
                channel.read(buf, position + DefaultRecordBatch.LAST_OFFSET_DELTA_OFFSET);
                if (buf.hasRemaining())
                    throw new KafkaException("Failed to read magic byte from FileChannel " + channel);
                return offset + buf.getInt(0);
            } catch (IOException e) {
                throw new KafkaException(e);
            }
        }

        public int position() {
            return position;
        }

        @Override
        public byte magic() {
            if (underlying != null)
                return underlying.magic();

            try {
                byte[] magic = new byte[1];
                ByteBuffer buf = ByteBuffer.wrap(magic);
                Utils.readFullyOrFail(channel, buf, position + Records.MAGIC_OFFSET, "magic byte");
                return magic[0];
            } catch (IOException e) {
                throw new KafkaException(e);
            }
        }

        @Override
        public long producerId() {
            loadUnderlyingRecordBatch();
            return underlying.producerId();
        }

        @Override
        public short producerEpoch() {
            loadUnderlyingRecordBatch();
            return underlying.producerEpoch();
        }

        @Override
        public int baseSequence() {
            loadUnderlyingRecordBatch();
            return underlying.baseSequence();
        }

        @Override
        public int lastSequence() {
            loadUnderlyingRecordBatch();
            return underlying.lastSequence();
        }

        private void loadUnderlyingRecordBatch() {
            try {
                if (underlying != null)
                    return;

                ByteBuffer entryBuffer = ByteBuffer.allocate(LOG_OVERHEAD + entrySize);
                Utils.readFullyOrFail(channel, entryBuffer, position, "full entry");
                entryBuffer.rewind();

                byte magic = entryBuffer.get(LOG_OVERHEAD + LegacyRecord.MAGIC_OFFSET);
                if (magic > RecordBatch.MAGIC_VALUE_V1)
                    underlying = new DefaultRecordBatch(entryBuffer);
                else
                    underlying = new AbstractLegacyRecordBatch.ByteBufferLegacyRecordBatch(entryBuffer);
            } catch (IOException e) {
                throw new KafkaException("Failed to load log entry at position " + position + " from file channel " + channel);
            }
        }

        @Override
        public Iterator<Record> iterator() {
            loadUnderlyingRecordBatch();
            return underlying.iterator();
        }

        @Override
        public boolean isValid() {
            loadUnderlyingRecordBatch();
            return underlying.isValid();
        }

        @Override
        public void ensureValid() {
            loadUnderlyingRecordBatch();
            underlying.ensureValid();
        }

        @Override
        public long checksum() {
            loadUnderlyingRecordBatch();
            return underlying.checksum();
        }

        @Override
        public int sizeInBytes() {
            return LOG_OVERHEAD + entrySize;
        }

        @Override
        public Integer countOrNull() {
            loadUnderlyingRecordBatch();
            return underlying.countOrNull();
        }

        @Override
        public void writeTo(ByteBuffer buffer) {
            try {
                buffer.limit(entrySize + LOG_OVERHEAD);
                buffer.putLong(offset);
                buffer.putInt(entrySize);
                Utils.readFully(channel, buffer, position);
            } catch (IOException e) {
                throw new KafkaException("Failed to read log entry at position " + position + " from file channel " +
                        channel, e);
            }
        }

        @Override
        public boolean isTransactional() {
            loadUnderlyingRecordBatch();
            return underlying.isTransactional();
        }

        @Override
        public int partitionLeaderEpoch() {
            loadUnderlyingRecordBatch();
            return underlying.partitionLeaderEpoch();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            FileChannelRecordBatch that = (FileChannelRecordBatch) o;

            if (offset != that.offset) return false;
            if (position != that.position) return false;
            if (entrySize != that.entrySize) return false;
            return channel != null ? channel.equals(that.channel) : that.channel == null;
        }

        @Override
        public int hashCode() {
            int result = super.hashCode();
            result = 31 * result + (int) (offset ^ (offset >>> 32));
            result = 31 * result + (channel != null ? channel.hashCode() : 0);
            result = 31 * result + position;
            result = 31 * result + entrySize;
            return result;
        }
    }
}
