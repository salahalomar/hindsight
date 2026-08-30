package sample.shop;

import org.springframework.stereotype.Service;

@Service
class CheckoutService {

    private final CustomerRepository customers;
    private final ShippingCalculator shipping;

    CheckoutService(CustomerRepository customers, ShippingCalculator shipping) {
        this.customers = customers;
        this.shipping = shipping;
    }

    Quote checkout(String orderId) {
        Customer customer = customers.findById(orderId);
        return shipping.quote(customer);
    }
}
