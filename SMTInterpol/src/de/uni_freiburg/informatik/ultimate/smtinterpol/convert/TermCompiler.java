/*
 * Copyright (C) 2009-2012 University of Freiburg
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
package de.uni_freiburg.informatik.ultimate.smtinterpol.convert;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import de.uni_freiburg.informatik.ultimate.logic.AnnotatedTerm;
import de.uni_freiburg.informatik.ultimate.logic.Annotation;
import de.uni_freiburg.informatik.ultimate.logic.ApplicationTerm;
import de.uni_freiburg.informatik.ultimate.logic.ConstantTerm;
import de.uni_freiburg.informatik.ultimate.logic.FormulaUnLet;
import de.uni_freiburg.informatik.ultimate.logic.FunctionSymbol;
import de.uni_freiburg.informatik.ultimate.logic.NonRecursive;
import de.uni_freiburg.informatik.ultimate.logic.QuantifiedFormula;
import de.uni_freiburg.informatik.ultimate.logic.Rational;
import de.uni_freiburg.informatik.ultimate.logic.SMTLIBException;
import de.uni_freiburg.informatik.ultimate.logic.Sort;
import de.uni_freiburg.informatik.ultimate.logic.Term;
import de.uni_freiburg.informatik.ultimate.logic.TermTransformer;
import de.uni_freiburg.informatik.ultimate.logic.TermVariable;
import de.uni_freiburg.informatik.ultimate.logic.Theory;
import de.uni_freiburg.informatik.ultimate.smtinterpol.proof.IProofTracker;
import de.uni_freiburg.informatik.ultimate.smtinterpol.proof.ProofConstants;
import de.uni_freiburg.informatik.ultimate.util.datastructures.UnifyHash;

/**
 * Build a representation of the formula where only not, or, ite and =/2 are
 * present.  Linear arithmetic terms are converted into SMTAffineTerms.  We
 * normalize quantifiers to universal quantifiers.  Additionally, this term
 * transformer removes all annotations from the formula.
 * @author Jochen Hoenicke, Juergen Christ
 */
public class TermCompiler extends TermTransformer {

	private boolean mBy0Seen = false;
	private Map<Term, Set<String>> mNames;

	private IProofTracker mTracker;
	private Utils mUtils;
	private final UnifyHash<SMTAffineTerm> mAffineUnifier
		= new UnifyHash<SMTAffineTerm>();

	static class TransitivityStep implements Walker {
		final Term mFirst;

		public TransitivityStep(final Term first) {
			mFirst = first;
		}

		@Override
		public void walk(final NonRecursive engine) {
			final TermCompiler compiler = (TermCompiler) engine;
			final Term second = compiler.getConverted();
			compiler.setResult(compiler.mTracker.transitivity(mFirst, second));
		}
	}

	public void setProofTracker(final IProofTracker tracker) {
		mTracker = tracker;
		mUtils = new Utils(tracker);
	}

	public void setAssignmentProduction(final boolean on) {
		if (on) {
			mNames = new HashMap<Term, Set<String>>();
		} else {
			mNames = null;
		}
	}

	public Map<Term, Set<String>> getNames() {
		return mNames;
	}

	@Override
	public void convert(final Term term) {
		if (term instanceof ApplicationTerm) {
			final ApplicationTerm appTerm = (ApplicationTerm) term;
			final FunctionSymbol fsym = appTerm.getFunction();
			if (fsym.isModelValue()) {
				throw new SMTLIBException("Model values not allowed in input");
			}
			final Term[] params = appTerm.getParameters();
			if (fsym.isLeftAssoc() && params.length > 2) {
				final Theory theory = appTerm.getTheory();
				final String fsymName = fsym.getName();
				if (fsymName == "and" || fsymName == "or" || fsymName == "+" || fsymName == "-" || fsymName == "*") {
					// We keep some n-ary internal operators
					enqueueWalker(new BuildApplicationTerm(appTerm));
					pushTerms(params);
					return;
				}
				Term rhs = params[0];
				for (int i = 1; i < params.length; i++) {
					rhs = theory.term(fsym, rhs, params[i]);
				}
				final Term rewrite = mTracker.buildRewrite(appTerm, rhs, ProofConstants.RW_EXPAND);
				enqueueWalker(new TransitivityStep(rewrite));
				for (int i = params.length - 1; i > 0; i--) {
					final ApplicationTerm binAppTerm = (ApplicationTerm) rhs;
					enqueueWalker(new BuildApplicationTerm(binAppTerm));
					pushTerm(binAppTerm.getParameters()[1]);
					rhs = binAppTerm.getParameters()[0];
				}
				pushTerm(params[0]);
				return;
			}
			if (fsym.isRightAssoc() && params.length > 2) {
				final Theory theory = appTerm.getTheory();
				if (fsym == theory.mImplies) {
					// We keep n-ary implies
					enqueueWalker(new BuildApplicationTerm(appTerm));
					pushTerms(params);
					return;
				}
				Term rhs = params[params.length - 1];
				for (int i = params.length - 2; i >= 0; i--) {
					rhs = theory.term(fsym, params[i], rhs);
				}
				final Term rewrite = mTracker.buildRewrite(appTerm, rhs, ProofConstants.RW_EXPAND);
				enqueueWalker(new TransitivityStep(rewrite));
				for (int i = params.length - 1; i > 0; i--) {
					final ApplicationTerm binAppTerm = (ApplicationTerm) rhs;
					enqueueWalker(new BuildApplicationTerm(binAppTerm));
					rhs = binAppTerm.getParameters()[1];
				}
				pushTerms(params);
				return;
			}
			if (fsym.isChainable() && params.length > 2
					&& !fsym.getName().equals("=")) {
				final Theory theory = appTerm.getTheory();
				final Term[] conjs = new Term[params.length - 1];
				for (int i = 0; i < params.length - 1; i++) {
					conjs[i] = theory.term(fsym, params[i], params[i + 1]);
				}
				final ApplicationTerm rhs = theory.term("and", conjs);
				final Term rewrite = mTracker.buildRewrite(appTerm, rhs, ProofConstants.RW_EXPAND);
				enqueueWalker(new TransitivityStep(rewrite));
				enqueueWalker(new BuildApplicationTerm(rhs));
				pushTerms(conjs);
				return;
			}
		} else if (term instanceof ConstantTerm) {
			final SMTAffineTerm res = SMTAffineTerm.create(term);
			setResult(mTracker.buildRewrite(term, res, ProofConstants.RW_CANONICAL_SUM));
			return;
		}
		super.convert(term);
	}

	@Override
	public void convertApplicationTerm(final ApplicationTerm appTerm, Term[] args) {
		final FunctionSymbol fsym = appTerm.getFunction();
		final Theory theory = appTerm.getTheory();

		final Sort[] paramSorts = fsym.getParameterSorts();
		if (theory.getLogic().isIRA()
			&& paramSorts.length == 2
			&& paramSorts[0].getName().equals("Real")
			&& paramSorts[1] == paramSorts[0]) {
			// IRA-Hack
			if (args == appTerm.getParameters()) {
				args = args.clone();
			}
			for (int i = 0; i < args.length; i++) {
				if (args[i].getSort().getName().equals("Int")) {
					args[i] = mTracker.castReal(args[i], paramSorts[0]);
				}
			}
		}

		final Term convertedApp = mTracker.congruence(mTracker.reflexivity(appTerm), args);
		final Term[] params = ((ApplicationTerm) mTracker.getProvedTerm(convertedApp)).getParameters();

		if (fsym.getDefinition() != null) {
			final HashMap<TermVariable, Term> substs = new HashMap<>();
			for (int i = 0; i < params.length; i++) {
				substs.put(fsym.getDefinitionVars()[i], SMTAffineTerm.cleanup(params[i]));
			}
			final FormulaUnLet unletter = new FormulaUnLet();
			unletter.addSubstitutions(substs);
			final Term expanded = unletter.unlet(fsym.getDefinition());
			final Term expandedProof = mTracker.buildRewrite(mTracker.getProvedTerm(convertedApp), expanded,
					ProofConstants.RW_EXPAND_DEF);
			enqueueWalker(new TransitivityStep(expandedProof));
			pushTerm(expanded);
			return;
		}

		if (fsym.isIntern()) {
			switch (fsym.getName()) {
			case "not":
				setResult(mUtils.convertNot(convertedApp));
				return;
			case "and":
				setResult(mUtils.convertAnd(convertedApp));
				return;
			case "or":
				setResult(mUtils.convertOr(convertedApp));
				return;
			case "xor":
				setResult(mUtils.convertXor(convertedApp));
				return;
			case "=>":
				setResult(mUtils.convertImplies(convertedApp));
				return;
			case "ite":
				setResult(mUtils.convertIte(convertedApp));
				return;
			case "=":
				setResult(mUtils.convertEq(convertedApp));
				return;
			case "distinct":
				setResult(mUtils.convertDistinct(convertedApp));
				return;
			case "<=":
			{
				final Term lhs = mTracker.getProvedTerm(convertedApp);
				Term rhs = SMTAffineTerm.create(params[0])
						.add(SMTAffineTerm.create(Rational.MONE, params[1]))
						.normalize(this);
				rhs = theory.term("<=", rhs, theory.constant(Rational.ZERO, rhs.getSort()));
				final Term rewrite = mTracker.buildRewrite(lhs, rhs, ProofConstants.RW_LEQ_TO_LEQ0);
				setResult(mUtils.convertLeq0(mTracker.transitivity(convertedApp, rewrite)));
				return;
			}
			case ">=":
			{
				final Term lhs = mTracker.getProvedTerm(convertedApp);
				Term rhs = SMTAffineTerm.create(params[1])
						.add(SMTAffineTerm.create(Rational.MONE, params[0]))
						.normalize(this);
				rhs = theory.term("<=", rhs, theory.constant(Rational.ZERO, rhs.getSort()));
				final Term rewrite = mTracker.buildRewrite(lhs, rhs, ProofConstants.RW_GEQ_TO_LEQ0);
				setResult(mUtils.convertLeq0(mTracker.transitivity(convertedApp, rewrite)));
				return;
			}
			case ">":
			{
				final Term lhs = mTracker.getProvedTerm(convertedApp);
				Term rhs = SMTAffineTerm.create(params[0])
						.add(SMTAffineTerm.create(Rational.MONE, params[1]))
						.normalize(this);
				final Term leq = theory.term("<=", rhs, theory.constant(Rational.ZERO, rhs.getSort()));
				rhs = theory.term("not", leq);
				Term rewrite = mTracker.buildRewrite(lhs, rhs, ProofConstants.RW_GT_TO_LEQ0);
				final Term leqRewrite = mUtils.convertLeq0(mTracker.reflexivity(leq));
				rewrite = mTracker.congruence(mTracker.transitivity(convertedApp, rewrite), new Term[] { leqRewrite });
				setResult(mUtils.convertNot(rewrite));
				return;
			}
			case "<":
			{
				final Term lhs = mTracker.getProvedTerm(convertedApp);
				Term rhs = SMTAffineTerm.create(params[1])
						.add(SMTAffineTerm.create(Rational.MONE, params[0]))
						.normalize(this);
				final Term leq = theory.term("<=", rhs, theory.constant(Rational.ZERO, rhs.getSort()));
				rhs = theory.term("not", leq);
				Term rewrite = mTracker.buildRewrite(lhs, rhs, ProofConstants.RW_LT_TO_LEQ0);
				final Term leqRewrite = mUtils.convertLeq0(mTracker.reflexivity(leq));
				rewrite = mTracker.congruence(mTracker.transitivity(convertedApp, rewrite), new Term[] { leqRewrite });
				setResult(mUtils.convertNot(rewrite));
				return;
			}
			case "+":
			{
				final Term lhs = mTracker.getProvedTerm(convertedApp);
				SMTAffineTerm sum = SMTAffineTerm.create(params[0]);
				for (int i = 1; i < params.length; i++) {
					sum = sum.add(SMTAffineTerm.create(params[i]));
				}
				final Term rhs = sum.normalize(this);
				final Term rewrite = mTracker.buildRewrite(lhs, rhs, ProofConstants.RW_CANONICAL_SUM);
				setResult(mTracker.transitivity(convertedApp, rewrite));
				return;
			}
			case "-":
			{
				final Term lhs = mTracker.getProvedTerm(convertedApp);
				SMTAffineTerm result;
				if (params.length == 1) {
					result = SMTAffineTerm.create(params[0]).negate();
				} else {
					result = SMTAffineTerm.create(params[0]);
					for (int i = 1; i < params.length; i++) {
						result = result.add(SMTAffineTerm.create(params[i]).negate());
					}
				}
				final Term rhs = result.normalize(this);
				final Term rewrite = mTracker.buildRewrite(lhs, rhs, ProofConstants.RW_CANONICAL_SUM);
				setResult(mTracker.transitivity(convertedApp, rewrite));
				return;
			}
			case "*":
			{
				final Term lhs = mTracker.getProvedTerm(convertedApp);
				SMTAffineTerm prod = SMTAffineTerm.create(params[0]);
				for (int i = 1; i < params.length; i++) {
					final SMTAffineTerm factor = SMTAffineTerm.create(params[i]);
					if (prod.isConstant()) {
						prod = factor.mul(prod.getConstant());
					} else if (factor.isConstant()) {
						prod = prod.mul(factor.getConstant());
					} else {
						throw new UnsupportedOperationException("Unsupported non-linear arithmetic");
					}
				}
				final Term rhs = prod.normalize(this);
				final Term rewrite = mTracker.buildRewrite(lhs, rhs, ProofConstants.RW_CANONICAL_SUM);
				setResult(mTracker.transitivity(convertedApp, rewrite));
				return;
			}
			case "/":
			{
				final Term lhs = mTracker.getProvedTerm(convertedApp);
				final SMTAffineTerm arg0 = SMTAffineTerm.create(params[0]);
				final SMTAffineTerm arg1 = SMTAffineTerm.create(params[1]);
				if (arg1.isConstant()) {
					if (arg1.getConstant().equals(Rational.ZERO)) {
						mBy0Seen = true;
						final Term rhs = theory.term("@/0", params[0]);
						final Term rewrite = mTracker.reflexivity(rhs);
						setResult(mTracker.transitivity(convertedApp, rewrite));
					} else {
						final Term rhs = arg0.mul(arg1.getConstant().inverse()).normalize(this);
						final Term rewrite = mTracker.buildRewrite(lhs, rhs, ProofConstants.RW_CANONICAL_SUM);
						setResult(mTracker.transitivity(convertedApp, rewrite));
					}
					return;
				} else {
					throw new UnsupportedOperationException("Unsupported non-linear arithmetic");
				}
			}
			case "div":
			{
				final Term lhs = mTracker.getProvedTerm(convertedApp);
				final SMTAffineTerm arg0 = SMTAffineTerm.create(params[0]);
				final SMTAffineTerm arg1 = SMTAffineTerm.create(params[1]);
				final Rational divisor = arg1.getConstant();
				if (arg1.isConstant() && divisor.isIntegral()) {
					if (divisor.equals(Rational.ZERO)) {
						mBy0Seen = true;
						final Term rhs = theory.term("@div0", params[0]);
						final Term rewrite = mTracker.reflexivity(rhs);
						setResult(mTracker.transitivity(convertedApp, rewrite));
					} else if (divisor.equals(Rational.ONE)) {
						final Term rhs = arg0.normalize(this);
						final Term rewrite = mTracker.buildRewrite(lhs, rhs, ProofConstants.RW_DIV_ONE);
						setResult(mTracker.transitivity(convertedApp, rewrite));
					} else if (divisor.equals(Rational.MONE)) {
						final Term rhs = arg0.negate().normalize(this);
						final Term rewrite = mTracker.buildRewrite(lhs, rhs, ProofConstants.RW_DIV_MONE);
						setResult(mTracker.transitivity(convertedApp, rewrite));
					} else if (arg0.isConstant()) {
						// We have (div c0 c1) ==> constDiv(c0, c1)
						final Rational div = constDiv(arg0.getConstant(), arg1.getConstant());
						final Term rhs = SMTAffineTerm.create(div.toTerm(arg0.getSort())).normalize(this);
						final Term rewrite = mTracker.buildRewrite(lhs, rhs, ProofConstants.RW_DIV_CONST);
						setResult(mTracker.transitivity(convertedApp, rewrite));
					} else {
						setResult(convertedApp);
					}
					return;
				} else {
					throw new UnsupportedOperationException("Unsupported non-linear arithmetic");
				}
			}
			case "mod":
			{
				final Term lhs = mTracker.getProvedTerm(convertedApp);
				final SMTAffineTerm arg0 = SMTAffineTerm.create(params[0]);
				final SMTAffineTerm arg1 = SMTAffineTerm.create(params[1]);
				final Rational divisor = arg1.getConstant();
				if (arg1.isConstant() && divisor.isIntegral()) {
					if (divisor.equals(Rational.ZERO)) {
						mBy0Seen = true;
						final Term rhs = theory.term("@mod0", params[0]);
						final Term rewrite = mTracker.reflexivity(rhs);
						setResult(mTracker.transitivity(convertedApp, rewrite));
					} else if (divisor.equals(Rational.ONE)) {
						// (mod x 1) == 0
						final Term rhs = SMTAffineTerm.create(
								Rational.ZERO.toTerm(arg0.getSort()))
								.normalize(this);
						final Term rewrite = mTracker.buildRewrite(lhs, rhs, ProofConstants.RW_MODULO_ONE);
						setResult(mTracker.transitivity(convertedApp, rewrite));
					} else if (divisor.equals(Rational.MONE)) {
						// (mod x -1) == 0
						final Term rhs = SMTAffineTerm.create(
								Rational.ZERO.toTerm(arg0.getSort()))
								.normalize(this);
						final Term rewrite = mTracker.buildRewrite(lhs, rhs, ProofConstants.RW_MODULO_MONE);
						setResult(mTracker.transitivity(convertedApp, rewrite));
					} else if (arg0.isConstant()) {
						// We have (mod c0 c1) ==> c0 - c1 * constDiv(c0, c1)
						final Rational c0 = arg0.getConstant();
						final Rational c1 = arg1.getConstant();
						final Rational mod = c0.sub(constDiv(c0, c1).mul(c1));
						final Term rhs = SMTAffineTerm.create(
								mod.toTerm(arg0.getSort())).normalize(this);
						final Term rewrite = mTracker.buildRewrite(lhs, rhs, ProofConstants.RW_MODULO_CONST);
						setResult(mTracker.transitivity(convertedApp, rewrite));
					} else {
						final SMTAffineTerm ydiv =
								SMTAffineTerm.create(theory.term(
										"div", arg0, arg1)).
										mul(arg1.getConstant());
						final Term rhs = arg0.add(ydiv.negate()).normalize(this);
						final Term rewrite = mTracker.buildRewrite(lhs, rhs, ProofConstants.RW_MODULO);
						setResult(mTracker.transitivity(convertedApp, rewrite));
					}
					return;
				} else {
					throw new UnsupportedOperationException("Unsupported non-linear arithmetic");
				}
			}
			case "to_real":
			{
				final SMTAffineTerm arg = SMTAffineTerm.create(params[0]);
				final Term lhs = mTracker.getProvedTerm(convertedApp);
				final Term rhs = arg.typecast(fsym.getReturnSort()).normalize(this);
				final Term rewrite = mTracker.buildRewrite(lhs, rhs, ProofConstants.RW_TO_REAL);
				setResult(mTracker.transitivity(convertedApp, rewrite));
				return;
			}
			case "to_int":
			{
				// We don't convert to_int here but defer it to the clausifier
				// But we simplify it here...
				final SMTAffineTerm arg0 = SMTAffineTerm.create(params[0]);
				if (arg0.isConstant()) {
					final Term lhs = mTracker.getProvedTerm(convertedApp);
					final Term rhs = SMTAffineTerm.create(
							arg0.getConstant().floor().toTerm(
									fsym.getReturnSort())).normalize(this);
					final Term rewrite = mTracker.buildRewrite(lhs, rhs, ProofConstants.RW_TO_INT);
					setResult(mTracker.transitivity(convertedApp, rewrite));
					return;
				}
				break;
			}
			case "divisible":
			{
				final Term lhs = mTracker.getProvedTerm(convertedApp);
				final SMTAffineTerm arg0 = SMTAffineTerm.create(params[0]);
				final SMTAffineTerm arg1 = SMTAffineTerm.create(
						Rational.valueOf(fsym.getIndices()[0], BigInteger.ONE),
						arg0.getSort());
				Term rhs;
				if (arg1.getConstant().equals(Rational.ONE)) {
					rhs = theory.mTrue;
				} else if (arg0.isConstant()) {
					final Rational c0 = arg0.getConstant();
					final Rational c1 = arg1.getConstant();
					final Rational mod = c0.sub(constDiv(c0, c1).mul(c1));
					rhs = mod.equals(Rational.ZERO) ? theory.mTrue : theory.mFalse;
				} else {
					rhs = theory.term("=", arg0, SMTAffineTerm.create(
						theory.term("div", arg0, arg1)).mul(arg1.getConstant())
						.normalize(this));
				}
				final Term rewrite = mTracker.buildRewrite(lhs, rhs, ProofConstants.RW_DIVISIBLE);
				setResult(mTracker.transitivity(convertedApp, rewrite));
				return;
			}
			case "store": {
				final Term lhs = mTracker.getProvedTerm(convertedApp);
				final Term array = params[0];
				final Term idx = params[1];
				final Term nestedIdx = getArrayStoreIdx(array);
				if (nestedIdx != null) {
					// Check for store-over-store
					final SMTAffineTerm diff = SMTAffineTerm.create(idx).add(
							SMTAffineTerm.create(nestedIdx).negate());
					if (diff.isConstant() && diff.getConstant().equals(Rational.ZERO)) {
						// Found store-over-store => ignore inner store
						final ApplicationTerm appArray = (ApplicationTerm) array;
						final Term rhs = theory.term("store", appArray.getParameters()[0], params[1], params[2]);
						final Term rewrite = mTracker.buildRewrite(lhs, rhs, ProofConstants.RW_STORE_OVER_STORE);
						setResult(mTracker.transitivity(convertedApp, rewrite));
						return;
					}
				}
				break;
			}
			case "select": {
				final Term lhs = mTracker.getProvedTerm(convertedApp);
				final Term array = params[0];
				final Term idx = params[1];
				final Term nestedIdx = getArrayStoreIdx(array);
				if (nestedIdx != null) {
					// Check for select-over-store
					final SMTAffineTerm diff = SMTAffineTerm.create(idx).add(
							SMTAffineTerm.create(nestedIdx).negate());
					if (diff.isConstant()) {
						// Found select-over-store
						final ApplicationTerm store = (ApplicationTerm) array;
						final Term rhs;
						if (diff.getConstant().equals(Rational.ZERO)) {
							// => transform into value
							rhs = store.getParameters()[2];
						} else { // Both indices are numerical and distinct.
							// => transform into (select a idx)
							rhs = theory.term("select", store.getParameters()[0], idx);
						}
						final Term rewrite = mTracker.buildRewrite(lhs, rhs, ProofConstants.RW_SELECT_OVER_STORE);
						setResult(mTracker.transitivity(convertedApp, rewrite));
						return;
					}
				}
				break;
			}
			}
		}
		setResult(convertedApp);
	}

	public final static Rational constDiv(final Rational c0, final Rational c1) {
		final Rational div = c0.div(c1);
		return c1.isNegative() ? div.ceil() : div.floor();
	}

	private final static Term getArrayStoreIdx(final Term array) {
		if (array instanceof ApplicationTerm) {
			final ApplicationTerm appArray = (ApplicationTerm) array;
			final FunctionSymbol arrayFunc = appArray.getFunction();
			if (arrayFunc.isIntern() && arrayFunc.getName().equals("store")) {
				// (store a i v)
				return appArray.getParameters()[1];
			}
		}
		return null;
	}

	@Override
	public void postConvertQuantifier(final QuantifiedFormula old, final Term newBody) {
		if (old.getQuantifier() == QuantifiedFormula.EXISTS) {
			setResult(mTracker.exists(old, newBody));
		} else {
			final Theory theory = old.getTheory();
			final Term notNewBody = mTracker.congruence(mTracker.reflexivity(theory.term("not", old.getSubformula())),
					new Term[] { newBody });
			setResult(mUtils.convertNot(mTracker.forall(old, notNewBody)));
		}
	}

	@Override
	public void postConvertAnnotation(final AnnotatedTerm old,
			final Annotation[] newAnnots, final Term newBody) {
		if (mNames != null && newBody.getSort() == newBody.getTheory().getBooleanSort()) {
			final Annotation[] oldAnnots = old.getAnnotations();
			for (final Annotation annot : oldAnnots) {
				if (annot.getKey().equals(":named")) {
					Set<String> oldNames = mNames.get(newBody);
					if (oldNames == null) {
						oldNames = new HashSet<String>();
						mNames.put(newBody, oldNames);
					}
					oldNames.add(annot.getValue().toString());
				}
			}
		}
		setResult(mTracker.transitivity(mTracker.buildRewrite(old, old.getSubterm(), ProofConstants.RW_STRIP),
				newBody));
	}
	/**
	 * Get and reset the division-by-0 seen flag.
	 * @return The old division-by-0 seen flag.
	 */
	public boolean resetBy0Seen() {
		final boolean old = mBy0Seen;
		mBy0Seen = false;
		return old;
	}

	public SMTAffineTerm unify(final SMTAffineTerm affine) {
		return mAffineUnifier.unify(affine);
	}
}
