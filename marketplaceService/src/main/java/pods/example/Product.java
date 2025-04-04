package pods.example;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;

public class Product extends AbstractBehavior<Product.Command> {
    public static final EntityTypeKey<Command> ENTITY_KEY = EntityTypeKey.create(Command.class, "Product");

    public interface Command {}

    public static class CreateProduct implements Command {
        public final String name;
        public final String description;
        public final Integer price;
        public final Integer stock_quantity;
        public final ActorRef<Response> replyTo;

        public CreateProduct(String name, String description, Integer price, Integer stock_quantity, ActorRef<Response> replyTo) {
            this.name = name;
            this.description = description;
            this.price = price;
            this.stock_quantity = stock_quantity;
            this.replyTo = replyTo;
        }
    }

    public static class GetProduct implements Command {
        public final ActorRef<Response> replyTo;
        public GetProduct(ActorRef<Response> replyTo) {
            this.replyTo = replyTo;
        }
    }

    public static class UpdateStock implements Command {
        public final int quantity;
        public final ActorRef<Response> replyTo;
        public UpdateStock(int quantity, ActorRef<Response> replyTo) {
            this.quantity = quantity;
            this.replyTo = replyTo;
        }
    }

    public interface Response extends Gateway.Command {}

    public static class ProductResponse implements Response {
        public final String id;
        public final String name;
        public final String description;
        public final Integer price;
        public final Integer stock_quantity;

        public ProductResponse(String id, String name, String description, Integer price, Integer stock_quantity) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.price = price;
            this.stock_quantity = stock_quantity;
        }
    }

    public static class StockUpdated implements Response {
        public final boolean success;
        public final String message;

        public StockUpdated(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    private final String id;
    private String name;
    private String description;
    private Integer price;
    private Integer stock_quantity;

    public static Behavior<Command> create(String id, String name, String description, Integer price, Integer stock_quantity) {
        return Behaviors.setup(context -> new Product(context, id, name, description, price, stock_quantity));
    }

    private Product(ActorContext<Command> context, String id, String name, String description, Integer price, Integer stock_quantity) {
        super(context);
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.stock_quantity = stock_quantity;
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(CreateProduct.class, this::onCreateProduct)
            .onMessage(GetProduct.class, this::onGetProduct)
            .onMessage(UpdateStock.class, this::onUpdateStock)
            .build();
    }

    private Behavior<Command> onCreateProduct(CreateProduct command) {
        this.name = command.name;
        this.description = command.description;
        this.price = command.price;
        this.stock_quantity = command.stock_quantity;
        command.replyTo.tell(new ProductResponse(id, name, description, price, stock_quantity));
        return this;
    }

    private Behavior<Command> onGetProduct(GetProduct command) {
        command.replyTo.tell(new ProductResponse(id, name, description, price, stock_quantity));
        return this;
    }

    private Behavior<Command> onUpdateStock(UpdateStock command) {
        System.out.println("[Product] Updating stock for product: " + id + ", quantity change: " + command.quantity);
        System.out.println("[Product] Current stock: " + stock_quantity);
        
        if (command.quantity < 0 && Math.abs(command.quantity) > stock_quantity) {
            System.out.println("[Product] Insufficient stock for product: " + id);
            command.replyTo.tell(new StockUpdated(false, "Insufficient stock"));
        } else {
            stock_quantity += command.quantity;
            System.out.println("[Product] Stock updated successfully for product: " + id + ", new stock: " + stock_quantity);
            command.replyTo.tell(new StockUpdated(true, "Stock updated successfully"));
        }
        return this;
    }
} 