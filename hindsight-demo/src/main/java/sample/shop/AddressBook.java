package sample.shop;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Where the bug lives.
 *
 * <p>A lookup miss returns {@code null}. Nothing fails here, nothing is logged here, and this
 * method has returned long before anything goes wrong. It will not appear in the stack trace.
 */
@Component
class AddressBook {

    private static final Map<String, Address> ADDRESSES = Map.of(
            "order-1", new Address("14 Bridge Street", "EH1 1AA"),
            "order-3", new Address("2 Quay Road", "G2 4BB"));

    Address addressFor(String orderId) {
        return ADDRESSES.get(orderId);
    }
}
