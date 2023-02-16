package com.linkedin.venice.utils;

import com.linkedin.venice.kafka.consumer.KafkaConsumerFactoryImpl;
import com.linkedin.venice.kafka.protocol.ControlMessage;
import com.linkedin.venice.kafka.protocol.KafkaMessageEnvelope;
import com.linkedin.venice.kafka.protocol.StartOfPush;
import com.linkedin.venice.kafka.protocol.enums.ControlMessageType;
import com.linkedin.venice.message.KafkaKey;
import com.linkedin.venice.pubsub.PubSubTopicPartitionImpl;
import com.linkedin.venice.pubsub.PubSubTopicRepository;
import com.linkedin.venice.pubsub.api.PubSubTopic;
import com.linkedin.venice.pubsub.api.PubSubTopicPartition;
import com.linkedin.venice.pubsub.consumer.PubSubConsumer;
import com.linkedin.venice.serialization.KafkaKeySerializer;
import com.linkedin.venice.serialization.avro.KafkaValueSerializer;
import java.nio.ByteBuffer;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class DictionaryUtils {
  private static final Logger LOGGER = LogManager.getLogger(DictionaryUtils.class);

  private static Properties getKafkaConsumerProps() {
    Properties props = new Properties();
    // Increase receive buffer to 1MB to check whether it can solve the metadata timing out issue
    props.put(ConsumerConfig.RECEIVE_BUFFER_CONFIG, 1024 * 1024);
    return props;
  }

  public static ByteBuffer readDictionaryFromKafka(String topicName, VeniceProperties props) {
    KafkaConsumerFactoryImpl kafkaConsumerFactory = new KafkaConsumerFactoryImpl(props);
    PubSubTopicRepository pubSubTopicRepository = new PubSubTopicRepository();
    try (PubSubConsumer pubSubConsumer = kafkaConsumerFactory.getConsumer(getKafkaConsumerProps())) {
      return DictionaryUtils.readDictionaryFromKafka(topicName, pubSubConsumer, pubSubTopicRepository);
    }
  }

  /**
   * This function reads the kafka topic for the store version for the Start Of Push message which contains the
   * compression dictionary. Once the Start of Push message has been read, the consumer stops.
   * @return The compression dictionary wrapped in a ByteBuffer, or null if no dictionary was present in the
   * Start Of Push message.
   */
  public static ByteBuffer readDictionaryFromKafka(
      String topicName,
      PubSubConsumer pubSubConsumer,
      PubSubTopicRepository pubSubTopicRepository) {
    LOGGER.info("Consuming from topic: {} till StartOfPush", topicName);
    PubSubTopic pubSubTopic = pubSubTopicRepository.getTopic(topicName);
    PubSubTopicPartition pubSubTopicPartition = new PubSubTopicPartitionImpl(pubSubTopic, 0);
    pubSubConsumer.subscribe(pubSubTopicPartition, 0);
    boolean startOfPushReceived = false;
    ByteBuffer compressionDictionary = null;
    KafkaKeySerializer keySerializer = new KafkaKeySerializer();
    KafkaValueSerializer valueSerializer = new KafkaValueSerializer();
    KafkaKey kafkaKey;
    KafkaMessageEnvelope kafkaValue = null;
    while (!startOfPushReceived) {
      ConsumerRecords<byte[], byte[]> records = pubSubConsumer.poll(10 * Time.MS_PER_SECOND);
      for (final ConsumerRecord<byte[], byte[]> record: records) {
        kafkaKey = keySerializer.deserialize(null, record.key());
        kafkaValue = valueSerializer.deserialize(record.value(), kafkaValue);
        if (kafkaKey.isControlMessage()) {
          ControlMessage controlMessage = (ControlMessage) kafkaValue.payloadUnion;
          ControlMessageType type = ControlMessageType.valueOf(controlMessage);
          LOGGER.info(
              "Consumed ControlMessage: {} from topic: {}, partition: {}",
              type.name(),
              record.topic(),
              record.partition());
          if (type == ControlMessageType.START_OF_PUSH) {
            startOfPushReceived = true;
            compressionDictionary = ((StartOfPush) controlMessage.controlMessageUnion).compressionDictionary;
            if (compressionDictionary == null || !compressionDictionary.hasRemaining()) {
              LOGGER.warn(
                  "No dictionary present in Start of Push message from topic: {}, partition: {}",
                  record.topic(),
                  record.partition());
              return null;
            }
            break;
          }
        } else {
          LOGGER.error(
              "Consumed non Control Message before Start of Push from topic: {}, partition: {}",
              record.topic(),
              record.partition());
          return null;
        }
      }
    }
    return compressionDictionary;
  }
}
