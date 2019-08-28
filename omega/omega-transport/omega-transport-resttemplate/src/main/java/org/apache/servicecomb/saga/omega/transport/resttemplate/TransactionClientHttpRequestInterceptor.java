/*
 * Copyright (c) 2018-2019 ActionTech.
 * based on code by ServiceComb Pack CopyrightHolder Copyright (C) 2018,
 * License: http://www.apache.org/licenses/LICENSE-2.0 Apache License 2.0 or higher.
 */

package org.apache.servicecomb.saga.omega.transport.resttemplate;

import org.apache.servicecomb.saga.omega.context.OmegaContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.lang.invoke.MethodHandles;

import static org.apache.servicecomb.saga.omega.context.OmegaContext.*;

class TransactionClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {
  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private final OmegaContext omegaContext;

  TransactionClientHttpRequestInterceptor(OmegaContext omegaContext) {
    this.omegaContext = omegaContext;
  }

  @Override
  public ClientHttpResponse intercept(HttpRequest request, byte[] body,
      ClientHttpRequestExecution execution) throws IOException {

    if (omegaContext != null && omegaContext.globalTxId() != null) {
      request.getHeaders().add(GLOBAL_TX_ID_KEY, omegaContext.globalTxId());
      request.getHeaders().add(LOCAL_TX_ID_KEY, omegaContext.localTxId());
      request.getHeaders().add(GLOBAL_TX_CATEGORY_KEY, omegaContext.category());

      LOG.debug("Added {} {} and {} {} to request header",
          GLOBAL_TX_ID_KEY,
          omegaContext.globalTxId(),
          LOCAL_TX_ID_KEY,
          omegaContext.localTxId(),
          GLOBAL_TX_CATEGORY_KEY,
          omegaContext.category());
    }
    return execution.execute(request, body);
  }
}
