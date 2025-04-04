package pods.example;

import akka.actor.typed.ActorSystem;
import akka.http.javadsl.Http;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.MediaTypes;
import akka.http.javadsl.unmarshalling.Unmarshaller;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.concurrent.CompletionStage;

public class ExternalServiceClient {
    private final Http http;
    private final ObjectMapper objectMapper;
    private final String accountServiceBaseUrl;
    private final String walletServiceBaseUrl;
    private final ActorSystem<?> system;

    public ExternalServiceClient(ActorSystem<?> system, String accountServiceBaseUrl, String walletServiceBaseUrl) {
        this.http = Http.get(system);
        this.objectMapper = new ObjectMapper();
        this.accountServiceBaseUrl = accountServiceBaseUrl;
        this.walletServiceBaseUrl = walletServiceBaseUrl;
        this.system = system;
    }

    public CompletionStage<UserResponse> getUser(Integer userId) {
        HttpRequest request = HttpRequest.GET(accountServiceBaseUrl + "/users/" + userId);

        return http.singleRequest(request)
            .thenCompose(response -> Unmarshaller.entityToString()
                .unmarshal(response.entity(), system)
                .thenApply(body -> {
                    try {
                        return objectMapper.readValue(body, UserResponse.class);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to parse user response", e);
                    }
                }));
    }

    public CompletionStage<WalletResponse> getWallet(Integer userId) {
        HttpRequest request = HttpRequest.GET(walletServiceBaseUrl + "/wallets/" + userId);

        return http.singleRequest(request)
            .thenCompose(response -> Unmarshaller.entityToString()
                .unmarshal(response.entity(), system)
                .thenApply(body -> {
                    try {
                        return objectMapper.readValue(body, WalletResponse.class);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to parse wallet response", e);
                    }
                }));
    }

    public CompletionStage<Boolean> updateWallet(Integer userId, String action, Integer amount) {
        System.out.println("[ExternalServiceClient] Updating wallet for user: " + userId + ", action: " + action + ", amount: " + amount);
        String body = String.format("{\"action\":\"%s\",\"amount\":%d}", action, amount);
        HttpRequest request = HttpRequest.PUT(walletServiceBaseUrl + "/wallets/" + userId)
            .withEntity(MediaTypes.APPLICATION_JSON.toContentType(), body);

        System.out.println("[ExternalServiceClient] Sending request to: " + request.getUri());
        
        return http.singleRequest(request)
            .thenApply(response -> {
                System.out.println("[ExternalServiceClient] Received response with status: " + response.status());
                return response.status().isSuccess();
            })
            .exceptionally(throwable -> {
                System.out.println("[ExternalServiceClient] Error updating wallet: " + throwable.getMessage());
                System.out.println("[ExternalServiceClient] Stack trace: " + Arrays.toString(throwable.getStackTrace()));
                return false;
            });
    }

    public CompletionStage<Boolean> updateDiscount(Integer userId) {
        String body = "{\"discount_availed\":true}";
        HttpRequest request = HttpRequest.PUT(accountServiceBaseUrl + "/updateDiscount/" + userId)
            .withEntity(MediaTypes.APPLICATION_JSON.toContentType(), body);

        return http.singleRequest(request)
            .thenApply(response -> response.status().isSuccess());
    }

    public static class UserResponse {
        public Integer id;
        public String name;
        public String email;
        public Boolean discount_availed;
    }

    public static class WalletResponse {
        public Integer user_id;
        public Integer balance;
    }
} 