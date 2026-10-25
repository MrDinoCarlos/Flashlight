package es.mrdino.flashlight;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Human-friendly durations with a strict one-tick to one-hour bound. */
public final class CommandDuration {
    private static final Pattern FORMAT = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)([st]?)");
    private CommandDuration() { }

    public static int ticks(String input) {
        Matcher matcher = FORMAT.matcher(input.toLowerCase(Locale.ROOT));
        if (!matcher.matches()) throw new IllegalArgumentException("Use seconds or ticks, e.g. 3s, 0.5s or 40t.");
        BigDecimal ticks = new BigDecimal(matcher.group(1));
        if (!matcher.group(2).equals("t")) ticks = ticks.multiply(BigDecimal.valueOf(20));
        if (ticks.compareTo(BigDecimal.ONE) < 0 || ticks.compareTo(BigDecimal.valueOf(72_000)) > 0) {
            throw new IllegalArgumentException("Duration must be between 1 tick and 1 hour.");
        }
        return ticks.setScale(0, RoundingMode.CEILING).intValueExact();
    }
}
