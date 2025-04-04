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
import java.util.concurrent.CompletionStage;

public class DeleteOrder extends AbstractBehavior<DeleteOrder.Command> {
    public interface Command {}

    public static class GetOrderResponse implements Command {
        public final Order.OrderResponse order;
        public GetOrderResponse(Order.OrderResponse order) {
            this.order = order;
        }
    }

    public static class UpdateStock implements Command {
        public final boolean success;
        public final int index;
        public UpdateStock(boolean success, int index) {
            this.success = success;
            this.index = index;
        }
    }

    public static class UpdateWallet implements Command {
        public final boolean success;
        public UpdateWallet(boolean success) {
            this.success = success;
        }
    }

    private final String orderId;
    private final ActorRef<Gateway.Response> replyTo;
    private final ExternalServiceClient externalServiceClient;
    private final ClusterSharding sharding;
    private Order.OrderResponse order;
    private int updatedStocks;

    public static Behavior<Command> create(String orderId, ActorRef<Gateway.Response> replyTo, 
            ExternalServiceClient externalServiceClient) {
        return Behaviors.setup(context -> new DeleteOrder(context, orderId, replyTo, externalServiceClient));
    }

    private DeleteOrder(ActorContext<Command> context, String orderId, ActorRef<Gateway.Response> replyTo,
            ExternalServiceClient externalServiceClient) {
        super(context);
        this.orderId = orderId;
        this.replyTo = replyTo;
        this.externalServiceClient = externalServiceClient;
        this.sharding = ClusterSharding.get(context.getSystem());
        this.updatedStocks = 0;

        System.out.println("[DeleteOrder] Starting cancellation process for order: " + orderId);
        getOrder();
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(GetOrderResponse.class, this::onGetOrderResponse)
            .onMessage(UpdateStock.class, this::onUpdateStock)
            .onMessage(UpdateWallet.class, this::onUpdateWallet)
            .build();
    }

    private void getOrder() {
        System.out.println("[DeleteOrder] Fetching order details for: " + orderId);
        EntityRef<Order.Command> orderRef = sharding.entityRefFor(Order.ENTITY_KEY, orderId);
        ActorRef<Order.Response> adapter = getContext().messageAdapter(Order.Response.class, 
            response -> {
                if (response instanceof Order.OrderResponse) {
                    return new GetOrderResponse((Order.OrderResponse) response);
                }
                return null;
            });
        orderRef.tell(new Order.GetOrder(adapter));
    }

    private Behavior<Command> onGetOrderResponse(GetOrderResponse command) {
        System.out.println("[DeleteOrder] Received order response for: " + orderId);
        this.order = command.order;

        if (order == null) {
            System.out.println("[DeleteOrder] Order not found: " + orderId);
            replyTo.tell(new Gateway.OperationResponse(false, "Order not found"));
            return Behaviors.stopped();
        }

        if (!order.status.equals("PLACED")) {
            System.out.println("[DeleteOrder] Order cannot be cancelled. Current status: " + order.status);
            replyTo.tell(new Gateway.OperationResponse(false, "Order cannot be cancelled"));
            return Behaviors.stopped();
        }

        System.out.println("[DeleteOrder] Starting stock updates for " + order.items.size() + " items");
        // Update stock for each order item
        for (int idx = 0; idx < order.items.size(); idx++) {
            final int i = idx;
            OrderItem.OrderItemResponse item = order.items.get(i);
            System.out.println("[DeleteOrder] Updating stock for product: " + item.productId + ", quantity: " + item.quantity);
            EntityRef<Product.Command> productRef = sharding.entityRefFor(Product.ENTITY_KEY, item.productId.toString());
            ActorRef<Product.Response> adapter = getContext().messageAdapter(Product.Response.class, 
                response -> new UpdateStock(((Product.StockUpdated) response).success, i));
            productRef.tell(new Product.UpdateStock(item.quantity, adapter));
        }

        return this;
    }

    private Behavior<Command> onUpdateStock(UpdateStock command) {
        System.out.println("[DeleteOrder] Received stock update response for index: " + command.index + ", success: " + command.success);
        
        if (!command.success) {
            System.out.println("[DeleteOrder] Stock update failed, rolling back previous updates");
            // Rollback previous stock updates
            for (int idx = 0; idx < updatedStocks; idx++) {
                final int i = idx;
                OrderItem.OrderItemResponse item = order.items.get(i);
                System.out.println("[DeleteOrder] Rolling back stock for product: " + item.productId);
                EntityRef<Product.Command> productRef = sharding.entityRefFor(Product.ENTITY_KEY, item.productId.toString());
                productRef.tell(new Product.UpdateStock(-item.quantity, getContext().messageAdapter(Product.Response.class, 
                    response -> new UpdateStock(((Product.StockUpdated) response).success, i))));
            }
            replyTo.tell(new Gateway.OperationResponse(false, "Failed to update stock"));
            return Behaviors.stopped();
        }

        updatedStocks++;
        System.out.println("[DeleteOrder] Stock updates completed: " + updatedStocks + "/" + order.items.size());
        
        if (updatedStocks == order.items.size()) {
            System.out.println("[DeleteOrder] All stock updates successful, proceeding to update wallet");
            updateWallet();
        }

        return this;
    }

    private void updateWallet() {
        System.out.println("[DeleteOrder] Updating wallet for user: " + order.userId + ", amount: " + order.totalPrice);
        CompletionStage<Boolean> walletFuture = externalServiceClient.updateWallet(
            Integer.parseInt(order.userId), 
            "credit", 
            order.totalPrice
        );
        walletFuture.thenAccept(success -> {
            System.out.println("[DeleteOrder] Wallet update result: " + success);
            if (success) {
                getContext().getSelf().tell(new UpdateWallet(true));
            } else {
                getContext().getSelf().tell(new UpdateWallet(false));
            }
        }).exceptionally(throwable -> {
            System.out.println("[DeleteOrder] Wallet update failed with exception: " + throwable.getMessage());
            getContext().getSelf().tell(new UpdateWallet(false));
            return null;
        });
    }

    private Behavior<Command> onUpdateWallet(UpdateWallet command) {
        System.out.println("[DeleteOrder] Received wallet update response, success: " + command.success);
        
        if (!command.success) {
            System.out.println("[DeleteOrder] Wallet update failed, rolling back stock updates");
            // Rollback stock updates
            for (int idx = 0; idx < order.items.size(); idx++) {
                final int i = idx;
                OrderItem.OrderItemResponse item = order.items.get(i);
                System.out.println("[DeleteOrder] Rolling back stock for product: " + item.productId);
                EntityRef<Product.Command> productRef = sharding.entityRefFor(Product.ENTITY_KEY, item.productId.toString());
                productRef.tell(new Product.UpdateStock(-item.quantity, getContext().messageAdapter(Product.Response.class, 
                    response -> new UpdateStock(((Product.StockUpdated) response).success, i))));
            }
            replyTo.tell(new Gateway.OperationResponse(false, "Failed to update wallet"));
            return Behaviors.stopped();
        }

        System.out.println("[DeleteOrder] Updating order status to CANCELLED");
        // Update order status
        EntityRef<Order.Command> orderRef = sharding.entityRefFor(Order.ENTITY_KEY, orderId);
        orderRef.tell(new Order.DeleteOrder(getContext().messageAdapter(Order.Response.class, 
            response -> new UpdateWallet(((Order.OrderUpdated) response).success))));

        // Send response
        System.out.println("[DeleteOrder] Order cancellation completed successfully");
        replyTo.tell(new Gateway.OperationResponse(true, "Order cancelled successfully"));
        return Behaviors.stopped();
    }
} 