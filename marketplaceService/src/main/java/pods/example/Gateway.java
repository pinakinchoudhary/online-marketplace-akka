package pods.example;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.EntityRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletionStage;

public class Gateway extends AbstractBehavior<Gateway.Command> {
	/* This class is also a skeleton class. 
	 * Actually, Command below should be an interface. It should have the necessary number of implementing classes and corresponding event handlers. The implementing classes should have fields to hold the required elements of the http request (request path and request body), in addition to the reply-to ActorRef. The event handlers could use some suitable json parser to parse any json in the original http request body. Similarly, the Response class should be populated with the actual response.
	 */
	int responseNum = 0;
	
	public interface Command {}

	public static class GetProducts implements Command {
		public final ActorRef<Response> replyTo;
		public GetProducts(ActorRef<Response> replyTo) {
			this.replyTo = replyTo;
		}
	}

	public static class GetProduct implements Command {
		public final String productId;
		public final ActorRef<Response> replyTo;
		public GetProduct(String productId, ActorRef<Response> replyTo) {
			this.productId = productId;
			this.replyTo = replyTo;
		}
	}

	public static class GetOrder implements Command {
		public final String orderId;
		public final ActorRef<Response> replyTo;
		public GetOrder(String orderId, ActorRef<Response> replyTo) {
			this.orderId = orderId;
			this.replyTo = replyTo;
		}
	}

	public static class GetUserOrders implements Command {
		public final String userId;
		public final ActorRef<Response> replyTo;
		public GetUserOrders(String userId, ActorRef<Response> replyTo) {
			this.userId = userId;
			this.replyTo = replyTo;
		}
	}

	public static class CreateOrder implements Command {
		public final String userId;
		public final Map<String, Integer> products;
		public final ActorRef<Response> replyTo;
		public CreateOrder(String userId, Map<String, Integer> products, ActorRef<Response> replyTo) {
			this.userId = userId;
			this.products = products;
			this.replyTo = replyTo;
		}
	}

	public static class UpdateOrder implements Command {
		public final String orderId;
		public final String status;
		public final ActorRef<Response> replyTo;
		public UpdateOrder(String orderId, String status, ActorRef<Response> replyTo) {
			this.orderId = orderId;
			this.status = status;
			this.replyTo = replyTo;
		}
	}

	public static class DeleteOrder implements Command {
		public final String orderId;
		public final ActorRef<Response> replyTo;
		public DeleteOrder(String orderId, ActorRef<Response> replyTo) {
			this.orderId = orderId;
			this.replyTo = replyTo;
		}
	}

	public static class DeleteUserOrders implements Command {
		public final String userId;
		public final ActorRef<Response> replyTo;
		public DeleteUserOrders(String userId, ActorRef<Response> replyTo) {
			this.userId = userId;
			this.replyTo = replyTo;
		}
	}

	public interface Response {}

	public static class ProductsResponse implements Response {
		public final List<Product.ProductResponse> products;
		public ProductsResponse(List<Product.ProductResponse> products) {
			this.products = products;
		}
	}

	public static class ProductResponse implements Response {
		public final Integer id;
		public final String name;
		public final String description;
		public final Integer price;
		public final Integer stock_quantity;

		public ProductResponse(String id, String name, String description, Integer price, Integer stock_quantity) {
			this.id = (id != null && !id.isEmpty()) ? Integer.parseInt(id) : null;
			this.name = name;
			this.description = description;
			this.price = price;
			this.stock_quantity = stock_quantity;
		}
	}

	public static class OrderResponse implements Response {
		@JsonProperty("order_id")
		public final Integer orderId;
		@JsonProperty("user_id")
		public final Integer userId;
		@JsonProperty("total_price")
		public final Integer totalPrice;
		public final String status;
		public final List<OrderItem.OrderItemResponse> items;

		public OrderResponse(String orderId, String userId, Integer totalPrice, String status, List<OrderItem.OrderItemResponse> items) {
			if (orderId == null || orderId.isEmpty()) {
				this.orderId = null;
				this.userId = null;
				this.totalPrice = null;
				this.status = null;
				this.items = null;
			} else {
				this.orderId = Integer.parseInt(orderId);
				this.userId = (userId != null && !userId.isEmpty()) ? Integer.parseInt(userId) : null;
				this.totalPrice = totalPrice;
				this.status = status;
				this.items = items;
			}
		}
	}

	public static class OrdersResponse implements Response {
		public final List<OrderResponse> orders;
		public OrdersResponse(List<OrderResponse> orders) {
			this.orders = orders;
		}
	}

	public static class OperationResponse implements Response {
		public final boolean success;
		public final String message;

		public OperationResponse(boolean success, String message) {
			this.success = success;
			this.message = message;
		}
	}

	public static class OrderItemRequest {
		public Integer productId;
		public Integer quantity;
	}

	private final ClusterSharding sharding;
	private final ObjectMapper objectMapper;
	private final Map<String, ActorRef<Response>> pendingRequests;
	private final ExternalServiceClient externalServiceClient;
	private int orderCounter;

	public static Behavior<Command> create() {
		return Behaviors.setup(context -> new Gateway(context));
	}

	private Gateway(ActorContext<Command> context) {
		super(context);
		this.sharding = ClusterSharding.get(context.getSystem());
		this.objectMapper = new ObjectMapper();
		this.pendingRequests = new HashMap<>();
		this.externalServiceClient = new ExternalServiceClient(
			context.getSystem(),
			"http://localhost:8080", // Account service base URL
			"http://localhost:8082"  // Wallet service base URL
		);
		this.orderCounter = 0;
		initializeProducts();
	}

	private void initializeProducts() {
		try {
			String content = new String(Files.readAllBytes(Paths.get("src/main/resources/static/products.csv")));
			String[] lines = content.split("\n");
	
			System.out.println("Initializing Products...");
	
			for (int i = 1; i < lines.length; i++) {
				String line = lines[i].trim(); // Trim whitespace & carriage returns
				if (line.isEmpty()) continue; // Skip empty lines
	
				String[] parts = line.split(",");
				if (parts.length < 5) {
					System.err.println("Skipping malformed line: " + line);
					continue; // Skip if there aren't enough parts
				}
	
				try {
					Integer id = Integer.parseInt(parts[0].trim());
					String name = parts[1].trim();
					String description = parts[2].trim();
					Integer price = Integer.parseInt(parts[3].trim());
					Integer stock_quantity = Integer.parseInt(parts[4].trim());
	
					// Print product to console
					System.out.printf("Product Loaded: ID=%d, Name=%s, Description=%s, Price=%d, Stock=%d%n",
							id, name, description, price, stock_quantity);

					EntityRef<Product.Command> productRef = sharding.entityRefFor(Product.ENTITY_KEY, id.toString());
					ActorRef<Product.Response> adapter = getContext().messageAdapter(Product.Response.class, response -> response);
					productRef.tell(new Product.CreateProduct(name, description, price, stock_quantity, adapter));
				} catch (NumberFormatException e) {
					System.err.println("Invalid number format in line: " + line);
					e.printStackTrace();
				}
			}
	
			System.out.println("Product Initialization Complete.");
	
		} catch (IOException e) {
			System.err.println("Failed to initialize products.");
			e.printStackTrace();
		}
	}
	

	@Override
	public Receive<Command> createReceive() {
		return newReceiveBuilder()
			.onMessage(GetProducts.class, this::onGetProducts)
			.onMessage(GetProduct.class, this::onGetProduct)
			.onMessage(GetOrder.class, this::onGetOrder)
			.onMessage(GetUserOrders.class, this::onGetUserOrders)
			.onMessage(CreateOrder.class, this::onCreateOrder)
			.onMessage(UpdateOrder.class, this::onUpdateOrder)
			.onMessage(DeleteOrder.class, this::onDeleteOrder)
			.onMessage(DeleteUserOrders.class, this::onDeleteUserOrders)
			.onMessage(Product.ProductResponse.class, this::onProductResponse)
			.onMessage(Order.OrderResponse.class, this::onOrderResponse)
			.onMessage(Order.OrderUpdated.class, this::onOrderUpdated)
			.onMessage(Product.StockUpdated.class, this::onStockUpdated)
			.build();
	}

	private Behavior<Command> onGetProducts(GetProducts command) {
		// TODO: Implement getting all products
		return this;
	}

	private Behavior<Command> onGetProduct(GetProduct command) {
		EntityRef<Product.Command> productRef = sharding.entityRefFor(Product.ENTITY_KEY, command.productId);
		pendingRequests.put(command.productId, command.replyTo);
		ActorRef<Product.Response> adapter = getContext().messageAdapter(Product.Response.class, response -> response);
		productRef.tell(new Product.GetProduct(adapter));
		return this;
	}

	private Behavior<Command> onGetOrder(GetOrder command) {
		EntityRef<Order.Command> orderRef = sharding.entityRefFor(Order.ENTITY_KEY, command.orderId);
		pendingRequests.put(command.orderId, command.replyTo);
		ActorRef<Order.Response> adapter = getContext().messageAdapter(Order.Response.class, response -> response);
		orderRef.tell(new Order.GetOrder(adapter));
		return this;
	}

	private Behavior<Command> onGetUserOrders(GetUserOrders command) {
		// TODO: Implement getting user orders
		return this;
	}

	private Behavior<Command> onCreateOrder(CreateOrder command) {
		// Increment order counter and create a new order ID
		String orderId = String.valueOf(++orderCounter);
		
		// Spawn a PostOrder actor to handle the order creation
		getContext().spawn(PostOrder.create(command.userId, command.products, command.replyTo, orderId, externalServiceClient), 
			"post-order-" + orderId);
		return this;
	}

	private Behavior<Command> onUpdateOrder(UpdateOrder command) {
		System.out.println("[Gateway] Received update order request for order: " + command.orderId + ", status: " + command.status);
		
		EntityRef<Order.Command> orderRef = sharding.entityRefFor(Order.ENTITY_KEY, command.orderId);
		pendingRequests.put(command.orderId, command.replyTo);
		ActorRef<Order.Response> adapter = getContext().messageAdapter(Order.Response.class, response -> response);
		orderRef.tell(new Order.UpdateOrder(command.status, adapter));
		return this;
	}

	private Behavior<Command> onDeleteOrder(DeleteOrder command) {
		// Spawn a DeleteOrder actor to handle the order cancellation
		getContext().spawn(pods.example.DeleteOrder.create(command.orderId, command.replyTo, externalServiceClient), 
			"delete-order-" + command.orderId);
		return this;
	}

	private Behavior<Command> onDeleteUserOrders(DeleteUserOrders command) {
		// TODO: Implement deleting user orders
		return this;
	}

	private Behavior<Command> onProductResponse(Product.ProductResponse response) {
		ActorRef<Response> replyTo = pendingRequests.remove(response.id.toString());
		if (replyTo != null) {
			// Only send response if the product has actual data (not just default values)
			if (response.name != null && !response.name.isEmpty()) {
				Gateway.ProductResponse gatewayResponse = new Gateway.ProductResponse(
					response.id.toString(),
					response.name,
					response.description,
					response.price,
					response.stock_quantity
				);
				replyTo.tell(gatewayResponse);
			} else {
				// Product not found - send 404 equivalent
				replyTo.tell(new Gateway.OperationResponse(false, "Product not found"));
			}
		}
		return this;
	}

	private Behavior<Command> onOrderResponse(Order.OrderResponse response) {
		ActorRef<Response> replyTo = pendingRequests.remove(response.orderId);
		if (replyTo != null) {
			if (response.orderId == null || response.userId == null || response.status == null || response.items == null) {
				replyTo.tell(new OperationResponse(false, "Order not found"));
				return this;
			}
			Gateway.OrderResponse gatewayResponse = new Gateway.OrderResponse(
				response.orderId,
				response.userId,
				response.totalPrice,
				response.status,
				response.items
			);
			replyTo.tell(gatewayResponse);
		}
		return this;
	}

	private Behavior<Command> onOrderUpdated(Order.OrderUpdated response) {
		System.out.println("[Gateway] Received order update response, success: " + response.success + ", message: " + response.message);
		// Find the pending request by looking for the first one that's waiting for an OrderUpdated response
		for (Map.Entry<String, ActorRef<Response>> entry : pendingRequests.entrySet()) {
			ActorRef<Response> replyTo = entry.getValue();
			if (replyTo != null) {
				replyTo.tell(new OperationResponse(response.success, response.message));
				pendingRequests.remove(entry.getKey());
				break;
			}
		}
		return this;
	}

	private Behavior<Command> onStockUpdated(Product.StockUpdated response) {
		// TODO: Handle stock updated response
		return this;
	}
}
