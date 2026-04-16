package db.sql.lexer;

/**
 * A single lexical unit produced by the Lexer.
 *
 * Immutable. Carries:
 *   - type   : what kind of token this is
 *   - value  : the raw string from the source (e.g. "SELECT", "42", "users")
 *   - line   : 1-based line number (for error messages)
 *   - column : 1-based column number (for error messages)
 *
 * Examples:
 *   Token(SELECT,          "SELECT",  line=1, col=1)
 *   Token(IDENTIFIER,      "users",   line=1, col=13)
 *   Token(INTEGER_LITERAL, "42",      line=1, col=25)
 *   Token(EOF,             "",        line=1, col=30)
 */
public final class Token {

    private final TokenType type;
    private final String value;
    private final int line;
    private final int column;

    public Token(TokenType type, String value, int line, int column) {
        this.type   = type;
        this.value  = value;
        this.line   = line;
        this.column = column;
    }

    public TokenType getType()   { return type; }
    public String    getValue()  { return value; }
    public int       getLine()   { return line; }
    public int       getColumn() { return column; }

    /** Convenience: is this token of a given type? */
    public boolean is(TokenType t) { return this.type == t; }

    /** Convenience: is this token one of several types? */
    public boolean isAny(TokenType... types) {
        for (TokenType t : types) {
            if (this.type == t) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return String.format("Token(%s, \"%s\", %d:%d)",
            type, value, line, column);
    }
}