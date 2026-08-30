package sample.shop;

/** Carries a nullable address, which is where this service goes wrong. */
record Customer(String id, String name, Address address) {
}
