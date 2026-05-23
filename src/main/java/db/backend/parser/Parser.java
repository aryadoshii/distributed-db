package db.sql.parser;

import db.sql.ast.*;
import db.sql.lexer.Lexer;
import db.sql.lexer.Token;
import db.sql.lexer.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser.
 *
 * Consumes a token list produced by the Lexer and builds an AST.
 * One method per grammar rule. Each method:
 *   1. Peeks at the current token to decide which branch to take
 *   2. Consumes tokens it owns
 *   3. Delegates to sub-rules for nested structures
 *
 * Grammar (simplified):
 *   statement    → select | insert | create
 *   select       → SELECT columns FROM IDENT [where] [orderBy] [limit]
 *   insert       → INSERT INTO IDENT ( col_list ) VALUES ( val_list )
 *   create       → CREATE TABLE IDENT ( col_def_list )
 *   columns      → STAR | expr (, expr)*
 *   where        → WHERE expr
 *   orderBy      → ORDER BY IDENT [ASC | DESC]
 *   limit        → LIMIT INTEGER
 *   expr         → term ((AND | OR) term)*
 *   term         → operand [comparison_op operand] | operand IS [NOT] NULL
 *   operand      → IDENT | literal
 *   literal      → INTEGER | STRING | NULL | TRUE | FALSE
 *   col_def      → IDENT type [PRIMARY KEY]
 *   type         → INT | VARCHAR ( INTEGER )
 */
public class Parser {

    private final List<Token> tokens;
    private int cursor;   // index of the current token

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
        this.cursor = 0;
    }

    /** Entry point — parse a single SQL statement. */
    public Statement parse() {
        Statement stmt = parseStatement();
        // Optionally consume trailing semicolon
        if (peek().is(TokenType.SEMICOLON)) consume();
        expect(TokenType.EOF);
        return stmt;
    }

    /** Convenience: lex and parse in one call. */
    public static Statement parseSQL(String sql) {
        List<Token> tokens = new Lexer(sql).tokenize();
        return new Parser(tokens).parse();
    }

    // ---------------------------------------------------------------
    //  Statement dispatch
    // ---------------------------------------------------------------

    private Statement parseStatement() {
        return switch (peek().getType()) {
            case SELECT -> parseSelect();
            case INSERT -> parseInsert();
            case CREATE -> parseCreate();
            default -> throw new ParseException(
                "Expected SELECT, INSERT, or CREATE but got: " + peek().getValue(),
                peek().getLine(), peek().getColumn()
            );
        };
    }

    // ---------------------------------------------------------------
    //  SELECT
    // ---------------------------------------------------------------

    private SelectStmt parseSelect() {
        expect(TokenType.SELECT);

        // Column list — either * or named columns
        List<Expr> columns = new ArrayList<>();
        if (peek().is(TokenType.STAR)) {
            consume(); // SELECT * — leave columns empty to signal "all"
        } else {
            columns.add(parseExpr());
            while (peek().is(TokenType.COMMA)) {
                consume();
                columns.add(parseExpr());
            }
        }

        expect(TokenType.FROM);
        String table = expectIdentifier();

        // Optional WHERE
        Expr where = null;
        if (peek().is(TokenType.WHERE)) {
            consume();
            where = parseExpr();
        }

        // Optional ORDER BY
        String orderByColumn = null;
        boolean ascending = true;
        if (peek().is(TokenType.ORDER)) {
            consume();
            expect(TokenType.BY);
            orderByColumn = expectIdentifier();
            if (peek().is(TokenType.DESC)) { consume(); ascending = false; }
            else if (peek().is(TokenType.ASC)) { consume(); }
        }

        // Optional LIMIT
        int limit = -1;
        if (peek().is(TokenType.LIMIT)) {
            consume();
            limit = expectInteger();
        }

        return new SelectStmt(columns, table, where, orderByColumn, ascending, limit);
    }

    // ---------------------------------------------------------------
    //  INSERT
    // ---------------------------------------------------------------

    private InsertStmt parseInsert() {
        expect(TokenType.INSERT);
        expect(TokenType.INTO);
        String table = expectIdentifier();

        expect(TokenType.LPAREN);
        List<String> columns = new ArrayList<>();
        columns.add(expectIdentifier());
        while (peek().is(TokenType.COMMA)) {
            consume();
            columns.add(expectIdentifier());
        }
        expect(TokenType.RPAREN);

        expect(TokenType.VALUES);
        expect(TokenType.LPAREN);
        List<Expr> values = new ArrayList<>();
        values.add(parseLiteral());
        while (peek().is(TokenType.COMMA)) {
            consume();
            values.add(parseLiteral());
        }
        expect(TokenType.RPAREN);

        return new InsertStmt(table, columns, values);
    }

    // ---------------------------------------------------------------
    //  CREATE TABLE
    // ---------------------------------------------------------------

    private CreateTableStmt parseCreate() {
        expect(TokenType.CREATE);
        expect(TokenType.TABLE);
        String tableName = expectIdentifier();

        expect(TokenType.LPAREN);
        List<ColumnDef> columnDefs = new ArrayList<>();
        columnDefs.add(parseColumnDef());
        while (peek().is(TokenType.COMMA)) {
            consume();
            // Stop if next is RPAREN (trailing comma tolerance)
            if (peek().is(TokenType.RPAREN)) break;
            columnDefs.add(parseColumnDef());
        }
        expect(TokenType.RPAREN);

        return new CreateTableStmt(tableName, columnDefs);
    }

    private ColumnDef parseColumnDef() {
        String name = expectIdentifier();
        ColumnDef.DataType type;
        int typeParam = 0;

        if (peek().is(TokenType.INT)) {
            consume();
            type = ColumnDef.DataType.INT;
        } else if (peek().is(TokenType.VARCHAR)) {
            consume();
            expect(TokenType.LPAREN);
            typeParam = expectInteger();
            expect(TokenType.RPAREN);
            type = ColumnDef.DataType.VARCHAR;
        } else if (peek().is(TokenType.BOOLEAN)) {
            consume();
            type = ColumnDef.DataType.BOOLEAN;
        } else {
            throw new ParseException(
                "Expected column type (INT, VARCHAR, BOOLEAN) but got: " + peek().getValue(),
                peek().getLine(), peek().getColumn()
            );
        }

        boolean pk = false;
        if (peek().is(TokenType.PRIMARY)) {
            consume();
            expect(TokenType.KEY);
            pk = true;
        }

        return new ColumnDef(name, type, typeParam, pk);
    }

    // ---------------------------------------------------------------
    //  Expressions
    // ---------------------------------------------------------------

    /**
     * Parse an expression — handles AND/OR at the top level.
     * Precedence: OR < AND < comparison (lowest to highest)
     */
    private Expr parseExpr() {
        Expr left = parseTerm();

        while (peek().isAny(TokenType.AND, TokenType.OR)) {
            BinaryExpr.Op op = peek().is(TokenType.AND)
                ? BinaryExpr.Op.AND
                : BinaryExpr.Op.OR;
            consume();
            Expr right = parseTerm();
            left = new BinaryExpr(left, op, right);
        }

        return left;
    }

    /**
     * Parse a comparison term: operand [op operand] or operand IS [NOT] NULL
     */
    private Expr parseTerm() {
        Expr left = parseOperand();

        // IS NULL / IS NOT NULL
        if (peek().is(TokenType.IS)) {
            consume();
            if (peek().is(TokenType.NOT)) {
                consume();
                expect(TokenType.NULL);
                return new BinaryExpr(left, BinaryExpr.Op.IS_NOT_NULL, LiteralExpr.ofNull());
            }
            expect(TokenType.NULL);
            return new BinaryExpr(left, BinaryExpr.Op.IS_NULL, LiteralExpr.ofNull());
        }

        // Comparison operators
        if (peek().isAny(TokenType.EQ, TokenType.NEQ, TokenType.LT,
                         TokenType.LTE, TokenType.GT, TokenType.GTE)) {
            BinaryExpr.Op op = toComparisonOp(consume());
            Expr right = parseOperand();
            return new BinaryExpr(left, op, right);
        }

        return left;
    }

    /** Parse the lowest-level operand: column reference or literal. */
    private Expr parseOperand() {
        if (peek().is(TokenType.IDENTIFIER)) {
            return new ColumnExpr(consume().getValue());
        }
        return parseLiteral();
    }

    /** Parse a literal value: integer, string, NULL, TRUE, FALSE. */
    private Expr parseLiteral() {
        Token t = peek();
        return switch (t.getType()) {
            case INTEGER_LITERAL -> { consume(); yield LiteralExpr.ofInt(Integer.parseInt(t.getValue())); }
            case STRING_LITERAL  -> { consume(); yield LiteralExpr.ofString(t.getValue()); }
            case NULL            -> { consume(); yield LiteralExpr.ofNull(); }
            case TRUE            -> { consume(); yield LiteralExpr.ofBool(true); }
            case FALSE           -> { consume(); yield LiteralExpr.ofBool(false); }
            default -> throw new ParseException(
                "Expected literal value but got: " + t.getValue(),
                t.getLine(), t.getColumn()
            );
        };
    }

    // ---------------------------------------------------------------
    //  Token cursor helpers
    // ---------------------------------------------------------------

    /** Look at the current token without consuming it. */
    private Token peek() {
        return tokens.get(cursor);
    }

    /** Consume and return the current token, advancing the cursor. */
    private Token consume() {
        return tokens.get(cursor++);
    }

    /**
     * Consume the current token if it matches the expected type.
     * Throws ParseException if it doesn't.
     */
    private Token expect(TokenType type) {
        Token t = peek();
        if (!t.is(type)) {
            throw new ParseException(
                "Expected " + type + " but got " + t.getType()
                + " (\"" + t.getValue() + "\")",
                t.getLine(), t.getColumn()
            );
        }
        return consume();
    }

    /** Expect an IDENTIFIER token and return its string value. */
    private String expectIdentifier() {
        return expect(TokenType.IDENTIFIER).getValue();
    }

    /** Expect an INTEGER_LITERAL token and return its int value. */
    private int expectInteger() {
        Token t = expect(TokenType.INTEGER_LITERAL);
        return Integer.parseInt(t.getValue());
    }

    /** Map a comparison operator token to a BinaryExpr.Op. */
    private BinaryExpr.Op toComparisonOp(Token t) {
        return switch (t.getType()) {
            case EQ  -> BinaryExpr.Op.EQ;
            case NEQ -> BinaryExpr.Op.NEQ;
            case LT  -> BinaryExpr.Op.LT;
            case LTE -> BinaryExpr.Op.LTE;
            case GT  -> BinaryExpr.Op.GT;
            case GTE -> BinaryExpr.Op.GTE;
            default  -> throw new ParseException(
                "Not a comparison operator: " + t.getValue(),
                t.getLine(), t.getColumn()
            );
        };
    }
}