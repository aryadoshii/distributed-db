package db.sql.lexer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts a raw SQL string into a flat list of Tokens.
 *
 * Scanning rules (in priority order):
 *   1. Skip whitespace
 *   2. Single-line comment (--) → skip to end of line
 *   3. String literal ('...') → STRING_LITERAL
 *   4. Number ([0-9]+)         → INTEGER_LITERAL
 *   5. Word ([a-zA-Z_][a-zA-Z0-9_]*) → keyword or IDENTIFIER
 *   6. Two-char operators (<=, >=, !=) → operator token
 *   7. Single-char operators/punctuation → token
 *   8. Anything else → UNKNOWN
 *
 * The lexer is stateless after tokenize() returns.
 * Create a new Lexer instance per SQL string.
 */
public class Lexer {

    // All SQL keywords — case-insensitive match
    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
        Map.entry("select",   TokenType.SELECT),
        Map.entry("from",     TokenType.FROM),
        Map.entry("where",    TokenType.WHERE),
        Map.entry("insert",   TokenType.INSERT),
        Map.entry("into",     TokenType.INTO),
        Map.entry("values",   TokenType.VALUES),
        Map.entry("create",   TokenType.CREATE),
        Map.entry("table",    TokenType.TABLE),
        Map.entry("drop",     TokenType.DROP),
        Map.entry("delete",   TokenType.DELETE),
        Map.entry("update",   TokenType.UPDATE),
        Map.entry("set",      TokenType.SET),
        Map.entry("and",      TokenType.AND),
        Map.entry("or",       TokenType.OR),
        Map.entry("not",      TokenType.NOT),
        Map.entry("null",     TokenType.NULL),
        Map.entry("is",       TokenType.IS),
        Map.entry("int",      TokenType.INT),
        Map.entry("varchar",  TokenType.VARCHAR),
        Map.entry("boolean",  TokenType.BOOLEAN),
        Map.entry("order",    TokenType.ORDER),
        Map.entry("by",       TokenType.BY),
        Map.entry("asc",      TokenType.ASC),
        Map.entry("desc",     TokenType.DESC),
        Map.entry("limit",    TokenType.LIMIT),
        Map.entry("offset",   TokenType.OFFSET),
        Map.entry("primary",  TokenType.PRIMARY),
        Map.entry("key",      TokenType.KEY),
        Map.entry("true",     TokenType.TRUE),
        Map.entry("false",    TokenType.FALSE)
    );

    private final String source;
    private int pos;        // current character index
    private int line;       // current line number (1-based)
    private int lineStart;  // index of the first character on the current line

    public Lexer(String source) {
        this.source    = source;
        this.pos       = 0;
        this.line      = 1;
        this.lineStart = 0;
    }

    /**
     * Tokenize the entire source string.
     * Always ends with an EOF token.
     */
    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        while (true) {
            Token token = nextToken();
            tokens.add(token);
            if (token.is(TokenType.EOF)) break;
        }
        return tokens;
    }

    // ---------------------------------------------------------------
    //  Core scanning loop
    // ---------------------------------------------------------------

    private Token nextToken() {
        skipWhitespaceAndComments();

        if (pos >= source.length()) {
            return makeToken(TokenType.EOF, "", pos);
        }

        int tokenStart = pos;
        char c = current();

        // String literal
        if (c == '\'') return readStringLiteral();

        // Number
        if (Character.isDigit(c)) return readInteger();

        // Keyword or identifier
        if (Character.isLetter(c) || c == '_') return readWord();

        // Two-character operators (maximal munch)
        if (c == '<' && peek() == '=') { pos += 2; return makeToken(TokenType.LTE,  "<=", tokenStart); }
        if (c == '>' && peek() == '=') { pos += 2; return makeToken(TokenType.GTE,  ">=", tokenStart); }
        if (c == '!' && peek() == '=') { pos += 2; return makeToken(TokenType.NEQ,  "!=", tokenStart); }

        // Single-character tokens
        pos++;
        return switch (c) {
            case '=' -> makeToken(TokenType.EQ,        "=", tokenStart);
            case '<' -> makeToken(TokenType.LT,        "<", tokenStart);
            case '>' -> makeToken(TokenType.GT,        ">", tokenStart);
            case '*' -> makeToken(TokenType.STAR,      "*", tokenStart);
            case '+' -> makeToken(TokenType.PLUS,      "+", tokenStart);
            case '-' -> makeToken(TokenType.MINUS,     "-", tokenStart);
            case '/' -> makeToken(TokenType.SLASH,     "/", tokenStart);
            case '(' -> makeToken(TokenType.LPAREN,    "(", tokenStart);
            case ')' -> makeToken(TokenType.RPAREN,    ")", tokenStart);
            case ',' -> makeToken(TokenType.COMMA,     ",", tokenStart);
            case ';' -> makeToken(TokenType.SEMICOLON, ";", tokenStart);
            case '.' -> makeToken(TokenType.DOT,       ".", tokenStart);
            default  -> makeToken(TokenType.UNKNOWN, String.valueOf(c), tokenStart);
        };
    }

    // ---------------------------------------------------------------
    //  Specific token readers
    // ---------------------------------------------------------------

    /**
     * Read a string literal enclosed in single quotes.
     * Handles escaped quotes: '' inside a string = literal single quote.
     * e.g.  'it''s fine'  →  it's fine
     */
    private Token readStringLiteral() {
        int start = pos;
        pos++; // skip opening quote
        StringBuilder sb = new StringBuilder();

        while (pos < source.length()) {
            char c = current();
            if (c == '\'') {
                pos++;
                // Two consecutive quotes = escaped quote inside string
                if (pos < source.length() && current() == '\'') {
                    sb.append('\'');
                    pos++;
                } else {
                    break; // closing quote
                }
            } else {
                if (c == '\n') { line++; lineStart = pos + 1; }
                sb.append(c);
                pos++;
            }
        }
        return makeToken(TokenType.STRING_LITERAL, sb.toString(), start);
    }

    /**
     * Read a sequence of digits as an integer literal.
     * We support only integers for now — floating point is a future extension.
     */
    private Token readInteger() {
        int start = pos;
        while (pos < source.length() && Character.isDigit(current())) {
            pos++;
        }
        return makeToken(TokenType.INTEGER_LITERAL, source.substring(start, pos), start);
    }

    /**
     * Read a word (letters, digits, underscores).
     * Check against the keyword map after reading — keywords are case-insensitive.
     */
    private Token readWord() {
        int start = pos;
        while (pos < source.length()
               && (Character.isLetterOrDigit(current()) || current() == '_')) {
            pos++;
        }
        String word = source.substring(start, pos);
        TokenType type = KEYWORDS.getOrDefault(word.toLowerCase(), TokenType.IDENTIFIER);
        return makeToken(type, word, start);
    }

    // ---------------------------------------------------------------
    //  Whitespace and comments
    // ---------------------------------------------------------------

    private void skipWhitespaceAndComments() {
        while (pos < source.length()) {
            char c = current();

            if (c == '\n') {
                line++;
                lineStart = pos + 1;
                pos++;
            } else if (Character.isWhitespace(c)) {
                pos++;
            } else if (c == '-' && peek() == '-') {
                // Single-line comment — skip to end of line
                while (pos < source.length() && current() != '\n') {
                    pos++;
                }
            } else {
                break;
            }
        }
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private char current() {
        return source.charAt(pos);
    }

    /** Look one character ahead without consuming it. Returns '\0' at EOF. */
    private char peek() {
        return (pos + 1 < source.length()) ? source.charAt(pos + 1) : '\0';
    }

    /** Column number of the given source position (1-based). */
    private int columnAt(int sourcePos) {
        return sourcePos - lineStart + 1;
    }

    private Token makeToken(TokenType type, String value, int sourcePos) {
        return new Token(type, value, line, columnAt(sourcePos));
    }
}