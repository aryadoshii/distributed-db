package db.sql.lexer;

/**
 * Every possible token the lexer can produce.
 *
 * Grouped by category for readability:
 *   - Keywords     : reserved words that cannot be used as identifiers
 *   - Literals     : concrete values in the query
 *   - Identifiers  : user-defined names (tables, columns)
 *   - Operators    : comparison and arithmetic symbols
 *   - Punctuation  : structural characters
 *   - Special      : meta-tokens (EOF, UNKNOWN)
 */
public enum TokenType {

    // --- Keywords ---
    SELECT, FROM, WHERE, INSERT, INTO, VALUES,
    CREATE, TABLE, DROP, DELETE, UPDATE, SET,
    AND, OR, NOT, NULL, IS,
    INT, VARCHAR, BOOLEAN,
    ORDER, BY, ASC, DESC,
    LIMIT, OFFSET,
    PRIMARY, KEY,
    TRUE, FALSE,

    // --- Literals ---
    INTEGER_LITERAL,   // e.g.  42
    STRING_LITERAL,    // e.g. 'hello'

    // --- Identifier ---
    IDENTIFIER,        // e.g. table_name, column_name

    // --- Operators ---
    EQ,          // =
    NEQ,         // !=
    LT,          // 
    LTE,         // <=
    GT,          // >
    GTE,         // >=
    STAR,        // *  (SELECT * or multiply)
    PLUS,        // +
    MINUS,       // -
    SLASH,       // /

    // --- Punctuation ---
    LPAREN,      // (
    RPAREN,      // )
    COMMA,       // ,
    SEMICOLON,   // ;
    DOT,         // .

    // --- Special ---
    EOF,         // end of input — always the last token
    UNKNOWN      // unrecognised character — parser will report an error
}