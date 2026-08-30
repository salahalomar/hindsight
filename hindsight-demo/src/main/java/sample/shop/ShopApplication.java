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
@SpringBootApplication
public class ShopApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShopApplication.class, args);
    }
}
