/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.kafka.test.context;

import java.io.StringReader;
import java.util.Map;
import java.util.Properties;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultSingletonBeanRegistry;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.test.rule.KafkaEmbedded;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * The {@link ContextCustomizer} implementation for Spring Integration specific environment.
 * <p>
 * Registers {@link KafkaEmbedded} bean.
 *
 * @author Artem Bilan
 *
 * @since 1.3
 */
class EmbeddedKafkaContextCustomizer implements ContextCustomizer {

	private final EmbeddedKafka embeddedKafka;

	EmbeddedKafkaContextCustomizer(EmbeddedKafka embeddedKafka) {
		this.embeddedKafka = embeddedKafka;
	}

	@Override
	@SuppressWarnings("unchecked")
	public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {

		if (this.embeddedKafka.disabled()) {
			return;
		}

		ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
		Assert.isInstanceOf(DefaultSingletonBeanRegistry.class, beanFactory);

		KafkaEmbedded kafkaEmbedded = new KafkaEmbedded(this.embeddedKafka.count(),
				this.embeddedKafka.controlledShutdown(),
				this.embeddedKafka.partitions(),
				this.embeddedKafka.topics());

		Properties properties = new Properties();

		for (String pair : this.embeddedKafka.brokerProperties()) {
			if (!StringUtils.hasText(pair)) {
				continue;
			}
			try {
				properties.load(new StringReader(pair));
			}
			catch (Exception ex) {
				throw new IllegalStateException("Failed to load broker property from [" + pair + "]", ex);
			}
		}

		kafkaEmbedded.brokerProperties((Map<String, String>) (Map<?, ?>) properties);

		beanFactory.initializeBean(kafkaEmbedded, KafkaEmbedded.BEAN_NAME);
		beanFactory.registerSingleton(KafkaEmbedded.BEAN_NAME, kafkaEmbedded);
		((DefaultSingletonBeanRegistry) beanFactory).registerDisposableBean(KafkaEmbedded.BEAN_NAME, kafkaEmbedded);
	}

}

