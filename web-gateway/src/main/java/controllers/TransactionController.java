package controllers;

import com.example.auction.item.api.ItemStatus;
import com.example.auction.pagination.PaginatedSequence;
import com.example.auction.transaction.api.*;
import com.example.auction.user.api.User;
import com.example.auction.user.api.UserService;
import com.lightbend.lagom.javadsl.api.transport.TransportException;
import com.typesafe.config.Config;
import play.data.Form;
import play.data.FormFactory;
import play.i18n.MessagesApi;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Call;
import play.mvc.Http;
import play.mvc.Result;

import javax.inject.Inject;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static com.example.auction.security.ClientSecurity.authenticate;

public class TransactionController extends AbstractController {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_PAGE_SIZE = 15;

    private final FormFactory formFactory;
    private final TransactionService transactionService;

    private final Boolean showInlineInstruction;
    private HttpExecutionContext ec;

    @Inject
    public TransactionController(Config config,
                                 MessagesApi messagesApi,
                                 UserService userService,
                                 FormFactory formFactory,
                                 TransactionService transactionService,
                                 HttpExecutionContext ec) {
        super(messagesApi, userService);
        this.formFactory = formFactory;
        this.transactionService = transactionService;

        showInlineInstruction = config.getBoolean("online-auction.instruction.show");
        this.ec = ec;
    }

    public CompletionStage<Result> myTransactions(String statusParam, int page, int pageSize) {
        TransactionInfoStatus status = TransactionInfoStatus.valueOf(statusParam.toUpperCase(Locale.ENGLISH));
        return requireUser(ctx(),
                userId -> loadNav(userId).thenCombineAsync(
                        getTransactionsForUser(userId, status, page, pageSize), (nav, items) ->
                                ok(views.html.myTransactions.render(showInlineInstruction, status, items, nav)),
                        ec.current())
        );
    }

    private CompletionStage<PaginatedSequence<TransactionSummary>> getTransactionsForUser(
            UUID userId, TransactionInfoStatus status, int page, int pageSize) {
        return transactionService
                .getTransactionsForUser(status, Optional.of(page), Optional.of(pageSize))
                .handleRequestHeader(authenticate(userId))
                .invoke();
    }

    public static Call transactionsPage(TransactionInfoStatus status) {
        return transactionsPage(status, DEFAULT_PAGE, DEFAULT_PAGE_SIZE);
    }

    public static Call transactionsPage(TransactionInfoStatus status, int page, int pageSize) {
        return routes.TransactionController.myTransactions(status.name().toLowerCase(Locale.ENGLISH), page, pageSize);
    }

    public CompletionStage<Result> getTransaction(String id) {
        return requireUser(ctx(), user ->
                loadNav(user).thenComposeAsync(nav -> {
                    UUID itemId = UUID.fromString(id);
                    CompletionStage<TransactionInfo> transactionFuture = transactionService.getTransaction(itemId).handleRequestHeader(authenticate(user)).invoke();
                    return transactionFuture.handle((transaction, exception) -> {
                        if (exception == null) {
                            Optional<User> seller = Optional.empty();
                            Optional<User> winner = Optional.empty();
                            for (User u : nav.getUsers()) {
                                if (transaction.getCreator().equals(u.getId())) {
                                    seller = Optional.of(u);
                                }
                                if (transaction.getWinner().equals(u.getId())) {
                                    winner = Optional.of(u);
                                }
                            }
                            Currency currency = Currency.valueOf(transaction.getItemData().getCurrencyId());
                            return ok(views.html.transaction.render(showInlineInstruction, Optional.of(transaction), seller, winner, Optional.of(currency), Optional.empty(), nav));
                        } else {
                            String msg = ((TransportException) exception.getCause()).exceptionMessage().detail();
                            return ok(views.html.transaction.render(showInlineInstruction, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(msg), nav));
                        }
                    });
                }, ec.current())
        );
    }

    public CompletionStage<Result> submitDeliveryDetailsForm(String id) {
        return requireUser(ctx(), user ->
                loadNav(user).thenComposeAsync(nav -> {
                            UUID itemId = UUID.fromString(id);
                            CompletionStage<TransactionInfo> transactionFuture = transactionService.getTransaction(itemId).handleRequestHeader(authenticate(user)).invoke();
                            return transactionFuture.handle((transaction, exception) -> {
                                if (exception == null) {
                                    DeliveryDetailsForm form = new DeliveryDetailsForm();
                                    Optional<DeliveryInfo> maybeDeliveryInfo = transaction.getDeliveryInfo();
                                    if (maybeDeliveryInfo.isPresent()) {
                                        form.setAddressLine1(maybeDeliveryInfo.get().getAddressLine1());
                                        form.setAddressLine2(maybeDeliveryInfo.get().getAddressLine2());
                                        form.setCity(maybeDeliveryInfo.get().getCity());
                                        form.setState(maybeDeliveryInfo.get().getState());
                                        form.setPostalCode(maybeDeliveryInfo.get().getPostalCode());
                                        form.setCountry(maybeDeliveryInfo.get().getCountry());
                                    }
                                    return ok(
                                            views.html.deliveryDetails.render(
                                                    showInlineInstruction,
                                                    !transaction.getCreator().equals(user),
                                                    itemId,
                                                    formFactory.form(DeliveryDetailsForm.class).fill(form),
                                                    transaction.getStatus(),
                                                    Optional.empty(),
                                                    nav)
                                    );
                                } else {
                                    String msg = ((TransportException) exception.getCause()).exceptionMessage().detail();
                                    return ok(views.html.deliveryDetails.render(showInlineInstruction, false, itemId, formFactory.form(DeliveryDetailsForm.class), TransactionInfoStatus.NEGOTIATING_DELIVERY, Optional.of(msg), nav));
                                }
                            });
                        },
                        ec.current())
        );
    }

    public CompletionStage<Result> submitDeliveryDetails(String id, String transactionStatus, boolean isBuyer) {
        Http.Context ctx = ctx();
        return requireUser(ctx(), user -> {

            Form<DeliveryDetailsForm> form = formFactory.form(DeliveryDetailsForm.class).bindFromRequest(ctx.request());
            UUID itemId = UUID.fromString(id);
            TransactionInfoStatus status = TransactionInfoStatus.valueOf(transactionStatus);

            if (form.hasErrors()) {
                return loadNav(user).thenApplyAsync(nav ->
                                ok(views.html.deliveryDetails.render(showInlineInstruction, isBuyer, itemId, form, status, Optional.empty(), nav)),
                        ec.current()
                );
            } else {
                return transactionService.submitDeliveryDetails(itemId)
                        .handleRequestHeader(authenticate(user))
                        .invoke(fromForm(form.get()))
                        .handle((done, exception) -> {
                            if (exception == null) {
                                return CompletableFuture.completedFuture(redirect(routes.TransactionController.getTransaction(id)));
                            } else {
                                String msg = ((TransportException) exception.getCause()).exceptionMessage().detail();
                                return loadNav(user).thenApplyAsync(nav ->
                                                ok(views.html.deliveryDetails.render(showInlineInstruction, isBuyer, itemId, form, status, Optional.of(msg), nav)),
                                        ec.current());
                            }
                        }).thenComposeAsync(x -> x, ec.current());
            }
        });
    }

    private DeliveryInfo fromForm(DeliveryDetailsForm deliveryForm) {
        return new DeliveryInfo(
                deliveryForm.getAddressLine1(),
                deliveryForm.getAddressLine2(),
                deliveryForm.getCity(),
                deliveryForm.getState(),
                deliveryForm.getPostalCode(),
                deliveryForm.getCountry()
        );
    }
}
