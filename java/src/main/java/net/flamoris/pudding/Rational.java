package net.flamoris.pudding;

import java.math.BigInteger;

/** Exact authored arithmetic; quantize only at the public result boundary. */
public record Rational(BigInteger n, BigInteger d) {
    public Rational {
        if (d.signum() == 0) throw new IllegalArgumentException("zero denominator");
        if (d.signum() < 0) { n = n.negate(); d = d.negate(); }
        BigInteger gcd = n.gcd(d); n = n.divide(gcd); d = d.divide(gcd);
    }
    public static Rational of(long n) { return of(n, 1); }
    public static Rational of(long n, long d) { return new Rational(BigInteger.valueOf(n), BigInteger.valueOf(d)); }
    public Rational add(Rational b) { return new Rational(n.multiply(b.d).add(b.n.multiply(d)), d.multiply(b.d)); }
    public Rational sub(Rational b) { return add(new Rational(b.n.negate(), b.d)); }
    public Rational mul(Rational b) { return new Rational(n.multiply(b.n), d.multiply(b.d)); }
    public int halfUp() {
        if (n.signum() < 0) return -new Rational(n.negate(), d).halfUp();
        return n.shiftLeft(1).add(d).divide(d.shiftLeft(1)).intValueExact();
    }
}
