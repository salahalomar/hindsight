package sample.workload;

import java.util.Map;

final class Pricing {

    private static final Map<String, Long> TIERS = Map.of(
            "acme", 1L, "globex", 2L, "initech", 3L, "umbrella", 4L,
            "soylent", 1L, "hooli", 2L, "vehement", 3L, "massive", 4L);

    long unitPrice(int orderId) {
        return base(orderId) + surcharge(orderId);
    }

    private long base(int orderId) {
        return 1000L + (orderId % 97) * 7L;
    }

    private long surcharge(int orderId) {
        return (orderId % 13) * 3L;
    }

    long discountFor(String customer, int quantity) {
        return tier(customer) * volume(quantity);
    }

    private long tier(String customer) {
        return TIERS.getOrDefault(customer, 0L);
    }

    private long volume(int quantity) {
        return quantity > 8 ? 3L : 1L;
    }

    long total(long unit, int quantity, long discount) {
        return unit * quantity - discount;
    }
}
