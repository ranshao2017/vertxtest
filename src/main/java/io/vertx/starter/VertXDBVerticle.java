package io.vertx.starter;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.asyncsql.MySQLClient;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class VertXDBVerticle extends AbstractVerticle {

  public static final String CONFIG_WIKIDB_QUEUE = "wikidb.queue";

  private SQLClient dbClient;

  private static final Logger LOGGER = LoggerFactory.getLogger(VertXDBVerticle.class);

  private final Map<SqlQuery, String> sqlQueries = new HashMap<>();


  private enum SqlQuery {
    ALL_PAGES,
    GET_PAGE,
    SAVE_PAGE,
    UPDATE_PAGE,
    DELETE_PAGE
  }

  private void loadSqlQueries() throws IOException {
    InputStream queriesInputStream = getClass().getResourceAsStream("/db-queries.properties");

    Properties queriesProps = new Properties();
    queriesProps.load(queriesInputStream);
    queriesInputStream.close();

    sqlQueries.put(SqlQuery.ALL_PAGES, queriesProps.getProperty("all-pages"));
    sqlQueries.put(SqlQuery.GET_PAGE, queriesProps.getProperty("get-page"));
    sqlQueries.put(SqlQuery.SAVE_PAGE, queriesProps.getProperty("save-page"));
    sqlQueries.put(SqlQuery.UPDATE_PAGE, queriesProps.getProperty("update-page"));
    sqlQueries.put(SqlQuery.DELETE_PAGE, queriesProps.getProperty("delete-page"));

  }

  public static void main(String[] args){
    Vertx vertx = Vertx.vertx();
    vertx.deployVerticle(new VertXDBVerticle());
  }

  @Override
  public void start(Future<Void> startFuture) throws Exception {

    /*
     * Note: this uses blocking APIs, but data is small...
     */
    loadSqlQueries();

    JsonObject cfg = new JsonObject();
    cfg.put("host", "47.97.100.240");
    cfg.put("port", 3306);
    cfg.put("database", "licaishi");
    cfg.put("username", "lcs");
    cfg.put("password", "lcs2018");

    dbClient = MySQLClient.createShared(vertx, cfg);

    dbClient.getConnection(ar -> {
      if (ar.failed()) {
        LOGGER.error("Could not open a database connection", ar.cause());
        startFuture.fail(ar.cause());
      } else {
          vertx.eventBus().consumer(config().getString(CONFIG_WIKIDB_QUEUE, "wikidb.queue"), this::onMessage);
      }
    });
  }

  public enum ErrorCodes {
    NO_ACTION_SPECIFIED,
    BAD_ACTION,
    DB_ERROR
  }

  public void onMessage(Message<JsonObject> message) {

    if (!message.headers().contains("action")) {
      LOGGER.error("No action header specified for message with headers {} and body {}",
        message.headers(), message.body().encodePrettily());
      message.fail(ErrorCodes.NO_ACTION_SPECIFIED.ordinal(), "No action header specified");
      return;
    }
    String action = message.headers().get("action");

    switch (action) {
      case "all-pages":
        fetchAllPages(message);
        break;
      case "get-page":
        fetchPage(message);
        break;
      case "save-page":
        savePage(message);
        break;
      case "update-page":
        updatePage(message);
        break;
      case "delete-page":
        deletePage(message);
        break;
      default:
        message.fail(ErrorCodes.BAD_ACTION.ordinal(), "Bad action: " + action);
    }
  }

  private void fetchAllPages(Message<JsonObject> message) {
    dbClient.query(sqlQueries.get(SqlQuery.ALL_PAGES), res -> {
      if (res.succeeded()) {
        List<JsonObject> pages = res.result().getRows();
        message.reply(new JsonObject().put("pages", new JsonArray(pages)));
      } else {
        reportQueryError(message, res.cause());
      }
    });
  }

  private void fetchPage(Message<JsonObject> message) {
    String requestedPage = message.body().getString("uid");
    JsonArray params = new JsonArray().add(requestedPage);

    dbClient.queryWithParams(sqlQueries.get(SqlQuery.GET_PAGE), params, fetch -> {
      if (fetch.succeeded()) {
        JsonObject response = new JsonObject();
        ResultSet resultSet = fetch.result();
        if (resultSet.getNumRows() == 0) {
          response.put("found", false);
        } else {
          response.put("found", true);
          JsonArray row = resultSet.getResults().get(0);
          response.put("uid", row.getInteger(0));
          response.put("name", row.getString(1));
        }
        message.reply(response);
      } else {
        reportQueryError(message, fetch.cause());
      }
    });
  }

  private void savePage(Message<JsonObject> message) {
    JsonObject request = message.body();
    JsonArray data = new JsonArray()
      .add(request.getString("name"));

    dbClient.updateWithParams(sqlQueries.get(SqlQuery.SAVE_PAGE), data, res -> {
      if (res.succeeded()) {
        message.reply("ok");
      } else {
        reportQueryError(message, res.cause());
      }
    });
  }

  private void updatePage(Message<JsonObject> message) {
    JsonObject request = message.body();
    JsonArray data = new JsonArray()
      .add(request.getString("name"))
      .add(request.getString("uid"));

    dbClient.updateWithParams(sqlQueries.get(SqlQuery.UPDATE_PAGE), data, res -> {
      if (res.succeeded()) {
        message.reply("ok");
      } else {
        reportQueryError(message, res.cause());
      }
    });
  }

  private void deletePage(Message<JsonObject> message) {
    JsonArray data = new JsonArray().add(message.body().getString("uid"));

    dbClient.updateWithParams(sqlQueries.get(SqlQuery.DELETE_PAGE), data, res -> {
      if (res.succeeded()) {
        message.reply("ok");
      } else {
        reportQueryError(message, res.cause());
      }
    });
  }

  private void reportQueryError(Message<JsonObject> message, Throwable cause) {
    LOGGER.error("Database query error", cause);
    message.fail(ErrorCodes.DB_ERROR.ordinal(), cause.getMessage());
  }
}
