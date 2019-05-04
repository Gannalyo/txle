/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.servicecomb.saga.alpha.core;

import org.apache.servicecomb.saga.alpha.core.kafka.IKafkaMessageProducer;
import org.apache.servicecomb.saga.alpha.core.kafka.IKafkaMessageRepository;
import org.apache.servicecomb.saga.alpha.core.kafka.KafkaMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.apache.servicecomb.saga.common.EventType.*;

public class TxConsistentService {
  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final TxEventRepository eventRepository;

  @Autowired
  private IKafkaMessageProducer kafkaMessageProducer;

  @Autowired
  private IKafkaMessageRepository kafkaMessageRepository;

	@Autowired
	UtxMetrics utxMetrics;

  private final List<String> types = Arrays.asList(TxStartedEvent.name(), SagaEndedEvent.name());

  public TxConsistentService(TxEventRepository eventRepository) {
    this.eventRepository = eventRepository;
  }

  public boolean handle(TxEvent event) {
	  utxMetrics.startMarkTxDuration(event);// start duration.
	  if (types.contains(event.type()) && isGlobalTxAborted(event)) {
		  LOG.info("Transaction event {} rejected, because its parent with globalTxId {} was already aborted",
				  event.type(), event.globalTxId());
		  utxMetrics.endMarkTxDuration(event);// end duration.
		  return false;
	  }

	  eventRepository.save(event);
	  utxMetrics.endMarkTxDuration(event);// end duration.

	  return true;
  }

	/**
	 * handle the event. support transaction pause/continue/auto-continue.
	 *
	 * @author Gannalyo
	 */
	public int handleSupportTxPause(TxEvent event) {
		utxMetrics.startMarkTxDuration(event);// start duration.
		utxMetrics.countChildTxNumber(event);// child transaction count
		if (types.contains(event.type()) && isGlobalTxAborted(event)) {
			LOG.info("Transaction event {} rejected, because its parent with globalTxId {} was already aborted", event.type(), event.globalTxId());
			boolean isRetried = eventRepository.checkIsRetriedEvent(event.globalTxId());
			utxMetrics.countTxNumber(event, false, isRetried);
			utxMetrics.endMarkTxDuration(event);// end duration.
			return -1;
		}

		/**
		 * To save event only when the status of the global transaction is not paused.
		 * If not, return to client immediately, and client will do something, like sending again.
		 */
		boolean isPaused = isGlobalTxPaused(event.globalTxId());
		if (!isPaused) {
			CurrentThreadContext.put(event.globalTxId(), event);

			// We could intercept this method or use the Observer Design model on it, the aim is to handle some operations around it, but apparently, it is not easy to maintain code, so we reserved this idea.
			if (!eventRepository.checkIsExistsTxCompensatedEvent(event.type(), event.localTxId())) {// 保存事件前，检查是否已经存在某子事务的某种事件，如果存在则不再保存。如：检测某事务超时后，若在下次检测时做出补偿处理，则会保存多条超时事件信息，为避免则先检测是否存在
				eventRepository.save(event);

				boolean isRetried = eventRepository.checkIsRetriedEvent(event.globalTxId());
				utxMetrics.countTxNumber(event, false, isRetried);
				utxMetrics.endMarkTxDuration(event);// end duration.

				// To send message to Kafka.
				kafkaMessageProducer.send(event);
			}

			return 1;
		}

		utxMetrics.endMarkTxDuration(event);// end duration.

		return 0;
	}

  private boolean isGlobalTxAborted(TxEvent event) {
    return !eventRepository.findTransactions(event.globalTxId(), TxAbortedEvent.name()).isEmpty();
  }
  
	public boolean isGlobalTxPaused(String globalTxId) {
		boolean isPaused = false;
		try {
			List<TxEvent> pauseContinueEventList = eventRepository.selectPausedAndContinueEvent(globalTxId);
			if (pauseContinueEventList != null && !pauseContinueEventList.isEmpty()) {
				isPaused = pauseContinueEventList.size() % 2 == 1;
				if (isPaused) {
					TxEvent event = pauseContinueEventList.get(0);
					if (event != null) {
						Date expiryTime = event.expiryTime();
						if (expiryTime.compareTo(new Date()) < 1) {
							try {
								// was due, create the event 'SagaAutoContinueEvent' to make event to continue running.
								eventRepository.save(new TxEvent(event.serviceName(),
										event.instanceId(), event.globalTxId(), event.localTxId(), event.parentTxId(),
										AdditionalEventType.SagaAutoContinuedEvent.name(), "", 0, "", 0, event.category(), event.payloads()));
								isPaused = false;
							} catch (Exception e) {
								isPaused = true;
								LOG.error("Fail to save the event 'SagaAutoContinuedEvent'.", e);
							}
						}
					}
				}
			}
		} catch (Exception e) {
			LOG.error("Fail to execute the method 'isGlobalTxPaused'.", e);
		}
		return isPaused;
	}

	public Set<String> fetchLocalTxIdOfEndedGlobalTx(Set<String> localTxIdSet) {
		return eventRepository.selectEndedGlobalTx(localTxIdSet);
	}

	public boolean saveKafkaMessage(KafkaMessage message) {
		return kafkaMessageRepository.save(message);
	}
}
