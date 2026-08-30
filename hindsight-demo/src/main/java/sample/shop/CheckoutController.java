package sample.shop;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The outermost frame the agent instruments. Everything above it belongs to Spring and Tomcat and
 * is outside the recorded package, so a trace begins here and covers exactly one request.
 */
@RestController
class CheckoutController {

    private final CheckoutService checkout;

    CheckoutController(CheckoutService checkout) {
        this.checkout = checkout;
    }

    @GetMapping("/checkout/{orderId}")
    Quote checkout(@PathVariable String orderId) {
        return checkout.checkout(orderId);
    }
}
