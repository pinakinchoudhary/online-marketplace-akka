package pods.example;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.EntityRef;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.HashMap;
import java.util.concurrent.CompletionStage;

public class PostOrder extends AbstractBehavior<PostOrder.Command> {
    public interface Command {}

    public static class ProductStockResponse implements Command {
        public final String productId;
        public final boolean success;
        public final String message;
        public final Integer price;
        public final Integer stock;

        public ProductStockResponse(String productId, boolean success, String message, Integer price, Integer stock) {
            this.productId = productId;
            this.success = success;
            this.message = message;
            this.price = price;
            this.stock = stock;
        }
    }

    public static class UserResponse implements Command {
        public final boolean success;
        public final String message;
        public final Boolean discountAvailed;

        public UserResponse(boolean success, String message, Boolean discountAvailed) {
            this.success = success;
            this.message = message;
            this.discountAvailed = discountAvailed;
        }
    }

    public static class WalletResponse implements Command {
        public final boolean success;
        public final String message;

        public WalletResponse(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    public static class DiscountResponse implements Command {
        public final boolean success;
        public final String message;

        public DiscountResponse(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    private final String userId;
    private final Map<String, Integer> products;
    private final ActorRef<Gateway.Response> replyTo;
    private final ClusterSharding sharding;
    private final String orderId;
    private final ExternalServiceClient externalServiceClient;
    private int pendingResponses;
    private double totalAmount;
    private boolean orderCreated;
    private final Map<String, Integer> productPrices;
    private final Map<String, Integer> productStocks;
    private Boolean userDiscountAvailed;
    private boolean userValidated;
    private boolean walletUpdated;
    private boolean discountUpdated;

    public static Behavior<Command> create(String userId, Map<String, Integer> products, ActorRef<Gateway.Response> replyTo, String orderId, ExternalServiceClient externalServiceClient) {
        return Behaviors.setup(context -> new PostOrder(context, userId, products, replyTo, orderId, externalServiceClient));
    }

    private PostOrder(ActorContext<Command> context, String userId, Map<String, Integer> products, ActorRef<Gateway.Response> replyTo, String orderId, ExternalServiceClient externalServiceClient) {
        super(context);
        this.userId = userId;
        this.products = products;
        this.replyTo = replyTo;
        this.sharding = ClusterSharding.get(context.getSystem());
        this.orderId = orderId;
        this.externalServiceClient = externalServiceClient;
        this.pendingResponses = products.size();
        this.totalAmount = 0.0;
        this.orderCreated = false;
        this.productPrices = new HashMap<>();
        this.productStocks = new HashMap<>();
        this.userDiscountAvailed = null;
        this.userValidated = false;
        this.walletUpdated = false;
        this.discountUpdated = false;

        // Validate payload
        if (products.isEmpty()) {
            replyTo.tell(new Gateway.OperationResponse(false, "Invalid payload: No products specified"));
            return;
        }

        // Start by validating user
        validateUser();
    }

    private void validateUser() {
        CompletionStage<ExternalServiceClient.UserResponse> userFuture = externalServiceClient.getUser(Integer.parseInt(userId));
        userFuture.thenAccept(user -> {
            if (user != null) {
                getContext().getSelf().tell(new UserResponse(true, "", user.discount_availed));
            } else {
                getContext().getSelf().tell(new UserResponse(false, "User not found", null));
            }
        }).exceptionally(throwable -> {
            getContext().getSelf().tell(new UserResponse(false, "Error while fetching user: " + throwable.getMessage(), null));
            return null;
        });
    }

    private void checkProductAvailability() {
        // Start checking product availability and get prices
        for (Map.Entry<String, Integer> entry : products.entrySet()) {
            String productId = entry.getKey();
            int quantity = entry.getValue();
            EntityRef<Product.Command> productRef = sharding.entityRefFor(Product.ENTITY_KEY, productId);
            ActorRef<Product.Response> adapter = getContext().messageAdapter(Product.Response.class, response -> {
                if (response instanceof Product.ProductResponse) {
                    Product.ProductResponse productResponse = (Product.ProductResponse) response;
                    return new ProductStockResponse(
                        productId, 
                        true, 
                        "", 
                        productResponse.price,
                        productResponse.stockQuantity
                    );
                }
                return new ProductStockResponse(productId, false, "Product not found", null, null);
            });
            productRef.tell(new Product.GetProduct(adapter));
        }
    }

    private void updateWallet() {
        CompletionStage<Boolean> walletFuture = externalServiceClient.updateWallet(
            Integer.parseInt(userId),
            "debit",
            (int)totalAmount
        );
        walletFuture.thenAccept(success -> {
            getContext().getSelf().tell(new WalletResponse(success, success ? "" : "Insufficient balance"));
        }).exceptionally(throwable -> {
            getContext().getSelf().tell(new WalletResponse(false, "Error updating wallet: " + throwable.getMessage()));
            return null;
        });
    }

    private void updateDiscount() {
        CompletionStage<Boolean> discountFuture = externalServiceClient.updateDiscount(Integer.parseInt(userId));
        discountFuture.thenAccept(success -> {
            getContext().getSelf().tell(new DiscountResponse(success, success ? "" : "Failed to update discount"));
        }).exceptionally(throwable -> {
            getContext().getSelf().tell(new DiscountResponse(false, "Error updating discount: " + throwable.getMessage()));
            return null;
        });
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(UserResponse.class, this::onUserResponse)
            .onMessage(ProductStockResponse.class, this::onProductStockResponse)
            .onMessage(WalletResponse.class, this::onWalletResponse)
            .onMessage(DiscountResponse.class, this::onDiscountResponse)
            .build();
    }

    private Behavior<Command> onUserResponse(UserResponse response) {
        if (!response.success) {
            replyTo.tell(new Gateway.OperationResponse(false, response.message));
            return Behaviors.stopped();
        }

        userValidated = true;
        userDiscountAvailed = response.discountAvailed;
        checkProductAvailability();
        return this;
    }

    private Behavior<Command> onProductStockResponse(ProductStockResponse response) {
        pendingResponses--;

        if (!response.success) {
            replyTo.tell(new Gateway.OperationResponse(false, response.message));
            return Behaviors.stopped();
        }

        // Validate quantity
        int requestedQuantity = products.get(response.productId);
        if (requestedQuantity <= 0) {
            replyTo.tell(new Gateway.OperationResponse(false, "Product quantity must be greater than zero"));
            return Behaviors.stopped();
        }

        if (requestedQuantity > response.stock) {
            replyTo.tell(new Gateway.OperationResponse(false, "Product " + response.productId + " is out of stock"));
            return Behaviors.stopped();
        }

        productPrices.put(response.productId, response.price);
        productStocks.put(response.productId, response.stock);

        if (pendingResponses == 0) {
            // Calculate total amount
            for (Map.Entry<String, Integer> entry : products.entrySet()) {
                String productId = entry.getKey();
                int quantity = entry.getValue();
                totalAmount += productPrices.get(productId) * quantity;
            }

            // Apply discount if user hasn't availed one
            if (userDiscountAvailed != null && !userDiscountAvailed) {
                totalAmount *= 0.9;
            }

            // Update wallet
            updateWallet();
        }

        return this;
    }

    private Behavior<Command> onWalletResponse(WalletResponse response) {
        if (!response.success) {
            replyTo.tell(new Gateway.OperationResponse(false, response.message));
            return Behaviors.stopped();
        }

        walletUpdated = true;

        // If user hasn't availed discount, update it
        if (userDiscountAvailed != null && !userDiscountAvailed) {
            updateDiscount();
        } else {
            createOrder();
        }

        return this;
    }

    private Behavior<Command> onDiscountResponse(DiscountResponse response) {
        if (!response.success) {
            // Rollback wallet update
            CompletionStage<Boolean> walletFuture = externalServiceClient.updateWallet(
                Integer.parseInt(userId),
                "credit",
                (int)totalAmount
            );
            walletFuture.thenAccept(success -> {
                if (!success) {
                    getContext().getLog().error("Failed to rollback wallet update");
                }
            });
            replyTo.tell(new Gateway.OperationResponse(false, response.message));
            return Behaviors.stopped();
        }

        discountUpdated = true;
        createOrder();
        return this;
    }

    private void createOrder() {
        // Convert products map to list of OrderItemResponse
        List<OrderItem.OrderItemResponse> items = products.entrySet().stream()
            .map(entry -> new OrderItem.OrderItemResponse(
                null, // id will be set by the database
                Integer.parseInt(orderId),
                Integer.parseInt(entry.getKey()),
                entry.getValue()
            ))
            .collect(Collectors.toList());

        // Create the order with all required data
        EntityRef<Order.Command> orderRef = sharding.entityRefFor(Order.ENTITY_KEY, orderId);
        orderRef.tell(new Order.CreateOrder(orderId, userId, (int)totalAmount, "PLACED", items, getContext().messageAdapter(Order.Response.class, r -> {
            if (r instanceof Order.OrderUpdated) {
                Order.OrderUpdated updated = (Order.OrderUpdated) r;
                if (!updated.success) {
                    getContext().getLog().error("Failed to create order: {}", updated.message);
                }
            }
            return new ProductStockResponse("", true, "", 0, 0); // Return a valid response
        })));

        // Update stock for each product
        for (Map.Entry<String, Integer> entry : products.entrySet()) {
            String productId = entry.getKey();
            int quantity = entry.getValue();
            EntityRef<Product.Command> productRef = sharding.entityRefFor(Product.ENTITY_KEY, productId);
            productRef.tell(new Product.UpdateStock(-quantity, getContext().messageAdapter(Product.Response.class, r -> {
                if (r instanceof Product.StockUpdated) {
                    Product.StockUpdated updated = (Product.StockUpdated) r;
                    if (!updated.success) {
                        getContext().getLog().error("Failed to update stock for product {}: {}", productId, updated.message);
                    }
                }
                return new ProductStockResponse(productId, true, "", 0, 0); // Return a valid response
            })));
        }

        // Send response
        replyTo.tell(new Gateway.OrderResponse(orderId, userId, (int)totalAmount, "PLACED", items));
        orderCreated = true;
    }
} 