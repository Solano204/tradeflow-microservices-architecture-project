//package io.tradeflow.search;
//
//import brave.kafka.clients.KafkaTracing;
//import org.apache.kafka.clients.consumer.ConsumerConfig;
//import org.apache.kafka.clients.producer.ProducerConfig;
//import org.apache.kafka.common.serialization.StringDeserializer;
//import org.apache.kafka.common.serialization.StringSerializer;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.kafka.annotation.EnableKafka;
//import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
//import org.springframework.kafka.core.*;
//import org.springframework.kafka.listener.ContainerProperties;
//import org.springframework.kafka.listener.DefaultErrorHandler;
//import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
//import org.springframework.kafka.support.serializer.JsonDeserializer;
//import org.springframework.kafka.support.serializer.JsonSerializer;
//import org.springframework.util.backoff.FixedBackOff;
//
//import java.util.HashMap;
//import java.util.Map;
//
///**
// * IdentityKafkaConfig — complete Kafka config WITH distributed tracing.
// *
// * ┌─────────────────────────────────────────────────────────────────────┐
// * │  COPY-PASTE INSTRUCTIONS:                                           │
// * │                                                                     │
// * │  If you already have a KafkaConfig.java:                            │
// * │    1. Add KafkaTracing parameter to your ProducerFactory bean       │
// * │    2. Wrap createKafkaProducer() as shown below                     │
// * │    3. Add KafkaTracing parameter to your ConsumerFactory bean       │
// * │    4. Wrap createKafkaConsumer() as shown below                     │
// * │    5. Done. No other changes needed.                                │
// * │                                                                     │
// * │  If you don't have a KafkaConfig.java: use this file as-is,        │
// * │  just update KAFKA_BOOTSTRAP_SERVERS and group IDs.                 │
// * └─────────────────────────────────────────────────────────────────────┘
// *
// * HOW TRACE PROPAGATION WORKS:
// *
// *   ① HTTP POST /identity/buyers arrives
// *      → Micrometer auto-creates root span: traceId = abc123, spanId = 001
// *
// *   ② Service layer calls kafkaTemplate.send("identity.buyer-events", payload)
// *      → TracingProducer (see createKafkaProducer below) injects header:
// *         traceparent: 00-abc123-002-01
// *      → Kafka record now carries traceId abc123 in its headers
// *
// *   ③ Auth Service, Order Service, etc. consume the message
// *      → TracingConsumer (see createKafkaConsumer below) extracts header
// *      → Creates child span: traceId = abc123, spanId = 003 (NEW spanId, SAME traceId)
// *      → All DB queries inside the consumer are also children of abc123
// *
// *   ④ In Zipkin UI: search by traceId abc123
// *      → See the FULL chain: HTTP → Identity DB → Kafka → Auth DB → ...
// */
//@Configuration
//@EnableKafka
//public class IdentityKafkaConfig {
//
//    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
//    private String bootstrapServers;
//
//    // ─────────────────────────────────────────────────────────────────────────
//    // PRODUCER — wraps with KafkaTracing so traceId is injected into headers
//    // ─────────────────────────────────────────────────────────────────────────
//
//    @Bean
//    public ProducerFactory<String, Object> producerFactory(KafkaTracing kafkaTracing) {
//        Map<String, Object> config = new HashMap<>();
//        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
//        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
//        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
//        config.put(ProducerConfig.ACKS_CONFIG, "all");
//        config.put(ProducerConfig.RETRIES_CONFIG, 3);
//        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
//        // Don't add __TypeId__ headers — other services don't need them
//        config.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
//
//        return new DefaultKafkaProducerFactory<>(config) {
//            /**
//             * ✅ KEY CHANGE: wrap every created Producer with KafkaTracing.
//             * This TracingProducer automatically injects the current traceId
//             * as "traceparent" header on every message sent.
//             */
//            @Override
//            protected org.apache.kafka.clients.producer.Producer<String, Object> createKafkaProducer() {
//                return kafkaTracing.producer(super.createKafkaProducer());
//            }
//        };
//    }
//
//    @Bean
//    public KafkaTemplate<String, Object> kafkaTemplate(
//            ProducerFactory<String, Object> producerFactory) {
//        return new KafkaTemplate<>(producerFactory);
//    }
//
//    // ─────────────────────────────────────────────────────────────────────────
//    // CONSUMER — wraps with KafkaTracing so traceId is extracted from headers
//    // ─────────────────────────────────────────────────────────────────────────
//
//    @Bean
//    public ConsumerFactory<String, Object> consumerFactory(KafkaTracing kafkaTracing) {
//        Map<String, Object> config = new HashMap<>();
//        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
//        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
//        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
//
//        // ErrorHandlingDeserializer wraps JsonDeserializer to prevent
//        // deserialization failures from crashing the consumer thread
//        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
//        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
//        config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class.getName());
//
//        // Don't require __TypeId__ headers — other services don't send them
//        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
//        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, "java.util.HashMap");
//        config.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
//
//        return new DefaultKafkaConsumerFactory<>(config) {
//            /**
//             * ✅ KEY CHANGE: wrap every created Consumer with KafkaTracing.
//             * This TracingConsumer automatically extracts the "traceparent" header
//             * from each incoming message and makes it the current trace context,
//             * so all processing inside the @KafkaListener method runs under
//             * the SAME traceId that originated in the producer service.
//             */
//            @Override
//            protected org.apache.kafka.clients.consumer.Consumer<String, Object> createKafkaConsumer(
//                    String groupId, String clientIdSuffix, String clientId,
//                    java.util.Properties properties) {
//                return kafkaTracing.consumer(
//                        super.createKafkaConsumer(groupId, clientIdSuffix, clientId, properties));
//            }
//        };
//    }
//
//    @Bean
//    public ConcurrentKafkaListenerContainerFactory<String, Object>
//    kafkaListenerContainerFactory(ConsumerFactory<String, Object> consumerFactory) {
//
//        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
//                new ConcurrentKafkaListenerContainerFactory<>();
//
//        factory.setConsumerFactory(consumerFactory);
//        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
//        factory.setConcurrency(2);
//
//        // Retry 3 times with 1s delay before skipping a bad message
//        DefaultErrorHandler errorHandler = new DefaultErrorHandler(new FixedBackOff(1000L, 3L));
//        factory.setCommonErrorHandler(errorHandler);
//
//        return factory;
//    }
//}