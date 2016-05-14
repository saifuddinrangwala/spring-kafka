/*
 * Copyright 2016 the original author or authors.
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

package org.springframework.kafka.listener.adapter;

import org.apache.kafka.clients.consumer.ConsumerRecord;

import org.springframework.kafka.listener.MessageListener;

/**
 * A {@link MessageListener} adapter that implements de-duplication logic
 * via a DeDuplicationStrategy.
 *
 * @author Gary Russell
 *
 */
public class DeduplicatingMessageListenerAdapter<K, V> extends AbstractDeDuplicatingMessageListener<K, V>
		implements MessageListener<K, V> {

	private final MessageListener<K, V> delegate;

	public DeduplicatingMessageListenerAdapter(DeDuplicationStrategy<K, V> deDupStrategy,
			MessageListener<K, V> delegate) {
		super(deDupStrategy);
		this.delegate = delegate;
	}

	@Override
	public void onMessage(ConsumerRecord<K, V> consumerRecord) {
		if (!isDuplicate(consumerRecord)) {
			this.delegate.onMessage(consumerRecord);
		}
	}

}
