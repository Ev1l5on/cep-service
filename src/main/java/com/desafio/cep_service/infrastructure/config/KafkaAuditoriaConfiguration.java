package com.desafio.cep_service.infrastructure.config;

import com.desafio.cep_service.application.exception.EventoConsultaInvalidoException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class KafkaAuditoriaConfiguration {
    private static final Logger log = LoggerFactory.getLogger(KafkaAuditoriaConfiguration.class);

    @Bean
    DefaultKafkaProducerFactory<String, String> auditoriaProducerFactory(KafkaProperties properties) {
        return new DefaultKafkaProducerFactory<>(properties.buildProducerProperties());
    }

    @Bean
    KafkaTemplate<String, String> auditoriaKafkaTemplate(DefaultKafkaProducerFactory<String, String> factory) {
        var template = new KafkaTemplate<>(factory);
        // O adaptador trata callbacks; o listener padrão pode imprimir chave/payload e duplicar logs.
        template.setProducerListener(null);
        return template;
    }

    @Bean
    DefaultKafkaConsumerFactory<String, String> auditoriaConsumerFactory(KafkaProperties properties) {
        return new DefaultKafkaConsumerFactory<>(properties.buildConsumerProperties());
    }

    @Bean
    ThreadPoolTaskExecutor auditoriaPublisherExecutor(@Value("${auditoria.publisher.queue-capacity}") int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("A fila de auditoria deve ter capacidade positiva");
        }
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(capacity);
        executor.setThreadNamePrefix("auditoria-publisher-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    @Bean
    NewTopic consultasTopic(@Value("${auditoria.kafka.topic}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic consultasDltTopic(@Value("${auditoria.kafka.dlt-topic}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1)
                .config("retention.ms", "1209600000").build();
    }

    @Bean
    DeadLetterPublishingRecoverer auditoriaRecoverer(KafkaTemplate<String, String> kafka,
            @Value("${auditoria.kafka.dlt-topic}") String dlt) {
        var recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, ex) -> new TopicPartition(dlt, record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(20));
        recoverer.setLogRecoveryRecord(false);
        recoverer.setExceptionHeadersCreator((headers, ex, key, names) -> headers.add(
                new RecordHeader("audit-failure-type", ex.getClass().getSimpleName().getBytes(StandardCharsets.UTF_8))));
        return recoverer;
    }

    @Bean
    DefaultErrorHandler auditoriaErrorHandler(DeadLetterPublishingRecoverer recoverer,
            @Value("${auditoria.consumer.retry-interval-ms}") long interval,
            @Value("${auditoria.consumer.max-retries}") long retries) {
        if (interval < 1 || retries < 0 || retries > 10) {
            throw new IllegalArgumentException("Configure intervalo positivo e entre zero e dez retries de auditoria");
        }
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(interval, retries));
        handler.addNotRetryableExceptions(EventoConsultaInvalidoException.class);
        handler.setResetStateOnExceptionChange(false);
        handler.setResetStateOnRecoveryFailure(false);
        handler.setAckAfterHandle(true);
        handler.setLogLevel(KafkaException.Level.DEBUG);
        handler.setRetryListeners(new RetryListener() {
            @Override
            public void failedDelivery(org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record,
                    Exception ex, int attempt) {
                log.warn("Falha auditoria: topic={} partition={} offset={} tentativa={} tipo={}",
                        record.topic(), record.partition(), record.offset(), attempt,
                        ex == null ? "desconhecido" : ex.getClass().getSimpleName());
            }

            @Override
            public void recovered(org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record, Exception ex) {
                log.error("Auditoria encaminhada à DLT: topic={} partition={} offset={}",
                        record.topic(), record.partition(), record.offset());
            }

            @Override
            public void recoveryFailed(org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record,
                    Exception original, Exception failure) {
                log.error("Falha de envio DLT; offset não confirmado: topic={} partition={} offset={} tipo={}",
                        record.topic(), record.partition(), record.offset(), failure.getClass().getSimpleName());
            }
        });
        return handler;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> auditoriaKafkaListenerContainerFactory(
            DefaultKafkaConsumerFactory<String, String> consumerFactory, DefaultErrorHandler errorHandler,
            KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.setAutoStartup(properties.getListener().isAutoStartup());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setSyncCommits(true);
        return factory;
    }
}
