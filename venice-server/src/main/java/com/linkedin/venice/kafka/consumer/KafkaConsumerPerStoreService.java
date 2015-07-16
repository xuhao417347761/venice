package com.linkedin.venice.kafka.consumer;

import com.linkedin.venice.config.VeniceServerConfig;
import com.linkedin.venice.config.VeniceStoreConfig;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.kafka.partitioner.PartitionZeroPartitioner;
import com.linkedin.venice.serialization.Avro.AzkabanJobAvroAckRecordGenerator;
import com.linkedin.venice.serialization.KafkaKeySerializer;
import com.linkedin.venice.serialization.KafkaValueSerializer;
import com.linkedin.venice.server.PartitionNodeAssignmentRepository;
import com.linkedin.venice.server.StoreRepository;
import com.linkedin.venice.server.VeniceConfigService;
import com.linkedin.venice.service.AbstractVeniceService;
import com.linkedin.venice.utils.Utils;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.CommitType;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.TopicPartition;
import org.apache.log4j.Logger;

/**
 * Assumes: One to One mapping between a Venice Store and Kafka Topic.
 * Manages Kafka topics and partitions that need to be consumed for the stores on this node.
 *
 * Launches KafkaPerStoreConsumptionTask for each store to consume and process messages.
 *
 * Uses the "new" Kafka Consumer.
 */
public class KafkaConsumerPerStoreService extends AbstractVeniceService implements KafkaConsumerService {

  private static final String ACK_PARTITION_CONSUMPTION_KAFKA_TOPIC = "venice-partition-consumption-acknowledgement-1";
  private static final String VENICE_SERVICE_NAME = "kafka-consumer-service";
  private static final String GROUP_ID_FORMAT = "%s_%s_%d";

  private static final Logger logger = Logger.getLogger(KafkaConsumerPerStoreService.class.getName());

  private final StoreRepository storeRepository;
  private final VeniceConfigService veniceConfigService;

  private KafkaProducer<byte[], byte[]> ackPartitionConsumptionProducer;
  private AzkabanJobAvroAckRecordGenerator ackRecordGenerator;

  /**
   * A repository mapping each Kafka Topic to its corresponding Kafka Consumer Client.
   */
  private final Map<String, Consumer> topicNameToKafkaConsumerClientMap;
  /**
   * A repository mapping each Kafka Topic to it corresponding Consumption task responsible
   * for consuming messages and making changes to the local store accordingly.
   */
  private final Map<String, KafkaPerStoreConsumptionTask> topicNameToKafkaMessageConsumptionTaskMap;

  private final int nodeId;

  private ExecutorService consumerExecutorService;

  // Need to make sure that the service has started before start running KafkaConsumptionTask.
  private final AtomicBoolean canRunTasks;

  public KafkaConsumerPerStoreService(StoreRepository storeRepository, VeniceConfigService veniceConfigService) {
    super(VENICE_SERVICE_NAME);
    this.storeRepository = storeRepository;

    this.topicNameToKafkaConsumerClientMap = Collections.synchronizedMap(new HashMap<>());
    this.topicNameToKafkaMessageConsumptionTaskMap = Collections.synchronizedMap(new HashMap<>());
    canRunTasks = new AtomicBoolean(false);

    this.veniceConfigService = veniceConfigService;

    VeniceServerConfig serverConfig = veniceConfigService.getVeniceServerConfig();
    nodeId = serverConfig.getNodeId();

    // initialize internal kafka producer for acknowledging consumption (if enabled)
    if (serverConfig.isEnableConsumptionAcksForAzkabanJobs()) {
      ackPartitionConsumptionProducer = new KafkaProducer<>(getAcksKafkaProducerProperties(serverConfig));
      ackRecordGenerator = new AzkabanJobAvroAckRecordGenerator(ACK_PARTITION_CONSUMPTION_KAFKA_TOPIC);
    } else {
      ackPartitionConsumptionProducer = null;
    }
  }

  /**
   * Initializes internal kafka producer for acknowledging kafka message consumption (if enabled)
   */
  private static Properties getAcksKafkaProducerProperties(VeniceServerConfig veniceServerConfig) {
    Properties properties = new Properties();
    properties.setProperty("metadata.broker.list", veniceServerConfig.getKafkaConsumptionAcksBrokerUrl());
    properties.setProperty("request.required.acks", "1");
    properties.setProperty("producer.type", "sync");
    properties.setProperty("partitioner.class", PartitionZeroPartitioner.class.getName());
    return properties;
  }

  /**
   * Starts the Kafka consumption tasks for already subscribed partitions.
   */
  @Override
  public void startInner() {
    logger.info("Enabling consumerExecutorService and kafka consumer tasks on node: " + nodeId);
    consumerExecutorService = Executors.newCachedThreadPool();
    topicNameToKafkaMessageConsumptionTaskMap.values().forEach(consumerExecutorService::submit);
    canRunTasks.set(true);
    logger.info("Kafka consumer tasks started.");
  }

  /**
   * Function to start kafka message consumption for all stores according to the PartitionNodeAssignment.
   * Ideally, should only be used when NOT using Helix.
   * @param partitionNodeAssignmentRepository
   */
  public void consumeForPartitionNodeAssignmentRepository(PartitionNodeAssignmentRepository partitionNodeAssignmentRepository) {
    for (VeniceStoreConfig storeConfig : veniceConfigService.getAllStoreConfigs().values()) {
      String topic = storeConfig.getStoreName();
      Set<Integer> currentTopicPartitions = partitionNodeAssignmentRepository.getLogicalPartitionIds(topic, nodeId);
      for(Integer partitionId : currentTopicPartitions) {
        startConsumption(storeConfig, partitionId);
      }
    }
  }

  private KafkaPerStoreConsumptionTask getConsumerTask(VeniceStoreConfig veniceStore, Consumer kafkaConsumer) {
    return new KafkaPerStoreConsumptionTask(kafkaConsumer, storeRepository,
        ackPartitionConsumptionProducer, ackRecordGenerator, nodeId, veniceStore.getStoreName());
  }

  /**
   * Stops all the Kafka consumption tasks.
   * Closes all the Kafka clients.
   */
  @Override
  public void stopInner() {
    logger.info("Shutting down Kafka consumer service for node: " + nodeId);
    canRunTasks.set(false);
    if (consumerExecutorService != null) {
      consumerExecutorService.shutdown();
    }
    topicNameToKafkaConsumerClientMap.values().forEach(Consumer::close);
    if(ackPartitionConsumptionProducer != null) {
      ackPartitionConsumptionProducer.close();
    }
    logger.info("Shut down complete");
  }

  /**
   * Starts consuming messages from Kafka Partition corresponding to Venice Partition.
   * Subscribes to partition if required.
   * @param veniceStore Venice Store for the partition.
   * @param partitionId Venice partition's id.
   */
  @Override
  public void startConsumption(VeniceStoreConfig veniceStore, int partitionId) {
    String topic = veniceStore.getStoreName();
    Consumer kafkaConsumer = topicNameToKafkaConsumerClientMap.get(topic);
    if(kafkaConsumer == null) {
      kafkaConsumer = new KafkaConsumer(getKafkaConsumerProperties(veniceStore));
      topicNameToKafkaConsumerClientMap.put(topic, kafkaConsumer);
    }

    if(!topicNameToKafkaMessageConsumptionTaskMap.containsKey(topic)) {
      KafkaPerStoreConsumptionTask consumerTask = getConsumerTask(veniceStore, kafkaConsumer);
      topicNameToKafkaMessageConsumptionTaskMap.put(topic, consumerTask);
      if(canRunTasks.get()) {
        consumerExecutorService.submit(consumerTask);
      }
    }

    TopicPartition topicPartition = new TopicPartition(topic, partitionId);
    if(kafkaConsumer.subscriptions().contains(topicPartition)) {
      logger.info("Already Consuming - Kafka Partition: " + topicPartition + ".");
      return;
    }
    kafkaConsumer.subscribe(topicPartition);
    logger.info("Started Consuming - Kafka Partition: " + topic + "-" + partitionId + ".");
  }

  /**
   * Stops consuming messages from Kafka Partition corresponding to Venice Partition.
   * @param veniceStore Venice Store for the partition.
   * @param partitionId Venice partition's id.
   */
  @Override
  public void stopConsumption(VeniceStoreConfig veniceStore, int partitionId) {
    String topic = veniceStore.getStoreName();
    if(!topicNameToKafkaConsumerClientMap.containsKey(topic)) {
      throw new VeniceException("No Consumer for - Kafka Partition: " + topic + "-" + partitionId + ".");
    }
    Consumer consumer = topicNameToKafkaConsumerClientMap.get(topic);
    TopicPartition topicPartition = new TopicPartition(topic, partitionId);
    if(!consumer.subscriptions().contains(topicPartition)) {
      logger.info("Already not consuming - Kafka Partition: " + topic + "-" + partitionId + ".");
      return;
    }
    consumer.unsubscribe(topicPartition);
    if(consumer.subscriptions().isEmpty()) {
      KafkaPerStoreConsumptionTask consumerTask = topicNameToKafkaMessageConsumptionTaskMap.get(topic);
      consumerTask.stop();
      topicNameToKafkaMessageConsumptionTaskMap.remove(topic);
    }
    logger.info("Stopped Consuming - Kafka Partition: " + topicPartition + ".");
  }

  /**
   * Resets Offset to beginning for Kafka Partition corresponding to Venice Partition.
   * @param veniceStore Venice Store for the partition.
   * @param partitionId Venice partition's id.
   */
  @Override
  public void resetConsumptionOffset(VeniceStoreConfig veniceStore, int partitionId) {
    String topic = veniceStore.getStoreName();
    if(!topicNameToKafkaConsumerClientMap.containsKey(topic)) {
      throw new VeniceException("No Consumer for - Kafka Partition: " + topic + "-" + partitionId + ".");
    }
    Consumer consumer = topicNameToKafkaConsumerClientMap.get(topic);

    TopicPartition topicPartition = new TopicPartition(topic, partitionId);
    // Change offset to beginning.
    consumer.seekToBeginning(topicPartition);
    // Commit the beginning offset to prevent the use of old committed offset.
    Map<TopicPartition, Long> offsetMap = new HashMap<>();
    offsetMap.put(topicPartition, consumer.position(topicPartition));
    consumer.commit(offsetMap, CommitType.SYNC);
    logger.info("Offset reset to beginning - Kafka Partition: " + topic + "-" + partitionId + ".");
  }

  /**
   * @return Group Id for kafka consumer.
   */
  private static String getGroupId(String topic, int nodeId) {
    return String.format(GROUP_ID_FORMAT, topic, Utils.getHostName(), nodeId);
  }

  /**
   * @return Properties Kafka properties corresponding to the venice store.
   */
  private static Properties getKafkaConsumerProperties(VeniceStoreConfig storeConfig) {
    Properties kafkaConsumerProperties = new Properties();
    kafkaConsumerProperties.setProperty(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, storeConfig.getKafkaBootstrapServers());
    kafkaConsumerProperties.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
        String.valueOf(storeConfig.kafkaEnableAutoOffsetCommit()));
    kafkaConsumerProperties.setProperty(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG,
        String.valueOf(storeConfig.getKafkaAutoCommitIntervalMs()));
    kafkaConsumerProperties.setProperty(ConsumerConfig.GROUP_ID_CONFIG,
        getGroupId(storeConfig.getStoreName(), storeConfig.getNodeId()));
    kafkaConsumerProperties.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
        KafkaKeySerializer.class.getName());
    kafkaConsumerProperties.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
        KafkaValueSerializer.class.getName());
    return kafkaConsumerProperties;
  }

}
