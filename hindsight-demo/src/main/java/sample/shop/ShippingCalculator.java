package sample.shop;

import org.springframework.stereotype.Component;

/**
 * Where the failure surfaces, which is not where it came from.
 *
 * <p>The stack trace will point here, and here is not the problem.
 */
@Component
class ShippingCalculator {

    Quote quote(Customer customer) {
        String postcode = customer.address().postcode();
        return new Quote(customer.id(), postcode, rateFor(postcode));
    }

    private long rateFor(String postcode) {
        return 350L + postcode.length() * 10L;
    }
}
