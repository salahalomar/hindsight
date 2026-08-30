package dev.hindsight.playground;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnippetTest {

    private static final String RUNNABLE = """
            public class Orders {
                public static void main(String[] args) {
                    System.out.println(1);
                }
            }
            """;

    @DisplayName("there has to be something to run")
    @ParameterizedTest(name = "[{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\n\n"})
    void rejectsNothing(String pasted) {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Snippet.of(pasted))
                .getMessage().contains("nothing to run"));
    }

    @Test
    @DisplayName("a snippet has to be a class")
    void rejectsWhatIsNotAClass() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Snippet.of("int x = 1;")).getMessage().contains("class"));
    }

    @Test
    @DisplayName("a snippet has to have a way in, because execution is what gets recorded")
    void rejectsAClassWithNoMain() {
        String message = assertThrows(IllegalArgumentException.class,
                () -> Snippet.of("public class Orders { int total() { return 1; } }")).getMessage();

        assertTrue(message.contains("main method"), message);
        assertTrue(message.contains("records what a program did"), message);
    }

    @Test
    @DisplayName("the class name is taken from the declaration, so the file can be named for it")
    void findsTheClassName() {
        assertEquals("Orders", Snippet.of(RUNNABLE).className());
        assertEquals("Orders.java", Snippet.of(RUNNABLE).fileName());
        assertEquals("Orders", Snippet.of("final class Orders { static void main(String[] a) {} }").className());
        assertEquals("Orders", Snippet.of("class Orders { static void main(String[] a) {} }").className());
    }

    @Test
    @DisplayName("a snippet is moved into the package the agent watches")
    void putsTheSnippetSomewhereInstrumentable() {
        Snippet snippet = Snippet.of(RUNNABLE);

        // The default package produces binary names with no dots, which cannot match any package
        // prefix, so a snippet left there would run perfectly and record absolutely nothing.
        assertTrue(snippet.source().startsWith("package " + Snippet.PACKAGE + ";"), snippet.source());
        assertEquals(Snippet.PACKAGE + ".Orders", snippet.qualifiedName());
    }

    @Test
    @DisplayName("an author's own package is replaced rather than honoured")
    void replacesAnExistingPackage() {
        Snippet snippet = Snippet.of("package com.example.orders;\n" + RUNNABLE);

        assertEquals(1, snippet.source().lines().filter(line -> line.startsWith("package ")).count(),
                "two package declarations would not compile");
        assertTrue(snippet.source().startsWith("package " + Snippet.PACKAGE + ";"));
        assertTrue(snippet.source().contains("class Orders"));
    }

    @Test
    @DisplayName("only one line is added, so compiler diagnostics can be shifted back accurately")
    void reportsHowMuchItAdded() {
        Snippet snippet = Snippet.of(RUNNABLE);

        assertEquals(snippet.addedLines(),
                snippet.source().lines().count() - RUNNABLE.lines().count());
    }
}
