package com.linkedin.venice.kafka;

import static com.linkedin.venice.ConfigConstants.DEFAULT_TOPIC_DELETION_STATUS_POLL_INTERVAL_MS;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.kafka.KafkaClientFactory.MetricsParameters;
import com.linkedin.venice.kafka.admin.KafkaAdminWrapper;
import com.linkedin.venice.kafka.partitionoffset.PartitionOffsetFetcher;
import com.linkedin.venice.kafka.partitionoffset.PartitionOffsetFetcherFactory;
import com.linkedin.venice.meta.HybridStoreConfig;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.pubsub.PubSubTopicPartitionInfo;
import com.linkedin.venice.pubsub.PubSubTopicRepository;
import com.linkedin.venice.pubsub.api.PubSubTopic;
import com.linkedin.venice.pubsub.api.PubSubTopicPartition;
import com.linkedin.venice.utils.ExceptionUtils;
import com.linkedin.venice.utils.RetryUtils;
import com.linkedin.venice.utils.Time;
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.utils.lazy.Lazy;
import io.tehuti.metrics.MetricsRepository;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import java.io.Closeable;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.errors.InvalidReplicationFactorException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * Topic Manager is shared by multiple cluster's controllers running in one physical Venice controller instance.
 *
 * This class contains one global {@link Consumer}, which is not thread-safe, so when you add new functions,
 * which is using this global consumer, please add 'synchronized' keyword, otherwise this {@link TopicManager}
 * won't be thread-safe, and Kafka consumer will report the following error when multiple threads are trying to
 * use the same consumer: KafkaConsumer is not safe for multi-threaded access.
 */
public class TopicManager implements Closeable {
  private static final int MINIMUM_TOPIC_DELETION_STATUS_POLL_TIMES = 10;
  private static final int FAST_KAFKA_OPERATION_TIMEOUT_MS = Time.MS_PER_SECOND;
  protected static final long ETERNAL_TOPIC_RETENTION_POLICY_MS = Long.MAX_VALUE;

  public static final long DEFAULT_TOPIC_RETENTION_POLICY_MS = 5 * Time.MS_PER_DAY;
  public static final long BUFFER_REPLAY_MINIMAL_SAFETY_MARGIN = 2 * Time.MS_PER_DAY;

  public static final int DEFAULT_KAFKA_OPERATION_TIMEOUT_MS = 30 * Time.MS_PER_SECOND;
  public static final long UNKNOWN_TOPIC_RETENTION = Long.MIN_VALUE;
  public static final int MAX_TOPIC_DELETE_RETRIES = 3;
  public static final int DEFAULT_KAFKA_REPLICATION_FACTOR = 3;

  /**
   * Default setting is that no log compaction should happen for hybrid store version topics
   * if the messages are produced within 24 hours; otherwise servers could encounter MISSING
   * data DIV errors for reprocessing jobs which could potentially generate lots of
   * duplicate keys.
   */
  public static final long DEFAULT_KAFKA_MIN_LOG_COMPACTION_LAG_MS = 24 * Time.MS_PER_HOUR;

  /**
   * admin tool and venice topic consumer create this class.  We'll set this policy to false by default so those paths
   * aren't necessarily compromised with potentially new bad behavior.
   */
  public static final boolean CONCURRENT_TOPIC_DELETION_REQUEST_POLICY = false;

  private static final List<Class<? extends Throwable>> CREATE_TOPIC_RETRIABLE_EXCEPTIONS =
      Collections.unmodifiableList(
          Arrays
              .asList(InvalidReplicationFactorException.class, org.apache.kafka.common.errors.TimeoutException.class));

  // Immutable state
  private final Logger logger;
  private final String kafkaBootstrapServers;
  private final long kafkaOperationTimeoutMs;
  private final long topicDeletionStatusPollIntervalMs;
  private final long topicMinLogCompactionLagMs;
  private final KafkaClientFactory kafkaClientFactory;

  private Consumer<byte[], byte[]> kafkaRawBytesConsumer;
  // private final Lazy<KafkaAdminWrapper> kafkaAdmin;
  private final Lazy<KafkaAdminWrapper> kafkaWriteOnlyAdmin;
  private final Lazy<KafkaAdminWrapper> kafkaReadOnlyAdmin;
  private final PartitionOffsetFetcher partitionOffsetFetcher;

  // It's expensive to grab the topic config over and over again, and it changes infrequently. So we temporarily cache
  // queried configs.
  Cache<PubSubTopic, Properties> topicConfigCache = Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).build();

  // TODO: Consider adding a builder for this class as the number of constructors is getting high.
  public TopicManager(
      long kafkaOperationTimeoutMs,
      long topicDeletionStatusPollIntervalMs,
      long topicMinLogCompactionLagMs,
      KafkaClientFactory kafkaClientFactory,
      Optional<MetricsRepository> optionalMetricsRepository) {
    this.kafkaOperationTimeoutMs = kafkaOperationTimeoutMs;
    this.topicDeletionStatusPollIntervalMs = topicDeletionStatusPollIntervalMs;
    this.topicMinLogCompactionLagMs = topicMinLogCompactionLagMs;
    this.kafkaClientFactory = kafkaClientFactory;
    this.kafkaBootstrapServers = kafkaClientFactory.getKafkaBootstrapServers();
    this.logger = LogManager.getLogger(this.getClass().getSimpleName() + " [" + kafkaBootstrapServers + "]");

    PubSubTopicRepository pubSubTopicRepository = new PubSubTopicRepository(); // TODO: Remove this.
    this.kafkaReadOnlyAdmin = Lazy.of(() -> {
      KafkaAdminWrapper kafkaReadOnlyAdmin =
          kafkaClientFactory.getReadOnlyKafkaAdmin(optionalMetricsRepository, pubSubTopicRepository);
      logger.info(
          "{} is using kafka read-only admin client of class: {}",
          this.getClass().getSimpleName(),
          kafkaReadOnlyAdmin.getClassName());
      return kafkaReadOnlyAdmin;
    });

    this.kafkaWriteOnlyAdmin = Lazy.of(() -> {
      KafkaAdminWrapper kafkaWriteOnlyAdmin =
          kafkaClientFactory.getWriteOnlyKafkaAdmin(optionalMetricsRepository, pubSubTopicRepository);
      logger.info(
          "{} is using kafka write-only admin client of class: {}",
          this.getClass().getSimpleName(),
          kafkaWriteOnlyAdmin.getClassName());
      return kafkaWriteOnlyAdmin;
    });

    Optional<MetricsParameters> metricsForPartitionOffsetFetcher = kafkaClientFactory.getMetricsParameters()
        .map(
            mp -> new MetricsParameters(
                this.kafkaClientFactory.getClass(),
                PartitionOffsetFetcher.class,
                kafkaClientFactory.getKafkaBootstrapServers(),
                mp.metricsRepository));
    this.partitionOffsetFetcher = PartitionOffsetFetcherFactory.createDefaultPartitionOffsetFetcher(
        kafkaClientFactory.clone(kafkaClientFactory.getKafkaBootstrapServers(), metricsForPartitionOffsetFetcher),
        kafkaReadOnlyAdmin,
        kafkaOperationTimeoutMs,
        optionalMetricsRepository);
  }

  // This constructor is used mostly for testing purpose
  public TopicManager(
      long kafkaOperationTimeoutMs,
      long topicDeletionStatusPollIntervalMs,
      long topicMinLogCompactionLagMs,
      KafkaClientFactory kafkaClientFactory) {
    this(
        kafkaOperationTimeoutMs,
        topicDeletionStatusPollIntervalMs,
        topicMinLogCompactionLagMs,
        kafkaClientFactory,
        Optional.empty());
  }

  /**
   * This constructor is used in server only; server doesn't have access to controller config like
   * topic.deletion.status.poll.interval.ms, so we use default config defined in this class; besides, TopicManager
   * in server doesn't use the config mentioned above.
   */
  public TopicManager(KafkaClientFactory kafkaClientFactory) {
    this(
        DEFAULT_KAFKA_OPERATION_TIMEOUT_MS,
        DEFAULT_TOPIC_DELETION_STATUS_POLL_INTERVAL_MS,
        DEFAULT_KAFKA_MIN_LOG_COMPACTION_LAG_MS,
        kafkaClientFactory);
  }

  // For testing only
  public TopicManager(KafkaAdminWrapper kafkaWriteOnlyAdmin, KafkaAdminWrapper kafkaReadOnlyAdmin) {
    this.kafkaWriteOnlyAdmin = Lazy.of(() -> kafkaWriteOnlyAdmin);
    this.kafkaReadOnlyAdmin = Lazy.of(() -> kafkaReadOnlyAdmin);
    this.logger = LogManager.getLogger(TopicManager.class);
    this.kafkaBootstrapServers = null;
    this.kafkaOperationTimeoutMs = TimeUnit.SECONDS.toMillis(60);
    this.topicDeletionStatusPollIntervalMs = TimeUnit.SECONDS.toMillis(1);
    this.topicMinLogCompactionLagMs = TimeUnit.SECONDS.toMillis(60);
    this.kafkaClientFactory = null;
    this.kafkaRawBytesConsumer = null;
    this.partitionOffsetFetcher = null;
  }

  /**
   * Create a topic, and block until the topic is created, with a default timeout of
   * {@value #DEFAULT_KAFKA_OPERATION_TIMEOUT_MS}, after which this function will throw a VeniceException.
   *
   * @see {@link #createTopic(PubSubTopic, int, int, boolean, boolean, Optional)}
   */
  public void createTopic(PubSubTopic topicName, int numPartitions, int replication, boolean eternal) {
    createTopic(topicName, numPartitions, replication, eternal, false, Optional.empty(), false);
  }

  public void createTopic(
      PubSubTopic topicName,
      int numPartitions,
      int replication,
      boolean eternal,
      boolean logCompaction,
      Optional<Integer> minIsr) {
    createTopic(topicName, numPartitions, replication, eternal, logCompaction, minIsr, true);
  }

  /**
   * Create a topic, and block until the topic is created, with a default timeout of
   * {@value #DEFAULT_KAFKA_OPERATION_TIMEOUT_MS}, after which this function will throw a VeniceException.
   *
   * @param topicName Name for the new topic
   * @param numPartitions number of partitions
   * @param replication replication factor
   * @param eternal if true, the topic will have "infinite" (~250 mil years) retention
   *                if false, its retention will be set to {@link #DEFAULT_TOPIC_RETENTION_POLICY_MS} by default
   * @param logCompaction whether to enable log compaction on the topic
   * @param minIsr if present, will apply the specified min.isr to this topic,
   *               if absent, Kafka cluster defaults will be used
   * @param useFastKafkaOperationTimeout if false, normal kafka operation timeout will be used,
   *                            if true, a much shorter timeout will be used to make topic creation non-blocking.
   */
  public void createTopic(
      PubSubTopic topicName,
      int numPartitions,
      int replication,
      boolean eternal,
      boolean logCompaction,
      Optional<Integer> minIsr,
      boolean useFastKafkaOperationTimeout) {
    long retentionTimeMs;
    if (eternal) {
      retentionTimeMs = ETERNAL_TOPIC_RETENTION_POLICY_MS;
    } else {
      retentionTimeMs = DEFAULT_TOPIC_RETENTION_POLICY_MS;
    }
    createTopic(
        topicName,
        numPartitions,
        replication,
        retentionTimeMs,
        logCompaction,
        minIsr,
        useFastKafkaOperationTimeout);
  }

  /**
   * Create a topic, and block until the topic is created, with a default timeout of
   * {@value #DEFAULT_KAFKA_OPERATION_TIMEOUT_MS}, after which this function will throw a VeniceException.
   *
   * @param topicName Name for the new topic
   * @param numPartitions number of partitions
   * @param replication replication factor
   * @param retentionTimeMs Retention time, in ms, for the topic
   * @param logCompaction whether to enable log compaction on the topic
   * @param minIsr if present, will apply the specified min.isr to this topic,
   *               if absent, Kafka cluster defaults will be used
   * @param useFastKafkaOperationTimeout if false, normal kafka operation timeout will be used,
   *                            if true, a much shorter timeout will be used to make topic creation non-blocking.
   */
  public void createTopic(
      PubSubTopic topicName,
      int numPartitions,
      int replication,
      long retentionTimeMs,
      boolean logCompaction,
      Optional<Integer> minIsr,
      boolean useFastKafkaOperationTimeout) {

    long startTime = System.currentTimeMillis();
    long deadlineMs =
        startTime + (useFastKafkaOperationTimeout ? FAST_KAFKA_OPERATION_TIMEOUT_MS : kafkaOperationTimeoutMs);

    logger.info("Creating topic: {} partitions: {} replication: {}", topicName, numPartitions, replication);
    Properties topicProperties = new Properties();
    // TODO: move these Kafka related configs out of this class.
    topicProperties.put(TopicConfig.RETENTION_MS_CONFIG, Long.toString(retentionTimeMs));
    if (logCompaction) {
      topicProperties.put(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT);
      topicProperties.put(TopicConfig.MIN_COMPACTION_LAG_MS_CONFIG, Long.toString(topicMinLogCompactionLagMs));
    } else {
      topicProperties.put(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE);
    }

    // If not set, Kafka cluster defaults will apply
    minIsr.ifPresent(minIsrConfig -> topicProperties.put(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, minIsrConfig));

    // Just in case the Kafka cluster isn't configured as expected.
    topicProperties.put(TopicConfig.MESSAGE_TIMESTAMP_TYPE_CONFIG, "LogAppendTime");

    try {
      RetryUtils.executeWithMaxAttemptAndExponentialBackoff(
          () -> kafkaWriteOnlyAdmin.get().createTopic(topicName, numPartitions, replication, topicProperties),
          10,
          Duration.ofMillis(200),
          Duration.ofSeconds(1),
          Duration.ofMillis(useFastKafkaOperationTimeout ? FAST_KAFKA_OPERATION_TIMEOUT_MS : kafkaOperationTimeoutMs),
          CREATE_TOPIC_RETRIABLE_EXCEPTIONS);
    } catch (Exception e) {
      if (ExceptionUtils.recursiveClassEquals(e, TopicExistsException.class)) {
        logger.info("Topic: {} already exists, will update retention policy.", topicName);
        waitUntilTopicCreated(topicName, numPartitions, deadlineMs);
        updateTopicRetention(topicName, retentionTimeMs);
        logger.info("Updated retention policy to be {}ms for topic: {}", retentionTimeMs, topicName);
        return;
      } else {
        throw new VeniceOperationAgainstKafkaTimedOut(
            "Timeout while creating topic: " + topicName + ". Topic still does not exist after "
                + (deadlineMs - startTime) + "ms.",
            e);
      }
    }
    waitUntilTopicCreated(topicName, numPartitions, deadlineMs);
    boolean eternal = retentionTimeMs == ETERNAL_TOPIC_RETENTION_POLICY_MS;
    logger.info("Successfully created {}topic: {}", eternal ? "eternal " : "", topicName);
  }

  protected void waitUntilTopicCreated(PubSubTopic topicName, int partitionCount, long deadlineMs) {
    long startTime = System.currentTimeMillis();
    while (!containsTopicAndAllPartitionsAreOnline(topicName, partitionCount)) {
      if (System.currentTimeMillis() > deadlineMs) {
        throw new VeniceOperationAgainstKafkaTimedOut(
            "Timeout while creating topic: " + topicName + ".  Topic still did not pass all the checks after "
                + (deadlineMs - startTime) + "ms.");
      }
      Utils.sleep(200);
    }
  }

  /**
   * This method sends a delete command to Kafka and immediately returns with a future. The future could be null if the
   * underlying Kafka admin client doesn't support it. In both cases, deletion will occur asynchronously.
   * @param topicName
   */
  private Future<Void> ensureTopicIsDeletedAsync(PubSubTopic topicName) {
    // TODO: Stop using Kafka APIs which depend on ZK.
    logger.info("Deleting topic: {}", topicName);
    return kafkaWriteOnlyAdmin.get().deleteTopic(topicName);
  }

  public int getReplicationFactor(PubSubTopic topicName) {
    return partitionsFor(topicName).iterator().next().replicasNum();
  }

  /**
   * Update retention for the given topic.
   * If the topic doesn't exist, this operation will throw {@link TopicDoesNotExistException}
   * @param topicName
   * @param retentionInMS
   * @return true if the retention time config of the input topic gets updated; return false if nothing gets updated
   */
  public boolean updateTopicRetention(PubSubTopic topicName, long retentionInMS) throws TopicDoesNotExistException {
    Properties topicProperties = getTopicConfig(topicName);
    return updateTopicRetention(topicName, retentionInMS, topicProperties);
  }

  /**
   * Update retention for the given topic given a {@link Properties}.
   * @param topicName
   * @param retentionInMS
   * @param topicProperties
   * @return true if the retention time gets updated; false if no update is needed.
   */
  public boolean updateTopicRetention(PubSubTopic topicName, long retentionInMS, Properties topicProperties)
      throws TopicDoesNotExistException {
    String retentionInMSStr = Long.toString(retentionInMS);
    if (!topicProperties.containsKey(TopicConfig.RETENTION_MS_CONFIG) || // config doesn't exist
        !topicProperties.getProperty(TopicConfig.RETENTION_MS_CONFIG).equals(retentionInMSStr)) { // config is different
      topicProperties.put(TopicConfig.RETENTION_MS_CONFIG, Long.toString(retentionInMS));
      kafkaWriteOnlyAdmin.get().setTopicConfig(topicName, topicProperties);
      logger.info(
          "Updated topic: {} with retention.ms: {} in cluster [{}]",
          topicName,
          retentionInMS,
          this.kafkaBootstrapServers);
      return true;
    }
    // Retention time has already been updated for this topic before
    return false;
  }

  /**
   * Update topic compaction policy.
   * @throws TopicDoesNotExistException, if the topic doesn't exist
   */
  public synchronized void updateTopicCompactionPolicy(PubSubTopic topicName, boolean logCompaction)
      throws TopicDoesNotExistException {
    Properties topicProperties = getTopicConfig(topicName);
    // If the compaction policy doesn't exist, by default it is disabled.
    String currentCompactionPolicy = topicProperties.containsKey(TopicConfig.CLEANUP_POLICY_CONFIG)
        ? (String) topicProperties.get(TopicConfig.CLEANUP_POLICY_CONFIG)
        : TopicConfig.CLEANUP_POLICY_DELETE;
    String expectedCompactionPolicy =
        logCompaction ? TopicConfig.CLEANUP_POLICY_COMPACT : TopicConfig.CLEANUP_POLICY_DELETE;
    long currentMinLogCompactionLagMs = topicProperties.containsKey(TopicConfig.MIN_COMPACTION_LAG_MS_CONFIG)
        ? Long.parseLong((String) topicProperties.get(TopicConfig.MIN_COMPACTION_LAG_MS_CONFIG))
        : 0L;
    long expectedMinLogCompactionLagMs = logCompaction ? topicMinLogCompactionLagMs : 0L;
    boolean needToUpdateTopicConfig = false;
    if (!expectedCompactionPolicy.equals(currentCompactionPolicy)) {
      // Different, then update
      needToUpdateTopicConfig = true;
      topicProperties.put(TopicConfig.CLEANUP_POLICY_CONFIG, expectedCompactionPolicy);
    }
    if (currentMinLogCompactionLagMs != expectedMinLogCompactionLagMs) {
      needToUpdateTopicConfig = true;
      topicProperties.put(TopicConfig.MIN_COMPACTION_LAG_MS_CONFIG, Long.toString(expectedMinLogCompactionLagMs));
    }
    if (needToUpdateTopicConfig) {
      kafkaWriteOnlyAdmin.get().setTopicConfig(topicName, topicProperties);
      logger.info(
          "Kafka compaction policy for topic: {} has been updated from {} to {}, min compaction lag updated from {} to {}",
          topicName,
          currentCompactionPolicy,
          expectedCompactionPolicy,
          currentMinLogCompactionLagMs,
          expectedCompactionPolicy);
    }
  }

  public boolean isTopicCompactionEnabled(PubSubTopic topicName) {
    Properties topicProperties = getCachedTopicConfig(topicName);
    return topicProperties.containsKey(TopicConfig.CLEANUP_POLICY_CONFIG)
        && topicProperties.get(TopicConfig.CLEANUP_POLICY_CONFIG).equals(TopicConfig.CLEANUP_POLICY_COMPACT);
  }

  public long getTopicMinLogCompactionLagMs(PubSubTopic topicName) {
    Properties topicProperties = getCachedTopicConfig(topicName);
    return topicProperties.containsKey(TopicConfig.MIN_COMPACTION_LAG_MS_CONFIG)
        ? Long.parseLong((String) topicProperties.get(TopicConfig.MIN_COMPACTION_LAG_MS_CONFIG))
        : 0L;
  }

  public Map<PubSubTopic, Long> getAllTopicRetentions() {
    return kafkaReadOnlyAdmin.get().getAllTopicRetentions();
  }

  /**
   * Return topic retention time in MS.
   */
  public long getTopicRetention(PubSubTopic topicName) throws TopicDoesNotExistException {
    Properties topicProperties = getTopicConfig(topicName);
    if (topicProperties.containsKey(TopicConfig.RETENTION_MS_CONFIG)) {
      return Long.parseLong(topicProperties.getProperty(TopicConfig.RETENTION_MS_CONFIG));
    }
    return UNKNOWN_TOPIC_RETENTION;
  }

  public long getTopicRetention(Properties topicProperties) throws TopicDoesNotExistException {
    if (topicProperties.containsKey(TopicConfig.RETENTION_MS_CONFIG)) {
      return Long.parseLong(topicProperties.getProperty(TopicConfig.RETENTION_MS_CONFIG));
    }
    return UNKNOWN_TOPIC_RETENTION;
  }

  /**
   * Check whether topic is absent or truncated
   * @param topicName
   * @param truncatedTopicMaxRetentionMs
   * @return true if the topic does not exist or if it exists but its retention time is below truncated threshold
   *         false if the topic exists and its retention time is above truncated threshold
   */
  public boolean isTopicTruncated(PubSubTopic topicName, long truncatedTopicMaxRetentionMs) {
    try {
      return isRetentionBelowTruncatedThreshold(getTopicRetention(topicName), truncatedTopicMaxRetentionMs);
    } catch (TopicDoesNotExistException e) {
      return true;
    }
  }

  public boolean isRetentionBelowTruncatedThreshold(long retention, long truncatedTopicMaxRetentionMs) {
    return retention != UNKNOWN_TOPIC_RETENTION && retention <= truncatedTopicMaxRetentionMs;
  }

  /**
   * This operation is a little heavy, since it will pull the configs for all the topics.
   */
  public Properties getTopicConfig(PubSubTopic topicName) throws TopicDoesNotExistException {
    final Properties properties = kafkaReadOnlyAdmin.get().getTopicConfig(topicName);
    topicConfigCache.put(topicName, properties);
    return properties;
  }

  public Properties getTopicConfigWithRetry(PubSubTopic topicName) {
    final Properties properties = kafkaReadOnlyAdmin.get().getTopicConfigWithRetry(topicName);
    topicConfigCache.put(topicName, properties);
    return properties;
  }

  /**
   * Still heavy, but can be called repeatedly to amortize that cost.
   */
  public Properties getCachedTopicConfig(PubSubTopic topicName) {
    // query the cache first, if it doesn't have it, query it from kafka and store it.
    Properties properties = topicConfigCache.getIfPresent(topicName);
    if (properties == null) {
      properties = getTopicConfigWithRetry(topicName);
    }
    return properties;
  }

  public Map<PubSubTopic, Properties> getSomeTopicConfigs(Set<PubSubTopic> topicNames) {
    final Map<PubSubTopic, Properties> topicConfigs = kafkaReadOnlyAdmin.get().getSomeTopicConfigs(topicNames);
    for (Map.Entry<PubSubTopic, Properties> topicConfig: topicConfigs.entrySet()) {
      topicConfigCache.put(topicConfig.getKey(), topicConfig.getValue());
    }
    return topicConfigs;
  }

  /**
   * This function is used to address the following problem:
   * 1. Topic deletion is a async operation in Kafka;
   * 2. Topic deletion triggered by Venice could happen in the middle of other Kafka operation;
   * 3. Kafka operations against non-existing topic will hang;
   * By using this function, the topic deletion is a sync op, which bypasses the hanging issue of
   * non-existing topic operations.
   * Once Kafka addresses the hanging issue of non-existing topic operations, we can safely revert back
   * to use the async version: {@link #ensureTopicIsDeletedAsync(PubSubTopic)}
   *
   * It is intentional to make this function to be non-synchronized since it could lock
   * {@link TopicManager} for a pretty long time (up to 30 seconds) if topic deletion is slow.
   * When topic deletion slowness happens, it will cause other operations, such as {@link #getTopicLatestOffsets(PubSubTopic)}
   * to be blocked for a long time, and this could cause push job failure.
   *
   * Even with non-synchronized function, Venice could still guarantee there will be only one topic
   * deletion at one time since all the topic deletions are handled by topic cleanup service serially.
   *
   * @param topicName
   */
  public void ensureTopicIsDeletedAndBlock(PubSubTopic topicName) throws ExecutionException {
    if (!containsTopicAndAllPartitionsAreOnline(topicName)) {
      // Topic doesn't exist
      return;
    }

    // TODO: Remove the isConcurrentTopicDeleteRequestsEnabled flag and make topic deletion to be always blocking or
    // refactor this method to actually support concurrent topic deletion if that's something we want.
    // This is trying to guard concurrent topic deletion in Kafka.
    if (!CONCURRENT_TOPIC_DELETION_REQUEST_POLICY &&
    /**
     * TODO: Add support for this call in the {@link com.linkedin.venice.kafka.admin.KafkaAdminClient}
     * This is the last remaining call that depends on {@link kafka.utils.ZkUtils}.
     */
        kafkaReadOnlyAdmin.get().isTopicDeletionUnderway()) {
      throw new VeniceException("Delete operation already in progress! Try again later.");
    }

    Future<Void> future = ensureTopicIsDeletedAsync(topicName);
    if (future != null) {
      // Skip additional checks for Java kafka client since the result of the future can guarantee that the topic is
      // deleted.
      try {
        future.get(kafkaOperationTimeoutMs, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        throw new VeniceException("Thread interrupted while waiting to delete topic: " + topicName);
      } catch (ExecutionException e) {
        if (e.getCause() instanceof UnknownTopicOrPartitionException) {
          // No-op. Topic is deleted already, consider this as a successful deletion.
        } else {
          throw e;
        }
      } catch (TimeoutException e) {
        throw new VeniceOperationAgainstKafkaTimedOut(
            "Failed to delete kafka topic: " + topicName + " after " + kafkaOperationTimeoutMs);
      }
      logger.info("Topic: {} has been deleted", topicName);
      // TODO: Remove the checks below once we have fully migrated to use the Kafka admin client.
      return;
    }
    // Since topic deletion is async, we would like to poll until topic doesn't exist any more
    long MAX_TIMES = topicDeletionStatusPollIntervalMs == 0
        ? kafkaOperationTimeoutMs
        : (kafkaOperationTimeoutMs / topicDeletionStatusPollIntervalMs);
    /**
     * In case we have bad config, MAX_TIMES can not be smaller than {@link #MINIMUM_TOPIC_DELETION_STATUS_POLL_TIMES}.
     */
    MAX_TIMES = Math.max(MAX_TIMES, MINIMUM_TOPIC_DELETION_STATUS_POLL_TIMES);
    final int MAX_CONSUMER_RECREATION_INTERVAL = 100;
    int current = 0;
    int lastConsumerRecreation = 0;
    int consumerRecreationInterval = 5;
    while (++current <= MAX_TIMES) {
      Utils.sleep(topicDeletionStatusPollIntervalMs);
      // Re-create consumer every once in a while, in case it's wedged on some stale state.
      boolean closeAndRecreateConsumer = (current - lastConsumerRecreation) == consumerRecreationInterval;
      if (closeAndRecreateConsumer) {
        /**
         * Exponential back-off:
         * Recreate the consumer after polling status for 2 times, (2+)4 times, (2+4+)8 times... and maximum 100 times
         */
        lastConsumerRecreation = current;
        consumerRecreationInterval = Math.min(consumerRecreationInterval * 2, MAX_CONSUMER_RECREATION_INTERVAL);
        if (consumerRecreationInterval <= 0) {
          // In case it overflows
          consumerRecreationInterval = MAX_CONSUMER_RECREATION_INTERVAL;
        }
      }
    }
    throw new VeniceOperationAgainstKafkaTimedOut(
        "Failed to delete kafka topic: " + topicName + " after " + kafkaOperationTimeoutMs + " ms (" + current
            + " attempts).");
  }

  public void ensureTopicIsDeletedAndBlockWithRetry(PubSubTopic topicName) throws ExecutionException {
    // Topic deletion may time out, so go ahead and retry the operation up the max number of attempts, if we
    // simply cannot succeed, bubble the exception up.
    int attempts = 0;
    while (true) {
      try {
        ensureTopicIsDeletedAndBlock(topicName);
        return;
      } catch (VeniceOperationAgainstKafkaTimedOut e) {
        attempts++;
        logger.warn(
            "Topic deletion for topic {} timed out!  Retry attempt {} / {}",
            topicName,
            attempts,
            MAX_TOPIC_DELETE_RETRIES);
        if (attempts == MAX_TOPIC_DELETE_RETRIES) {
          logger.error("Topic deletion for topic {} timed out! Giving up!!", topicName, e);
          throw e;
        }
      } catch (ExecutionException e) {
        attempts++;
        logger.warn(
            "Topic deletion for topic {} errored out!  Retry attempt {} / {}",
            topicName,
            attempts,
            MAX_TOPIC_DELETE_RETRIES);
        if (attempts == MAX_TOPIC_DELETE_RETRIES) {
          logger.error("Topic deletion for topic {} errored out! Giving up!!", topicName, e);
          throw e;
        }
      }
    }
  }

  public synchronized Set<PubSubTopic> listTopics() {
    return kafkaReadOnlyAdmin.get().listAllTopics();
  }

  /**
   * A quick check to see whether the topic exists.
   */
  public boolean containsTopic(PubSubTopic topic) {
    return kafkaReadOnlyAdmin.get().containsTopic(topic);
  }

  /**
   * See Java doc of {@link KafkaAdminWrapper#containsTopicWithExpectationAndRetry} which provides exactly the same
   * semantics.
   */
  public boolean containsTopicWithExpectationAndRetry(
      PubSubTopic topic,
      int maxAttempts,
      final boolean expectedResult) {
    return kafkaReadOnlyAdmin.get().containsTopicWithExpectationAndRetry(topic, maxAttempts, expectedResult);
  }

  public boolean containsTopicWithExpectationAndRetry(
      PubSubTopic topic,
      int maxAttempts,
      final boolean expectedResult,
      Duration initialBackoff,
      Duration maxBackoff,
      Duration maxDuration) {
    return kafkaReadOnlyAdmin.get()
        .containsTopicWithExpectationAndRetry(
            topic,
            maxAttempts,
            expectedResult,
            initialBackoff,
            maxBackoff,
            maxDuration);
  }

  /**
   * @see {@link #containsTopicAndAllPartitionsAreOnline(PubSubTopic, Integer)}
   */
  public boolean containsTopicAndAllPartitionsAreOnline(PubSubTopic topic) {
    return containsTopicAndAllPartitionsAreOnline(topic, null);
  }

  /**
   * This is an extensive check to mitigate an edge-case where a topic is not yet created in all the brokers.
   *
   * @return true if the topic exists and all its partitions have at least one in-sync replica
   *         false if the topic does not exist at all or if it exists but isn't completely available
   */
  public synchronized boolean containsTopicAndAllPartitionsAreOnline(
      PubSubTopic topic,
      Integer expectedPartitionCount) {
    if (!containsTopic(topic)) {
      return false;
    }
    List<PubSubTopicPartitionInfo> partitionInfoList = partitionOffsetFetcher.partitionsFor(topic);
    if (partitionInfoList == null) {
      logger.warn("getConsumer().partitionsFor() returned null for topic: {}", topic);
      return false;
    }

    if (expectedPartitionCount != null && partitionInfoList.size() != expectedPartitionCount) {
      // Unexpected. Should perhaps even throw...
      logger.error(
          "getConsumer().partitionsFor() returned the wrong number of partitions for topic: {}, "
              + "expectedPartitionCount: {}, actual size: {}, partitionInfoList: {}",
          topic,
          expectedPartitionCount,
          partitionInfoList.size(),
          Arrays.toString(partitionInfoList.toArray()));
      return false;
    }

    boolean allPartitionsHaveAnInSyncReplica =
        partitionInfoList.stream().allMatch(PubSubTopicPartitionInfo::hasInSyncReplicas);
    if (allPartitionsHaveAnInSyncReplica) {
      logger.trace("The following topic has the at least one in-sync replica for each partition: {}", topic);
    } else {
      logger.info(
          "getConsumer().partitionsFor() returned some partitionInfo with no in-sync replica for topic: {}, partitionInfoList: {}",
          topic,
          Arrays.toString(partitionInfoList.toArray()));
    }
    return allPartitionsHaveAnInSyncReplica;
  }

  /**
   * Generate a map from partition number to the last offset available for that partition
   * @param topic
   * @return a Map of partition to latest offset, or an empty map if there's any problem
   */
  public Int2LongMap getTopicLatestOffsets(PubSubTopic topic) {
    return partitionOffsetFetcher.getTopicLatestOffsets(topic);
  }

  public long getPartitionLatestOffsetAndRetry(PubSubTopicPartition pubSubTopicPartition, int retries) {
    return partitionOffsetFetcher.getPartitionLatestOffsetAndRetry(pubSubTopicPartition, retries);
  }

  public long getProducerTimestampOfLastDataRecord(PubSubTopicPartition pubSubTopicPartition, int retries) {
    return partitionOffsetFetcher.getProducerTimestampOfLastDataRecord(pubSubTopicPartition, retries);
  }

  public long getPartitionEarliestOffsetAndRetry(PubSubTopicPartition pubSubTopicPartition, int retries) {
    return partitionOffsetFetcher.getPartitionEarliestOffsetAndRetry(pubSubTopicPartition, retries);
  }

  /**
   * Get offsets for only one partition with a specific timestamp.
   */
  public long getPartitionOffsetByTime(PubSubTopicPartition pubSubTopicPartition, long timestamp) {
    return partitionOffsetFetcher.getPartitionOffsetByTime(pubSubTopicPartition, timestamp);
  }

  /**
   * Get a list of {@link PubSubTopicPartitionInfo} objects for the specified topic.
   * @param topic
   * @return
   */
  public List<PubSubTopicPartitionInfo> partitionsFor(PubSubTopic topic) {
    return partitionOffsetFetcher.partitionsFor(topic);
  }

  public String getKafkaBootstrapServers() {
    return this.kafkaBootstrapServers;
  }

  @Override
  public synchronized void close() {
    Utils.closeQuietlyWithErrorLogged(partitionOffsetFetcher);
    kafkaReadOnlyAdmin.ifPresent(Utils::closeQuietlyWithErrorLogged);
    kafkaWriteOnlyAdmin.ifPresent(Utils::closeQuietlyWithErrorLogged);
  }

  // For testing only
  public void setTopicConfigCache(Cache<PubSubTopic, Properties> topicConfigCache) {
    this.topicConfigCache = topicConfigCache;
  }

  /**
   * The default retention time for the RT topic is defined in {@link TopicManager#DEFAULT_TOPIC_RETENTION_POLICY_MS},
   * but if the rewind time is larger than this, then the RT topic's retention time needs to be set even higher,
   * in order to guarantee that buffer replays do not lose data. In order to achieve this, the retention time is
   * set to the max of either:
   *
   * 1. {@link TopicManager#DEFAULT_TOPIC_RETENTION_POLICY_MS}; or
   * 2. {@link HybridStoreConfig#getRewindTimeInSeconds()} + {@link Store#getBootstrapToOnlineTimeoutInHours()} + {@value #BUFFER_REPLAY_MINIMAL_SAFETY_MARGIN};
   *
   * This is a convenience function, and thus must be ignored by the JSON machinery.
   *
   * @return the retention time for the RT topic, in milliseconds.
   */
  public static long getExpectedRetentionTimeInMs(Store store, HybridStoreConfig hybridConfig) {
    long rewindTimeInMs = hybridConfig.getRewindTimeInSeconds() * Time.MS_PER_SECOND;
    long bootstrapToOnlineTimeInMs = (long) store.getBootstrapToOnlineTimeoutInHours() * Time.MS_PER_HOUR;
    long minimumRetentionInMs = rewindTimeInMs + bootstrapToOnlineTimeInMs + BUFFER_REPLAY_MINIMAL_SAFETY_MARGIN;
    return Math.max(minimumRetentionInMs, TopicManager.DEFAULT_TOPIC_RETENTION_POLICY_MS);
  }
}
