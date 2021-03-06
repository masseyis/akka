/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package sample.java;

import javax.ws.rs.Path;
import javax.ws.rs.GET;
import javax.ws.rs.Produces;

import se.scalablesolutions.akka.annotation.transactionrequired;
import se.scalablesolutions.akka.annotation.prerestart;
import se.scalablesolutions.akka.annotation.postrestart;
import se.scalablesolutions.akka.state.TransactionalState;
import se.scalablesolutions.akka.state.TransactionalMap;
import se.scalablesolutions.akka.state.CassandraStorageConfig;

/**                                
 * Try service out by invoking (multiple times):
 * <pre>
 * curl http://localhost:9998/javacount
 * </pre>
 * Or browse to the URL from a web browser.
 */
@Path("/javacount")
@transactionrequired
public class SimpleService {
  private String KEY = "COUNTER";

  private boolean hasStartedTicking = false;
  private TransactionalMap storage = TransactionalState.newMap();

  @GET
  @Produces({"application/json"})
  public String count() {
    if (!hasStartedTicking) {
      storage.put(KEY, 0);
      hasStartedTicking = true;
      return "Tick: 0\n";
    } else {
      int counter = (Integer)storage.get(KEY).get() + 1;
      storage.put(KEY, counter);
      return "Tick: " + counter + "\n";
    }
  }

  @prerestart
  public void preRestart() {
    System.out.println("Prepare for restart by supervisor");
  }

  @postrestart
  public void postRestart() {
    System.out.println("Reinitialize after restart by supervisor");
  }
}