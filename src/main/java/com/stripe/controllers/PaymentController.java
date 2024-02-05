package com.stripe.controllers;

import com.stripe.CustomerUtil;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.*;
import com.stripe.model.checkout.Session;
import com.stripe.param.*;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.param.checkout.SessionCreateParams.LineItem.PriceData;
import com.stripe.param.checkout.SessionCreateParams.LineItem.PriceData.ProductData;
import com.stripe.param.checkout.SessionCreateParams.LineItem.PriceData.Recurring;
import com.stripe.repository.ProductDAO;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import com.stripe.service.StripeService;
import com.stripe.utils.Response;

import java.text.SimpleDateFormat;
import java.util.*;

@RestController
@CrossOrigin
public class PaymentController {

    @Value("${stripe.key.public}")
    private String API_PUBLIC_KEY;

    @Value("${stripe.key.secret}")
    private String API_SECRET_KEY;


    @Value("${client.base-url}")
    private String CLIENT_BASE_URL;

    private final StripeService stripeService;

    @Autowired
    public PaymentController(StripeService stripeService) {
        this.stripeService = stripeService;
    }

    @GetMapping("/")
    public String homepage() {
        return "homepage";
    }

    @Data
    static
    class RequestDTO {
        private String customerEmail;
        private String customerName;
        private String subscriptionId;
        private boolean invoiceNeeded;
        private List<Product> items;
    }

    @PostMapping("/checkout/hosted")
    String hostedCheckout(@RequestBody RequestDTO requestDTO) throws StripeException {

        Stripe.apiKey = API_SECRET_KEY;
        String clientBaseURL = CLIENT_BASE_URL;

        // Start by finding an existing customer record from Stripe or creating a new one if needed
        Customer customer = CustomerUtil.findOrCreateCustomer(requestDTO.getCustomerEmail(), requestDTO.getCustomerName());

        // Next, create a checkout session by adding the details of the checkout
        SessionCreateParams.Builder paramsBuilder =
                SessionCreateParams.builder()
                        .setMode(SessionCreateParams.Mode.PAYMENT)
                        .setCustomer(customer.getId())
                        .setSuccessUrl(clientBaseURL + "/success?session_id={CHECKOUT_SESSION_ID}")
                        .setCancelUrl(clientBaseURL + "/failure");

        for (Product product : requestDTO.getItems()) {
            paramsBuilder.addLineItem(
                    SessionCreateParams.LineItem.builder()
                            .setQuantity(1L)
                            .setPriceData(
                                    PriceData.builder()
                                            .setProductData(
                                                    ProductData.builder()
                                                            .putMetadata("app_id", product.getId())
                                                            .setName(product.getName())
                                                            .build()
                                            )
                                            .setCurrency(ProductDAO.getProduct(product.getId()).getDefaultPriceObject().getCurrency())
                                            .setUnitAmountDecimal(ProductDAO.getProduct(product.getId()).getDefaultPriceObject().getUnitAmountDecimal())
                                            .build())
                            .build());
        }

        if (requestDTO.isInvoiceNeeded()) {
            paramsBuilder.setInvoiceCreation(SessionCreateParams.InvoiceCreation.builder().setEnabled(true).build());
        }
        Session session = Session.create(paramsBuilder.build());

        return session.getUrl();
    }

    @PostMapping("/checkout/integrated")
    String integratedCheckout(@RequestBody RequestDTO requestDTO) throws StripeException {

        Stripe.apiKey = API_SECRET_KEY;

        // Start by finding existing customer or creating a new one if needed
        Customer customer = CustomerUtil.findOrCreateCustomer(requestDTO.getCustomerEmail(), requestDTO.getCustomerName());

        PaymentIntent paymentIntent;
        // Create a PaymentIntent and send it's client secret to the client
        if (!requestDTO.isInvoiceNeeded()) {
            PaymentIntentCreateParams params =
                    PaymentIntentCreateParams.builder()
                            .setAmount(Long.parseLong(calculateOrderAmount(requestDTO.getItems())))
                            .setCurrency("usd")
                            .setCustomer(customer.getId())
                            .setAutomaticPaymentMethods(
                                    PaymentIntentCreateParams.AutomaticPaymentMethods
                                            .builder()
                                            .setEnabled(true)
                                            .build()
                            )
                            .build();

            paymentIntent = PaymentIntent.create(params);
        } else {
            // If invoice is needed, create the invoice object, add line items to it, and finalize it to create the PaymentIntent automatically
            InvoiceCreateParams invoiceCreateParams = new InvoiceCreateParams.Builder()
                    .setCustomer(customer.getId())
                    .build();

            Invoice invoice = Invoice.create(invoiceCreateParams);

            // Add each item to the invoice one by one
            for (Product product : requestDTO.getItems()) {

                // Look for existing Product in Stripe before creating a new one
                Product stripeProduct;

                ProductSearchResult results = Product.search(ProductSearchParams.builder()
                        .setQuery("metadata['app_id']:'" + product.getId() + "'")
                        .build());

                if (!results.getData().isEmpty())
                    stripeProduct = results.getData().get(0);
                else {

                    // If a product is not found in Stripe database, create it
                    ProductCreateParams productCreateParams = new ProductCreateParams.Builder()
                            .setName(product.getName())
                            .putMetadata("app_id", product.getId())
                            .build();

                    stripeProduct = Product.create(productCreateParams);
                }

                // Create an invoice line item using the product object for the line item
                InvoiceItemCreateParams invoiceItemCreateParams = new InvoiceItemCreateParams.Builder()
                        .setInvoice(invoice.getId())
                        .setQuantity(1L)
                        .setCustomer(customer.getId())
                        .setPriceData(
                                InvoiceItemCreateParams.PriceData.builder()
                                        .setProduct(stripeProduct.getId())
                                        .setCurrency(ProductDAO.getProduct(product.getId()).getDefaultPriceObject().getCurrency())
                                        .setUnitAmountDecimal(ProductDAO.getProduct(product.getId()).getDefaultPriceObject().getUnitAmountDecimal())
                                        .build())
                        .build();

                InvoiceItem.create(invoiceItemCreateParams);
            }

            // Mark the invoice as final so that a PaymentIntent is created for it
            invoice = invoice.finalizeInvoice();

            // Retrieve the payment intent object from the invoice
            paymentIntent = PaymentIntent.retrieve(invoice.getPaymentIntent());
        }


        // Send the client secret from the payment intent to the client
        return paymentIntent.getClientSecret();
    }

    @PostMapping("/subscriptions/new")
    String newSubscription(@RequestBody RequestDTO requestDTO) throws StripeException {

        Stripe.apiKey = API_SECRET_KEY;

        String clientBaseURL = CLIENT_BASE_URL;

        // Start by finding existing customer record from Stripe or creating a new one if needed
        Customer customer = CustomerUtil.findOrCreateCustomer(requestDTO.getCustomerEmail(), requestDTO.getCustomerName());

        // Next, create a checkout session by adding the details of the checkout
        SessionCreateParams.Builder paramsBuilder =
                SessionCreateParams.builder()
                        // For subscriptions, you need to set the mode as subscription
                        .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                        .setCustomer(customer.getId())
                        .setSuccessUrl(clientBaseURL + "/success?session_id={CHECKOUT_SESSION_ID}")
                        .setCancelUrl(clientBaseURL + "/failure");

        return addLineItem(requestDTO, paramsBuilder);
    }

    @PostMapping("/subscriptions/list")
    List<Map<String, String>> viewSubscriptions(@RequestBody RequestDTO requestDTO) throws StripeException {

        Stripe.apiKey = API_SECRET_KEY;

        // Start by finding existing customer record from Stripe
        Customer customer = CustomerUtil.findCustomerByEmail(requestDTO.getCustomerEmail());

        // If no customer record was found, no subscriptions exist either, so return an empty list
        if (customer == null) {
            return new ArrayList<>();
        }

        // Search for subscriptions for the current customer
        SubscriptionCollection subscriptions = Subscription.list(
                SubscriptionListParams.builder()
                        .setCustomer(customer.getId())
                        .build());

        List<Map<String, String>> response = new ArrayList<>();

        // For each subscription record, query its item records and collect in a list of objects to send to the client
        for (Subscription subscription : subscriptions.getData()) {
            SubscriptionItemCollection currSubscriptionItems =
                    SubscriptionItem.list(SubscriptionItemListParams.builder()
                            .setSubscription(subscription.getId())
                            .addExpand("data.price.product")
                            .build());

            for (SubscriptionItem item : currSubscriptionItems.getData()) {
                HashMap<String, String> subscriptionData = new HashMap<>();
                subscriptionData.put("appProductId", item.getPrice().getProductObject().getMetadata().get("app_id"));
                subscriptionData.put("subscriptionId", subscription.getId());
                subscriptionData.put("subscribedOn", new SimpleDateFormat("dd/MM/yyyy").format(new Date(subscription.getStartDate() * 1000)));
                subscriptionData.put("nextPaymentDate", new SimpleDateFormat("dd/MM/yyyy").format(new Date(subscription.getCurrentPeriodEnd() * 1000)));
                subscriptionData.put("price", item.getPrice().getUnitAmountDecimal().toString());

                if (subscription.getTrialEnd() != null && new Date(subscription.getTrialEnd() * 1000).after(new Date()))
                    subscriptionData.put("trialEndsOn", new SimpleDateFormat("dd/MM/yyyy").format(new Date(subscription.getTrialEnd() * 1000)));
                response.add(subscriptionData);
            }

        }

        return response;
    }

    @PostMapping("/subscriptions/cancel")
    String cancelSubscription(@RequestBody RequestDTO requestDTO) throws StripeException {
        Stripe.apiKey = API_SECRET_KEY;

        Subscription subscription =
                Subscription.retrieve(
                        requestDTO.getSubscriptionId()
                );

        Subscription deletedSubscription =
                subscription.cancel();

        return deletedSubscription.getStatus();
    }

    @PostMapping("/subscriptions/trial")
    String newSubscriptionWithTrial(@RequestBody RequestDTO requestDTO) throws StripeException {

        Stripe.apiKey = API_SECRET_KEY;

        String clientBaseURL = CLIENT_BASE_URL;

        // Start by finding existing customer record from Stripe or creating a new one if needed
        Customer customer = CustomerUtil.findOrCreateCustomer(requestDTO.getCustomerEmail(), requestDTO.getCustomerName());

        // Next, create a checkout session by adding the details of the checkout
        SessionCreateParams.Builder paramsBuilder =
                SessionCreateParams.builder()
                        .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                        .setCustomer(customer.getId())
                        .setSuccessUrl(clientBaseURL + "/success?session_id={CHECKOUT_SESSION_ID}")
                        .setCancelUrl(clientBaseURL + "/failure")
                        // For trials, you need to set the trial period in the session creation request
                        .setSubscriptionData(SessionCreateParams.SubscriptionData.builder().setTrialPeriodDays(30L).build());

        return addLineItem(requestDTO, paramsBuilder);
    }

    @PostMapping("/invoices/list")
    List<Map<String, String>> listInvoices(@RequestBody RequestDTO requestDTO) throws StripeException {

        Stripe.apiKey = API_SECRET_KEY;

        // Start by finding existing customer record from Stripe
        Customer customer = CustomerUtil.findCustomerByEmail(requestDTO.getCustomerEmail());

        // If no customer record was found, no subscriptions exist either, so return an empty list
        if (customer == null) {
            return new ArrayList<>();
        }

        // Search for invoices for the current customer
        Map<String, Object> invoiceSearchParams = new HashMap<>();
        invoiceSearchParams.put("customer", customer.getId());
        InvoiceCollection invoices =
                Invoice.list(invoiceSearchParams);

        List<Map<String, String>> response = new ArrayList<>();

        // For each invoice, extract its number, amount, and PDF URL to send to the client
        for (Invoice invoice : invoices.getData()) {
            HashMap<String, String> map = new HashMap<>();

            map.put("number", invoice.getNumber());
            map.put("amount", String.valueOf((invoice.getTotal() / 100f)));
            map.put("url", invoice.getInvoicePdf());

            response.add(map);
        }

        return response;
    }

    private String addLineItem(@RequestBody RequestDTO requestDTO, SessionCreateParams.Builder paramsBuilder) throws StripeException {
        for (Product product : requestDTO.getItems()) {
            paramsBuilder.addLineItem(
                    SessionCreateParams.LineItem.builder()
                            .setQuantity(1L)
                            .setPriceData(
                                    PriceData.builder()
                                            .setProductData(
                                                    ProductData.builder()
                                                            .putMetadata("app_id", product.getId())
                                                            .setName(product.getName())
                                                            .build()
                                            )
                                            .setCurrency(ProductDAO.getProduct(product.getId()).getDefaultPriceObject().getCurrency())
                                            .setUnitAmountDecimal(ProductDAO.getProduct(product.getId()).getDefaultPriceObject().getUnitAmountDecimal())
                                            .setRecurring(Recurring.builder().setInterval(Recurring.Interval.MONTH).build())
                                            .build())
                            .build());
        }

        Session session = Session.create(paramsBuilder.build());

        return session.getUrl();
    }

    static String calculateOrderAmount(List<Product> items) {
        long total = 0L;

        for (Product item: items) {
            // Look up the application database to find the prices for the products in the given list
            total += (long) ProductDAO.getProduct(item.getId()).getDefaultPriceObject().getUnitAmountDecimal().floatValue();
        }
        return String.valueOf(total);
    }

    @GetMapping("/subscription")
    public String subscriptionPage(Model model) {
        model.addAttribute("stripePublicKey", API_PUBLIC_KEY);
        return "subscription";
    }

    @GetMapping("/charge")
    public String chargePage(Model model) {
        model.addAttribute("stripePublicKey", API_PUBLIC_KEY);
        return "charge";
    }

    @PostMapping("/create-subscription")
    public @ResponseBody Response createSubscription(String email, String token, String plan, String coupon) {

        if (token == null || plan.isEmpty()) {
            return new Response(false, "Stripe payment token is missing. Please try again later.");
        }

        String customerId = stripeService.createCustomer(email, token);

        if (customerId == null) {
            return new Response(false, "An error accurred while trying to create customer");
        }

        String subscriptionId = stripeService.createSubscription(customerId, plan, coupon);

        if (subscriptionId == null) {
            return new Response(false, "An error accurred while trying to create subscription");
        }

        return new Response(true, "Success! your subscription id is " + subscriptionId);
    }

    @PostMapping("/cancel-subscription")
    public @ResponseBody Response cancelSubscription(String subscriptionId) {

        boolean subscriptionStatus = stripeService.cancelSubscription(subscriptionId);

        if (!subscriptionStatus) {
            return new Response(false, "Faild to cancel subscription. Please try again later");
        }

        return new Response(true, "Subscription cancelled successfully");
    }

    @PostMapping("/coupon-validator")
    public @ResponseBody Response couponValidator(String code) {

        Coupon coupon = stripeService.retriveCoupon(code);

        if (coupon != null && coupon.getValid()) {
            String details = (coupon.getPercentOff() == null ? "$" + (coupon.getAmountOff() / 100)
                    : coupon.getPercentOff() + "%") + "OFF" + coupon.getDuration();
            return new Response(true, details);
        }
        return new Response(false, "This coupon code is not available. This may be because it has expired or has "
                + "already been applied to your account.");
    }

    @PostMapping("/create-charge")
    public @ResponseBody Response createCharge(String email, String token) {

        if (token == null) {
            return new Response(false, "Stripe payment token is missing. please try again later.");
        }

        String chargeId = stripeService.createCharge(email, token, 999);// 9.99 usd

        if (chargeId == null) {
            return new Response(false, "An error accurred while trying to charge.");
        }

        // You may want to store charge id along with order information

        return new Response(true, "Success your charge id is " + chargeId);
    }

    @PostMapping("/create-checkout-session")
    public ResponseEntity<?> charge() {
        var url = stripeService.createCheckOutSession();
        return ResponseEntity.ok().body(url);
    }
}
