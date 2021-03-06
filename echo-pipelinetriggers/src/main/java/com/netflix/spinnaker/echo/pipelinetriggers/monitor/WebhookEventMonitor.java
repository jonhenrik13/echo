/*
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.echo.pipelinetriggers.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.spectator.api.Registry;
import com.netflix.spinnaker.echo.model.Event;
import com.netflix.spinnaker.echo.model.Pipeline;
import com.netflix.spinnaker.echo.model.Trigger;
import com.netflix.spinnaker.echo.model.trigger.WebhookEvent;
import com.netflix.spinnaker.echo.model.trigger.TriggerEvent;
import com.netflix.spinnaker.echo.pipelinetriggers.PipelineCache;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import rx.Observable;
import rx.functions.Action1;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

@Component @Slf4j
public class WebhookEventMonitor extends TriggerMonitor {

  public static final String TRIGGER_TYPE = "webhook";

  private final ObjectMapper objectMapper = new ObjectMapper();

  private final PipelineCache pipelineCache;

  @Autowired
  public WebhookEventMonitor(@NonNull PipelineCache pipelineCache,
                             @NonNull Action1<Pipeline> subscriber,
                             @NonNull Registry registry) {
    super(subscriber, registry);
    this.pipelineCache = pipelineCache;
  }

  @Override
  public void processEvent(Event event) {
    super.validateEvent(event);
    if (event.getDetails().getType() == null) {
      return;
    }

    /* Need to create WebhookEvent, since TriggerEvent is abstract */
    WebhookEvent webhookEvent = objectMapper.convertValue(event, WebhookEvent.class);
    webhookEvent.setDetails(event.getDetails());
    webhookEvent.setPayload(event.getContent());

    Observable.just(webhookEvent)
      .doOnNext(this::onEchoResponse)
      .subscribe(triggerEachMatchFrom(pipelineCache.getPipelines()));
  }

  @Override
  protected boolean isSuccessfulTriggerEvent(final TriggerEvent event) {
    return true;
  }

  @Override
  protected Function<Trigger, Pipeline> buildTrigger(Pipeline pipeline, TriggerEvent event) {
    return trigger -> pipeline.withTrigger(trigger.atConstraints(event.getPayload()));
  }

  @Override
  protected boolean isValidTrigger(final Trigger trigger) {
    boolean valid =  trigger.isEnabled() &&
      (
          TRIGGER_TYPE.equals(trigger.getType())
      );

    return valid;
  }

  @Override
  protected Predicate<Trigger> matchTriggerFor(final TriggerEvent event) {
    String type = event.getDetails().getType();

    return trigger ->
      trigger.getType().equals(type) &&
        (
          // The Constraints in the Trigger could be null. That's OK.
          trigger.getConstraints() == null ||

            // If the Constraints are present, check that there are equivalents in the webhook payload.
            (  trigger.getConstraints() != null &&
               isConstraintInPayload(trigger.getConstraints(), event.getPayload())
            )

        );

  }

  /**
   * Check that there is a key in the payload for each constraint declared in a Trigger.
   * Also check that if there is a value for a given key, that the value matches the value in the payload.
   * @param constraints A map of constraints configured in the Trigger (eg, created in Deck).
   * @param payload A map of the payload contents POST'd in the Webhook.
   * @return Whether every key (and value if applicable) in the constraints map is represented in the payload.
     */
  protected boolean isConstraintInPayload(final Map constraints, final Map payload) {
    for (Object key : constraints.keySet()) {
      if (!payload.containsKey(key) || payload.get(key) == null) {
        log.info("Webhook trigger ignored. Item " + key.toString() + " was not found in payload");
        return false;
      }
      if (!constraints.get(key).equals("") && (!constraints.get(key).equals(payload.get(key)))){
        log.info("Webhook trigger ignored. Value of item " + key.toString() + " in payload does not match constraint");
        return false;
      }
    }
    return true;
  }

  protected void onMatchingPipeline(Pipeline pipeline) {
    super.onMatchingPipeline(pipeline);
    val id = registry.createId("pipelines.triggered")
      .withTag("application", pipeline.getApplication())
      .withTag("name", pipeline.getName());
    id.withTag("type", pipeline.getTrigger().getType());
    registry.counter(id).increment();
  }
}

