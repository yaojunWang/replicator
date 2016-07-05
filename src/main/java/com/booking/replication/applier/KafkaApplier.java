package com.booking.replication.applier;

import static com.codahale.metrics.MetricRegistry.name;

import com.booking.replication.Configuration;
import com.booking.replication.Metrics;
import com.booking.replication.augmenter.AugmentedRow;
import com.booking.replication.augmenter.AugmentedRowsEvent;
import com.booking.replication.augmenter.AugmentedSchemaChangeEvent;
import com.booking.replication.pipeline.PipelineOrchestrator;

import com.google.code.or.binlog.impl.event.FormatDescriptionEvent;
import com.google.code.or.binlog.impl.event.QueryEvent;
import com.google.code.or.binlog.impl.event.RotateEvent;
import com.google.code.or.binlog.impl.event.XidEvent;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by raynald on 08/06/16.
 */

public class KafkaApplier implements Applier {
    private static long totalRowsCounter = 0;
    private static long totalOutliersCounter = 0;
    private KafkaProducer<String, String> producer;
    private KafkaConsumer<String, String> consumer;
    private static List<String> topicList;
    private String schemaName;

    private AtomicBoolean exceptionFlag = new AtomicBoolean(false);
    private static final Meter kafka_messages = Metrics.registry.meter(name("Kafka", "producerToBroker"));
    private static final Counter exception_counter = Metrics.registry.counter(name("Kafka", "exceptionCounter"));
    private static final Counter outlier_counter = Metrics.registry.counter(name("Kafka", "outliersCounter"));
    private static final Timer closureTimer = Metrics.registry.timer(name("Kafka", "producerCloseTimer"));
    private static final HashMap<Integer, String> lastCommited = new HashMap<>();
    private int numberOfPartition;
    private String brokerAddress;
    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaApplier.class);
    private String eventLastUuid = "";

    private static Properties getProducerProperties(String broker) {
        // Below is the new version of producer configuration
        Properties prop = new Properties();
        prop.put("bootstrap.servers", broker);
        prop.put("acks", "all"); // Default 1
        prop.put("retries", 30); // Default value: 0
        prop.put("batch.size", 5384); // Default value: 16384
        prop.put("linger.ms", 20); // Default 0, Artificial delay
        prop.put("buffer.memory", 33554432); // Default value: 33554432
        prop.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        prop.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        prop.put("metric.reporters", "com.booking.replication.applier.KafkaMetricsCollector");
        prop.put("request.timeout.ms", 100000);
        return prop;
    }

    private static Properties getConsumerProperties(String broker) {
        // Consumer configuration
        Properties prop = new Properties();
        prop.put("bootstrap.servers", broker);
        prop.put("group.id", "getLastCommittedMessages");
        prop.put("auto.offset.reset", "latest");
        prop.put("enable.auto.commit", "false");
        prop.put("auto.commit.interval.ms", "1000");
        prop.put("session.timeout.ms", "30000");
        prop.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        prop.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        return prop;
    }

    public KafkaApplier(Configuration configuration) throws IOException {
        // Constructor of KafkaApplier
        brokerAddress = configuration.getKafkaBrokerAddress();
        producer = new KafkaProducer<>(getProducerProperties(brokerAddress));
        schemaName = configuration.getReplicantSchemaName();
        numberOfPartition = producer.partitionsFor(schemaName).size();
        consumer = new KafkaConsumer<>(getConsumerProperties(brokerAddress));
        topicList = configuration.getKafkaTopicList();
        LOGGER.info("Start to fetch last positions");
        // Enable it to fetch lats committed messages on each partition to prevent duplicate messages
        getLastPosition();
        LOGGER.info("Size of last committed hashmap: " + lastCommited.size());
        for (Integer i: lastCommited.keySet()) {
            LOGGER.info("Show last committed partition: " + i.toString() + " -> uniqueID: " + lastCommited.get(i));
        }
    }

    private void getLastPosition() throws IOException {
        // Method to fetch the last committed message in each partition of each topic.
        final int RoundLimit = 100;
        ConsumerRecords<String, String> records;
        final int POLL_TIME_OUT = 1000;

        for (PartitionInfo pi: producer.partitionsFor(schemaName)) {
            if (pi.partition() != 5 && pi.partition() != 1) {
                continue;
            }
            TopicPartition partition = new TopicPartition(schemaName, pi.partition());
            consumer.assign(Collections.singletonList(partition));
            LOGGER.info("Position: " + String.valueOf(consumer.position(partition)));
            long lastPosition = consumer.position(partition);
            // There is an edge case here. With a brand new partition, consumer position is equal to 0
            if (lastPosition > 0) {
                LOGGER.info("Consumer seek to position minus one");
                consumer.seek(partition, lastPosition - 1);
                if (consumer.position(partition) != lastPosition - 1) {
                    LOGGER.error("Error seek position minus one");
                }
                int round = 0;
                while (!lastCommited.containsKey(pi.partition()) && round < RoundLimit) {
                    records = consumer.poll(POLL_TIME_OUT);
                    for (ConsumerRecord<String, String> record : records) {
                        String cutString = record.value().substring(record.value().indexOf("uniqueID"));
                        // TODO: JsonDeserialization doesn't work for BinlogEventV4Header
                        // Now extracting uuid from String by index instead
                        String uuid = cutString.substring("uniqueID': ".length(), cutString.indexOf(",") - 1);
                        if (!lastCommited.containsKey(pi.partition()) || lastCommited.get(pi.partition()).compareTo(uuid) < 0) {
                            lastCommited.put(pi.partition(), uuid);
                        }
                    }
                    round++;
                }
                if (!lastCommited.containsKey(pi.partition())) {
                    LOGGER.error("Poll failed, probably the messages get purged!");
                    System.exit(1);
                }
            }
        }
    }

    @Override
    public void applyAugmentedRowsEvent(AugmentedRowsEvent augmentedSingleRowEvent, PipelineOrchestrator caller) {
        final int AggregationLimit = 500;
        ProducerRecord<String, String> message;
        long singleRowsCounter = 0;
        int partitionNum;
        long eventPosition;
        String topic;
        String rowUniqueID;
        String binlogFileName = augmentedSingleRowEvent.getBinlogFileName();

        for (AugmentedRow row : augmentedSingleRowEvent.getSingleRowEvents()) {
            if (exceptionFlag.get()) {
                throw new RuntimeException("Producer has problem with sending messages, could be a connection issue");
            }
            if (row.getTableName() == null) {
                LOGGER.error("tableName not exists");
                throw new RuntimeException("tableName does not exist");
            }

            topic = row.getTableName();
            eventPosition = row.getEventV4Header().getPosition();
            if (topicList.contains(topic)) {
                totalRowsCounter++;
                rowUniqueID = String.format("%s:%020d:%03d", binlogFileName, eventPosition, singleRowsCounter ++);
                if (rowUniqueID.compareTo(eventLastUuid) <= 0) {
                    throw new RuntimeException("Something wrong with the event position. This should never happen.");
                }
                eventLastUuid = rowUniqueID;
                partitionNum = (row.getTableName().hashCode() % numberOfPartition + numberOfPartition) % numberOfPartition;
                if (!lastCommited.containsKey(partitionNum)
                        || rowUniqueID.compareTo(lastCommited.get(partitionNum)) > 0) {
                    row.setUniqueID(rowUniqueID);
                    message = new ProducerRecord<>(
                            schemaName,
                            partitionNum,
                            rowUniqueID,
                            row.toJson());
                    producer.send(message, new Callback() {
                        @Override
                        public void onCompletion(RecordMetadata recordMetadata, Exception sendException) {
                            if (sendException != null) {
                                LOGGER.error("Error producing to Kafka broker");
                                sendException.printStackTrace();
                                exceptionFlag.set(true);
                                exception_counter.inc();
                            }
                        }
                    });
                    if (totalRowsCounter % AggregationLimit == 0) {
                        LOGGER.info(String.format("%d lines have been sent to Kafka broker...", AggregationLimit));
                    }
                    kafka_messages.mark();
                }
            } else {
                totalOutliersCounter ++;
                if (totalOutliersCounter % 500 == 0) {
                    LOGGER.warn(String.format("Over %d non-supported topics, for example: %s", AggregationLimit, topic));
                }
                outlier_counter.inc();
            }
        }
    }

    @Override
    public void applyCommitQueryEvent(QueryEvent event) {

    }

    @Override
    public void applyXidEvent(XidEvent event) {

    }

    @Override
    public void applyRotateEvent(RotateEvent event) {

    }

    @Override
    public void applyAugmentedSchemaChangeEvent(AugmentedSchemaChangeEvent augmentedSchemaChangeEvent, PipelineOrchestrator caller) {

    }

    @Override
    public void forceFlush() {

    }

    @Override
    public void applyFormatDescriptionEvent(FormatDescriptionEvent event) {

    }

    @Override
    public void waitUntilAllRowsAreCommitted(RotateEvent event) {
        final Timer.Context context = closureTimer.time();
        producer.close();
        context.stop();
        producer = new KafkaProducer<>(getProducerProperties(brokerAddress));
        LOGGER.info("A new producer has been created");
    }
}
