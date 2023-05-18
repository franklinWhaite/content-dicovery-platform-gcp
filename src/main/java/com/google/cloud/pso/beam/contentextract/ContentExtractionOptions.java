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
package com.google.cloud.pso.beam.contentextract;

import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.Validation;

/** */
public interface ContentExtractionOptions extends DataflowPipelineOptions {

  @Description("The PubSub subscription to read events from.")
  @Validation.Required
  String getSubscription();

  void setSubscription(String value);

  @Description("The PubSub topic to send retries.")
  @Validation.Required
  String getTopic();

  void setTopic(String value);

  @Description("The GCS location where extracted content will be written to.")
  @Validation.Required
  String getBucketLocation();

  void setBucketLocation(String value);

  @Description("The secret identifier to use while accessing credentials.")
  @Validation.Required
  String getSecretManagerId();

  void setSecretManagerId(String value);

  @Description("The index identifier.")
  @Validation.Required
  String getMatchingEngineIndexId();

  void setMatchingEngineIndexId(String value);

  @Description("The local expansion server url in the form of 'localhost:PORT'.")
  @Validation.Required
  String getExpansionService();

  void setExpansionService(String value);
}
