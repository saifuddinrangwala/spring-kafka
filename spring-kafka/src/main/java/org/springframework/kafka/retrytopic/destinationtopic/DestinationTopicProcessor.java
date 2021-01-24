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

package org.springframework.kafka.retrytopic.destinationtopic;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 *
 * Contains the methods for the {@link org.springframework.kafka.retrytopic.RetryTopicConfigurer}
 * to process the destination topics.
 *
 * @author Tomaz Fernandes
 * @since 2.7.0
 */
public interface DestinationTopicProcessor {

	void processDestinationProperties(Consumer<DestinationTopic.Properties> destinationPropertiesProcessor, Context context);
	void registerTopicDestination(String mainTopic, DestinationTopic destinationTopic, Context context);
	void processRegisteredDestinations(Consumer<Collection<String>> topicsConsumer, Context context);

	class Context {
		protected final Map<String, List<DestinationTopic>> destinationsByTopicMap;
		protected final List<DestinationTopic.Properties> properties;

		public Context(List<DestinationTopic.Properties> properties) {
			this.destinationsByTopicMap = new HashMap<>();
			this.properties = properties;
		}
	}
}
