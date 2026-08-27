package dev.hindsight.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageScopeTest {

    @DisplayName("an unconfigured scope selects nothing")
    @ParameterizedTest(name = "[{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", ",", " , , "})
    void nothingIsSelectedByDefault(String configured) {
        PackageScope scope = PackageScope.parse(configured);

        assertTrue(scope.isEmpty());
        assertFalse(scope.includes("com.example.Anything"));
    }

    @Test
    @DisplayName("classes under a selected package are included")
    void includesTheSelectedPackage() {
        PackageScope scope = PackageScope.parse("com.example");

        assertTrue(scope.includes("com.example.Order"));
        assertTrue(scope.includes("com.example.orders.OrderService"));
        assertTrue(scope.includes("com.example.orders.OrderService$Inner"));
    }

    @Test
    @DisplayName("matching stops at a package boundary, not at a character")
    void doesNotBleedIntoNeighbouringPackages() {
        PackageScope scope = PackageScope.parse("com.example");

        // A bare startsWith would happily drag this in, and the user never asked for it.
        assertFalse(scope.includes("com.exampleother.Order"));
        assertFalse(scope.includes("com.examples.Order"));
        assertFalse(scope.includes("org.example.Order"));
    }

    @Test
    @DisplayName("several packages can be selected at once")
    void supportsMultiplePrefixes() {
        PackageScope scope = PackageScope.parse("com.example,org.acme.billing");

        assertTrue(scope.includes("com.example.Order"));
        assertTrue(scope.includes("org.acme.billing.Invoice"));
        assertFalse(scope.includes("org.acme.shipping.Parcel"));
    }

    @DisplayName("whitespace and trailing dots are how people actually write these")
    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"com.example", " com.example ", "com.example.", "com.example..",
            "com.example , ", ",com.example"})
    void toleratesTheObviousSpellings(String configured) {
        assertTrue(PackageScope.parse(configured).includes("com.example.Order"));
    }

    @Test
    @DisplayName("a repeated package is not matched twice over")
    void deduplicates() {
        assertEquals("com.example", PackageScope.parse("com.example,com.example.").describe());
    }

    @Test
    @DisplayName("an unnamed class is never in scope")
    void nullIsNeverIncluded() {
        assertFalse(PackageScope.parse("com.example").includes(null));
    }

    @Test
    @DisplayName("the scope describes itself the way it was written")
    void describesItself() {
        assertEquals("com.example, org.acme", PackageScope.parse("com.example, org.acme").describe());
        assertEquals("nothing", PackageScope.none().describe());
    }
}
