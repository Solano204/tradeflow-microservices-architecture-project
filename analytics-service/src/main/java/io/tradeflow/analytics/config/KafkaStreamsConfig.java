package io.tradeflow.analytics.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.annotation.KafkaStreamsDefaultConfiguration;
import org.springframework.kafka.config.KafkaStreamsConfiguration;
import org.springframework.kafka.config.StreamsBuilderFactoryBeanConfigurer;
import org.springframework.kafka.config.TopicBuilder;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka Streams configuration for Analytics Service.
 *
 * IMPORTANT — MissingSourceTopicException fix:
 *   Kafka Streams throws MissingSourceTopicException and shuts down if ANY
 *   source topic does not exist at startup time. Unlike regular @KafkaListener
 *   consumers, Streams does NOT wait — it crashes immediately.
 *
 *   The upstream services (Order, Payment, Catalog) auto-create their topics
 *   when they publish the first event. If Analytics starts before those services
 *   have published anything, the topics won't exist yet.
 *
 *   Fix: declare @Bean NewTopic for every source topic. Spring Kafka's
 *   KafkaAdmin creates them on startup with replication-factor=1 and
 *   IF-NOT-EXISTS semantics — so existing topics are never overwritten.
 *
 * Source topics consumed by Analytics topologies:
 *   Topology 1 (Revenue):          payment.events
 *   Topology 2 (Order Volume):     order.events
 *   Topology 3 (Product Perf):     payment.events + catalog.product-events (KTable)
 *   LiveSalesConsumer:             payment.events
 */
@Configuration
@EnableKafkaStreams
@Slf4j
public class KafkaStreamsConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${analytics.kafka-streams.application-id:analytics-streams-local-v1}")
    private String applicationId;

    @Value("${analytics.kafka-streams.state-dir:./data/kafka-streams}")
    private String stateDir;

    // ── Source topic pre-creation ──────────────────────────────────────────
    // These beans tell KafkaAdmin to create the topics before Streams starts.
    // If the topic already exists (created by the upstream service), this is a
    // no-op — Spring checks IF-NOT-EXISTS internally.

    @Bean
    public NewTopic paymentEventsTopic() {
        return TopicBuilder.name("payment.events")
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic orderEventsTopic() {
        return TopicBuilder.name("order.events")
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic catalogProductEventsTopic() {
        return TopicBuilder.name("catalog.product-events")
                .partitions(1)
                .replicas(1)
                .build();
    }

    // ── Optional: also pre-create topics that other services need ──────────
    // Uncomment if you start Analytics before Inventory or Notification:
    // @Bean public NewTopic inventoryEventsTopic() {
    //     return TopicBuilder.name("inventory.events").partitions(1).replicas(1).build();
    // }
    // @Bean public NewTopic notificationsTopic() {
    //     return TopicBuilder.name("notifications").partitions(1).replicas(1).build();
    // }

    // ── Kafka Streams configuration ────────────────────────────────────────

    @Bean(name = KafkaStreamsDefaultConfiguration.DEFAULT_STREAMS_CONFIG_BEAN_NAME)
    public KafkaStreamsConfiguration kafkaStreamsConfig() {
        Map<String, Object> props = new HashMap<>();

        props.put(StreamsConfig.APPLICATION_ID_CONFIG, applicationId);
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(StreamsConfig.STATE_DIR_CONFIG, stateDir);

        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG,
                Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG,
                Serdes.String().getClass().getName());

        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.AT_LEAST_ONCE);
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 10_000L);
        props.put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, 10 * 1024 * 1024L);
        props.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 2);

        return new KafkaStreamsConfiguration(props);
    }

    @Bean
    public StreamsBuilderFactoryBeanConfigurer streamsConfigurer() {
        return factoryBean -> {
            factoryBean.setKafkaStreamsCustomizer(kafkaStreams -> {
                kafkaStreams.setUncaughtExceptionHandler(exception -> {
                    log.error("Kafka Streams uncaught exception: {}",
                            exception.getMessage(), exception);
                    // REPLACE_THREAD: restart the failed thread instead of
                    // shutting down the entire Streams instance
                    return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD;
                });
                kafkaStreams.setStateListener((newState, oldState) ->
                        log.info("Kafka Streams state: {} → {}", oldState, newState));
            });
        };
    }
}