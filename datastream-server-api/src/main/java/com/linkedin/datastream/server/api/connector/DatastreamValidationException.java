package com.linkedin.datastream.server.api.connector;

import com.linkedin.datastream.common.DatastreamException;


public class DatastreamValidationException extends DatastreamException {
  private static final long serialVersionUID = 1;

  public DatastreamValidationException() {
    super();
  }

  public DatastreamValidationException(String message, Throwable cause) {
    super(message, cause);
  }

  public DatastreamValidationException(String message) {
    super(message);
  }

  public DatastreamValidationException(Throwable cause) {
    super(cause);
  }
}
