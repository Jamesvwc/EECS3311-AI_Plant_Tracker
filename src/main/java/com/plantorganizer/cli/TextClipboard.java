package com.plantorganizer.cli;

/**
 * Where Ctrl+C at the prompt copies to and Ctrl+V pastes from. In Agent.jar
 * this is the computer's clipboard ({@link SystemClipboard}), so text copied
 * at the prompt can be pasted anywhere else, and the other way round.
 */
public interface TextClipboard {

    void copy(String text);

    /** The clipboard's text; "" when it holds no text. */
    String paste();

    /** A clipboard that only this program sees (the tests use it). */
    static TextClipboard inMemory() {
        return new TextClipboard() {
            private String text = "";

            @Override
            public void copy(String copied) {
                text = copied;
            }

            @Override
            public String paste() {
                return text;
            }
        };
    }
}
