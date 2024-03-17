/*
 * Copyright 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.kafka.retrytopic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.converter.ConversionException;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.util.backoff.FixedBackOff;


/**
 * Test class level non-blocking retries.
 *
 * @author Wang Zhiyang
 *
 * @since 3.2
 */
@SpringJUnitConfig
@DirtiesContext
@EmbeddedKafka
class RetryTopicClassLevelExceptionRoutingIntegrationTests {

	final static String BLOCKING_AND_TOPIC_RETRY = "blocking-and-topic-retry";
	final static String ONLY_RETRY_VIA_BLOCKING = "only-retry-blocking-topic";
	final static String ONLY_RETRY_VIA_TOPIC = "only-retry-topic";
	final static String USER_FATAL_EXCEPTION_TOPIC = "user-fatal-topic";
	final static String FRAMEWORK_FATAL_EXCEPTION_TOPIC = "framework-fatal-topic";

	@Autowired
	private KafkaTemplate<String, String> kafkaTemplate;

	@Autowired
	private CountDownLatchContainer latchContainer;

	@Test
	void shouldRetryViaBlockingAndTopics() {
		kafkaTemplate.send(BLOCKING_AND_TOPIC_RETRY, "Test message to " + BLOCKING_AND_TOPIC_RETRY);
		assertThat(awaitLatch(latchContainer.blockingAndTopicsLatch)).isTrue();
		assertThat(awaitLatch(latchContainer.dltProcessorLatch)).isTrue();
	}

	@Test
	void shouldRetryOnlyViaBlocking() {
		kafkaTemplate.send(ONLY_RETRY_VIA_BLOCKING, "Test message to ");
		assertThat(awaitLatch(latchContainer.onlyRetryViaBlockingLatch)).isTrue();
		assertThat(awaitLatch(latchContainer.annotatedDltOnlyBlockingLatch)).isTrue();
	}

	@Test
	void shouldRetryOnlyViaTopic() {
		kafkaTemplate.send(ONLY_RETRY_VIA_TOPIC, "Test message to " + ONLY_RETRY_VIA_TOPIC);
		assertThat(awaitLatch(latchContainer.onlyRetryViaTopicLatch)).isTrue();
		assertThat(awaitLatch(latchContainer.dltProcessorWithErrorLatch)).isTrue();
	}

	@Test
	void shouldGoStraightToDltIfUserProvidedFatal() {
		kafkaTemplate.send(USER_FATAL_EXCEPTION_TOPIC, "Test message to " + USER_FATAL_EXCEPTION_TOPIC);
		assertThat(awaitLatch(latchContainer.fatalUserLatch)).isTrue();
		assertThat(awaitLatch(latchContainer.annotatedDltUserFatalLatch)).isTrue();
	}

	@Test
	void shouldGoStraightToDltIfFrameworkProvidedFatal() {
		kafkaTemplate.send(FRAMEWORK_FATAL_EXCEPTION_TOPIC, "Testing topic with annotation 1");
		assertThat(awaitLatch(latchContainer.fatalFrameworkLatch)).isTrue();
		assertThat(awaitLatch(latchContainer.annotatedDltFrameworkFatalLatch)).isTrue();
	}

	private static void countdownIfCorrectInvocations(AtomicInteger invocations, int expected, CountDownLatch latch) {
		int actual = invocations.get();
		if (actual == expected) {
			latch.countDown();
		}
	}

	private boolean awaitLatch(CountDownLatch latch) {
		try {
			return latch.await(30, TimeUnit.SECONDS);
		}
		catch (Exception e) {
			fail(e.getMessage());
			throw new RuntimeException(e);
		}
	}

	static class BlockingAndTopicRetriesListener {

		@Autowired
		CountDownLatchContainer container;

		@KafkaListener(id = "firstTopicId", topics = BLOCKING_AND_TOPIC_RETRY)
		public void listen(String message, @Header(KafkaHeaders.RECEIVED_TOPIC) String receivedTopic) {
			container.blockingAndTopicsLatch.countDown();
			container.blockingAndTopicsListenerInvocations.incrementAndGet();
			throw new ShouldRetryViaBothException("Woooops... in topic " + receivedTopic);
		}
	}

	static class DltProcessor {

		@Autowired
		CountDownLatchContainer container;

		public void processDltMessage(Object message) {
			countdownIfCorrectInvocations(container.blockingAndTopicsListenerInvocations, 12,
					container.dltProcessorLatch);
		}
	}

	@KafkaListener(topics = ONLY_RETRY_VIA_TOPIC)
	static class OnlyRetryViaTopicListener {

		@Autowired
		CountDownLatchContainer container;

		@KafkaHandler
		public void listenAgain(String message, @Header(KafkaHeaders.RECEIVED_TOPIC) String receivedTopic) {
			container.onlyRetryViaTopicLatch.countDown();
			container.onlyRetryViaTopicListenerInvocations.incrementAndGet();
			throw new ShouldRetryOnlyByTopicException("Another woooops... " + receivedTopic);
		}
	}

	static class DltProcessorWithError {

		@Autowired
		CountDownLatchContainer container;

		public void processDltMessage(Object message) {
			countdownIfCorrectInvocations(container.onlyRetryViaTopicListenerInvocations,
					3, container.dltProcessorWithErrorLatch);
			throw new RuntimeException("Dlt Error!");
		}
	}

	@RetryableTopic(exclude = ShouldRetryOnlyBlockingException.class, traversingCauses = "true",
			backoff = @Backoff(50), kafkaTemplate = "kafkaTemplate")
	@KafkaListener(topics = ONLY_RETRY_VIA_BLOCKING)
	static class OnlyRetryBlockingListener {

		@Autowired
		CountDownLatchContainer container;

		@KafkaHandler
		public void listenWithAnnotation(String message, @Header(KafkaHeaders.RECEIVED_TOPIC) String receivedTopic) {
			container.onlyRetryViaBlockingLatch.countDown();
			container.onlyRetryViaBlockingListenerInvocations.incrementAndGet();
			throw new ShouldRetryOnlyBlockingException("User provided fatal exception!" + receivedTopic);
		}

		@DltHandler
		public void annotatedDltMethod(Object message, @Header(KafkaHeaders.RECEIVED_TOPIC) String receivedTopic) {
			countdownIfCorrectInvocations(container.onlyRetryViaBlockingListenerInvocations, 4,
					container.annotatedDltOnlyBlockingLatch);
		}
	}

	@RetryableTopic(backoff = @Backoff(50), kafkaTemplate = "kafkaTemplate")
	@KafkaListener(topics = USER_FATAL_EXCEPTION_TOPIC)
	static class UserFatalTopicListener {

		@Autowired
		CountDownLatchContainer container;

		@KafkaHandler
		public void listenWithAnnotation(String message, @Header(KafkaHeaders.RECEIVED_TOPIC) String receivedTopic) {
			container.fatalUserLatch.countDown();
			container.userFatalListenerInvocations.incrementAndGet();
			throw new ShouldSkipBothRetriesException("User provided fatal exception!" + receivedTopic);
		}

		@DltHandler
		public void annotatedDltMethod(Object message, @Header(KafkaHeaders.RECEIVED_TOPIC) String receivedTopic) {
			countdownIfCorrectInvocations(container.userFatalListenerInvocations, 1,
					container.annotatedDltUserFatalLatch);
		}
	}

	@RetryableTopic(backoff = @Backoff(50))
	@KafkaListener(topics = FRAMEWORK_FATAL_EXCEPTION_TOPIC)
	static class FrameworkFatalTopicListener {

		@Autowired
		CountDownLatchContainer container;


		@KafkaHandler
		public void listenWithAnnotation(String message, @Header(KafkaHeaders.RECEIVED_TOPIC) String receivedTopic) {
			container.fatalFrameworkLatch.countDown();
			container.fatalFrameworkListenerInvocations.incrementAndGet();
			throw new ConversionException("Woooops... in topic " + receivedTopic, new RuntimeException("Test RTE"));
		}

		@DltHandler
		public void annotatedDltMethod(Object message, @Header(KafkaHeaders.RECEIVED_TOPIC) String receivedTopic) {
			countdownIfCorrectInvocations(container.fatalFrameworkListenerInvocations, 1,
					container.annotatedDltFrameworkFatalLatch);
			throw new ConversionException("Woooops... in topic " + receivedTopic, new RuntimeException("Test RTE"));
		}
	}

	static class CountDownLatchContainer {

		CountDownLatch blockingAndTopicsLatch = new CountDownLatch(12);
		CountDownLatch onlyRetryViaBlockingLatch = new CountDownLatch(4);
		CountDownLatch onlyRetryViaTopicLatch = new CountDownLatch(3);
		CountDownLatch fatalUserLatch = new CountDownLatch(1);
		CountDownLatch fatalFrameworkLatch = new CountDownLatch(1);
		CountDownLatch annotatedDltOnlyBlockingLatch = new CountDownLatch(1);
		CountDownLatch annotatedDltUserFatalLatch = new CountDownLatch(1);
		CountDownLatch annotatedDltFrameworkFatalLatch = new CountDownLatch(1);
		CountDownLatch dltProcessorLatch = new CountDownLatch(1);
		CountDownLatch dltProcessorWithErrorLatch = new CountDownLatch(1);

		AtomicInteger blockingAndTopicsListenerInvocations = new AtomicInteger();
		AtomicInteger onlyRetryViaTopicListenerInvocations = new AtomicInteger();
		AtomicInteger onlyRetryViaBlockingListenerInvocations = new AtomicInteger();
		AtomicInteger userFatalListenerInvocations = new AtomicInteger();
		AtomicInteger fatalFrameworkListenerInvocations = new AtomicInteger();

	}

	static class ShouldRetryOnlyByTopicException extends RuntimeException {
		ShouldRetryOnlyByTopicException(String msg) {
			super(msg);
		}
	}

	static class ShouldSkipBothRetriesException extends RuntimeException {
		ShouldSkipBothRetriesException(String msg) {
			super(msg);
		}
	}

	static class ShouldRetryOnlyBlockingException extends RuntimeException {
		ShouldRetryOnlyBlockingException(String msg) {
			super(msg);
		}
	}

	static class ShouldRetryViaBothException extends RuntimeException {
		ShouldRetryViaBothException(String msg) {
			super(msg);
		}
	}

	@Configuration
	static class RetryTopicConfigurations {

		private static final String DLT_METHOD_NAME = "processDltMessage";

		@Bean
		RetryTopicConfiguration blockingAndTopic(KafkaTemplate<String, String> template) {
			return RetryTopicConfigurationBuilder
					.newInstance()
					.fixedBackOff(50)
					.includeTopic(BLOCKING_AND_TOPIC_RETRY)
					.dltHandlerMethod("dltProcessor", DLT_METHOD_NAME)
					.create(template);
		}

		@Bean
		RetryTopicConfiguration onlyTopic(KafkaTemplate<String, String> template) {
			return RetryTopicConfigurationBuilder
					.newInstance()
					.fixedBackOff(50)
					.includeTopic(ONLY_RETRY_VIA_TOPIC)
					.sameIntervalTopicReuseStrategy(SameIntervalTopicReuseStrategy.SINGLE_TOPIC)
					.doNotRetryOnDltFailure()
					.dltHandlerMethod("dltProcessorWithError", DLT_METHOD_NAME)
					.create(template);
		}

		@Bean
		BlockingAndTopicRetriesListener blockingAndTopicRetriesListener() {
			return new BlockingAndTopicRetriesListener();
		}

		@Bean
		OnlyRetryViaTopicListener onlyRetryViaTopicListener() {
			return new OnlyRetryViaTopicListener();
		}

		@Bean
		UserFatalTopicListener userFatalTopicListener() {
			return new UserFatalTopicListener();
		}

		@Bean
		OnlyRetryBlockingListener onlyRetryBlockingListener() {
			return new OnlyRetryBlockingListener();
		}

		@Bean
		FrameworkFatalTopicListener frameworkFatalTopicListener() {
			return new FrameworkFatalTopicListener();
		}

		@Bean
		CountDownLatchContainer latchContainer() {
			return new CountDownLatchContainer();
		}

		@Bean
		DltProcessor dltProcessor() {
			return new DltProcessor();
		}

		@Bean
		DltProcessorWithError dltProcessorWithError() {
			return new DltProcessorWithError();
		}

		@Bean
		TaskScheduler sched() {
			return new ThreadPoolTaskScheduler();
		}

	}

	@Configuration
	static class RoutingTestsConfigurationSupport extends RetryTopicConfigurationSupport {

		@Override
		protected void configureBlockingRetries(BlockingRetriesConfigurer blockingRetries) {
			blockingRetries
					.retryOn(ShouldRetryOnlyBlockingException.class, ShouldRetryViaBothException.class)
					.backOff(new FixedBackOff(50, 3));
		}

		@Override
		protected void manageNonBlockingFatalExceptions(List<Class<? extends Throwable>> nonBlockingFatalExceptions) {
			nonBlockingFatalExceptions.add(ShouldSkipBothRetriesException.class);
		}
	}

	@Configuration
	static class KafkaProducerConfig {

		@Autowired
		EmbeddedKafkaBroker broker;

		@Bean
		ProducerFactory<String, String> producerFactory() {
			Map<String, Object> configProps = new HashMap<>();
			configProps.put(
					ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
					this.broker.getBrokersAsString());
			configProps.put(
					ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
					StringSerializer.class);
			configProps.put(
					ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
					StringSerializer.class);
			return new DefaultKafkaProducerFactory<>(configProps);
		}

		@Bean
		KafkaTemplate<String, String> kafkaTemplate() {
			return new KafkaTemplate<>(producerFactory());
		}
	}

	@EnableKafka
	@Configuration
	static class KafkaConsumerConfig {

		@Autowired
		EmbeddedKafkaBroker broker;

		@Bean
		KafkaAdmin kafkaAdmin() {
			Map<String, Object> configs = new HashMap<>();
			configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, this.broker.getBrokersAsString());
			return new KafkaAdmin(configs);
		}

		@Bean
		ConsumerFactory<String, String> consumerFactory() {
			Map<String, Object> props = new HashMap<>();
			props.put(
					ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
					this.broker.getBrokersAsString());
			props.put(
					ConsumerConfig.GROUP_ID_CONFIG,
					"groupId");
			props.put(
					ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
					StringDeserializer.class);
			props.put(
					ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
					StringDeserializer.class);
			props.put(
					ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
			props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

			return new DefaultKafkaConsumerFactory<>(props);
		}

		@Bean
		ConcurrentKafkaListenerContainerFactory<String, String> retryTopicListenerContainerFactory(
				ConsumerFactory<String, String> consumerFactory) {

			ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
			ContainerProperties props = factory.getContainerProperties();
			props.setIdleEventInterval(100L);
			props.setPollTimeout(50L);
			props.setIdlePartitionEventInterval(100L);
			factory.setConsumerFactory(consumerFactory);
			factory.setConcurrency(1);
			factory.setContainerCustomizer(
					container -> container.getContainerProperties().setIdlePartitionEventInterval(100L));
			return factory;
		}

		@Bean
		ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
				ConsumerFactory<String, String> consumerFactory) {

			ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
			factory.setConsumerFactory(consumerFactory);
			factory.setConcurrency(1);
			return factory;
		}

	}
}
