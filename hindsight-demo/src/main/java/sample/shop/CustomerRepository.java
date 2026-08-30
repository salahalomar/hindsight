package sample.shop;

import org.springframework.stereotype.Component;

/** Assembles a customer, address and all, without noticing that the address may be missing. */
@Component
class CustomerRepository {

    private final AddressBook addresses;

    CustomerRepository(AddressBook addresses) {
        this.addresses = addresses;
    }

    Customer findById(String orderId) {
        return new Customer(orderId, nameFor(orderId), addresses.addressFor(orderId));
    }

    private String nameFor(String orderId) {
        return "customer-" + orderId;
    }
}
