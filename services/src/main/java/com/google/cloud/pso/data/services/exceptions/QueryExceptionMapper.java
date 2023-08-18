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
package com.google.cloud.pso.data.services.exceptions;

import java.util.Optional;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

/** */
@Provider
public class QueryExceptionMapper implements ExceptionMapper<Throwable> {

  public record ErrorResponse(String error) {}

  @Override
  public Response toResponse(Throwable t) {
    if (t instanceof QueryResourceException qre) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity(
              new ErrorResponse(
                  qre.getMessage()
                      + String.format(
                          " Query: '%s'. Session id: '%s'",
                          qre.getQueryText(), qre.getSessionId())))
          .build();
    }
    if (t instanceof IngestionResourceException ire) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity(
              new ErrorResponse(
                  ire.getMessage()
                      + String.format(" provided urls '%s'", ire.getUrls().toString())))
          .build();
    }
    return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
        .entity(
            new ErrorResponse(
                Optional.ofNullable(t).map(e -> e.getMessage()).orElse("Not Specified.")))
        .build();
  }
}
