package hbz;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpHeaders;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.folio.rest.jaxrs.model.CircDesk;
import org.folio.rest.jaxrs.model.Item;
import org.folio.rest.jaxrs.model.ItemPolicy;
import org.folio.rest.jaxrs.model.ItemStatus;
import org.folio.rest.jaxrs.model.Library;
import org.folio.rest.jaxrs.model.Loan;
import org.folio.rest.jaxrs.model.LocationCode;
import org.folio.rest.jaxrs.model.Patron;
import org.json.JSONArray;
import org.json.JSONObject;
import org.kie.api.KieServices;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;


import io.vertx.ext.web.templ.ThymeleafTemplateEngine;

public class MainVerticle extends AbstractVerticle {

  private String dataApiServer;
  private int dataApiPort;
  private String patronApi;
  private String itemApi;
  private String tenant;

  private Delivery delivery;
  private String patronId;
  private Patron patron;
  private String itemId;
  private Item item;
  private String loanId;
  private Loan loan;
  private String authorization = "aaaaa";

  KieServices kieServices = KieServices.Factory.get();
  KieContainer kContainer = kieServices.getKieClasspathContainer();

  private final Logger logger = LoggerFactory.getLogger("hbz-deliver-module");
  private final ThymeleafTemplateEngine engine = ThymeleafTemplateEngine.create();

  private void initConfiguration() {
    dataApiServer = config().getString("data.api.server", "localhost");
    dataApiPort = config().getInteger("data.api.port", 9130);
    patronApi = config().getString("data.api.patrons", "/patrons/");
    itemApi = config().getString("data.api.items", "/items/");
    tenant = config().getString("data.api.tenant", "hbz");
  }

  @Override
  public void start(Future<Void> fut) {
    initConfiguration();
    Router router = Router.router(vertx);
    final int port = Integer.parseInt(System.getProperty("port", "8080"));
    router.route("/deliver*").handler(BodyHandler.create());
    router.get("/deliver/loan").handler(this::showLoanScreen);
    router.post("/deliver/loan").handler(this::loan);
    router.post("/deliver/return").handler(this::returnItem);
    router.post("/deliver/renew").handler(this::renew);
    router.get("/deliver/loans/:patronId").handler(this::getLoansForPatron);
    router.get("/deliver/listLoans").handler(this::showLoanListScreen);

    // routes for sample data
    router.get("/deliver/sampleData").handler(this::showSampleDataScreen);
    router.post("/deliver/createPatron").handler(this::createPatron);
    router.post("/deliver/createItem").handler(this::createItem);
    router.delete("/deliver/deletePatron").handler(this::deletePatron);
    router.delete("/deliver/deleteItem").handler(this::deleteItem);

    // routes for rule management
    router.post("/deliver/createRule").handler(this::createRule);
    router.get("/deliver/rules").handler(this::showRuleScreen);

    vertx.createHttpServer().requestHandler(router::accept).listen(port, result -> {
      if (result.succeeded()) {
        fut.complete();
      } else {
        fut.fail(result.cause());
      }
    });
  }

  private void showLoanScreen(RoutingContext routingContext) {
    engine.render(routingContext, "templates/loan.html", response -> routingContext.response().setStatusCode(200)
        .putHeader(HttpHeaders.CONTENT_TYPE, "text/html").end(response.result()));
  }

  private void loan(RoutingContext routingContext) {
    delivery = Json.decodeValue(routingContext.getBodyAsString(), Delivery.class);
    patronId = delivery.getPatron();
    itemId = delivery.getItem();
    retrievePatron(routingContext);
  }

  private void retrievePatron(RoutingContext routingContext) {
    HttpClient httpClient = vertx.createHttpClient();
    httpClient.get(dataApiPort, dataApiServer, patronApi + patronId, response -> {
      if (response.statusCode() == 200) {
        response.bodyHandler(buffer -> {
          patron = Json.decodeValue(buffer.toString(), Patron.class);
          logger.info("Found patron with id " + patronId);
          retrieveItem(routingContext);
        });
      } else {
        routingContext.response().setStatusCode(404).putHeader(HttpHeaders.CONTENT_TYPE, "text/html")
            .end("Could not find patron with id " + patronId);
      }
    }).putHeader("content-type", "application/json").putHeader("accept", "application/json")
        .putHeader("authorization", authorization).putHeader("X-Okapi-Tenant", tenant).end();
  }

  private void retrieveItem(RoutingContext routingContext) {
    HttpClient httpClient = vertx.createHttpClient();
    httpClient.get(dataApiPort, dataApiServer, itemApi + itemId, response -> {
      if (response.statusCode() == 200) {
        response.bodyHandler(buffer -> {
          item = Json.decodeValue(buffer.toString(), Item.class);
          logger.info("Found item: " + item.getId());
          processLoan(routingContext);
        });
      } else {
        routingContext.response().setStatusCode(404).putHeader(HttpHeaders.CONTENT_TYPE, "text/html")
            .end("Could not find item with id " + itemId);
      }
    }).putHeader("content-type", "application/json").putHeader("accept", "application/json")
        .putHeader("authorization", authorization).putHeader("X-Okapi-Tenant", tenant).end();
  }

  private void processLoan(RoutingContext routingContext) {
    logger.info("Processing loan...");
    logger.info("Checking rules...");
    LoanPermission loanPermission = new LoanPermission();
    KieSession kSession = kContainer.newKieSession("ksession-rules");
    kSession.insert(patron);
    kSession.insert(item);
    kSession.insert(loanPermission);
    kSession.fireAllRules();
    if (loanPermission.isPermitted() == true) {
      createLoanForPatron(routingContext);
    } else {
      routingContext.response().setStatusCode(400)
          .end("Cannot loan! Either item is loaned or patron is not allowed.");
    }
    kSession.destroy();
  }

  private void createLoanForPatron(RoutingContext routingContext) {
    HttpClient httpClient = vertx.createHttpClient();
    loan = createLoanObject();
    String loanAsJson = Json.encode(loan);
    httpClient.post(dataApiPort, dataApiServer, patronApi + patronId + "/loans/", response -> {
      if (response.statusCode() == 201) {
        response.bodyHandler(buffer -> {
          loan = Json.decodeValue(buffer.toString(), Loan.class);
          logger.info("Created loan with id " + loan.getId() + " for patron " + patronId);
        });
        itemId = item.getId();
        updateItemStatus("02", "ITEM_STATUS_ON_LOAN", routingContext);
      } else {
        routingContext.response().setStatusCode(500).putHeader(HttpHeaders.CONTENT_TYPE, "text/html")
            .end("Could not create loan");
      }
    }).putHeader("content-type", "application/json").putHeader("accept", "text/plain")
        .putHeader("authorization", authorization).putHeader("X-Okapi-Tenant", tenant).end(loanAsJson);
  }

  private void updateItemStatus(String statusValue, String statusDescription, RoutingContext routingContext) {
    HttpClient httpClient = vertx.createHttpClient();
    ItemStatus status = new ItemStatus();
    status.setValue(statusValue);
    status.setDesc(statusDescription);
    item.setItemStatus(status);
    String itemAsJson = Json.encode(item);
    httpClient.put(dataApiPort, dataApiServer, itemApi + itemId, response -> {
      if (response.statusCode() == 204) {
        logger.info("Updated item status for item " + itemId);
        routingContext.response().setStatusCode(200)
            .end("Updated item status for item " + itemId + " to " + statusDescription);
      } else {
        routingContext.response().setStatusCode(500).putHeader(HttpHeaders.CONTENT_TYPE, "text/html")
            .end("Could not update item status");
      }
    }).putHeader("content-type", "application/json").putHeader("accept", "text/plain")
        .putHeader("authorization", authorization).putHeader("X-Okapi-Tenant", tenant).end(itemAsJson);

  }

  // This is just a dummy implementation to create a loan quickly
  private Loan createLoanObject() {
    Loan newLoan = new Loan();
    newLoan.setPatronId(patronId);
    newLoan.setItemBarcode(item.getBarcode());
    newLoan.setItemId(item.getId());
    newLoan.setDueDate((double) (System.currentTimeMillis() / 1000 + 1209600));
    newLoan.setItemPolicy(new ItemPolicy());
    newLoan.setCircDesk(new CircDesk());
    newLoan.setLoanStatus("loanStatus");
    newLoan.setTitle("title");
    newLoan.setLocationCode(new LocationCode());
    newLoan.setLoanFine(123);
    newLoan.setRenewable(true);
    newLoan.setLoanDate((double) System.currentTimeMillis());
    newLoan.setLibrary(new Library());
    newLoan.setRenewCount(0);
    return newLoan;
  }

  private void returnItem(RoutingContext routingContext) {
    final ReturnRenewal itemReturn = Json.decodeValue(routingContext.getBodyAsString(), ReturnRenewal.class);
    patronId = itemReturn.getPatron();
    loanId = itemReturn.getLoan();
    HttpClient httpClient = vertx.createHttpClient();
    httpClient.get(dataApiPort, dataApiServer, patronApi + patronId + "/loans/" + loanId, response -> {
      if (response.statusCode() == 200) {
        response.bodyHandler(buffer -> {
          loan = Json.decodeValue(buffer.toString(), Loan.class);
          logger.info("Found loan " + loanId + " for patron " + patronId);
          itemId = loan.getItemId();
          deleteLoanForPatron(routingContext);
        });
      } else {
        routingContext.response().setStatusCode(404).putHeader(HttpHeaders.CONTENT_TYPE, "text/html")
            .end("Did not find loan " + loanId + " for patron " + patronId);
      }
    }).putHeader("content-type", "application/json").putHeader("accept", "application/json")
        .putHeader("authorization", authorization).putHeader("X-Okapi-Tenant", tenant).end();
  }

  private void deleteLoanForPatron(RoutingContext routingContext) {
    logger.info("Deleting loan " + loanId + " for patron " + patronId);
    HttpClient httpClient = vertx.createHttpClient();
    httpClient.delete(dataApiPort, dataApiServer, patronApi + patronId + "/loans/" + loanId, response -> {
      if (response.statusCode() == 204) {
        logger.info("Deleted loan " + loanId + " for " + patronId);

        // retrieve item for return, todo: refactor!
        HttpClient httpClient2 = vertx.createHttpClient();
        httpClient2.get(dataApiPort, dataApiServer, itemApi + itemId, response2 -> {
          if (response2.statusCode() == 200) {
            response2.bodyHandler(buffer -> {
              item = Json.decodeValue(buffer.toString(), Item.class);
              logger.info("Found item for return: " + item.getId());
              updateItemStatus("03", "ITEM_STATUS_AVAILABLE", routingContext);
            });
          } else {
            routingContext.response().setStatusCode(404).putHeader(HttpHeaders.CONTENT_TYPE, "text/html")
                .end("Could not find item for return with id " + itemId);
          }
        }).putHeader("content-type", "text/plain").putHeader("accept", "application/json")
            .putHeader("authorization", authorization).putHeader("X-Okapi-Tenant", tenant).end();
      } else {
        routingContext.response().setStatusCode(500).putHeader(HttpHeaders.CONTENT_TYPE, "text/html")
            .end("Could not delete loan with id " + loanId);
      }
    }).putHeader("content-type", "application/json").putHeader("accept", "text/plain")
        .putHeader("authorization", authorization).putHeader("X-Okapi-Tenant", tenant).end();

  }

  private void getLoansForPatron(RoutingContext routingContext) {
    patronId = routingContext.request().getParam("patronId");
    HttpClient httpClient = vertx.createHttpClient();
    httpClient.get(dataApiPort, dataApiServer, patronApi + patronId + "/loans",
        response -> response.bodyHandler(buffer -> {
          try {
            JSONObject bufferAsJson = new JSONObject(buffer.toString());
            JSONArray loans = bufferAsJson.getJSONArray("loans");
            if (loans.length() > 0) {
              routingContext.response().end(loans.toString());
            } else {
              routingContext.response().setStatusCode(404).end("Error: No loans found");
            }
          } catch (Exception e) {
            logger.error(e);
          }
        })).putHeader("content-type", "application/json").putHeader("accept", "application/json")
        .putHeader("authorization", authorization).putHeader("X-Okapi-Tenant", tenant).end();
  }

  private void showLoanListScreen(RoutingContext routingContext) {
    engine.render(routingContext, "templates/loans.html", response -> routingContext.response().setStatusCode(200)
        .putHeader(HttpHeaders.CONTENT_TYPE, "text/html").end(response.result()));
  }

  private void renew(RoutingContext routingContext) {
    final ReturnRenewal itemReturn = Json.decodeValue(routingContext.getBodyAsString(), ReturnRenewal.class);
    patronId = itemReturn.getPatron();
    loanId = itemReturn.getLoan();
    retrievePatronForRenewal(routingContext);
  }

  // This method is recreating code; todo: find some way to reconcile them
  private void retrievePatronForRenewal(RoutingContext routingContext) {
    HttpClient httpClient = vertx.createHttpClient();
    httpClient.get(dataApiPort, dataApiServer, patronApi + patronId, response -> {
      if (response.statusCode() == 200) {
        response.bodyHandler(buffer -> {
          patron = Json.decodeValue(buffer.toString(), Patron.class);
          logger.info("Found patron with id " + patronId);
          retrieveLoanForRenewal(routingContext);
        });
      } else {
        routingContext.response().setStatusCode(404).putHeader(HttpHeaders.CONTENT_TYPE, "text/html")
            .end("Could not find patron with id " + patronId);
      }
    }).putHeader("content-type", "application/json").putHeader("accept", "application/json")
        .putHeader("authorization", authorization).putHeader("X-Okapi-Tenant", tenant).end();
  }

  // Same concern as above
  private void retrieveLoanForRenewal(RoutingContext routingContext) {
    HttpClient httpClient = vertx.createHttpClient();
    httpClient.get(dataApiPort, dataApiServer, patronApi + patronId + "/loans/" + loanId, response -> {
      if (response.statusCode() == 200) {
        response.bodyHandler(buffer -> {
          loan = Json.decodeValue(buffer.toString(), Loan.class);
          logger.info("Found loan with id " + loan.getId());
          renewLoan(routingContext);
        });
      } else {
        routingContext.response().setStatusCode(404).putHeader(HttpHeaders.CONTENT_TYPE, "text/html")
            .end("Could not find loan with id " + loanId);
      }
    }).putHeader("content-type", "application/json").putHeader("accept", "application/json")
        .putHeader("authorization", authorization).putHeader("X-Okapi-Tenant", tenant).end();
  }

  private void renewLoan(RoutingContext routingContext) {
    HttpClient httpClient = vertx.createHttpClient();
    loan.setDueDate((double) (System.currentTimeMillis() / 1000 + 1209600));
    String loanAsJson = Json.encode(loan);
    httpClient.put(dataApiPort, dataApiServer, patronApi + patronId + "/loans/" + loanId, response -> {
      if (response.statusCode() == 204) {
        response.bodyHandler(buffer -> {
          logger.info("Updated loan with id " + loanId + " for patron " + patronId);
          routingContext.response().setStatusCode(200).end("Renewed loan with id " + loanId);
        });
      } else {
        routingContext.response().setStatusCode(500).putHeader(HttpHeaders.CONTENT_TYPE, "text/html")
            .end("Could not update loan");
      }
    }).putHeader("content-type", "application/json").putHeader("accept", "text/plain")
        .putHeader("authorization", authorization).putHeader("X-Okapi-Tenant", tenant).end(loanAsJson);
  }

  /*
   ******************************************************************************************
   * Sample Data methods, only for testing the module (will have to go into a
   * separate class)
   ******************************************************************************************
   */

  private void showSampleDataScreen(RoutingContext routingContext) {
    HttpClient httpClient = vertx.createHttpClient();
    httpClient.get(dataApiPort, dataApiServer, patronApi, response -> response.bodyHandler(buffer -> {
      try {
        JSONObject bufferAsJson = new JSONObject(buffer.toString());
        JSONArray patrons = bufferAsJson.getJSONArray("patrons");
        routingContext.put("patrons", patrons.toString(2));

        httpClient
            .get(dataApiPort, dataApiServer, itemApi, itemResponse -> itemResponse.bodyHandler(itemBuffer -> {
              try {
                JSONObject itemBufferAsJson = new JSONObject(itemBuffer.toString());
                JSONArray items = itemBufferAsJson.getJSONArray("items");
                routingContext.put("items", items.toString(2));

                engine.render(routingContext, "templates/sampleData.html",
                    engineResponse -> routingContext.response().setStatusCode(200)
                        .putHeader(HttpHeaders.CONTENT_TYPE, "text/html")
                        .end(engineResponse.result()));

              } catch (Exception e) {
                logger.error(e);
              }
            })).putHeader("content-type", "application/json").putHeader("accept", "application/json")
            .putHeader("authorization", authorization).putHeader("X-Okapi-Tenant", tenant).end();

      } catch (Exception e) {
        logger.error(e);
      }
    })).putHeader("content-type", "application/json").putHeader("accept", "application/json")
        .putHeader("authorization", authorization).putHeader("X-Okapi-Tenant", tenant).end();
  }

  private void createPatron(RoutingContext routingContext) {
    String patron = routingContext.getBodyAsString();
    HttpClient httpClient = vertx.createHttpClient();
    httpClient.post(dataApiPort, dataApiServer, patronApi, response -> {
      if (response.statusCode() == 201) {
        routingContext.response().setStatusCode(201).putHeader(HttpHeaders.CONTENT_TYPE, "text/plain")
            .end("Patron created");
      } else {
        routingContext.response().setStatusCode(500).putHeader(HttpHeaders.CONTENT_TYPE, "text/plain")
            .end("Error creating patron. Try again. Typos?");
      }
    }).putHeader("content-type", "application/json").putHeader("accept", "application/json")
        .putHeader("authorization", authorization).putHeader("X-Okapi-Tenant", tenant).end(patron);

  }

  private void createItem(RoutingContext routingContext) {
    String patron = routingContext.getBodyAsString();
    HttpClient httpClient = vertx.createHttpClient();
    httpClient.post(dataApiPort, dataApiServer, itemApi, response -> {
      if (response.statusCode() == 201) {
        routingContext.response().setStatusCode(201).putHeader(HttpHeaders.CONTENT_TYPE, "text/plain")
            .end("Item created");
      } else {
        routingContext.response().setStatusCode(500).putHeader(HttpHeaders.CONTENT_TYPE, "text/plain")
            .end("Error creating item. Try again. Typos?");
      }
    }).putHeader("content-type", "application/json").putHeader("accept", "application/json")
        .putHeader("authorization", authorization).putHeader("X-Okapi-Tenant", tenant).end(patron);

  }

  private void deletePatron(RoutingContext routingContext) {
    String patronId = routingContext.getBodyAsString();
    HttpClient httpClient = vertx.createHttpClient();

    // check whether patron has open loans
    httpClient.get(dataApiPort, dataApiServer, patronApi + patronId + "/loans",
        loanResponse -> loanResponse.bodyHandler(buffer -> {
          try {
            JSONObject bufferAsJson = new JSONObject(buffer.toString());
            JSONArray loans = bufferAsJson.getJSONArray("loans");
            if (loans.length() == 0) {

              httpClient.delete(dataApiPort, dataApiServer, patronApi + patronId, response -> {
                if (response.statusCode() == 204) {
                  routingContext.response().setStatusCode(204)
                      .putHeader(HttpHeaders.CONTENT_TYPE, "text/plain").end("Patron deleted");
                } else {
                  routingContext.response().setStatusCode(500)
                      .putHeader(HttpHeaders.CONTENT_TYPE, "text/plain")
                      .end("Error deleting patron");
                }
              }).putHeader("content-type", "application/json").putHeader("accept", "text/plain")
                  .putHeader("authorization", authorization).putHeader("X-Okapi-Tenant", tenant)
                  .end();
            } else {
              routingContext.response().setStatusCode(404)
                  .end("Error: Patron has still open loans! Return items first");
            }
          } catch (Exception e) {
            logger.error(e);
          }
        })).putHeader("content-type", "application/json").putHeader("accept", "application/json")
        .putHeader("authorization", authorization).putHeader("X-Okapi-Tenant", tenant).end();

  }

  private void deleteItem(RoutingContext routingContext) {
    String itemId = routingContext.getBodyAsString();
    HttpClient httpClient = vertx.createHttpClient();
    httpClient.delete(dataApiPort, dataApiServer, itemApi + itemId, response -> {
      if (response.statusCode() == 204) {
        routingContext.response().setStatusCode(204).putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
            .end("Item deleted");
      } else {
        routingContext.response().setStatusCode(500).putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
            .end("Error deleting item");
      }
    }).putHeader("content-type", "application/json").putHeader("accept", "text/plain")
        .putHeader("authorization", authorization).putHeader("X-Okapi-Tenant", tenant).end();
  }

  /*
   ******************************************************************************************
   * Rule methods (will have to go into a separate class)
   ******************************************************************************************
   */

  private void createRule(RoutingContext routingContext) {
    String ruleAsJson = routingContext.getBodyAsString();
    Rule rule = Json.decodeValue(ruleAsJson, Rule.class);
    String ruleFileName = rule.getRuleName().replace(" ", "_") + ".drl";
    String rulesFile = config().getString("rules.folder", "/tmp") + "/" + ruleFileName;
    if (new File(rulesFile).exists()) {
      routingContext.response().setStatusCode(400).putHeader(HttpHeaders.CONTENT_TYPE, "text/html")
          .end("Rule already exists. Choose different name.");
    } else {
      try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(rulesFile))) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("package rules;\n");
        stringBuilder.append("import com.sling.rest.jaxrs.model.Patron;\n");
        stringBuilder.append("import com.sling.rest.jaxrs.model.Item;\n");
        stringBuilder.append("import com.sling.rest.jaxrs.model.Status;\n");
        stringBuilder.append("import hbz.LoanPermission;\n\n");
        stringBuilder.append("rule \"" + rule.getRuleName() + "\"\n");
        stringBuilder.append("$p : Patron(getStatus().equals(\"" + rule.getPatronStatus() + "\"), getType().equals(\"" + rule.getPatronType() + "\"))\n");
        stringBuilder.append("$i : Item(getStatus().getValue().equals(\"" + rule.getItemStatus() + "\")), getType().equals(\"" + rule.getItemType() + "\"))\n");
        stringBuilder.append("$l : LoanPermission()\n");
        stringBuilder.append("then\n");
        stringBuilder.append("$l.setPermitted(true);\n");
        stringBuilder.append("end");
        
        KieFileSystem kfs = kieServices.newKieFileSystem().write(rulesFile, stringBuilder.toString());
        kieServices.newKieBuilder(kfs).buildAll();
        
        routingContext.response().setStatusCode(201).putHeader(HttpHeaders.CONTENT_TYPE, "text/html")
            .end("Rule created.");
      } catch (IOException e) {
        routingContext.response().setStatusCode(500).putHeader(HttpHeaders.CONTENT_TYPE, "text/html")
            .end("Rule could not be created.");
        e.printStackTrace();
      }
    }
  }

  private void showRuleScreen(RoutingContext routingContext) {
    engine.render(routingContext, "templates/rulesEditor.html", response -> routingContext.response().setStatusCode(200)
        .putHeader(HttpHeaders.CONTENT_TYPE, "text/html").end(response.result()));
  }

}