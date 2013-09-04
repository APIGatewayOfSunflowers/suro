package com.netflix.suro.sink.kafka;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import com.netflix.suro.message.Message;
import com.netflix.suro.message.serde.MessageSerDe;
import com.netflix.suro.message.serde.SerDe;
import com.netflix.suro.sink.QueuedSink;
import com.netflix.suro.sink.Sink;
import com.netflix.suro.sink.queue.MemoryQueue4Sink;
import com.netflix.suro.sink.queue.MessageQueue4Sink;
import kafka.producer.*;
import kafka.producer.ProducerStatsRegistry;
import kafka.producer.ProducerTopicStatsRegistry;
import kafka.serializer.DefaultEncoder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class KafkaSink extends QueuedSink implements Sink {
    public final static String TYPE = "kafka";

    private String clientId;

    protected final KafkaProducer producer;
    protected SerDe<Message> msgSerDe = new MessageSerDe();

    @JsonCreator
    public KafkaSink(
            @JsonProperty("queue4Sink") MessageQueue4Sink queue4Sink,
            @JsonProperty("client.id") String clientId,
            @JsonProperty("metadata.broker.list") String brokerList,
            @JsonProperty("compression.codec") String codec,
            @JsonProperty("send.buffer.bytes") int sendBufferBytes,
            @JsonProperty("batchSize") int batchSize,
            @JsonProperty("batchTimeout") int batchTimeout,
            @JsonProperty("request.timeout.ms") int requestTimeout,
            @JsonProperty("request.required.acks") Integer acks,
            @JsonProperty("message.send.max.retries") int maxRetries,
            @JsonProperty("retry.backoff.ms") int retryBackoff,
            @JsonProperty("kafka.metrics") Properties metricsProps
    ) {
        Preconditions.checkNotNull(brokerList);
        Preconditions.checkNotNull(acks);
        Preconditions.checkNotNull(clientId);

        this.clientId = clientId;
        initialize(queue4Sink == null ? new MemoryQueue4Sink(10000) : queue4Sink, batchSize, batchTimeout);

        Properties props = new Properties();
        props.put("client.id", clientId);
        props.put("metadata.broker.list", brokerList);
        if (codec != null) {
            props.put("compression.codec", codec);
        }
        props.put("reconnect.interval", Integer.toString(Integer.MAX_VALUE));
        if (sendBufferBytes > 0) {
            props.put("send.buffer.bytes", Integer.toString(sendBufferBytes));
        }
        if (requestTimeout > 0) {
            props.put("request.timeout.ms", Integer.toString(requestTimeout));
        }
        props.put("request.required.acks", acks.toString());

        if (maxRetries > 0) {
            props.put("message.send.max.retries", Integer.toString(maxRetries));
        }
        if (retryBackoff > 0) {
            props.put("retry.backoff.ms", Integer.toString(retryBackoff));
        }
        props.put("serializer.class", DefaultEncoder.class.getName());

        if (metricsProps != null) {
            props.putAll(metricsProps);
        }

        producer = new KafkaProducer(props);
    }

    @Override
    public void writeTo(Message message) {
        queue4Sink.offer(message);
    }

    @Override
    public void open() {
        setName(KafkaSink.class.getSimpleName() + "-" + clientId);
        start();
    }

    @Override
    protected void beforePolling() throws IOException { /*do nothing */}

    @Override
    protected void write(List<Message> msgList) throws IOException {
        send(msgList);
        afterSend(msgList);
    }

    @Override
    protected void innerClose() throws IOException {
        queue4Sink.close();
        producer.close();
    }

    @Override
    public String recvNotify() {
        return null;
    }

    @Override
    public String getStat() {
        ProducerStats stats = ProducerStatsRegistry.getProducerStats(clientId);
        ProducerTopicStats topicStats = ProducerTopicStatsRegistry.getProducerTopicStats(clientId);

        StringBuilder sb = new StringBuilder();
        sb.append("resend rate: ").append(stats.resendRate().count()).append('\n');
        sb.append("serialization error rate: " ).append(stats.serializationErrorRate().count()).append('\n');
        sb.append("failed send rate: ").append(stats.failedSendRate().count()).append('\n');
        sb.append("message rate: ").append(topicStats.getProducerAllTopicsStats().messageRate().count()).append('\n');
        sb.append("byte rate: " ).append(topicStats.getProducerAllTopicsStats().byteRate().count()).append('\n');
        sb.append("dropped message rate: " ).append(topicStats.getProducerAllTopicsStats().droppedMessageRate().count()).append('\n');

        return sb.toString();
    }

    private void afterSend(List<Message> msgList) {
        msgList.clear();
        queue4Sink.commit();
        lastBatch = System.currentTimeMillis();
    }

    private List<KeyedMessage<String, byte[]>> kafkaMsgList = new ArrayList<KeyedMessage<String, byte[]>>();
    protected void send(List<Message> msgList) {
        for (Message m : msgList) {
            kafkaMsgList.add(new KeyedMessage<String, byte[]>(m.getRoutingKey(), msgSerDe.serialize(m)));
        }
        producer.send(kafkaMsgList);
        kafkaMsgList.clear();
    }
}
