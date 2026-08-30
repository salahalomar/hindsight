package sample.shop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A deliberately small shop service containing one deliberately planted bug.
 *
 * <p>The bug is chosen for a specific reason. {@link AddressBook#addressFor} returns {@code null}
 * for an unknown order instead of failing or returning an empty value. That null is put inside a
 * {@link Customer}, handed back up, and only dereferenced later by
 * {@link ShippingCalculator#quote}. By the time the exception is thrown, the method that produced
 * the null has already returned and is nowhere on the stack.
 *
 * <p>That is the case a stack trace cannot solve, and it is the whole argument for recording. The
 * trace shows {@code AddressBook.addressFor returned null} several events before the throw, in a
 * frame the stack trace does not mention at all.
 */
/*
 * The console lives in demo.console, outside this package tree, so that pointing the agent at
 * sample.shop does not record the console reading traces into the traces. Component scanning
 * starts from this class's own package, so that package has to be named explicitly: the isolation
 * that keeps the console out of the recording also keeps it out of the scan.
 */
@SpringBootApplication(scanBasePackages = {"sample.shop", "demo.console"})
public class ShopApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShopApplication.class, args);
    }
}
