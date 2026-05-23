package db.sql.parser;

/**
 * Thrown when the parser encounters a token it did not expect.
 * Unchecked so it doesn't pollute every calling method's signature.
 * Carries line and column for useful error messages.
 */
public class ParseException extends RuntimeException {

    private final int line;
    private final int column;

    public ParseException(String message, int line, int column) {
        super(String.format("Parse error at %d:%d — %s", line, column, message));
        this.line   = line;
        this.column = column;
    }

    public int getLine()   { return line; }
    public int getColumn() { return column; }
}