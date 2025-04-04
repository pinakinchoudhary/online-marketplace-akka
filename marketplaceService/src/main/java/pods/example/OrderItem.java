package pods.example;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;
import com.fasterxml.jackson.annotation.JsonProperty;

public class OrderItem extends AbstractBehavior<OrderItem.Command> {
    public static final EntityTypeKey<Command> ENTITY_KEY = EntityTypeKey.create(Command.class, "OrderItem");

    public interface Command {}

    public static class GetOrderItem implements Command {
        public final ActorRef<Response> replyTo;
        public GetOrderItem(ActorRef<Response> replyTo) {
            this.replyTo = replyTo;
        }
    }

    public interface Response extends Gateway.Command {}

    public static class OrderItemResponse implements Response {
        public final Integer id;
        @JsonProperty("order_id")
        public final Integer orderId;
        @JsonProperty("product_id")
        public final Integer productId;
        public final Integer quantity;

        public OrderItemResponse(Integer id, Integer orderId, Integer productId, Integer quantity) {
            this.id = id;
            this.orderId = orderId;
            this.productId = productId;
            this.quantity = quantity;
        }
    }

    private final Integer id;
    private final Integer orderId;
    private final Integer productId;
    private final Integer quantity;

    public static Behavior<Command> create(Integer id, Integer orderId, Integer productId, Integer quantity) {
        return Behaviors.setup(context -> new OrderItem(context, id, orderId, productId, quantity));
    }

    private OrderItem(ActorContext<Command> context, Integer id, Integer orderId, Integer productId, Integer quantity) {
        super(context);
        this.id = id;
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(GetOrderItem.class, this::onGetOrderItem)
            .build();
    }

    private Behavior<Command> onGetOrderItem(GetOrderItem command) {
        command.replyTo.tell(new OrderItemResponse(id, orderId, productId, quantity));
        return this;
    }
} 