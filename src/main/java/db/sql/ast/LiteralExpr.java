package db.sql.ast;

/**
 * A literal value in the SQL.
 *
 * value is typed as Object and holds:
 *   - Integer  for integer literals  (42)
 *   - String   for string literals   ('hello')
 *   - null     for NULL keyword
 *   - Boolean  for TRUE / FALSE
 *
 * The executor pattern-matches on value's runtime type.
 */
public record LiteralExpr(Object value) implements Expr {

    /** Convenience factories */
    public static LiteralExpr ofInt(int n)       { return new LiteralExpr(n); }
    public static LiteralExpr ofString(String s) { return new LiteralExpr(s); }
    public static LiteralExpr ofNull()           { return new LiteralExpr(null); }
    public static LiteralExpr ofBool(boolean b)  { return new LiteralExpr(b); }

    public boolean isNull() { return value == null; }
}