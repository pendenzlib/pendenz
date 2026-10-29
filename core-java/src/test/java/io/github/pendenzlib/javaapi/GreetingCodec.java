package io.github.pendenzlib.javaapi;

import io.github.pendenzlib.PayloadCodec;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A hand-written codec, as a Java team without a serialization library would write one. Tolerates
 * the whitespace PostgreSQL's jsonb adds when it normalises the stored text.
 */
final class GreetingCodec implements PayloadCodec<GreetingCodec.Greeting> {

    record Greeting(String name) {}

    static final GreetingCodec INSTANCE = new GreetingCodec();

    private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]*)\"");

    @Override
    public String encode(Greeting payload) {
        return "{\"name\":\"" + payload.name() + "\"}";
    }

    @Override
    public Greeting decode(String text) {
        Matcher matcher = NAME.matcher(text);
        if (!matcher.find()) {
            throw new IllegalArgumentException("not a greeting: " + text);
        }
        return new Greeting(matcher.group(1));
    }
}
