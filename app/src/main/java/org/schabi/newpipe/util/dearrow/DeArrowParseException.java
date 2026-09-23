package org.schabi.newpipe.util.dearrow;

/**
 * Thrown when a DeArrow API response cannot be understood.
 *
 * <p>This is deliberately a hard failure rather than a silent null: a response we cannot parse
 * means the API changed shape, and swallowing that would leave DeArrow quietly doing nothing
 * forever with no way to notice. It is caught exactly once, at the network boundary in
 * {@link DeArrowCache}, where the consequence is documented.</p>
 */
public class DeArrowParseException extends Exception {

    public DeArrowParseException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public DeArrowParseException(final String message) {
        super(message);
    }
}
