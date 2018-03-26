package io.axway.iron.spi.aws.kinesis;

import java.io.*;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import javax.annotation.*;
import com.amazonaws.services.kinesis.AmazonKinesis;
import com.amazonaws.services.kinesis.model.DescribeStreamRequest;
import com.amazonaws.services.kinesis.model.DescribeStreamResult;
import com.amazonaws.services.kinesis.model.GetRecordsRequest;
import com.amazonaws.services.kinesis.model.GetRecordsResult;
import com.amazonaws.services.kinesis.model.GetShardIteratorRequest;
import com.amazonaws.services.kinesis.model.GetShardIteratorResult;
import com.amazonaws.services.kinesis.model.ProvisionedThroughputExceededException;
import com.amazonaws.services.kinesis.model.PutRecordRequest;
import com.amazonaws.services.kinesis.model.Record;
import com.amazonaws.services.kinesis.model.Shard;
import com.amazonaws.services.kinesis.model.ShardIteratorType;
import io.axway.iron.spi.storage.TransactionStore;

import static com.google.common.base.Preconditions.checkState;
import static java.math.BigInteger.ZERO;

/**
 * Kinesis implementation of the TransactionStore.
 * Transactions are stored in a Kinesis Stream.
 */
class AwsKinesisTransactionStore implements TransactionStore {

    private static final long MINIMUM_DURATION_BETWEEN_TWO_GET_SHARD_ITERATOR_REQUESTS = 1_000 / 4; // max 5 calls per second
    private static final String USELESS_PARTITION_KEY = "uselessPartitionKey";

    private final String m_streamName;
    private final Shard m_shard;
    private final AmazonKinesis m_kinesis;
    private BigInteger m_seekTransactionId = null;
    @Nullable
    private Long m_lastGetShardIteratorRequestTime = null;

    /**
     * Create a KinesisTransactionStore based on an already active Kinesis Stream.
     * That is not the responsibility of this constructor to create the Kinesis Stream nor to wait that the Kinesis Stream reaches the 'ACTIVE" status.
     *
     * @param kinesis the kinesis consumer
     * @param streamName the stream name
     */
    AwsKinesisTransactionStore(AmazonKinesis kinesis, String streamName) {
        m_streamName = streamName;
        m_kinesis = kinesis;
        m_shard = getUniqueShard();
    }

    /**
     * Returns the single created shard (stream has a single shard).
     */
    private Shard getUniqueShard() {
        DescribeStreamRequest describeStreamRequest = new DescribeStreamRequest().withStreamName(m_streamName).withLimit(1);
        DescribeStreamResult describeStreamResult = m_kinesis.describeStream(describeStreamRequest);
        String streamStatus = describeStreamResult.getStreamDescription().getStreamStatus();
        if (streamStatus == null || !streamStatus.equals(AwsKinesisUtils.ACTIVE_STREAM_STATUS)) {
            throw new AwsKinesisException("Stream does not exist", args -> args.add("streamName", m_streamName));
        }
        List<Shard> shards = describeStreamResult.getStreamDescription().getShards();
        checkState(shards.size() == 1, "This Kinesis Stream %s should contain a single Shard, but it contains %d shards.", m_streamName, shards.size());
        return shards.get(0);
    }

    @Override
    public OutputStream createTransactionOutput() throws IOException {
        return new ByteArrayOutputStream() {
            @Override
            public void close() throws IOException {
                super.close();
                ByteBuffer data = ByteBuffer.wrap(toByteArray());
                m_kinesis.putRecord(new PutRecordRequest().withStreamName(m_streamName).withData(data).withPartitionKey(USELESS_PARTITION_KEY));
            }
        };
    }

    @Override
    public void seekTransactionPoll(BigInteger latestProcessedTransactionId) {
        m_seekTransactionId = latestProcessedTransactionId;
    }

    @Override
    public TransactionInput pollNextTransaction(long timeout, TimeUnit unit) {
        Record record = getNextRecord();
        if (record == null) {
            return null;
        }
        BigInteger seekTransactionId = new BigInteger(record.getSequenceNumber());
        m_seekTransactionId = seekTransactionId;
        ByteBuffer data = record.getData().asReadOnlyBuffer();
        return new TransactionInput() {
            @Override
            public InputStream getInputStream() throws IOException {
                return new ByteBufferBackedInputStream(data);
            }

            @Override
            public BigInteger getTransactionId() {
                return seekTransactionId;
            }
        };
    }

    @Nullable
    private Record getNextRecord() {
        Long currentGetShardIteratorRequestTime = System.currentTimeMillis();
        if (m_lastGetShardIteratorRequestTime != null
                && (currentGetShardIteratorRequestTime - m_lastGetShardIteratorRequestTime) < MINIMUM_DURATION_BETWEEN_TWO_GET_SHARD_ITERATOR_REQUESTS) {
            return null;
        }
        m_lastGetShardIteratorRequestTime = currentGetShardIteratorRequestTime;
        GetShardIteratorRequest getShardIteratorRequest;
        if (m_seekTransactionId == null || m_seekTransactionId
                .equals(ZERO)) { // First call to pollNextTransaction, no Snapshot has been created => retrieve the oldest element
            getShardIteratorRequest = new GetShardIteratorRequest().withStreamName(m_streamName)//
                    .withShardIteratorType(ShardIteratorType.TRIM_HORIZON)//
                    .withShardId(m_shard.getShardId());
        } else {// The m_seekTransactionId has already been set => retrieve elements after m_seekTransactionId
            getShardIteratorRequest = new GetShardIteratorRequest().withStreamName(m_streamName)//
                    .withShardIteratorType(ShardIteratorType.AFTER_SEQUENCE_NUMBER)//
                    .withShardId(m_shard.getShardId())//
                    .withStartingSequenceNumber(m_seekTransactionId.toString());
        }
        GetShardIteratorResult getShardIteratorResult = m_kinesis.getShardIterator(getShardIteratorRequest);
        // Suboptimal request, should store records in a list instead. To be fixed.
        GetRecordsRequest getRecordsRequest = new GetRecordsRequest().withShardIterator(getShardIteratorResult.getShardIterator()).withLimit(1);
        List<Record> records = getRecords(getRecordsRequest);
        Record record;
        if (records.isEmpty()) {
            record = null;
        } else {
            checkState(records.size() == 1, "Kinesis should not return more than one record, and returns %d records", records.size());
            record = records.get(0);
        }
        return record;
    }

    /**
     * Retrieves records corresponding to the request.
     *
     * @param getRecordsRequest the request
     * @return records corresponding to the request
     */
    private List<Record> getRecords(GetRecordsRequest getRecordsRequest) {
        while (true) {
            try {
                GetRecordsResult getRecordsResult = m_kinesis.getRecords(getRecordsRequest);
                return getRecordsResult.getRecords();
            } catch (ProvisionedThroughputExceededException e) {
                // Too much The request rate for the stream is too high, or the requested data is too large for the available throughput. Wait to try again.
                try {
                    Thread.sleep(100);
                } catch (InterruptedException exception) {
                    throw new AwsKinesisException("Interrupted while waiting provisioned throughput does no more exceed limit",
                                                  args -> args.add("streamName", m_streamName).add("shardId", m_shard.getShardId()), exception);
                }
            }
        }
    }

    /**
     * Simple {@link InputStream} implementation that exposes currently available content of a {@link ByteBuffer}.
     */
    class ByteBufferBackedInputStream extends InputStream {
        final ByteBuffer m_byteBuffer;

        ByteBufferBackedInputStream(ByteBuffer byteBuffer) {
            m_byteBuffer = byteBuffer;
        }

        @Override
        public int available() {
            return m_byteBuffer.remaining();
        }

        @Override
        public int read() throws IOException {
            return m_byteBuffer.hasRemaining() ? m_byteBuffer.get() & 255 : -1;
        }

        @Override
        public int read(byte[] bytes, int off, int len) throws IOException {
            if (!m_byteBuffer.hasRemaining()) {
                return -1;
            }
            len = Math.min(len, m_byteBuffer.remaining());
            m_byteBuffer.get(bytes, off, len);
            return len;
        }
    }
}