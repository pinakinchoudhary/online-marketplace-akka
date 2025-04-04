package pods.example;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;

import java.util.List;
import java.util.Map;

public class Order extends AbstractBehavior<Order.Command> {
    public static final EntityTypeKey<Command> ENTITY_KEY = EntityTypeKey.create(Command.class, "Order");

    public interface Command {}

    public static class CreateOrder implements Command {
        public final String orderId;
        public final String userId;
        public final Integer totalPrice;
        public final String status;
        public final List<OrderItem.OrderItemResponse> items;
        public final ActorRef<Response> replyTo;

        public CreateOrder(String orderId, String userId, Integer totalPrice, String status, List<OrderItem.OrderItemResponse> items, ActorRef<Response> replyTo) {
            this.orderId = orderId;
            this.userId = userId;
            this.totalPrice = totalPrice;
            this.status = status;
            this.items = items;
            this.replyTo = replyTo;
        }
    }

    public static class GetOrder implements Command {
        public final ActorRef<Response> replyTo;
        public GetOrder(ActorRef<Response> replyTo) {
            this.replyTo = replyTo;
        }
    }

    public static class UpdateOrder implements Command {
        public final String status;
        public final ActorRef<Response> replyTo;
        public UpdateOrder(String status, ActorRef<Response> replyTo) {
            this.status = status;
            this.replyTo = replyTo;
        }
    }

    public static class DeleteOrder implements Command {
        public final ActorRef<Response> replyTo;
        public DeleteOrder(ActorRef<Response> replyTo) {
            this.replyTo = replyTo;
        }
    }

    public interface Response extends Gateway.Command {}

    public static class OrderResponse implements Response {
        public final String orderId;
        public final String userId;
        public final Integer totalPrice;
        public final String status;
        public final List<OrderItem.OrderItemResponse> items;

        public OrderResponse(String orderId, String userId, Integer totalPrice, String status, List<OrderItem.OrderItemResponse> items) {
            this.orderId = orderId;
            this.userId = userId;
            this.totalPrice = totalPrice;
            this.status = status;
            this.items = items;
        }
    }

    public static class OrderUpdated implements Response {
        public final boolean success;
        public final String message;

        public OrderUpdated(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    private String orderId;
    private String userId;
    private Integer totalPrice;
    private String status;
    private List<OrderItem.OrderItemResponse> items;

    public static Behavior<Command> create(String orderId, String userId, Integer totalPrice, String status, List<OrderItem.OrderItemResponse> items) {
        return Behaviors.setup(context -> new Order(context, orderId, userId, totalPrice, status, items));
    }

    private Order(ActorContext<Command> context, String orderId, String userId, Integer totalPrice, String status, List<OrderItem.OrderItemResponse> items) {
        super(context);
        this.orderId = orderId;
        this.userId = userId;
        this.totalPrice = totalPrice;
        this.status = status;
        this.items = items;
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(CreateOrder.class, this::onCreateOrder)
            .onMessage(GetOrder.class, this::onGetOrder)
            .onMessage(UpdateOrder.class, this::onUpdateOrder)
            .onMessage(DeleteOrder.class, this::onDeleteOrder)
            .build();
    }

    private Behavior<Command> onCreateOrder(CreateOrder command) {
        this.orderId = command.orderId;
        this.userId = command.userId;
        this.totalPrice = command.totalPrice;
        this.status = command.status;
        this.items = command.items;
        command.replyTo.tell(new OrderUpdated(true, "Order created successfully"));
        return this;
    }

    private Behavior<Command> onGetOrder(GetOrder command) {
        if (orderId == null) {
            command.replyTo.tell(new OrderResponse(null, null, null, null, null));
            return this;
        }
        command.replyTo.tell(new OrderResponse(orderId, userId, totalPrice, status, items));
        return this;
    }

    private Behavior<Command> onUpdateOrder(UpdateOrder command) {
        System.out.println("[Order] Received update command for order: " + orderId + ", new status: " + command.status);
        
        // Check if order exists
        if (orderId == null) {
            System.out.println("[Order] Order not found");
            command.replyTo.tell(new OrderUpdated(false, "Order not found"));
            return this;
        }

        // Check if the requested status is DELIVERED
        if (!command.status.equals("DELIVERED")) {
            System.out.println("[Order] Invalid status update requested: " + command.status + ". Only DELIVERED status is allowed.");
            command.replyTo.tell(new OrderUpdated(false, "Invalid status. Only DELIVERED status is allowed."));
            return this;
        }

        // Check if current status is PLACED
        if (!status.equals("PLACED")) {
            System.out.println("[Order] Cannot update order status. Current status must be PLACED, but is: " + status);
            command.replyTo.tell(new OrderUpdated(false, "Cannot update order status. Current status must be PLACED, but is: " + status));
            return this;
        }

        System.out.println("[Order] Updating order status from " + status + " to " + command.status);
        status = command.status;
        command.replyTo.tell(new OrderUpdated(true, "Order status updated successfully"));
        return this;
    }

    private Behavior<Command> onDeleteOrder(DeleteOrder command) {
        System.out.println("[Order] Received delete command for order: " + orderId);
        
        // Check if order exists
        if (orderId == null) {
            System.out.println("[Order] Order not found");
            command.replyTo.tell(new OrderUpdated(false, "Order not found"));
            return this;
        }
        if (status.equals("DELIVERED")) {
            System.out.println("[Order] Cannot cancel delivered order: " + orderId);
            command.replyTo.tell(new OrderUpdated(false, "Cannot cancel delivered order"));
            return this;
        } else if (status.equals("CANCELLED")) {
            System.out.println("[Order] Cannot cancel already cancelled order: " + orderId);
            command.replyTo.tell(new OrderUpdated(false, "Cannot cancel already cancelled order"));
            return this;
        }
        
        System.out.println("[Order] Cancelling order: " + orderId);
        status = "CANCELLED";
        command.replyTo.tell(new OrderUpdated(true, "Order cancelled successfully"));
        return this;
    }

    private boolean isValidStatus(String status) {
        return status.equals("PLACED") || status.equals("CANCELLED") || status.equals("DELIVERED");
    }
}