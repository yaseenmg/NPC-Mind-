package com.yaseen.npcmind.agent;
import com.yaseen.npcmind.api.Internal;

/** Small deterministic arithmetic engine for counts, distances and resource calculations.
 * It intentionally supports only numeric expressions needed by gameplay; it never evaluates Java code. */
@Internal
public final class MathEngine {
    private MathEngine() {}
    public static double eval(String expression) {
        if (expression == null || expression.isBlank()) throw new IllegalArgumentException("empty_expression");
        Parser p = new Parser(expression);
        double v = p.expression();
        if (p.hasMore()) throw new IllegalArgumentException("unexpected_character_at=" + p.i);
        if (!Double.isFinite(v)) throw new IllegalArgumentException("non_finite_result");
        return v;
    }
    private static final class Parser {
        final String s; int i;
        Parser(String s) { this.s=s.replaceAll("\\s+", ""); }
        boolean hasMore(){return i<s.length();}
        double expression(){ double v=term(); while(hasMore() && (s.charAt(i)=='+'||s.charAt(i)=='-')){char c=s.charAt(i++); double r=term(); v=c=='+'?v+r:v-r;} return v; }
        double term(){ double v=factor(); while(hasMore() && (s.charAt(i)=='*'||s.charAt(i)=='/')){char c=s.charAt(i++); double r=factor(); if(c=='/'&&Math.abs(r)<1e-12)throw new IllegalArgumentException("division_by_zero"); v=c=='*'?v*r:v/r;} return v; }
        double factor(){ if(!hasMore())throw new IllegalArgumentException("missing_number"); char c=s.charAt(i); if(c=='+'){i++;return factor();} if(c=='-'){i++;return -factor();} if(c=='('){i++;double v=expression(); if(!hasMore()||s.charAt(i++)!=')')throw new IllegalArgumentException("missing_parenthesis");return v;} int st=i; while(hasMore()&&(Character.isDigit(s.charAt(i))||s.charAt(i)=='.'))i++; if(st==i)throw new IllegalArgumentException("expected_number_at="+i); return Double.parseDouble(s.substring(st,i)); }
    }
}
