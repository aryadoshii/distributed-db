package db.sql.lexer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static db.sql.lexer.TokenType.*;
import static org.junit.jupiter.api.Assertions.*;

class LexerTest {

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private List<Token> lex(String sql) {
        return new Lexer(sql).tokenize();
    }

    /** Assert a token's type and raw value. */
    private void assertToken(Token tok, TokenType expectedType, String expectedValue) {
        assertEquals(expectedType, tok.getType(),
            "Expected type " + expectedType + " but got " + tok.getType() +
            " (value=\"" + tok.getValue() + "\")");
        assertEquals(expectedValue, tok.getValue(),
            "Expected value \"" + expectedValue + "\" but got \"" + tok.getValue() + "\"");
    }

    /** Extract just the types from a token list (excluding the trailing EOF). */
    private List<TokenType> types(List<Token> tokens) {
        return tokens.stream()
            .map(Token::getType)
            .filter(t -> t != EOF)
            .toList();
    }

    // ---------------------------------------------------------------
    //  Empty / whitespace
    // ---------------------------------------------------------------

    @Test
    void emptyInputProducesOnlyEof() {
        List<Token> tokens = lex("");
        assertEquals(1, tokens.size());
        assertTrue(tokens.get(0).is(EOF));
    }

    @Test
    void whitespaceOnlyProducesOnlyEof() {
        List<Token> tokens = lex("   \t  \n  ");
        assertEquals(1, tokens.size());
        assertTrue(tokens.get(0).is(EOF));
    }

    // ---------------------------------------------------------------
    //  Keywords — case-insensitivity
    // ---------------------------------------------------------------

    @Test
    void keywordsAreCaseInsensitive() {
        List<TokenType> got = types(lex("SELECT select Select SeLeCt"));
        assertEquals(List.of(SELECT, SELECT, SELECT, SELECT), got);
    }

    @Test
    void allDmlKeywordsRecognised() {
        List<TokenType> got = types(lex("SELECT FROM WHERE INSERT INTO VALUES DELETE UPDATE SET CREATE TABLE DROP"));
        assertEquals(List.of(
            SELECT, FROM, WHERE, INSERT, INTO, VALUES,
            DELETE, UPDATE, SET, CREATE, TABLE, DROP
        ), got);
    }

    @Test
    void orderByAscDescRecognised() {
        List<TokenType> got = types(lex("ORDER BY ASC DESC"));
        assertEquals(List.of(ORDER, BY, ASC, DESC), got);
    }

    @Test
    void limitOffsetRecognised() {
        List<TokenType> got = types(lex("LIMIT 10 OFFSET 5"));
        assertEquals(List.of(LIMIT, INTEGER_LITERAL, OFFSET, INTEGER_LITERAL), got);
    }

    @Test
    void booleanLiteralsRecognised() {
        List<TokenType> got = types(lex("true false TRUE FALSE"));
        assertEquals(List.of(TRUE, FALSE, TRUE, FALSE), got);
    }

    @Test
    void typeKeywordsRecognised() {
        List<TokenType> got = types(lex("INT VARCHAR BOOLEAN"));
        assertEquals(List.of(INT, VARCHAR, BOOLEAN), got);
    }

    @Test
    void primaryKeyRecognised() {
        List<TokenType> got = types(lex("PRIMARY KEY"));
        assertEquals(List.of(PRIMARY, KEY), got);
    }

    @Test
    void nullIsKeyword() {
        List<TokenType> got = types(lex("NULL null IS NOT AND OR"));
        assertEquals(List.of(NULL, NULL, IS, NOT, AND, OR), got);
    }

    // ---------------------------------------------------------------
    //  Identifier
    // ---------------------------------------------------------------

    @Test
    void simpleIdentifier() {
        List<Token> tokens = lex("users");
        assertToken(tokens.get(0), IDENTIFIER, "users");
    }

    @Test
    void identifierWithUnderscoreAndDigits() {
        List<Token> tokens = lex("table_1_name");
        assertToken(tokens.get(0), IDENTIFIER, "table_1_name");
    }

    @Test
    void identifierStartingWithUnderscore() {
        List<Token> tokens = lex("_hidden");
        assertToken(tokens.get(0), IDENTIFIER, "_hidden");
    }

    @Test
    void keywordLookupDoesNotMutateOriginalCase() {
        // 'Users' is not a keyword — should be IDENTIFIER preserving case
        List<Token> tokens = lex("Users");
        assertToken(tokens.get(0), IDENTIFIER, "Users");
    }

    // ---------------------------------------------------------------
    //  Integer literals
    // ---------------------------------------------------------------

    @Test
    void singleDigitInteger() {
        List<Token> tokens = lex("7");
        assertToken(tokens.get(0), INTEGER_LITERAL, "7");
    }

    @Test
    void multiDigitInteger() {
        List<Token> tokens = lex("12345");
        assertToken(tokens.get(0), INTEGER_LITERAL, "12345");
    }

    @Test
    void integerAdjacentToOperator() {
        // '42=' — should be INTEGER_LITERAL + EQ
        List<TokenType> got = types(lex("42="));
        assertEquals(List.of(INTEGER_LITERAL, EQ), got);
    }

    // ---------------------------------------------------------------
    //  String literals
    // ---------------------------------------------------------------

    @Test
    void simpleStringLiteral() {
        List<Token> tokens = lex("'hello world'");
        assertToken(tokens.get(0), STRING_LITERAL, "hello world");
    }

    @Test
    void emptyStringLiteral() {
        List<Token> tokens = lex("''");
        assertToken(tokens.get(0), STRING_LITERAL, "");
    }

    @Test
    void escapedSingleQuoteInsideString() {
        // SQL standard: two consecutive single quotes = literal quote
        List<Token> tokens = lex("'it''s fine'");
        assertToken(tokens.get(0), STRING_LITERAL, "it's fine");
    }

    @Test
    void stringLiteralPreservesInternalSpaces() {
        List<Token> tokens = lex("'  spaces  '");
        assertToken(tokens.get(0), STRING_LITERAL, "  spaces  ");
    }

    // ---------------------------------------------------------------
    //  Operators
    // ---------------------------------------------------------------

    @Test
    void allComparisonOperators() {
        List<TokenType> got = types(lex("= != < <= > >="));
        assertEquals(List.of(EQ, NEQ, LT, LTE, GT, GTE), got);
    }

    @Test
    void arithmeticOperators() {
        List<TokenType> got = types(lex("+ - * /"));
        assertEquals(List.of(PLUS, MINUS, STAR, SLASH), got);
    }

    @Test
    void lteLtAreDistinct() {
        // '<=' must be LTE, standalone '<' must be LT
        List<Token> tokens = lex("<= <");
        assertToken(tokens.get(0), LTE, "<=");
        assertToken(tokens.get(1), LT,  "<");
    }

    @Test
    void gteGtAreDistinct() {
        List<Token> tokens = lex(">= >");
        assertToken(tokens.get(0), GTE, ">=");
        assertToken(tokens.get(1), GT,  ">");
    }

    // ---------------------------------------------------------------
    //  Punctuation
    // ---------------------------------------------------------------

    @Test
    void allPunctuationTokens() {
        List<TokenType> got = types(lex("( ) , ; ."));
        assertEquals(List.of(LPAREN, RPAREN, COMMA, SEMICOLON, DOT), got);
    }

    // ---------------------------------------------------------------
    //  Unknown character
    // ---------------------------------------------------------------

    @Test
    void unknownCharacterProducesUnknownToken() {
        List<Token> tokens = lex("@");
        assertToken(tokens.get(0), UNKNOWN, "@");
    }

    @Test
    void unknownCharacterDoesNotHaltLexing() {
        // Lexer should keep going after hitting an UNKNOWN token
        List<TokenType> got = types(lex("@ 42"));
        assertEquals(List.of(UNKNOWN, INTEGER_LITERAL), got);
    }

    // ---------------------------------------------------------------
    //  Comments
    // ---------------------------------------------------------------

    @Test
    void singleLineCommentIsSkipped() {
        List<TokenType> got = types(lex("-- this is a comment\n42"));
        assertEquals(List.of(INTEGER_LITERAL), got);
    }

    @Test
    void inlineCommentSkipsRestOfLine() {
        List<TokenType> got = types(lex("SELECT -- pick everything\nFROM"));
        assertEquals(List.of(SELECT, FROM), got);
    }

    @Test
    void commentWithNoNewlineAtEndOfInput() {
        List<TokenType> got = types(lex("-- just a comment"));
        assertEquals(List.of(), got);
    }

    // ---------------------------------------------------------------
    //  Line and column tracking
    // ---------------------------------------------------------------

    @Test
    void firstTokenIsOnLine1Column1() {
        Token tok = lex("SELECT").get(0);
        assertEquals(1, tok.getLine());
        assertEquals(1, tok.getColumn());
    }

    @Test
    void tokenAfterNewlineIsOnLine2() {
        List<Token> tokens = lex("SELECT\nFROM");
        Token from = tokens.get(1);
        assertEquals(2, from.getLine());
        assertEquals(1, from.getColumn());
    }

    @Test
    void columnNumberIsCorrectWithinLine() {
        // "SELECT users" — 'users' starts at column 8 (after "SELECT ")
        List<Token> tokens = lex("SELECT users");
        Token ident = tokens.get(1);
        assertEquals(1,  ident.getLine());
        assertEquals(8,  ident.getColumn());
    }

    @Test
    void eofTokenPosition() {
        List<Token> tokens = lex("x");
        Token eof = tokens.get(tokens.size() - 1);
        assertTrue(eof.is(EOF));
        assertEquals(1, eof.getLine());
    }

    // ---------------------------------------------------------------
    //  Full SQL statements
    // ---------------------------------------------------------------

    @Test
    void fullSelectStatement() {
        List<TokenType> got = types(lex(
            "SELECT id, name FROM users WHERE age >= 18;"
        ));
        assertEquals(List.of(
            SELECT, IDENTIFIER, COMMA, IDENTIFIER,
            FROM, IDENTIFIER,
            WHERE, IDENTIFIER, GTE, INTEGER_LITERAL,
            SEMICOLON
        ), got);
    }

    @Test
    void fullInsertStatement() {
        List<TokenType> got = types(lex(
            "INSERT INTO orders (id, status) VALUES (1, 'pending');"
        ));
        assertEquals(List.of(
            INSERT, INTO, IDENTIFIER,
            LPAREN, IDENTIFIER, COMMA, IDENTIFIER, RPAREN,
            VALUES,
            LPAREN, INTEGER_LITERAL, COMMA, STRING_LITERAL, RPAREN,
            SEMICOLON
        ), got);
    }

    @Test
    void fullCreateTableStatement() {
        List<TokenType> got = types(lex(
            "CREATE TABLE employees (id INT PRIMARY KEY, name VARCHAR);"
        ));
        assertEquals(List.of(
            CREATE, TABLE, IDENTIFIER,
            LPAREN,
            IDENTIFIER, INT, PRIMARY, KEY, COMMA,
            IDENTIFIER, VARCHAR,
            RPAREN, SEMICOLON
        ), got);
    }

    @Test
    void fullUpdateStatement() {
        List<TokenType> got = types(lex(
            "UPDATE users SET active = TRUE WHERE id = 5;"
        ));
        assertEquals(List.of(
            UPDATE, IDENTIFIER, SET,
            IDENTIFIER, EQ, TRUE,
            WHERE, IDENTIFIER, EQ, INTEGER_LITERAL,
            SEMICOLON
        ), got);
    }

    @Test
    void selectStarFromTable() {
        List<TokenType> got = types(lex("SELECT * FROM products;"));
        assertEquals(List.of(SELECT, STAR, FROM, IDENTIFIER, SEMICOLON), got);
    }

    @Test
    void qualifiedColumnName() {
        // table.column should produce IDENTIFIER DOT IDENTIFIER
        List<TokenType> got = types(lex("users.name"));
        assertEquals(List.of(IDENTIFIER, DOT, IDENTIFIER), got);
    }

    @Test
    void isNullCondition() {
        List<TokenType> got = types(lex("WHERE col IS NULL"));
        assertEquals(List.of(WHERE, IDENTIFIER, IS, NULL), got);
    }

    @Test
    void isNotNullCondition() {
        List<TokenType> got = types(lex("WHERE col IS NOT NULL"));
        assertEquals(List.of(WHERE, IDENTIFIER, IS, NOT, NULL), got);
    }

    @Test
    void multiLineQueryTracksLinesCorrectly() {
        String sql = "SELECT\n  id\nFROM\n  users";
        List<Token> tokens = lex(sql);

        assertEquals(1, tokens.get(0).getLine()); // SELECT
        assertEquals(2, tokens.get(1).getLine()); // id
        assertEquals(3, tokens.get(2).getLine()); // FROM
        assertEquals(4, tokens.get(3).getLine()); // users
    }

    // ---------------------------------------------------------------
    //  Token.is / Token.isAny helpers
    // ---------------------------------------------------------------

    @Test
    void tokenIsHelper() {
        Token tok = lex("SELECT").get(0);
        assertTrue(tok.is(SELECT));
        assertFalse(tok.is(FROM));
    }

    @Test
    void tokenIsAnyHelper() {
        Token tok = lex("WHERE").get(0);
        assertTrue(tok.isAny(SELECT, FROM, WHERE));
        assertFalse(tok.isAny(INSERT, UPDATE, DELETE));
    }
}
