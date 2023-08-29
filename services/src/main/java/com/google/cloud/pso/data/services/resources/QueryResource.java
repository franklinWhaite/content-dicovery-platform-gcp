/*
 * Copyright (C) 2023 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.pso.data.services.resources;

import com.google.cloud.pso.beam.contentextract.clients.EmbeddingsClient;
import com.google.cloud.pso.beam.contentextract.clients.MatchingEngineClient;
import com.google.cloud.pso.beam.contentextract.clients.PalmClient;
import com.google.cloud.pso.beam.contentextract.clients.Types;
import com.google.cloud.pso.data.services.beans.BeansProducer;
import com.google.cloud.pso.data.services.beans.BigTableService;
import com.google.cloud.pso.data.services.exceptions.QueryResourceException;
import com.google.cloud.pso.data.services.utils.PromptUtilities;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.enterprise.context.SessionScoped;
import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/query/content")
@SessionScoped
public class QueryResource {

  private static final Logger LOG = LoggerFactory.getLogger(QueryResource.class);

  @Inject BigTableService btService;
  @Inject EmbeddingsClient embeddingsService;
  @Inject MatchingEngineClient matchingEngineService;
  @Inject PalmClient palmService;
  @Inject BeansProducer.ResourceConfiguration configuration;

  @Inject
  @Named("botContextExpertise")
  String configuredBotContextExpertise;

  @Inject
  @Named("includeOwnKnowledgeEnrichment")
  Boolean includeOwnKnowledgeEnrichment;

  List<BigTableService.QAndA> removeRepeatedAndNegaviteAnswers(
      List<BigTableService.QAndA> qsAndAs) {
    var prevQuestions = Sets.<String>newHashSet();
    var deduplicatedQAndAs = Lists.<BigTableService.QAndA>newArrayList();
    for (var qaa : qsAndAs) {
      if (!prevQuestions.contains(qaa.question())
          && !PromptUtilities.checkNegativeAnswer(qaa.answer())) {
        prevQuestions.add(qaa.question());
        deduplicatedQAndAs.add(qaa);
      }
    }
    return deduplicatedQAndAs;
  }

  Optional<Types.PalmSummarizationResponse> retrievePreviousSummarizedConversation(
      List<BigTableService.QAndA> qsAndAs) {
    if (qsAndAs.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        palmService.predictSummarizationWithRetries(
            new Types.PalmSummarizationRequest(
                new Types.PalmRequestParameters(
                    configuration.temperature(),
                    configuration.maxOutputTokens(),
                    configuration.topK(),
                    configuration.topP()),
                new Types.SummarizationInstances(
                    PromptUtilities.formatChatSummaryPrompt(
                        qsAndAs.stream().flatMap(q -> q.toExchange().stream()).toList())))));
  }

  Types.PalmRequestParameters palmRequestParameters(Optional<QueryParameters> parameters) {
    return new Types.PalmRequestParameters(
        parameters.map(p -> p.temperature()).orElse(configuration.temperature()),
        parameters.map(p -> p.maxOutputTokens()).orElse(configuration.maxOutputTokens()),
        parameters.map(p -> p.topK()).orElse(configuration.topK()),
        parameters.map(p -> p.topP()).orElse(configuration.topP()));
  }

  @POST
  @Produces(MediaType.APPLICATION_JSON)
  public QueryResult query(UserQuery query) {
    try {
      Preconditions.checkState(
          query.sessionId() != null, "Session id should be present, even if empty.");
      Preconditions.checkState(query.text() != null, "A valid question should be provided.");
      Preconditions.checkState(!query.text().trim().isEmpty(), "Provided query is empty.");

      // retrieve the previous q and as from the conversation context removing the repeated and
      // negative answers coming from the model
      var qAndAs =
          removeRepeatedAndNegaviteAnswers(
              btService.retrieveConversationContext(query.sessionId()).qAndAs());

      // keep the embeddings context as the last 5 questions (summarization may become too
      // clumsy).
      var lastsQAndAsBeforeSumm =
          qAndAs.size() > 5 ? qAndAs.subList(qAndAs.size() - 6, qAndAs.size() - 1) : qAndAs;

      // retrieve the summary of the previous conversation and generate embeddings adding that
      // context to the user query
      var summarizationResponse = retrievePreviousSummarizedConversation(lastsQAndAsBeforeSumm);

      // in case of the summarization response to be blocked, we will remove the previous exchange
      // entirely. This is not ideal, but it will make the models to return non-expected responses
      // if those exchanges are included in the previous conversations
      var lastsQAndAs =
          summarizationResponse
              .filter(resp -> !resp.isBlockedResponse())
              .map(r -> lastsQAndAsBeforeSumm)
              .orElse(List.of());

      var previousSummarizedConversation =
          summarizationResponse
              .filter(resp -> !resp.isBlockedResponse())
              .map(r -> r.summary())
              .orElse("");

      // clear out the session on a separated thread, in case of a blocked response from the
      // summarization
      summarizationResponse
          .filter(resp -> resp.isBlockedResponse())
          .ifPresent(
              r ->
                  CompletableFuture.runAsync(() -> btService.removeSessionInfo(query.sessionId())));

      var embeddingRequest =
          new Types.EmbeddingRequest(
              Lists.newArrayList(
                  new Types.TextInstance(query.text() + "\n" + previousSummarizedConversation)));
      var embResponse = embeddingsService.retrieveEmbeddingsWithRetries(embeddingRequest);

      // retrieve the nearest neighbors using the computed embeddings
      var nnResp =
          matchingEngineService.queryNearestNeighborsWithRetries(
              embResponse.toNearestNeighborRequest(
                  configuration.matchingEngineIndexDeploymentId(),
                  // use min value between statically configured and the request one (if exists)
                  Integer.min(
                      configuration.maxNeighbors(),
                      Optional.ofNullable(query.parameters)
                          .flatMap(params -> Optional.ofNullable(params.maxNeighbors))
                          .orElse(Integer.MAX_VALUE))));

      // given the retrieved neighbors, use their ids to retrieve the chunks text content
      var context =
          nnResp.nearestNeighbors().stream()
              .flatMap(n -> n.neighbors().stream())
              // filter out the dummy index initial vector
              .filter(n -> n.distance() < configuration.maxNeighborDistance())
              .sorted((n1, n2) -> -n1.distance().compareTo(n2.distance()))
              // we keep only the 3 most relevant context entries
              .limit(3)
              // capture content and link from storage and preserve distance from original query
              .map(
                  nn -> {
                    var content = btService.queryByPrefix(nn.datapoint().datapointId());
                    return new ContentAndMetadata(
                        content.content(), content.sourceLink(), nn.distance());
                  })
              .toList();

      // given the textual context and the previously retrieved existing conversation request a chat
      // response to the model using the provided query.
      var contextContent = context.stream().map(ContentAndMetadata::content).toList();
      var palmRequestContext =
          PromptUtilities.formatChatContextPrompt(
              contextContent,
              // if there is a query param knowledge setup we use that
              Optional.ofNullable(query.parameters)
                  .map(p -> Optional.ofNullable(p.botContextExpertise()))
                  // or default to whatever was configured, if anything
                  .orElse(Optional.ofNullable(configuredBotContextExpertise)),
              // also use the query configured knowledge enrichment, if tis there.
              Optional.ofNullable(query.parameters)
                  .flatMap(p -> Optional.ofNullable(p.includeOwnKnowledgeEnrichment))
                  // or default to whatever was configured, if anything
                  .orElse(Optional.ofNullable(includeOwnKnowledgeEnrichment).orElse(true)));

      var currentExchange =
          Lists.newArrayList(
              lastsQAndAs.stream().flatMap(qaa -> qaa.toExchange().stream()).toList());
      currentExchange.add(new Types.Exchange("user", query.text()));
      var palmResp =
          palmService.predictChatAnswerWithRetries(
              new Types.PalmChatAnswerRequest(
                  palmRequestParameters(Optional.ofNullable(query.parameters)),
                  new Types.ChatInstances(
                      palmRequestContext, PromptUtilities.EXCHANGE_EXAMPLES, currentExchange)));

      // retrieve the model's text response
      var responseText =
          palmResp.predictions().stream()
                  .flatMap(pp -> pp.safetyAttributes().stream())
                  .anyMatch(saf -> saf.blocked())
              ? "Response blocked by model, check on provided document links if any available."
              : palmResp.predictions().stream()
                  .flatMap(pr -> pr.candidates().stream())
                  .map(ex -> ex.content())
                  .collect(Collectors.joining("\n"));

      // the context source links
      var sourceLinks =
          context.stream()
              // discard content
              .map(ContentAndMetadata::toLinkAndDistance)
              // filter empty links
              .filter(ld -> !ld.link().isBlank())
              // get max distance value per link
              .collect(
                  Collectors.toMap(
                      LinkAndDistance::link,
                      LinkAndDistance::distance,
                      (d1, d2) -> d1 > d2 ? d1 : d2))
              // deduplicate
              .entrySet()
              .stream()
              // order descending by distance
              .sorted((e1, e2) -> -e1.getValue().compareTo(e2.getValue()))
              .map(e -> new LinkAndDistance(e.getKey(), e.getValue()))
              .toList();
      var responseLinks =
          PromptUtilities.checkNegativeAnswer(responseText)
                  || responseText.contains(PromptUtilities.FOUND_IN_INTERNET)
              ? List.<LinkAndDistance>of()
              : sourceLinks;

      // store the new exchange
      btService.storeQueryToContext(query.sessionId(), query.text(), responseText);

      // to finally return a query response
      return new QueryResult(
          responseText,
          previousSummarizedConversation,
          responseLinks,
          palmResp.predictions().stream().flatMap(pr -> pr.citationMetadata().stream()).toList(),
          palmResp.predictions().stream().flatMap(pr -> pr.safetyAttributes().stream()).toList());
    } catch (Exception ex) {
      var msg = "Problems while executing the query resource. ";
      LOG.error(msg, ex);
      throw new QueryResourceException(msg + ex.getMessage(), query.text, query.sessionId, ex);
    }
  }

  public record LinkAndDistance(String link, Double distance) {}

  public record ContentAndMetadata(String content, String link, Double distance) {
    public LinkAndDistance toLinkAndDistance() {
      return new LinkAndDistance(link(), distance());
    }
  }

  public record QueryParameters(
      String botContextExpertise,
      Boolean includeOwnKnowledgeEnrichment,
      Integer maxNeighbors,
      Double temperature,
      Integer maxOutputTokens,
      Integer topK,
      Double topP) {}

  public record UserQuery(String text, String sessionId, QueryParameters parameters) {}

  public record QueryResult(
      String content,
      String previousConversationSummary,
      List<LinkAndDistance> sourceLinks,
      List<Types.CitationMetadata> citationMetadata,
      List<Types.SafetyAttributes> safetyAttributes) {}
}
