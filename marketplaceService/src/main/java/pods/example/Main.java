package pods.example;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.Scheduler;
import akka.actor.typed.javadsl.AskPattern;
import akka.actor.typed.javadsl.Behaviors;
import akka.cluster.typed.Cluster;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.Entity;
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;
import com.fasterxml.jackson.databind.ObjectMapper;

// Main class
public class Main {

	static ActorRef<Gateway.Command> gateway;
	static Duration askTimeout;
	static Scheduler scheduler;
	static ObjectMapper objectMapper = new ObjectMapper();
	
    static class MyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            try {
                if (method.equals("GET") && path.matches("/products/\\d+")) {
                    String productId = path.substring(path.lastIndexOf("/") + 1);
                    handleGetProduct(exchange, productId);
                } else if (method.equals("GET") && path.matches("/orders/\\d+")) {
                    String orderId = path.substring(path.lastIndexOf("/") + 1);
                    handleGetOrder(exchange, orderId);
                } else if (method.equals("POST") && path.equals("/orders")) {
                    handleCreateOrder(exchange);
                } else if (method.equals("PUT") && path.matches("/orders/\\d+")) {
                    String orderId = path.substring(path.lastIndexOf("/") + 1);
                    handleUpdateOrder(exchange, orderId);
                } else if (method.equals("DELETE") && path.matches("/orders/\\d+")) {
                    String orderId = path.substring(path.lastIndexOf("/") + 1);
                    handleDeleteOrder(exchange, orderId);
                } else {
                    sendResponse(exchange, 404, "Not Found");
                }
            } catch (Exception e) {
                sendResponse(exchange, 500, "Internal Server Error: " + e.getMessage());
            }
        }

        private void handleGetProduct(HttpExchange exchange, String productId) {
            CompletionStage<Gateway.Response> response = AskPattern.ask(
                gateway,
                replyTo -> new Gateway.GetProduct(productId, replyTo),
                askTimeout,
                scheduler
            );

            response.thenAccept(r -> {
                if (r instanceof Gateway.ProductResponse) {
                    Gateway.ProductResponse productResponse = (Gateway.ProductResponse) r;
                    try {
                        String jsonResponse = objectMapper.writeValueAsString(productResponse);
                        sendResponse(exchange, 200, jsonResponse);
                    } catch (Exception e) {
                        sendResponse(exchange, 500, "Error serializing response");
                    }
                } else {
                    sendResponse(exchange, 404, "Product not found");
                }
            });
        }

        private void handleGetOrder(HttpExchange exchange, String orderId) {
            CompletionStage<Gateway.Response> response = AskPattern.ask(
                gateway,
                replyTo -> new Gateway.GetOrder(orderId, replyTo),
                askTimeout,
                scheduler
            );

            response.thenAccept(r -> {
                if (r instanceof Gateway.OrderResponse) {
                    Gateway.OrderResponse orderResponse = (Gateway.OrderResponse) r;
                    try {
                        String jsonResponse = objectMapper.writeValueAsString(orderResponse);
                        sendResponse(exchange, 200, jsonResponse);
                    } catch (Exception e) {
                        sendResponse(exchange, 500, "Error serializing response");
                    }
                } else {
                    sendResponse(exchange, 404, "Order not found");
                }
            });
        }

        private void handleCreateOrder(HttpExchange exchange) throws IOException {
            String requestBody = new String(exchange.getRequestBody().readAllBytes());
            CreateOrderRequest request = objectMapper.readValue(requestBody, CreateOrderRequest.class);

            // Convert items to products map
            Map<String, Integer> products = request.items.stream()
                .collect(Collectors.toMap(
                    item -> String.valueOf(item.product_id),
                    item -> item.quantity
                ));

            CompletionStage<Gateway.Response> response = AskPattern.ask(
                gateway,
                replyTo -> new Gateway.CreateOrder(String.valueOf(request.user_id), products, replyTo),
                askTimeout,
                scheduler
            );

            response.thenAccept(r -> {
                if (r instanceof Gateway.OrderResponse) {
                    Gateway.OrderResponse orderResponse = (Gateway.OrderResponse) r;
                    try {
                        String jsonResponse = objectMapper.writeValueAsString(orderResponse);
                        sendResponse(exchange, 201, jsonResponse);
                    } catch (Exception e) {
                        sendResponse(exchange, 500, "Error serializing response");
                    }
                } else if (r instanceof Gateway.OperationResponse) {
                    Gateway.OperationResponse operationResponse = (Gateway.OperationResponse) r;
                    sendResponse(exchange, operationResponse.success ? 200 : 400, operationResponse.message);
                }
            });
        }

        private void handleUpdateOrder(HttpExchange exchange, String orderId) throws IOException {
            String requestBody = new String(exchange.getRequestBody().readAllBytes());
            UpdateOrderRequest request = objectMapper.readValue(requestBody, UpdateOrderRequest.class);

            CompletionStage<Gateway.Response> response = AskPattern.ask(
                gateway,
                replyTo -> new Gateway.UpdateOrder(orderId, request.status, replyTo),
                askTimeout,
                scheduler
            );

            response.thenAccept(r -> {
                if (r instanceof Gateway.OperationResponse) {
                    Gateway.OperationResponse operationResponse = (Gateway.OperationResponse) r;
                    sendResponse(exchange, operationResponse.success ? 200 : 400, operationResponse.message);
                }
            });
        }

        private void handleDeleteOrder(HttpExchange exchange, String orderId) {
            CompletionStage<Gateway.Response> response = AskPattern.ask(
                gateway,
                replyTo -> new Gateway.DeleteOrder(orderId, replyTo),
                askTimeout,
                scheduler
            );

            response.thenAccept(r -> {
                if (r instanceof Gateway.OperationResponse) {
                    Gateway.OperationResponse operationResponse = (Gateway.OperationResponse) r;
                    sendResponse(exchange, operationResponse.success ? 200 : 400, operationResponse.message);
                }
            });
        }

        private void sendResponse(HttpExchange exchange, int statusCode, String response) {
            try {
                exchange.sendResponseHeaders(statusCode, response.getBytes().length);
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    
    static class OrderItem {
        public int product_id;
        public int quantity;
    }

    static class CreateOrderRequest {
        public int user_id;
        public List<OrderItem> items;
    }

    static class UpdateOrderRequest {
        public String order_id;
        public String status;
    }

    public static Behavior<Void> create() {
        return Behaviors.setup(context -> {
            // Initialize cluster
            Cluster cluster = Cluster.get(context.getSystem());
            
            // Initialize sharding
            ClusterSharding sharding = ClusterSharding.get(context.getSystem());
            
            // Initialize product sharding
            sharding.init(Entity.of(
                Product.ENTITY_KEY,
                entityContext -> Product.create(
                    entityContext.getEntityId(),
                    "", "", 0, 0
                )
            ));
            
            // Initialize order sharding
            sharding.init(Entity.of(
                Order.ENTITY_KEY,
                entityContext -> Order.create(
                    entityContext.getEntityId(),
                    "", 0, "", null
                )
            ));

            // Initialize gateway
            gateway = context.spawn(Gateway.create(), "gateway");
            
            askTimeout = Duration.ofSeconds(5);
            scheduler = context.getSystem().scheduler();

            // Start HTTP server
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
                server.createContext("/", new MyHandler());
                server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
                server.start();
            } catch (IOException e) {
                context.getLog().error("Failed to start HTTP server", e);
            }

            return Behaviors.empty();
        });
    }

    public static void main(String[] args) {
        ActorSystem.create(Main.create(), "AccountSystem");
    }
}
