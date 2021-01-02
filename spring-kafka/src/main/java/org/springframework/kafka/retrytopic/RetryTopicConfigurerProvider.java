/*
 * Copyright 2018-2021 the original author or authors.
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

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.log.LogAccessor;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.adapter.AdapterUtils;
import org.springframework.lang.Nullable;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.backoff.ExponentialRandomBackOffPolicy;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.backoff.Sleeper;
import org.springframework.retry.backoff.SleepingBackOffPolicy;
import org.springframework.retry.backoff.UniformRandomBackOffPolicy;
import org.springframework.util.Assert;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;


/**
 *
 * Attempts to provide an instance of {@link RetryTopicConfigurer} by either creating one
 * from a {@link RetryableTopic} annotation first, or from the provided {@link BeanFactory} if no annotation
 * is available.
 *
 * If beans are found in the container there's a check to determine whether or not
 * the provided topics topics should be handled by any of such instances.
 *
 * If the annotation is provided, a {@link DltHandler} annotated method is looked up.
 *
 * @author Tomaz Fernandes
 * @since 2.7.0
 * @see RetryTopicConfigurer
 * @see RetryableTopic
 * @see DltHandler
 *
 */
public class RetryTopicConfigurerProvider {

	private static final LogAccessor logger = new LogAccessor(LogFactory.getLog(RetryTopicConfigurerProvider.class));
	private static final SpelExpressionParser PARSER;
	private static final String DEFAULT_KAFKA_TEMPLATE_BEAN_NAME = "retryKafkaTemplate";
	private static final RetryTopicConfigurer NO_INSTANCE_TO_PROVIDE = null;
	private static final BeanFactory NO_SUITABLE_FACTORY_INSTANCE = null;
	private final BeanFactory beanFactory;

	static {
		PARSER = new SpelExpressionParser();
	}

	public RetryTopicConfigurerProvider(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	@Nullable
	public RetryTopicConfigurer maybeGet(String[] topics, Method method, Object bean) {
		RetryableTopic annotation = AnnotationUtils.findAnnotation(method, RetryableTopic.class); // TODO: Enable class-level annotations
		return annotation != null
				? buildFromAnnotation(topics, method, annotation, bean)
				: maybeGetFromBeanFactory(topics);
	}

	public static RetryTopicConfigurerProvider create(BeanFactory beanFactory) {
		return new RetryTopicConfigurerProvider(beanFactory);
	}

	private RetryTopicConfigurer maybeGetFromBeanFactory(String[] topics) {
		if (this.beanFactory == null || !ListableBeanFactory.class.isAssignableFrom(this.beanFactory.getClass())) {
			logger.warn("No ListableBeanFactory found, skipping RetryTopic configuration.");
			return NO_INSTANCE_TO_PROVIDE;
		}

		Map<String, RetryTopicConfigurer> retryTopicProcessors = ((ListableBeanFactory) this.beanFactory).getBeansOfType(RetryTopicConfigurer.class);
		return retryTopicProcessors
				.values()
				.stream()
				.filter(topicProcessor -> topicProcessor.shouldHandleTopics(topics))
				.map(topicProcessor -> topicProcessor.setBeanFactory(this.beanFactory))
				.findFirst()
				.orElse(NO_INSTANCE_TO_PROVIDE);
	}

	public RetryTopicConfigurer buildFromAnnotation(String[] topics, Method method, RetryableTopic annotation, Object bean) {
		return RetryTopicConfigurer.builder()
				.maxAttempts(annotation.attempts())
				.customBackoff(createBackoffFromAnnotation(annotation.backoff(), this.beanFactory))
				.retryTopicSuffix(annotation.retryTopicSuffix())
				.dltSuffix(annotation.dltTopicSuffix())
				.dltHandlerMethod(getDltProcessor(method, bean))
				.includeTopics(Arrays.asList(topics))
				.listenerFactory(annotation.listenerContainerFactory())
				.autoCreateTopics(annotation.autoCreateTopics(), annotation.numPartitions(), annotation.replicationFactor())
				.retryOn(Arrays.asList(annotation.include()))
				.notRetryOn(Arrays.asList(annotation.exclude()))
				.traversingCauses(annotation.traversingCauses())
				.create(getKafkaTemplate(annotation.kafkaTemplate(), topics))
				.setBeanFactory(this.beanFactory);
	}

	private SleepingBackOffPolicy<?> createBackoffFromAnnotation(Backoff backoff, BeanFactory beanFactory) {
		Sleeper sleeper = BackOffValuesGenerator.createRetainerSleeper();
		StandardEvaluationContext evaluationContext = new StandardEvaluationContext();
		evaluationContext.setBeanResolver(new BeanFactoryResolver(beanFactory));

		// Yup, just copied the code from Spring Retry :)
		long min = backoff.delay() == 0 ? backoff.value() : backoff.delay();
		if (StringUtils.hasText(backoff.delayExpression())) {
			min = PARSER.parseExpression(resolve(backoff.delayExpression(), beanFactory), AdapterUtils.PARSER_CONTEXT)
					.getValue(evaluationContext, Long.class);
		}
		long max = backoff.maxDelay();
		if (StringUtils.hasText(backoff.maxDelayExpression())) {
			max = PARSER.parseExpression(resolve(backoff.maxDelayExpression(), beanFactory), AdapterUtils.PARSER_CONTEXT)
					.getValue(evaluationContext, Long.class);
		}
		double multiplier = backoff.multiplier();
		if (StringUtils.hasText(backoff.multiplierExpression())) {
			multiplier = PARSER.parseExpression(resolve(backoff.multiplierExpression(), beanFactory), AdapterUtils.PARSER_CONTEXT)
					.getValue(evaluationContext, Double.class);
		}
		if (multiplier > 0) {
			ExponentialBackOffPolicy policy = new ExponentialBackOffPolicy();
			if (backoff.random()) {
				policy = new ExponentialRandomBackOffPolicy();
			}
			policy.setInitialInterval(min);
			policy.setMultiplier(multiplier);
			policy.setMaxInterval(max > min ? max : ExponentialBackOffPolicy.DEFAULT_MAX_INTERVAL);
			policy.setSleeper(sleeper);
			return policy;
		}
		if (max > min) {
			UniformRandomBackOffPolicy policy = new UniformRandomBackOffPolicy();
			policy.setMinBackOffPeriod(min);
			policy.setMaxBackOffPeriod(max);
			policy.setSleeper(sleeper);
			return policy;
		}
		FixedBackOffPolicy policy = new FixedBackOffPolicy();
		policy.setBackOffPeriod(min);
		policy.setSleeper(sleeper);
		return policy;
	}

	private String resolve(String value, BeanFactory beanFactory) {
		if (beanFactory instanceof ConfigurableBeanFactory) {
			return ((ConfigurableBeanFactory) beanFactory).resolveEmbeddedValue(value);
		}
		return value;
	}

	private RetryTopicConfigurer.EndpointHandlerMethod getDltProcessor(Method listenerMethod, Object bean) {
		Class<?> declaringClass = listenerMethod.getDeclaringClass();
		return Arrays.stream(ReflectionUtils.getDeclaredMethods(declaringClass))
				.filter(method -> AnnotationUtils.findAnnotation(method, DltHandler.class) != null)
				.map(method -> RetryTopicConfigurer.createHandlerMethodWith(bean, method))
				.findFirst()
				.orElse(RetryTopicConfigurer.DEFAULT_DLT_HANDLER);
	}

	private KafkaOperations<?, ?> getKafkaTemplate(String kafkaTemplateName, String[] topics) {
		if (StringUtils.hasText(kafkaTemplateName)) {
			Assert.state(this.beanFactory != null, "BeanFactory must be set to obtain kafka template by bean name");
			try {
				return this.beanFactory.getBean(kafkaTemplateName, KafkaOperations.class);
			}
			catch (NoSuchBeanDefinitionException ex) {
				throw new BeanInitializationException("Could not register Kafka listener endpoint for topics " + topics + ", no " + KafkaOperations.class.getSimpleName()
						+ " with id '" + kafkaTemplateName + "' was found in the application context", ex);
			}
		}
		try {
			return this.beanFactory.getBean(DEFAULT_KAFKA_TEMPLATE_BEAN_NAME, KafkaOperations.class);
		}
		catch (NoSuchBeanDefinitionException ex) {
			throw new BeanInitializationException("Could not find a KafkaTemplate to configure the retry topics.", ex);
		}
	}
}
