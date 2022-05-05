/*
 * Copyright (C) 2012-2013 University of Freiburg
 *
 * This file is part of SMTInterpol.
 *
 * SMTInterpol is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * SMTInterpol is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with SMTInterpol.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.uni_freiburg.informatik.ultimate.smtinterpol.proof;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import de.uni_freiburg.informatik.ultimate.logic.AnnotatedTerm;
import de.uni_freiburg.informatik.ultimate.logic.Annotation;
import de.uni_freiburg.informatik.ultimate.logic.ApplicationTerm;
import de.uni_freiburg.informatik.ultimate.logic.Logics;
import de.uni_freiburg.informatik.ultimate.logic.Rational;
import de.uni_freiburg.informatik.ultimate.logic.SMTLIBConstants;
import de.uni_freiburg.informatik.ultimate.logic.Sort;
import de.uni_freiburg.informatik.ultimate.logic.Term;
import de.uni_freiburg.informatik.ultimate.logic.Theory;
import de.uni_freiburg.informatik.ultimate.smtinterpol.convert.SMTAffineTerm;
import de.uni_freiburg.informatik.ultimate.smtinterpol.smtlib2.SMTInterpol;
import de.uni_freiburg.informatik.ultimate.smtinterpol.theory.cclosure.CCEquality;

@RunWith(JUnit4.class)
public class ProofSimplifierTest {

	private final SMTInterpol mSmtInterpol;
	private final Theory mTheory;
	private final Random rng = new Random(123456);
	private final ProofSimplifier mSimplifier;
	private final MinimalProofChecker mProofChecker;

	public ProofSimplifierTest() {
		mSmtInterpol = new SMTInterpol();
		mSmtInterpol.setOption(SMTLIBConstants.PRODUCE_PROOFS, SMTLIBConstants.TRUE);
		mSmtInterpol.setOption(SMTLIBConstants.INTERACTIVE_MODE, SMTLIBConstants.TRUE);
		mSmtInterpol.setLogic(Logics.ALL);
		mTheory = mSmtInterpol.getTheory();
		mSimplifier = new ProofSimplifier(mSmtInterpol);
		mProofChecker = new MinimalProofChecker(mSmtInterpol, mSmtInterpol.getLogger());
		mSmtInterpol.declareSort("U", 0);
	}

	private Object[] shuffle(final Object[] o) {
		for (int i = 1; i < o.length; i++) {
			final int randomPos = rng.nextInt(i);
			final Object swap = o[i];
			o[i] = o[randomPos];
			o[randomPos] = swap;
		}
		return o;
	}

	public Term[] generateDummyTerms(final String name, final int len, final Sort sort) {
		final Term[] terms = new Term[len];
		for (int i = 0; i < len; i++) {
			mSmtInterpol.declareFun(name + i, new Sort[0], sort);
			terms[i] = mSmtInterpol.term(name + i);
		}
		return terms;
	}

	public Term generateTransitivityInt(final int len, final int swapFlags) {
		final Sort intSort = mSmtInterpol.sort("Int");
		final Term[] terms = generateDummyTerms("x", len, intSort);
		final Term[] eqs = new Term[len];
		final SMTAffineTerm affine = new SMTAffineTerm();
		affine.add((swapFlags & (1 << (len - 1))) != 0 ? Rational.ONE : Rational.MONE, terms[0]);
		affine.add(Rational.ONE);
		terms[len - 1] = affine.toTerm(intSort);
		for (int i = 0; i < len; i++) {
			if (i > 0) {
				eqs[i - 1] = (swapFlags & (1 << (i - 1))) != 0 ? mTheory.term("=", terms[i - 1], terms[i])
						: mTheory.term("=", terms[i], terms[i - 1]);
			}
		}
		eqs[len - 1] = (swapFlags & (1 << (len - 1))) != 0 ? mTheory.term("=", terms[0], terms[len - 1])
				: mTheory.term("=", terms[len - 1], terms[0]);
		final Term[] quotedEqs = new Term[len];
		for (int i = 0; i < len; i++) {
			quotedEqs[i] = mSmtInterpol.annotate(eqs[i], CCEquality.QUOTED_CC);
		}
		final Term[] orParams = new Term[len - 1];
		for (int i = 0; i < len - 1; i++) {
			orParams[i] = mSmtInterpol.term("not", quotedEqs[i]);
		}
		final Term clause = mSmtInterpol.term("or", (Term[]) shuffle(orParams));
		final Object[] subannots = new Object[] { quotedEqs[len - 1], ":subpath", terms };
		final Annotation[] lemmaAnnots = new Annotation[] { new Annotation(":CC", subannots) };
		final Term lemma = mSmtInterpol.term(ProofConstants.FN_LEMMA, mSmtInterpol.annotate(clause, lemmaAnnots));
		return lemma;
	}

	public Term generateTransitivity(final int len, final int swapFlags) {
		final Sort U = mSmtInterpol.sort("U");
		final Term[] terms = generateDummyTerms("x", len, U);
		final Term[] eqs   = new Term[len];
		for (int i = 0; i < len; i++) {
			if (i > 0) {
				eqs[i-1] = (swapFlags & (1<< (i-1))) != 0 ? mTheory.term("=", terms[i-1],terms[i]) : mTheory.term("=", terms[i],terms[i-1]);
			}
		}
		eqs[len - 1] = (swapFlags & (1<< (len-1))) != 0 ? mTheory.term("=", terms[0],terms[len-1]) : mTheory.term("=", terms[len-1],terms[0]);
		final Term[] quotedEqs   = new Term[len];
		for (int i = 0; i < len; i++) {
			quotedEqs[i] = mSmtInterpol.annotate(eqs[i], CCEquality.QUOTED_CC);
		}
		final Term[] orParams = new Term[len];
		for (int i = 0; i < len; i++) {
			orParams[i] = i == len - 1 ? quotedEqs[i] : mSmtInterpol.term("not", quotedEqs[i]);
		}
		final Term clause = mSmtInterpol.term("or", (Term[]) shuffle(orParams));
		final Object[] subannots = new Object[] {
			quotedEqs[len-1],
			":subpath",
			terms
		};
		final Annotation[] lemmaAnnots = new Annotation[] { new Annotation(":CC", subannots) };
		final Term lemma = mSmtInterpol.term(ProofConstants.FN_LEMMA,
				mSmtInterpol.annotate(clause, lemmaAnnots));
		return lemma;
	}

	void checkLemmaOrRewrite(final Term lemma, final Term[] lits) {
		final HashSet<ProofLiteral> expected = new HashSet<>();
		for (int i = 0; i < lits.length; i++) {
			Term atom = lits[i];
			boolean polarity = true;
			while (atom instanceof ApplicationTerm
					&& ((ApplicationTerm) atom).getFunction().getName() == SMTLIBConstants.NOT) {
				atom = ((ApplicationTerm) atom).getParameters()[0];
				polarity = !polarity;
			}
			expected.add(new ProofLiteral(atom, polarity));
		}
		final Term simpleLemma = mSimplifier.transform(lemma);
		final ProofLiteral[] provedLits = mProofChecker.getProvedClause(simpleLemma);
		final HashSet<ProofLiteral> actual = new HashSet<>(Arrays.asList(provedLits));
		Assert.assertEquals(expected, actual);
	}

	@Test
	public void testCCLemma() {
		for (int len = 3; len < 5; len++) {
			for (int flags = 0; flags < (1 << len); flags++) {
				for (int i = 0; i < 2; i++) {
					mSmtInterpol.push(1);
					final Term transLemma = i == 0 ? generateTransitivity(len, flags)
							: generateTransitivityInt(len, flags);
					final Term clause = ((AnnotatedTerm) ((ApplicationTerm) transLemma).getParameters()[0])
							.getSubterm();
					final Term[] orParams = ((ApplicationTerm) clause).getParameters();
					checkLemmaOrRewrite(transLemma, orParams);
					mSmtInterpol.pop(1);
				}
			}
		}
	}

	@Test
	public void testEqSameRewrite() {
		mSmtInterpol.push(1);
		final Sort U = mSmtInterpol.sort("U");
		final Term[] terms = generateDummyTerms("x", 5, U);

		for (int i = 0; i < 1000; i++) {
			final int len = rng.nextInt(10) + 2;
			final Term[] lhsTerms = new Term[len];
			for (int j = 0; j < len; j++) {
				lhsTerms[j] = terms[rng.nextInt(terms.length)];
			}
			final HashSet<Term> contents = new HashSet<>(Arrays.asList(lhsTerms));
			final Term[] rhsTerms = (Term[]) shuffle(contents.toArray(new Term[contents.size()]));
			final Term rhs = rhsTerms.length == 1 ? mSmtInterpol.term(SMTLIBConstants.TRUE)
					: mSmtInterpol.term("=", rhsTerms);

			final Term equality = mSmtInterpol.term("=", mSmtInterpol.term("=", lhsTerms), rhs);
			final Term rewriteEqSimp = mSmtInterpol.term(ProofConstants.FN_REWRITE, mSmtInterpol.annotate(equality,
					rhsTerms.length == 1 ? ProofConstants.RW_EQ_SAME : ProofConstants.RW_EQ_SIMP));
			checkLemmaOrRewrite(rewriteEqSimp, new Term[] { equality });
		}
		mSmtInterpol.pop(1);
	}

	@Test
	public void testEqDistinctRewrite() {

		{
			mSmtInterpol.push(1);
			final Term[] terms = generateDummyTerms("x", 5, mSmtInterpol.sort("Bool"));
			final Term equality = mSmtInterpol.term("=", mSmtInterpol.term("distinct", terms),
					mSmtInterpol.term(SMTLIBConstants.FALSE));
			final Term rewriteEqSimp = mSmtInterpol.term(ProofConstants.FN_REWRITE,
					mSmtInterpol.annotate(equality, ProofConstants.RW_DISTINCT_BOOL));
			checkLemmaOrRewrite(rewriteEqSimp, new Term[] { equality });
			mSmtInterpol.pop(1);
		}

		{
			mSmtInterpol.push(1);
			final Term[] terms = generateDummyTerms("x", 5, mSmtInterpol.sort("U"));
			terms[4] = terms[2];
			final Term equality = mSmtInterpol.term("=", mSmtInterpol.term("distinct", terms),
					mSmtInterpol.term(SMTLIBConstants.FALSE));
			final Term rewriteEqSimp = mSmtInterpol.term(ProofConstants.FN_REWRITE,
					mSmtInterpol.annotate(equality, ProofConstants.RW_DISTINCT_SAME));
			checkLemmaOrRewrite(rewriteEqSimp, new Term[] { equality });
			mSmtInterpol.pop(1);
		}

		for (int len = 2; len < 5; len++) {
			mSmtInterpol.push(1);
			final Term[] terms = generateDummyTerms("x", len, mSmtInterpol.sort("U"));
			final Term[] orParams = new Term[len * (len - 1) / 2];
			int offset = 0;
			for (int i = 0; i < terms.length; i++) {
				for (int j = i + 1; j < terms.length; j++) {
					orParams[offset++] = mSmtInterpol.term(SMTLIBConstants.EQUALS, terms[i], terms[j]);
				}
			}
			final Term orTerm = orParams.length == 1 ? orParams[0] : mSmtInterpol.term(SMTLIBConstants.OR, orParams);
			final Term equality = mSmtInterpol.term("=", mSmtInterpol.term("distinct", terms),
					mSmtInterpol.term(SMTLIBConstants.NOT, orTerm));
			final Term rewriteEqSimp = mSmtInterpol.term(ProofConstants.FN_REWRITE,
					mSmtInterpol.annotate(equality, ProofConstants.RW_DISTINCT_BINARY));
			checkLemmaOrRewrite(rewriteEqSimp, new Term[] { equality });
			mSmtInterpol.pop(1);
		}
	}

	@Test
	public void testEqToXorRewrite() {
		mSmtInterpol.push(1);
		final Sort U = mSmtInterpol.sort("Bool");
		final Term[] terms = generateDummyTerms("x", 2, U);

		// convert equality to not xor, simplify the xor term and possibly remove double
		// negation.
		final Term eqTerm = mSmtInterpol.term("=", terms);
		final Term xorTerm = mSmtInterpol.term("xor", terms);
		final Term equality = mSmtInterpol.term("=", eqTerm, mSmtInterpol.term("not", xorTerm));
		final Term rewriteEqSimp = mSmtInterpol.term(ProofConstants.FN_REWRITE,
				mSmtInterpol.annotate(equality, ProofConstants.RW_EQ_TO_XOR));
		checkLemmaOrRewrite(rewriteEqSimp, new Term[] { equality });
		mSmtInterpol.pop(1);
	}

	public void checkOneTrivialDiseq(final Term lhs, final Term rhs) {
		for (final Term trivialDiseq : new Term[] { mSmtInterpol.term("=", lhs, rhs),
				mSmtInterpol.term("=", rhs, lhs) }) {
			final Term equality = mSmtInterpol.term("=", trivialDiseq, mSmtInterpol.term(SMTLIBConstants.FALSE));
			final Term rewrite = mSmtInterpol.term(ProofConstants.FN_REWRITE,
					mSmtInterpol.annotate(equality, ProofConstants.RW_INTERN));
			checkLemmaOrRewrite(rewrite, new Term[] { equality });
		}
	}

	@Test
	public void testTrivialDiseq() {
		mSmtInterpol.push(1);
		final Sort intSort = mSmtInterpol.sort("Int");
		final Sort realSort = mSmtInterpol.sort("Real");
		final Sort[] numericSorts = new Sort[] { intSort, realSort };
		final Term[] intTerms = generateDummyTerms("i", 2, intSort);
		final Term[] realTerms = generateDummyTerms("r", 2, realSort);
		final Term[] mixedTerms = new Term[] { intTerms[0], realTerms[1] };

		for (final Sort sort : numericSorts) {
			checkOneTrivialDiseq(Rational.valueOf(2, 1).toTerm(sort), Rational.valueOf(3, 1).toTerm(sort));
			checkOneTrivialDiseq(Rational.valueOf(-1, 1).toTerm(sort), Rational.valueOf(3, 1).toTerm(sort));
			checkOneTrivialDiseq(Rational.valueOf(-5, 1).toTerm(sort), Rational.valueOf(-1, 1).toTerm(sort));
			checkOneTrivialDiseq(Rational.valueOf(2, 1).toTerm(sort), Rational.valueOf(0, 1).toTerm(sort));
			checkOneTrivialDiseq(Rational.valueOf(-2, 1).toTerm(sort), Rational.valueOf(0, 1).toTerm(sort));

			final Term[][] termCases = sort == intSort ? new Term[][] { intTerms } : new Term[][] { intTerms, realTerms, mixedTerms };
			final ArrayList<Rational> constants = new ArrayList<>();
			constants.add(Rational.valueOf(1, 1));
			constants.add(Rational.valueOf(-4, 1));
			if (sort == realSort) {
				constants.add(Rational.valueOf(5, 4));
				constants.add(Rational.valueOf(-3, 2));
			}
			final ArrayList<Rational> constantsWithZero = new ArrayList<>(constants);
			constantsWithZero.add(Rational.ZERO);

			for (final Term[] terms : termCases) {
				for (final Rational rat1 : constants) {
					for (final Rational rat2 : constantsWithZero) {
						final SMTAffineTerm lhs = new SMTAffineTerm();
						lhs.add(rat1, terms[0]);
						lhs.add(rat2, terms[1]);
						for (final Rational diff : constants) {
							final SMTAffineTerm rhs = new SMTAffineTerm(diff.toTerm(sort));
							rhs.add(lhs);
							checkOneTrivialDiseq(lhs.toTerm(sort), rhs.toTerm(sort));
						}
					}
				}
			}
		}
		{
			final SMTAffineTerm lhs = new SMTAffineTerm();
			lhs.add(Rational.valueOf(3, 1), intTerms[0]);
			final SMTAffineTerm rhs = new SMTAffineTerm();
			rhs.add(Rational.valueOf(25, 1));
			rhs.add(Rational.valueOf(6, 1), intTerms[1]);
			checkOneTrivialDiseq(lhs.toTerm(intSort), rhs.toTerm(intSort));
		}
		{
			final SMTAffineTerm lhs = new SMTAffineTerm();
			lhs.add(Rational.valueOf(3, 4), intTerms[0]);
			final SMTAffineTerm rhs = new SMTAffineTerm();
			rhs.add(Rational.valueOf(16, 6));
			rhs.add(Rational.valueOf(5, 4), intTerms[1]);
			checkOneTrivialDiseq(lhs.toTerm(realSort), rhs.toTerm(realSort));
		}
		{
			final SMTAffineTerm lhs = new SMTAffineTerm();
			lhs.add(Rational.valueOf(3, 4), intTerms[0]);
			lhs.add(Rational.valueOf(7, 3), realTerms[0]);
			final SMTAffineTerm rhs = new SMTAffineTerm();
			rhs.add(Rational.valueOf(16, 6));
			rhs.add(Rational.valueOf(5, 4), intTerms[1]);
			rhs.add(Rational.valueOf(7, 3), realTerms[0]);
			checkOneTrivialDiseq(lhs.toTerm(realSort), rhs.toTerm(realSort));
		}
		mSmtInterpol.pop(1);
	}

	private void checkIteRewrite(final Term cond, final Term thenCase, final Term elseCase, final Annotation rule,
			final Term rhs) {
		final Term ite = mSmtInterpol.term("ite", cond, thenCase, elseCase);
		final Term equality = mSmtInterpol.term("=", ite, rhs);
		final Term rewrite = mSmtInterpol.term(ProofConstants.FN_REWRITE, mSmtInterpol.annotate(equality, rule));
		checkLemmaOrRewrite(rewrite, new Term[] { equality });
	}

	@Test
	public void testRewriteIte() {
		final Sort boolSort = mSmtInterpol.sort(SMTLIBConstants.BOOL);
		final Term[] origTerms = generateDummyTerms("b", 3, boolSort);
		final Term trueTerm = mSmtInterpol.term(SMTLIBConstants.TRUE);
		final Term falseTerm = mSmtInterpol.term(SMTLIBConstants.FALSE);

		for(int flags = 0; flags < 8; flags++) {
			final Term[] terms = new Term[3];
			for (int i = 0; i < 3; i++) {
				terms[i] = ((flags >> i) & 1) != 0 ? mSmtInterpol.term(SMTLIBConstants.NOT, origTerms[i])
						: origTerms[i];
			}

			checkIteRewrite(trueTerm, terms[1], terms[2], ProofConstants.RW_ITE_TRUE, terms[1]);
			checkIteRewrite(falseTerm, terms[1], terms[2], ProofConstants.RW_ITE_FALSE, terms[2]);
			checkIteRewrite(terms[0], terms[1], terms[1], ProofConstants.RW_ITE_SAME, terms[1]);
			checkIteRewrite(terms[0], trueTerm, falseTerm, ProofConstants.RW_ITE_BOOL_1, terms[0]);
			checkIteRewrite(terms[0], falseTerm, trueTerm, ProofConstants.RW_ITE_BOOL_2,
					mSmtInterpol.term(SMTLIBConstants.NOT, terms[0]));
			checkIteRewrite(terms[0], trueTerm, terms[2], ProofConstants.RW_ITE_BOOL_3,
					mSmtInterpol.term(SMTLIBConstants.OR, terms[0], terms[2]));
			checkIteRewrite(terms[0], falseTerm, terms[2], ProofConstants.RW_ITE_BOOL_4, mSmtInterpol.term(
					SMTLIBConstants.NOT,
					mSmtInterpol.term(SMTLIBConstants.OR, terms[0], mSmtInterpol.term(SMTLIBConstants.NOT, terms[2]))));
			checkIteRewrite(terms[0], terms[1], trueTerm, ProofConstants.RW_ITE_BOOL_5,
					mSmtInterpol.term(SMTLIBConstants.OR, mSmtInterpol.term(SMTLIBConstants.NOT, terms[0]), terms[1]));
			checkIteRewrite(terms[0], terms[1], falseTerm, ProofConstants.RW_ITE_BOOL_6,
					mSmtInterpol.term(SMTLIBConstants.NOT,
							mSmtInterpol.term(SMTLIBConstants.OR, mSmtInterpol.term(SMTLIBConstants.NOT, terms[0]),
									mSmtInterpol.term(SMTLIBConstants.NOT, terms[1]))));
		}
	}

	private void checkDivModRewrite(final String divOrMod, final Term dividend, final Rational divisor,
			final Term result, final Annotation rule) {
		final Sort sort = dividend.getSort();
		final Term lhs = mSmtInterpol.term(divOrMod, dividend, divisor.toTerm(sort));
		final Term equality = mSmtInterpol.term("=", lhs, result);
		final Term rewrite = mSmtInterpol.term(ProofConstants.FN_REWRITE, mSmtInterpol.annotate(equality, rule));
		checkLemmaOrRewrite(rewrite, new Term[] { equality });
	}

	@Test
	public void testRewriteDivMod() {
		final Sort sort = mSmtInterpol.sort(SMTLIBConstants.INT);
		final Term[] terms = generateDummyTerms("i", 2, sort);
		final Rational[] someRationals = {
				Rational.ZERO, Rational.ONE, Rational.MONE,
				Rational.valueOf(1234, 1),
				Rational.valueOf(-123, 3),
				Rational.valueOf(new BigInteger("1234567890123456789012345678901234567890"), BigInteger.ONE),
				Rational.valueOf(new BigInteger("-1234567890123456789012345678901234567890"), BigInteger.ONE),
		};
		final Rational[] someDivisors = {
				Rational.valueOf(3, 1),
				Rational.valueOf(17, 1),
				Rational.valueOf(-5, 1),
				Rational.valueOf(new BigInteger("123456789012345678901234567890123456789"), BigInteger.ONE),
				Rational.valueOf(new BigInteger("-123456789012345678901234567890123456789"), BigInteger.ONE),
				Rational.valueOf(new BigInteger("123456789012345678901234567890123456788"), BigInteger.ONE),
				Rational.valueOf(new BigInteger("-123456789012345678901234567890123456788"), BigInteger.ONE),
		};
		final Term[] someTerms = {
				terms[0],
				mTheory.term(SMTLIBConstants.PLUS, terms),
				mTheory.term(SMTLIBConstants.MUL, Rational.TWO.toTerm(sort), terms[0]),
				mTheory.term(SMTLIBConstants.PLUS,
						mTheory.term(SMTLIBConstants.MUL, Rational.TWO.toTerm(sort), terms[0]),
						mTheory.term(SMTLIBConstants.MUL, someRationals[4].toTerm(sort), terms[1]))
		};
		final Term[] negatedTerms = {
				mTheory.term(SMTLIBConstants.MUL, Rational.MONE.toTerm(sort), terms[0]),
				mTheory.term(SMTLIBConstants.PLUS,
						mTheory.term(SMTLIBConstants.MUL, Rational.MONE.toTerm(sort), terms[1]),
						mTheory.term(SMTLIBConstants.MUL, Rational.MONE.toTerm(sort), terms[0])),
				mTheory.term(SMTLIBConstants.MUL, Rational.TWO.negate().toTerm(sort), terms[0]),
				mTheory.term(SMTLIBConstants.PLUS,
						mTheory.term(SMTLIBConstants.MUL, someRationals[4].negate().toTerm(sort), terms[1]),
						mTheory.term(SMTLIBConstants.MUL, Rational.TWO.negate().toTerm(sort), terms[0]))
		};

		final Term zero = Rational.ZERO.toTerm(sort);
		for (final Term t : someTerms) {
			checkDivModRewrite("div", t, Rational.ONE, t, ProofConstants.RW_DIV_ONE);
			checkDivModRewrite("mod", t, Rational.ONE, zero, ProofConstants.RW_MODULO_ONE);
		}
		for (final Rational r : someRationals) {
			checkDivModRewrite("div", r.toTerm(sort), Rational.ONE, r.toTerm(sort), ProofConstants.RW_DIV_ONE);
			checkDivModRewrite("mod", r.toTerm(sort), Rational.ONE, zero, ProofConstants.RW_MODULO_ONE);
		}
		for (int i = 0; i < someTerms.length; i++) {
			checkDivModRewrite("div", someTerms[i], Rational.MONE, negatedTerms[i], ProofConstants.RW_DIV_MONE);
			checkDivModRewrite("div", negatedTerms[i], Rational.MONE, someTerms[i], ProofConstants.RW_DIV_MONE);
			checkDivModRewrite("mod", someTerms[i], Rational.MONE, zero, ProofConstants.RW_MODULO_MONE);
			checkDivModRewrite("mod", negatedTerms[i], Rational.MONE, zero, ProofConstants.RW_MODULO_MONE);
		}
		for (final Rational r : someRationals) {
			for (final Rational divisor : someDivisors) {
				Rational quotient = r.div(divisor.abs()).floor();
				if  (divisor.signum() < 0) {
					quotient = quotient.negate();
				}
				final Rational remainder = r.sub(divisor.mul(quotient));
				checkDivModRewrite("div", r.toTerm(sort), divisor, quotient.toTerm(sort), ProofConstants.RW_DIV_CONST);
				checkDivModRewrite("mod", r.toTerm(sort), divisor, remainder.toTerm(sort), ProofConstants.RW_MODULO_CONST);
			}
		}
	}

	@Test
	public void testExcludedMiddle() {
		final Sort boolSort = mSmtInterpol.sort(SMTLIBConstants.BOOL);
		final Term[] terms = generateDummyTerms("b", 1, boolSort);
		final Term trueTerm = mSmtInterpol.term(SMTLIBConstants.TRUE);
		final Term falseTerm = mSmtInterpol.term(SMTLIBConstants.FALSE);

		for (int flags = 0; flags < 2; flags++) {
			final Term p = terms[0];
			Term equality = mSmtInterpol.term(SMTLIBConstants.EQUALS, p, (flags & 1) != 0 ? falseTerm : trueTerm);
			equality = mSmtInterpol.annotate(equality, new Annotation[] { new Annotation(":quotedCC", null) });
			final Term litp = (flags & 1) != 0 ? p : mSmtInterpol.term(SMTLIBConstants.NOT, p);
			final Term clause = mSmtInterpol.term(SMTLIBConstants.OR, equality, litp);
			final Annotation rule = (flags & 1) != 0 ? ProofConstants.AUX_EXCLUDED_MIDDLE_2
					: ProofConstants.AUX_EXCLUDED_MIDDLE_1;
			final Term tautology = mSmtInterpol.term(ProofConstants.FN_TAUTOLOGY, mSmtInterpol.annotate(clause, rule));
			checkLemmaOrRewrite(tautology, new Term[] { equality, litp });
		}
	}

	@Test
	public void testEqTrueFalse() {
		final Sort boolSort = mSmtInterpol.sort(SMTLIBConstants.BOOL);
		final Term[] terms = generateDummyTerms("b", 3, boolSort);

		for (int numTerms = 1; numTerms < 3; numTerms++) {
			for (int flags = 0; flags < (1 << (numTerms + 1)); flags++) {
				final boolean isFalse = (flags & (1 << numTerms)) != 0;
				final Annotation rule = isFalse ? ProofConstants.RW_EQ_FALSE : ProofConstants.RW_EQ_TRUE;
				for (int pos = 0; pos < numTerms + 1; pos++) {
					for (int dups = 0; dups < 2; dups++) {
						final Term[] nterms = new Term[numTerms];
						final Term[] input = new Term[numTerms + 1 + dups];
						final Term[] output = new Term[numTerms];
						for (int i = 0; i < numTerms; i++) {
							final boolean isNeg = (flags & (1 << i)) != 0;
							nterms[i] = isNeg ? mTheory.term(SMTLIBConstants.NOT, terms[i]) : terms[i];
							input[i < pos ? i : i + 1] = nterms[i];
							output[i] = isFalse ? nterms[i] : mTheory.term(SMTLIBConstants.NOT, nterms[i]);
						}
						input[pos] = isFalse ? mTheory.mFalse : mTheory.mTrue;
						for (int i = 0; i < dups; i++) {
							input[numTerms+1+i] = input[i];
						}
						final Term lhs = mTheory.term(SMTLIBConstants.EQUALS, input);
						final Term rhs;
						if (numTerms == 1) {
							rhs = isFalse ? mTheory.term(SMTLIBConstants.NOT, nterms[0]) : nterms[0];
						} else {
							rhs = mTheory.term(SMTLIBConstants.NOT, mTheory.term(SMTLIBConstants.OR, output));
						}
						final Term eq = mTheory.term(SMTLIBConstants.EQUALS, lhs, rhs);
						final Term rewrite = mSmtInterpol.term(ProofConstants.FN_REWRITE, mSmtInterpol.annotate(eq, rule));
						checkLemmaOrRewrite(rewrite, new Term[] { eq });
					}
				}
			}
		}
	}

	public void checkIteTermBound(final Term iteTerm, final Term baseTerm, final Rational min, final Rational max) {
		final Annotation rule = ProofConstants.AUX_TERM_ITE_BOUND;
		final SMTAffineTerm sumMin = new SMTAffineTerm(baseTerm);
		sumMin.add(min);
		sumMin.add(Rational.MONE, iteTerm);
		final Term leqMin = mTheory.term(SMTLIBConstants.LEQ, sumMin.toTerm(baseTerm.getSort()),
				Rational.ZERO.toTerm(baseTerm.getSort()));
		final Term tautologyMin = mSmtInterpol.term(ProofConstants.FN_TAUTOLOGY, mSmtInterpol.annotate(leqMin, rule));
		checkLemmaOrRewrite(tautologyMin, new Term[] { leqMin });

		final SMTAffineTerm sumMax = new SMTAffineTerm(iteTerm);
		sumMax.add(Rational.MONE, baseTerm);
		sumMax.add(Rational.MONE.mul(max));
		final Term leqMax = mTheory.term(SMTLIBConstants.LEQ, sumMax.toTerm(baseTerm.getSort()),
				Rational.ZERO.toTerm(baseTerm.getSort()));
		final Term tautologyMax = mSmtInterpol.term(ProofConstants.FN_TAUTOLOGY, mSmtInterpol.annotate(leqMax, rule));
		checkLemmaOrRewrite(tautologyMax, new Term[] { leqMax });
	}

	public void checkSomeIteTermBound(final Term[] boolTerms, final Term baseTerm) {
		final Rational[] rationals = new Rational[] { Rational.ZERO, Rational.valueOf(10,1),
				Rational.MONE, Rational.valueOf(new BigInteger("1000000000000000000000000000"), BigInteger.ONE),
				Rational.valueOf(1, 1),
				Rational.valueOf(-5, 1) };
		final Term[] modTerms = new Term[rationals.length];
		for (int i = 0; i < rationals.length; i++) {
			final SMTAffineTerm sum = new SMTAffineTerm(baseTerm);
			sum.add(rationals[i]);
			modTerms[i] = sum.toTerm(baseTerm.getSort());
		}
		final Term simpleIte = mTheory.term(SMTLIBConstants.ITE, boolTerms[0], modTerms[0], modTerms[1]);
		checkIteTermBound(simpleIte, baseTerm, rationals[0], rationals[1]);
		final Term nestedIte = mTheory.term(SMTLIBConstants.ITE, boolTerms[1], simpleIte, modTerms[2]);
		checkIteTermBound(nestedIte, baseTerm, rationals[2], rationals[1]);
		final Term complexIte = mTheory.term(SMTLIBConstants.ITE, boolTerms[2],
				mTheory.term(SMTLIBConstants.ITE, boolTerms[1], modTerms[5], modTerms[3]),
				mTheory.term(SMTLIBConstants.ITE, boolTerms[3], simpleIte,
						mTheory.term(SMTLIBConstants.ITE, boolTerms[0], modTerms[4], nestedIte)));
		checkIteTermBound(complexIte, baseTerm, rationals[5], rationals[3]);
	}

	@Test
	public void testIteTermBound() {
		final Sort intSort = mTheory.getSort(SMTLIBConstants.INT);
		final Sort realSort = mTheory.getSort(SMTLIBConstants.REAL);
		final Term[] intTerms = generateDummyTerms("i", 2, intSort);
		final Term[] realTerms = generateDummyTerms("r", 2, realSort);
		final Term[] boolTerms = generateDummyTerms("b", 6, mTheory.getBooleanSort());
		final SMTAffineTerm intSum1 = new SMTAffineTerm(intTerms[0]);
		intSum1.mul(Rational.TWO);
		final SMTAffineTerm intSum2 = new SMTAffineTerm(intTerms[0]);
		intSum2.mul(Rational.MONE);
		final SMTAffineTerm intSum3 = new SMTAffineTerm(intTerms[0]);
		intSum3.add(Rational.MONE, intTerms[1]);
		intSum3.add(Rational.TWO);
		final SMTAffineTerm realSum1 = new SMTAffineTerm(realTerms[0]);
		realSum1.mul(Rational.valueOf(2, 3));
		final SMTAffineTerm realSum2 = new SMTAffineTerm(realTerms[0]);
		realSum2.mul(Rational.MONE);
		final SMTAffineTerm realSum3 = new SMTAffineTerm(realTerms[0]);
		realSum3.add(Rational.valueOf(-5, 7), realTerms[1]);
		realSum3.add(Rational.TWO);

		final Term[] baseTerms = new Term[] { intTerms[0], realTerms[0], mTheory.rational(Rational.ZERO, intSort),
				mTheory.rational(Rational.ONE, intSort), mTheory.rational(Rational.MONE, intSort),
				mTheory.rational(Rational.ZERO, realSort), mTheory.rational(Rational.ONE, realSort),
				mTheory.rational(Rational.MONE, realSort), intSum1.toTerm(intSort), intSum2.toTerm(intSort),
				intSum3.toTerm(intSort), intSum1.toTerm(realSort), intSum2.toTerm(realSort), intSum3.toTerm(realSort),
				realSum1.toTerm(realSort), realSum2.toTerm(realSort), realSum3.toTerm(realSort) };
		for (final Term base : baseTerms) {
			checkSomeIteTermBound(boolTerms, base);
		}
	}
}
