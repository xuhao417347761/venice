package com.linkedin.venice.kafka;

import static com.linkedin.venice.ConfigConstants.DEFAULT_KAFKA_ADMIN_GET_TOPIC_CONFIG_RETRY_IN_SECONDS;

import com.linkedin.venice.ConfigKeys;
import com.linkedin.venice.kafka.admin.InstrumentedKafkaAdmin;
import com.linkedin.venice.kafka.admin.KafkaAdminWrapper;
import com.linkedin.venice.kafka.consumer.ApacheKafkaConsumer;
import com.linkedin.venice.pubsub.PubSubTopicRepository;
import com.linkedin.venice.pubsub.consumer.PubSubConsumer;
import com.linkedin.venice.pubsub.kafka.KafkaPubSubMessageDeserializer;
import com.linkedin.venice.schema.SchemaReader;
import com.linkedin.venice.utils.ReflectUtils;
import com.linkedin.venice.utils.VeniceProperties;
import io.tehuti.metrics.MetricsRepository;
import java.util.Optional;
import java.util.Properties;
import javax.annotation.Nonnull;
import org.apache.commons.lang.Validate;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * A factory that creates Kafka clients, specifically Kafka consumer and Kafka admin client.
 */
public abstract class KafkaClientFactory {
  private static final Logger LOGGER = LogManager.getLogger(KafkaClientFactory.class);

  protected final Optional<SchemaReader> kafkaMessageEnvelopeSchemaReader;

  private final Optional<MetricsParameters> metricsParameters;

  protected KafkaClientFactory() {
    this(Optional.empty(), Optional.empty());
  }

  protected KafkaClientFactory(
      @Nonnull Optional<SchemaReader> kafkaMessageEnvelopeSchemaReader,
      Optional<MetricsParameters> metricsParameters) {
    Validate.notNull(kafkaMessageEnvelopeSchemaReader);
    this.kafkaMessageEnvelopeSchemaReader = kafkaMessageEnvelopeSchemaReader;
    this.metricsParameters = metricsParameters;
  }

  public Optional<MetricsParameters> getMetricsParameters() {
    return metricsParameters;
  }

  public PubSubConsumer getConsumer(Properties props, KafkaPubSubMessageDeserializer kafkaPubSubMessageDeserializer) {
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    Properties propertiesWithSSL = setupSSL(props);
    return new ApacheKafkaConsumer(
        getKafkaConsumer(propertiesWithSSL),
        new VeniceProperties(props),
        isKafkaConsumerOffsetCollectionEnabled(),
        kafkaPubSubMessageDeserializer);
  }

  private <K, V> Consumer<K, V> getKafkaConsumer(Properties properties) {
    Properties propertiesWithSSL = setupSSL(properties);
    return new KafkaConsumer<>(propertiesWithSSL);
  }

  public KafkaAdminWrapper getWriteOnlyKafkaAdmin(
      Optional<MetricsRepository> optionalMetricsRepository,
      PubSubTopicRepository pubSubTopicRepository) {
    return createAdminClient(
        getWriteOnlyAdminClass(),
        optionalMetricsRepository,
        "WriteOnlyKafkaAdminStats",
        pubSubTopicRepository);
  }

  public KafkaAdminWrapper getReadOnlyKafkaAdmin(
      Optional<MetricsRepository> optionalMetricsRepository,
      PubSubTopicRepository pubSubTopicRepository) {
    return createAdminClient(
        getReadOnlyAdminClass(),
        optionalMetricsRepository,
        "ReadOnlyKafkaAdminStats",
        pubSubTopicRepository);
  }

  public KafkaAdminWrapper getKafkaAdminClient(
      Optional<MetricsRepository> optionalMetricsRepository,
      PubSubTopicRepository pubSubTopicRepository) {
    return createAdminClient(getKafkaAdminClass(), optionalMetricsRepository, "KafkaAdminStats", pubSubTopicRepository);
  }

  private KafkaAdminWrapper createAdminClient(
      String kafkaAdminClientClass,
      Optional<MetricsRepository> optionalMetricsRepository,
      String statsNamePrefix,
      PubSubTopicRepository pubSubTopicRepository) {
    KafkaAdminWrapper adminWrapper =
        ReflectUtils.callConstructor(ReflectUtils.loadClass(kafkaAdminClientClass), new Class[0], new Object[0]);
    Properties properties = setupSSL(new Properties());
    // Increase receive buffer to 1MB to check whether it can solve the metadata timing out issue
    properties.put(ConsumerConfig.RECEIVE_BUFFER_CONFIG, 1024 * 1024);
    if (!properties.contains(ConfigKeys.KAFKA_ADMIN_GET_TOPIC_CONFIG_MAX_RETRY_TIME_SEC)) {
      properties.put(
          ConfigKeys.KAFKA_ADMIN_GET_TOPIC_CONFIG_MAX_RETRY_TIME_SEC,
          DEFAULT_KAFKA_ADMIN_GET_TOPIC_CONFIG_RETRY_IN_SECONDS);
    }
    adminWrapper.initialize(properties, pubSubTopicRepository);
    final String kafkaBootstrapServers = getKafkaBootstrapServers();

    if (optionalMetricsRepository.isPresent()) {
      // Use Kafka bootstrap server to identify which Kafka admin client stats it is
      final String kafkaAdminStatsName =
          String.format("%s_%s_%s", statsNamePrefix, kafkaAdminClientClass, kafkaBootstrapServers);
      adminWrapper = new InstrumentedKafkaAdmin(adminWrapper, optionalMetricsRepository.get(), kafkaAdminStatsName);
      LOGGER.info(
          "Created instrumented Kafka admin client of class {} for Kafka cluster with bootstrap "
              + "server {} and has stats name prefix {}",
          kafkaAdminClientClass,
          kafkaBootstrapServers,
          statsNamePrefix);
    } else {
      LOGGER.info(
          "Created non-instrumented Kafka admin client of class {} for Kafka cluster with bootstrap server {}",
          kafkaAdminClientClass,
          kafkaBootstrapServers);
    }
    return adminWrapper;
  }

  protected boolean isKafkaConsumerOffsetCollectionEnabled() {
    return false;
  }

  /**
   * Setup essential ssl related configuration by putting all ssl properties of this factory into the given properties.
   */
  public abstract Properties setupSSL(Properties properties);

  abstract protected String getKafkaAdminClass();

  /**
   * Get the class name of an admin client that is used for "write-only" tasks such as create topics, update topic configs,
   * etc. "Write-only" means that it only modifies the Kafka cluster state and does not read it.
   *
   * @return Fully-qualified name name. For example: "com.linkedin.venice.kafka.admin.KafkaAdminClient"
   */
  abstract protected String getWriteOnlyAdminClass();

  /**
   * Get the class name of an admin client that is used for "read-only" tasks such as check topic existence, get topic configs,
   * etc. "Read-only" means that it only reads/get the topic metadata from a Kafka cluster and does not modify any of it.
   *
   * @return Fully-qualified name name. For example: "com.linkedin.venice.kafka.admin.KafkaAdminClient"
   */
  abstract protected String getReadOnlyAdminClass();

  public abstract String getKafkaBootstrapServers();

  abstract protected KafkaClientFactory clone(
      String kafkaBootstrapServers,
      Optional<MetricsParameters> metricsParameters);

  public static class MetricsParameters {
    final String uniqueName;
    final MetricsRepository metricsRepository;

    public MetricsParameters(String uniqueMetricNamePrefix, MetricsRepository metricsRepository) {
      this.uniqueName = uniqueMetricNamePrefix;
      this.metricsRepository = metricsRepository;
    }

    public MetricsParameters(
        Class kafkaFactoryClass,
        Class usingClass,
        String kafkaBootstrapUrl,
        MetricsRepository metricsRepository) {
      this(
          kafkaFactoryClass.getSimpleName() + "_used_by_" + usingClass + "_for_" + kafkaBootstrapUrl,
          metricsRepository);
    }
  }
}
