package sample.workload;

/**
 * The code being measured: a small service layer with the shape real ones have.
 *
 * <p>Deliberately neither trivial nor heavy, and the balance matters more than it looks. An earlier
 * version did integer arithmetic only; the compiler inlined the entire call chain into about
 * thirteen nanoseconds, which made the uninstrumented baseline faster than anything real and the
 * agent's cost look correspondingly ruinous. Anything touching a database would do the opposite and
 * drown the agent's cost in I/O. So this validates against a set, looks up a map, and builds a
 * couple of strings, which is what a request handler actually spends its time on.
 *
 * <p>Even so, the ratio measured here belongs to this workload. The transferable number is the
 * per-instrumented-call figure, which anyone can multiply by the call count of their own code.
 */
public final class OrderService {

    private final Validation validation = new Validation();
    private final Pricing pricing = new Pricing();
    private final Formatting formatting = new Formatting();

    public long handle(int orderId, String customer, int quantity) {
        validation.validate(customer, quantity);
        long unit = pricing.unitPrice(orderId);
        long discount = pricing.discountFor(customer, quantity);
        long total = pricing.total(unit, quantity, discount);
        String reference = formatting.reference(orderId, total);
        return total + reference.length();
    }
}
