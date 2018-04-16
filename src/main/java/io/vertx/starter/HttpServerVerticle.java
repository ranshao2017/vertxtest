package io.vertx.starter;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

public class HttpServerVerticle extends AbstractVerticle {

  private static final Logger LOGGER = LoggerFactory.getLogger(HttpServerVerticle.class);

  public static final String CONFIG_HTTP_SERVER_PORT = "http.server.port";
  public static final String CONFIG_WIKIDB_QUEUE = "wikidb.queue";

  private String wikiDbQueue = "wikidb.queue";

  public static void main(String[] args){
    Vertx vertx = Vertx.vertx();
    vertx.deployVerticle(new HttpServerVerticle());
  }

  @Override
  public void start(Future<Void> startFuture) throws Exception {

    wikiDbQueue = config().getString(CONFIG_WIKIDB_QUEUE, "wikidb.queue");

    HttpServer server = vertx.createHttpServer();

    Router router = Router.router(vertx);
    router.get("/getAll").handler(this::indexHandler);
    router.get("/apis/:uid").handler(this::pageRenderingHandler);
    router.post().handler(BodyHandler.create());
    router.post("/save").handler(this::pageUpdateHandler);
    router.post("/delete").handler(this::pageDeletionHandler);

    int portNumber = config().getInteger(CONFIG_HTTP_SERVER_PORT, 8080);
    server.requestHandler(router::accept)
      .listen(portNumber, ar -> {
        if (ar.succeeded()) {
          LOGGER.info("HTTP server running on port " + portNumber);
          startFuture.complete();
        } else {
          LOGGER.error("Could not start a HTTP server", ar.cause());
          startFuture.fail(ar.cause());
        }
      });
  }

  private void indexHandler(RoutingContext context) {
    DeliveryOptions options = new DeliveryOptions().addHeader("action", "all-pages");

    vertx.eventBus().send(wikiDbQueue, new JsonObject(), options, reply -> {
      if (reply.succeeded()) {
        JsonObject body = (JsonObject) reply.result().body();
        context.response().end(Json.encodePrettily(body));
      } else {
        context.fail(reply.cause());
      }
    });
  }

  private void pageRenderingHandler(RoutingContext context) {
    String requestedPage = context.request().getParam("uid");
    JsonObject request = new JsonObject().put("uid", requestedPage);

    DeliveryOptions options = new DeliveryOptions().addHeader("action", "get-page");
    vertx.eventBus().send(wikiDbQueue, request, options, reply -> {
      if (reply.succeeded()) {
        JsonObject body = (JsonObject) reply.result().body();
        context.response().end(Json.encodePrettily(body));
      } else {
        context.fail(reply.cause());
      }
    });
  }

  private void pageUpdateHandler(RoutingContext context) {
    String uid = context.request().getParam("uid");
    String name = context.request().getParam("name");

    JsonObject request = new JsonObject()
      .put("uid", uid)
      .put("name", name);

    DeliveryOptions options = new DeliveryOptions().addHeader("action", "update-page");

    vertx.eventBus().send(wikiDbQueue, request, options, reply -> {
      if (reply.succeeded()) {
        context.response().setStatusCode(303);
        context.response().putHeader("Location", "/apis/" + uid);
        context.response().end();
      } else {
        context.fail(reply.cause());
      }
    });
  }

  private void pageDeletionHandler(RoutingContext context) {
    String id = context.request().getParam("uid");
    JsonObject request = new JsonObject().put("uid", id);
    DeliveryOptions options = new DeliveryOptions().addHeader("action", "delete-page");
    vertx.eventBus().send(wikiDbQueue, request, options, reply -> {
      if (reply.succeeded()) {
        context.response().setStatusCode(303);
        context.response().putHeader("Location", "/getAll");
        context.response().end();
      } else {
        context.fail(reply.cause());
      }
    });
  }


}
