/*
 * Copyright 2020-2022 the original author or authors.
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

package org.springframework.kafka.listener;

import org.springframework.kafka.KafkaException;
import org.springframework.util.Assert;

/**
 * A top level abstract class for classes that can be configured with a
 * {@link KafkaException.Level}.
 *
 * @author Gary Russell
 * @since 2.5
 *
 */
public abstract class KafkaExceptionLogLevelAware {

	private KafkaException.Level logLevel = KafkaException.Level.ERROR;

	/**
	 * Set the level at which the exception thrown by this handler is logged.
	 * @param logLevel the level (default ERROR).
	 */
	public void setLogLevel(KafkaException.Level logLevel) {
		Assert.notNull(logLevel, "'logLevel' cannot be null");
		this.logLevel = logLevel;
	}

	/**
	 * Get the level at which the exception thrown by this handler is logged.
	 * @return the level.
	 */
	protected KafkaException.Level getLogLevel() {
		return this.logLevel;
	}

}
