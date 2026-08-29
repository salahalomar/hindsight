package sample.workload;

final class Formatting {

    String reference(int orderId, long total) {
        return prefix(orderId) + "-" + checksum(orderId, total);
    }

    private String prefix(int orderId) {
        return "ORD-" + (orderId & 0xffff);
    }

    private long checksum(int orderId, long total) {
        return (orderId * 31L + total) & 0xffffff;
    }
}
