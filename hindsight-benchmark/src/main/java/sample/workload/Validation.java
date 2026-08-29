package sample.workload;

import java.util.Set;

final class Validation {

    private static final Set<String> KNOWN = Set.of(
            "acme", "globex", "initech", "umbrella", "soylent", "hooli", "vehement", "massive");

    void validate(String customer, int quantity) {
        checkCustomer(customer);
        checkQuantity(quantity);
    }

    private void checkCustomer(String customer) {
        if (!KNOWN.contains(customer)) {
            throw new IllegalArgumentException("unknown customer: " + customer);
        }
    }

    private void checkQuantity(int quantity) {
        if (quantity < 1 || quantity > 64) {
            throw new IllegalArgumentException("quantity out of range: " + quantity);
        }
    }
}
