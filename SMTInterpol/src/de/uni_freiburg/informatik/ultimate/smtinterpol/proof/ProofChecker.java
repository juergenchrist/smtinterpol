/*
 * Copyright (C) 2009-2017 University of Freiburg
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
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Stack;

import de.uni_freiburg.informatik.ultimate.logic.AnnotatedTerm;
import de.uni_freiburg.informatik.ultimate.logic.Annotation;
import de.uni_freiburg.informatik.ultimate.logic.ApplicationTerm;
import de.uni_freiburg.informatik.ultimate.logic.ConstantTerm;
import de.uni_freiburg.informatik.ultimate.logic.FormulaUnLet;
import de.uni_freiburg.informatik.ultimate.logic.FunctionSymbol;
import de.uni_freiburg.informatik.ultimate.logic.NonRecursive;
import de.uni_freiburg.informatik.ultimate.logic.Rational;
import de.uni_freiburg.informatik.ultimate.logic.Script;
import de.uni_freiburg.informatik.ultimate.logic.Sort;
import de.uni_freiburg.informatik.ultimate.logic.Term;
import de.uni_freiburg.informatik.ultimate.logic.TermTransformer;
import de.uni_freiburg.informatik.ultimate.logic.TermVariable;
import de.uni_freiburg.informatik.ultimate.logic.Theory;
import de.uni_freiburg.informatik.ultimate.smtinterpol.LogProxy;
import de.uni_freiburg.informatik.ultimate.smtinterpol.convert.SMTAffineTerm;
import de.uni_freiburg.informatik.ultimate.smtinterpol.util.SymmetricPair;

/**
 * This proof checker checks compliance of SMTInterpol proofs with its documented format.
 *
 * @author Pascal Raiola, Jochen Hoenicke, Tanja Schindler
 */
public class ProofChecker extends NonRecursive {

	/*
	 * The proof checker uses a non-recursive iteration through the proof tree. The main type in a proof tree is the
	 * sort {@literal @}Proof. Each term of this sort proves a formula and the main task of this code is to compute the
	 * proven formula. The whole proof term should prove the formula false.
	 *
	 * The main idea of this non-recursive algorithm is to push a proof walker for the {@literal @}Proof terms on the
	 * todo stack, which will push the proved term of type Bool onto the result stack mStackResults. To handle functions
	 * like {@literal @}eq, {@literal @}cong, {@literal @}trans that take a {@literal @}Proof term as input, first a
	 * XYWalker the function XY is pushed on the todo stack and then the ProofWalker for the {@literal @}Proof terms are
	 * pushed. The Walker will then call the corresponding walkXY function which checks the proved arguments, computes
	 * the final proved formula and pushes that on the result stack.
	 *
	 * Simple functions that don't take {@literal @}Proof arguments are handled directly by calling the walkXY function.
	 */

	/**
	 * The main proof walker that handles a term of sort {@literal @}Proof. It just calls the walk function.
	 */
	public static class ProofWalker implements Walker {
		final ApplicationTerm mTerm;

		public ProofWalker(final Term term) {
			assert term.getSort().getName().equals("@Proof");
			mTerm = (ApplicationTerm) term;
		}

		@Override
		public void walk(final NonRecursive engine) {
			((ProofChecker) engine).walk(mTerm);
		}
	}

	/**
	 * The proof walker that handles a {@literal @}res application term after its arguments are converted. It just calls
	 * the walkResolution function.
	 */
	public static class ResolutionWalker implements Walker {
		final ApplicationTerm mTerm;

		public ResolutionWalker(final ApplicationTerm term) {
			assert term.getFunction().getName().equals("@res");
			mTerm = term;
		}

		@Override
		public void walk(final NonRecursive engine) {
			((ProofChecker) engine).walkResolution(mTerm);
		}
	}

	/**
	 * The proof walker that handles a {@literal @}eq application term after its arguments are converted. It just calls
	 * the walkEquality function.
	 */
	public static class EqualityWalker implements Walker {
		final ApplicationTerm mTerm;

		public EqualityWalker(final ApplicationTerm term) {
			assert term.getFunction().getName().equals("@eq");
			mTerm = term;
		}

		@Override
		public void walk(final NonRecursive engine) {
			((ProofChecker) engine).walkEquality(mTerm);
		}
	}

	/**
	 * The proof walker that handles a {@literal @}clause application after its first argument is converted. It just
	 * calls the walkClause function.
	 */
	public static class ClauseWalker implements Walker {
		final ApplicationTerm mTerm;

		public ClauseWalker(final ApplicationTerm term) {
			assert term.getFunction().getName().equals("@clause");
			mTerm = term;
		}

		@Override
		public void walk(final NonRecursive engine) {
			((ProofChecker) engine).walkClause(mTerm);
		}
	}

	/**
	 * The proof walker that handles a {@literal @}split application after its first argument is converted. It just
	 * calls the walkSplit function.
	 */
	public static class SplitWalker implements Walker {
		final ApplicationTerm mTerm;

		public SplitWalker(final ApplicationTerm term) {
			assert term.getFunction().getName().equals("@split");
			mTerm = term;
		}

		@Override
		public void walk(final NonRecursive engine) {
			((ProofChecker) engine).walkSplit(mTerm);
		}
	}

	/**
	 * The proof walker that handles a {@literal @}cong application after its arguments are converted. It just calls the
	 * walkCongruence function.
	 */
	public static class CongruenceWalker implements Walker {
		final ApplicationTerm mTerm;

		public CongruenceWalker(final ApplicationTerm term) {
			assert term.getFunction().getName().equals("@cong");
			mTerm = term;
		}

		@Override
		public void walk(final NonRecursive engine) {
			((ProofChecker) engine).walkCongruence(mTerm);
		}
	}

	/**
	 * The proof walker that handles a {@literal @}trans application after its arguments are converted. It just calls
	 * the walkTransitivity function.
	 */
	public static class TransitivityWalker implements Walker {
		final ApplicationTerm mTerm;

		public TransitivityWalker(final ApplicationTerm term) {
			assert term.getFunction().getName().equals("@trans");
			mTerm = term;
		}

		@Override
		public void walk(final NonRecursive engine) {
			((ProofChecker) engine).walkTransitivity(mTerm);
		}
	}

	/**
	 * Class converting Terms to SMTAffineTerm.
	 *
	 * @author Jochen Hoenicke
	 */
	class SMTAffineTermTransformer extends TermTransformer {
		private final HashSet<String> mFuncSet = new HashSet<String>();
		{
			mFuncSet.add("+");
			mFuncSet.add("-");
			mFuncSet.add("*");
			mFuncSet.add("/");
			mFuncSet.add("to_real");
		}

		@Override
		public void convert(final Term t) {
			if (t instanceof ApplicationTerm) {
				final ApplicationTerm appTerm = (ApplicationTerm) t;
				final FunctionSymbol funcSymb = appTerm.getFunction();
				if (funcSymb.isIntern() && mFuncSet.contains(funcSymb.getName())) {
					super.convert(t);
					return;
				}
			}
			/* do not descend into any other term */
			setResult(SMTAffineTerm.create(t));
		}

		@Override
		public void convertApplicationTerm(final ApplicationTerm appTerm, final Term[] newArgs) {
			final String funcName = appTerm.getFunction().getName();
			assert mFuncSet.contains(funcName);
			if (funcName == "+") {
				SMTAffineTerm sum = (SMTAffineTerm) newArgs[0];
				for (int i = 1; i < newArgs.length; i++) {
					sum = sum.add((SMTAffineTerm) newArgs[i]);
				}
				setResult(sum);
			} else if (funcName == "-") {
				SMTAffineTerm sum = (SMTAffineTerm) newArgs[0];
				if (newArgs.length == 1) {
					/* unary minus */
					sum = sum.negate();
				} else {
					/* subtract other arguments */
					for (int i = 1; i < newArgs.length; i++) {
						sum = sum.add(((SMTAffineTerm) newArgs[i]).negate());
					}
				}
				setResult(sum);
			} else if (funcName == "*") {
				SMTAffineTerm prod = (SMTAffineTerm) newArgs[0];
				for (int i = 1; i < newArgs.length; i++) {
					final SMTAffineTerm other = (SMTAffineTerm) newArgs[i];
					if (prod.isConstant()) {
						prod = other.mul(prod.getConstant());
					} else if (other.isConstant()) {
						prod = prod.mul(other.getConstant());
					} else {
						setResult(SMTAffineTerm.create(appTerm));
						return;
					}
				}
				setResult(prod);
			} else if (funcName == "/") {
				SMTAffineTerm prod = (SMTAffineTerm) newArgs[0];
				for (int i = 1; i < newArgs.length; i++) {
					final SMTAffineTerm other = (SMTAffineTerm) newArgs[i];
					if (other.isConstant() && !other.getConstant().equals(Rational.ZERO)) {
						prod = prod.mul(other.getConstant().inverse());
					} else {
						setResult(SMTAffineTerm.create(appTerm));
						return;
					}
				}
				setResult(prod);
			} else if (funcName == "to_real") {
				final SMTAffineTerm t = (SMTAffineTerm) newArgs[0];
				setResult(t.typecast(appTerm.getSort()));
			} else {
				throw new AssertionError("Unexpected Function: " + funcName);
			}
		}
	}

	/**
	 * The set of all asserted terms (collected from the script by calling getAssertions()). This is used to check the
	 * {@literal @}asserted rules.
	 */
	HashSet<Term> mAssertions;

	/**
	 * The SMT script (mainly used to create terms).
	 */
	Script mSkript;
	/**
	 * The logger where errors are reported.
	 */
	LogProxy mLogger;
	/**
	 * The number of reported errors.
	 */
	int mError;

	/**
	 * Debugging flags. This contains the names of the functionalities for which debugging logs should be created.
	 */
	HashSet<String> mDebug = new HashSet<String>();

	/**
	 * The proof cache. It maps each converted proof to the boolean term it proves.
	 */
	HashMap<Term, Term> mCacheConv;

	/**
	 * The result stack. This contains the terms proved by the proof terms.
	 */
	Stack<Term> mStackResults = new Stack<Term>();
	/**
	 * The result stack. This contains the original proof terms to find out of sync problems.
	 */
	Stack<Term> mStackResultsDebug = new Stack<Term>();

	/**
	 * An auxiliary object to convert Terms to SMT affine terms needed for various linear arithmetic proves.
	 */
	SMTAffineTermTransformer mAffineConverter = new SMTAffineTermTransformer();

	/**
	 * Create a proof checker.
	 *
	 * @param script
	 *            An SMT2 script.
	 * @param logger
	 *            The logger where errors are reported.
	 */
	public ProofChecker(final Script script, final LogProxy logger) {
		mSkript = script;
		mLogger = logger;
	}

	/**
	 * Check a proof for consistency. This reports minor errors and may throw assertions on some major errors.
	 *
	 * @param proof
	 *            the proof to check.
	 * @return true, if no errors were found.
	 */
	public boolean check(Term proof) {
		final FormulaUnLet unletter = new FormulaUnLet();
		final Term[] assertions = mSkript.getAssertions();
		mAssertions = new HashSet<Term>(assertions.length);
		for (final Term ass : assertions) {
			mAssertions.add(unletter.transform(ass));
		}

		// Just for debugging
		// mDebug.add("currently");
		// mDebug.add("hardTerm");
		// mDebug.add("LemmaLAadd");
		// mDebug.add("calculateTerm");
		// mDebug.add("WalkerPath");
		// mDebug.add("WalkerPathSmall");
		// mDebug.add("LemmaCC");
		// mDebug.add("newRules");
		// mDebug.add("convertAppID");
		// mDebug.add("cacheUsed");
		// mDebug.add("cacheUsedSmall");
		// mDebug.add("allSubpaths");
		// mDebug.add("split_notOr");
		// mDebug.add("CacheRuntimeCheck");
		// mLogger.setLevel(Level.DEBUG);

		// Initializing the proof-checker-cache
		mCacheConv = new HashMap<Term, Term>();
		mError = 0;
		// Now non-recursive:
		proof = unletter.unlet(proof);
		run(new ProofWalker(proof));

		assert (mStackResults.size() == 1);
		final Term result = stackPopCheck(proof);
		if (!isApplication("false", result)) {
			reportError("The proof did not yield a contradiction but " + result);
		}
		// clear state
		mAssertions = null;
		mCacheConv = null;

		return mError == 0;
	}

	private void reportError(final String msg) {
		mLogger.error(msg);
		mError++;
	}

	private void reportWarning(final String msg) {
		mLogger.warn(msg);
	}

	/**
	 * The proof walker. This takes a proof term and pushes the proven formula on the result stack. It also checks the
	 * proof cache to prevent running over the same term twice.
	 *
	 * @param proofTerm
	 *            The proof term. Its sort must be {@literal @}Proof.
	 */
	public void walk(final ApplicationTerm proofTerm) {
		/* Check the cache, if the unfolding step was already done */
		if (mCacheConv.containsKey(proofTerm)) {
			if (mDebug.contains("CacheRuntimeCheck")) {
				mLogger.debug("Cache-RT: K: " + proofTerm.toString() + " (known)");
			}
			if (mDebug.contains("cacheUsed")) {
				mLogger.debug("Calculation of the term " + proofTerm.toString() + " is known: "
						+ mCacheConv.get(proofTerm).toString());
			}
			if (mDebug.contains("cacheUsedSmall")) {
				mLogger.debug("Calculation known.");
			}
			stackPush(mCacheConv.get(proofTerm), proofTerm);
			return;
		} else if (mDebug.contains("CacheRuntimeCheck")) {
			mLogger.debug("Cache-RT: U: " + proofTerm.toString() + " (unknown)");
		}

		/* Get the function and parameters */
		final String rulename = proofTerm.getFunction().getName();
		final Term[] params = proofTerm.getParameters();

		/* Just for debugging */
		if (mDebug.contains("currently")) {
			mLogger.debug("Currently looking at: " + rulename + " \t (function)");
		}

		/* Look at the rule name and treat each different */
		if (rulename == "@res") {
			/*
			 * The resolution rule.
			 *
			 * This function is expected to have as first argument the main clause. The other parameters are clauses
			 * annotated with a pivot literal, on which they are resolved.
			 */
			enqueueWalker(new ResolutionWalker(proofTerm));
			for (int i = params.length - 1; i >= 1; i--) {
				final AnnotatedTerm pivotClause = (AnnotatedTerm) params[i];
				enqueueWalker(new ProofWalker(pivotClause.getSubterm()));
			}
			enqueueWalker(new ProofWalker(params[0]));
		} else if (rulename == "@eq") {
			enqueueWalker(new EqualityWalker(proofTerm));
			for (int i = params.length - 1; i >= 0; i--) {
				enqueueWalker(new ProofWalker(params[i]));
			}
		} else if (rulename == "@cong") {
			enqueueWalker(new CongruenceWalker(proofTerm));
			for (int i = params.length - 1; i >= 0; i--) {
				enqueueWalker(new ProofWalker(params[i]));
			}
		} else if (rulename == "@trans") {
			enqueueWalker(new TransitivityWalker(proofTerm));
			for (int i = params.length - 1; i >= 0; i--) {
				enqueueWalker(new ProofWalker(params[i]));
			}
		} else if (rulename == "@refl") {
			walkReflexivity(proofTerm);
		} else if (rulename == "@lemma") {
			walkLemma(proofTerm);
		} else if (rulename == "@tautology") {
			walkTautology(proofTerm);
		} else if (rulename == "@asserted") {
			walkAsserted(proofTerm);
		} else if (rulename == "@rewrite") {
			walkRewrite(proofTerm);
		} else if (rulename == "@intern") {
			walkIntern(proofTerm);
		} else if (rulename == "@split") {
			enqueueWalker(new SplitWalker(proofTerm));
			enqueueWalker(new ProofWalker(((AnnotatedTerm) params[0]).getSubterm()));
		} else if (rulename == "@clause") {
			enqueueWalker(new ClauseWalker(proofTerm));
			enqueueWalker(new ProofWalker(params[0]));
		} else {
			throw new AssertionError("Unknown proof rule " + rulename + ".");
		}
	}

	/* === Theory lemmas === */

	/**
	 * Walk a lemma rule. This checks the correctness of the lemma and returns the lemma, which is always the annotated
	 * sub term of this application. The result is pushed to the stack instead of being returned.
	 *
	 * If the lemma cannot be verified, an error is reported but the lemma is still used to check the remainder of the
	 * proof.
	 *
	 * @param lemmaApp
	 *            The {@literal @}lemma application.
	 */
	public void walkLemma(final ApplicationTerm lemmaApp) {
		/*
		 * The argument of the @lemma application is a single clause annotated with the lemma type, which has as object
		 * all the necessary annotation. For example (@lemma (! (or (not (= a b)) (not (= b c)) (= a c)) :CC ((= a c)
		 * :path (a b c))))
		 */
		final AnnotatedTerm annTerm = (AnnotatedTerm) lemmaApp.getParameters()[0];
		final String lemmaType = annTerm.getAnnotations()[0].getKey();
		final Object lemmaAnnotation = annTerm.getAnnotations()[0].getValue();
		final Term lemma = annTerm.getSubterm();
		final Term[] clause = termToClause(lemma);

		if (mDebug.contains("currently")) {
			mLogger.debug("Lemma-type: " + lemmaType);
		}

		if (lemmaType == ":LA") {
			checkLALemma(clause, (Term[]) lemmaAnnotation);
		} else if (lemmaType == ":CC" || lemmaType == ":read-over-weakeq" || lemmaType == ":weakeq-ext") {
			checkArrayLemma(lemmaType, clause, (Object[]) lemmaAnnotation);
		} else if (lemmaType == ":trichotomy") {
			checkTrichotomy(clause);
		} else if (lemmaType == ":EQ") {
			checkEQLemma(clause);
		} else {
			reportError("Cannot deal with lemma " + lemmaType);
			mLogger.error(annTerm);
		}

		stackPush(lemma, lemmaApp);
	}

	/**
	 * Check an array lemma for correctness. If a problem is found, an error is reported.
	 *
	 * @param type
	 *            the lemma type
	 * @param clause
	 *            the clause to check
	 * @param ccAnnotation
	 *            the argument of the :CC annotation.
	 */
	private void checkArrayLemma(final String type, final Term[] clause, final Object[] ccAnnotation) {
		int startSubpathAnnot = 0;

		Term goalEquality;
		if (ccAnnotation[0] instanceof Term) {
			startSubpathAnnot++;
			goalEquality = (Term) ccAnnotation[0];
		} else {
			goalEquality = mSkript.term("false");
		}

		/*
		 * weakPaths maps from a symmetric pair to the set of weak indices such that a weak path was proven for this
		 * pair. strongPaths contains the sets of all proven strong paths.
		 */
		final HashSet<SymmetricPair<Term>> strongPaths = new HashSet<SymmetricPair<Term>>();
		/* indexDiseqs contains all index equalities in the clause */
		final HashSet<SymmetricPair<Term>> indexDiseqs = new HashSet<SymmetricPair<Term>>();

		/* collect literals and search for the disequality */
		boolean foundDiseq = false;
		for (final Term literal : clause) {
			if (isApplication("not", literal)) {
				Term atom = ((ApplicationTerm) literal).getParameters()[0];
				atom = unquote(atom);
				if (!isApplication("=", atom)) {
					reportError("Unknown literal in CC lemma.");
					return;
				}
				final Term[] sides = ((ApplicationTerm) atom).getParameters();
				if (sides.length != 2) {
					reportError("Expected binary equality, found " + atom);
					return;
				}
				strongPaths.add(new SymmetricPair<Term>(sides[0], sides[1]));
			} else {
				final Term atom = unquote(literal);
				if (!isApplication("=", atom)) {
					reportError("Unknown literal in CC lemma.");
					return;
				}
				if (unquote(literal) != goalEquality) {
					if (type == ":CC") {
						reportError("Unexpected positive literal in CC lemma.");
					}
					final Term[] sides = ((ApplicationTerm) atom).getParameters();
					indexDiseqs.add(new SymmetricPair<Term>(sides[0], sides[1]));
				}
				foundDiseq = true;
			}
		}

		SymmetricPair<Term> lastPath = null;
		/*
		 * Check the paths in reverse order. Collect proven paths in a hash set, so that they can be used later.
		 */
		final HashMap<SymmetricPair<Term>, HashSet<Term>> weakPaths = new HashMap<SymmetricPair<Term>, HashSet<Term>>();
		for (int i = ccAnnotation.length - 2; i >= startSubpathAnnot; i -= 2) {
			if (!(ccAnnotation[i] instanceof String) || !(ccAnnotation[i + 1] instanceof Object[])) {
				reportError("Malformed Array subpath");
				return;
			}
			final Object[] annot = (Object[]) ccAnnotation[i + 1];
			if (ccAnnotation[i] == ":weakpath") {
				if (annot.length != 2 || !(annot[0] instanceof Term) || !(annot[1] instanceof Term[])) {
					reportError("Malformed Array weakpath");
					return;
				}
				final Term idx = (Term) annot[0];
				final Term[] path = (Term[]) annot[1];
				/* check weak path */
				checkArrayPath(idx, path, strongPaths, null, indexDiseqs);
				/* add it to premises */
				final SymmetricPair<Term> endPoints = new SymmetricPair<Term>(path[0], path[path.length - 1]);
				HashSet<Term> weakIdxs = weakPaths.get(endPoints);
				if (weakIdxs == null) {
					weakIdxs = new HashSet<Term>();
					weakPaths.put(endPoints, weakIdxs);
				}
				weakIdxs.add(idx);
			} else if (ccAnnotation[i] == ":subpath" && (annot instanceof Term[])) {
				final Term[] path = (Term[]) annot;
				final SymmetricPair<Term> endPoints = new SymmetricPair<Term>(path[0], path[path.length - 1]);
				/* check path */
				checkArrayPath(null, path, strongPaths, weakPaths.get(endPoints), indexDiseqs);
				/* add it to premises */
				strongPaths.add(endPoints);
				lastPath = endPoints;
			} else {
				reportError("Unknown subpath annotation");
			}
		}

		if (startSubpathAnnot == 0) {
			/* check that the mainPath is really a contradiction */
			final SMTAffineTerm diff =
					convertAffineTerm(lastPath.getFirst()).add(convertAffineTerm(lastPath.getSecond()).negate());
			if (!diff.isConstant() || diff.getConstant().equals(Rational.ZERO)) {
				reportError("No diseq, but main path is " + lastPath);
			}
		} else {
			if (!foundDiseq) {
				reportError("Did not find goal equality in CC lemma");
			}
			if (!isApplication("=", goalEquality)) {
				reportError("Goal equality is not an equality in CC lemma");
				return;
			}
			final Term[] sides = ((ApplicationTerm) goalEquality).getParameters();
			if (sides.length != 2) {
				reportError("Expected binary equality in CC lemma");
				return;
			}
			final SymmetricPair<Term> endPoints = new SymmetricPair<Term>(sides[0], sides[1]);
			if (strongPaths.contains(endPoints)) {
				/* everything fine */
				return;
			}

			if (isApplication("select", sides[0]) && isApplication("select", sides[1])) {
				final Term[] p1 = ((ApplicationTerm) sides[0]).getParameters();
				final Term[] p2 = ((ApplicationTerm) sides[1]).getParameters();
				if (p1[1] == p2[1] || strongPaths.contains(new SymmetricPair<Term>(p1[1], p2[1]))) {
					final HashSet<Term> weakPs = weakPaths.get(new SymmetricPair<Term>(p1[0], p2[0]));
					if (weakPs != null && (weakPs.contains(p1[1]) || weakPs.contains(p2[1]))) {
						return;
					}
				}
			}
			reportError("Cannot explain main equality " + goalEquality);
		}
	}

	/**
	 * Check if each step in a CC or array path is valid. This means, for each pair of consecutive terms, either there
	 * is a strong path between the two, or there exists a select path explaining element equality of array terms at the
	 * weak path index, or it is a weak store step, or a congruence. This reports errors using reportError.
	 *
	 * @param weakIdx
	 *            the weak path index or null for subpaths.
	 * @param path
	 *            the path to check.
	 * @param strongPaths
	 *            the equality literals and subpaths from the CC- and array lemma annotations.
	 * @param weakPaths
	 *            the weak paths (given by their weak index) needed for the main path in array lemmas, null if path is
	 *            not the main path.
	 * @param indexDiseqs
	 *            the index disequality literals.
	 */
	void checkArrayPath(final Term weakIdx, final Term[] path, final HashSet<SymmetricPair<Term>> strongPaths, final HashSet<Term> weakPaths,
			final HashSet<SymmetricPair<Term>> indexDiseqs) {
		if (path.length < 2) {
			reportError("Short path in ArrayLemma");
			return;
		}
		for (int i = 0; i < path.length - 1; i++) {
			final SymmetricPair<Term> pair = new SymmetricPair<Term>(path[i], path[i + 1]);
			/* check for strong path first */
			if (strongPaths.contains(pair)) {
				continue;
			}
			/* check for select path (only for weakeq-ext) */
			if (weakIdx != null) {
				/*
				 * check for select path with select indices equal to weakIdx, both trivially equal and proven equal by
				 * a strong path
				 */
				if (checkSelectPath(pair, weakIdx, strongPaths)) {
					continue;
				}
			}
			/* check for weak store step */
			final Term storeIndex = checkStoreIndex(path[i], path[i + 1]);
			if (storeIndex != null) {
				if (weakIdx != null) {
					if (indexDiseqs.contains(new SymmetricPair<Term>(weakIdx, storeIndex))) {
						continue;
					}
					final SMTAffineTerm diff = convertAffineTerm(weakIdx).add(convertAffineTerm(storeIndex).negate());
					if (diff.isConstant() && !diff.getConstant().equals(Rational.ZERO)) {
						continue;
					}
				} else {
					if (weakPaths != null && weakPaths.contains(storeIndex)) {
						continue;
					}
				}
			}
			/* check for congruence */
			if (path[i] instanceof ApplicationTerm && path[i + 1] instanceof ApplicationTerm) {
				final ApplicationTerm app1 = (ApplicationTerm) path[i];
				final ApplicationTerm app2 = (ApplicationTerm) path[i + 1];
				if (app1.getFunction() == app2.getFunction()) {
					final Term[] p1 = app1.getParameters();
					final Term[] p2 = app2.getParameters();
					for (int j = 0; j < p1.length; j++) {
						if (p1[j] == p2[j]) {
							continue;
						}
						if (!strongPaths.contains(new SymmetricPair<Term>(p1[j], p2[j]))) {
							reportError("unexplained equality");
						}
					}
					continue;
				}
			}
			reportError("unexplained equality " + path[i] + " == " + path[i + 1]);
		}
	}

	private boolean checkSelectPath(final SymmetricPair<Term> termPair, final Term weakIdx,
			final HashSet<SymmetricPair<Term>> strongPaths) {
		for (final SymmetricPair<Term> strongPath : strongPaths) {
			/* check for select terms */
			if (!(isApplication("select", strongPath.getFirst()) && isApplication("select", strongPath.getSecond()))) {
				continue;
			}
			/* check select arrays */
			final Term array1 = ((ApplicationTerm) strongPath.getFirst()).getParameters()[0];
			final Term array2 = ((ApplicationTerm) strongPath.getSecond()).getParameters()[0];
			final SymmetricPair<Term> arrayPair = new SymmetricPair<Term>(array1, array2);
			if (!arrayPair.equals(termPair)) {
				continue;
			}
			/* check index paths */
			final Term idx1 = ((ApplicationTerm) strongPath.getFirst()).getParameters()[1];
			final Term idx2 = ((ApplicationTerm) strongPath.getSecond()).getParameters()[1];
			if (idx1 != weakIdx && !strongPaths.contains(new SymmetricPair<Term>(idx1, weakIdx))) {
				continue;
			}
			if (idx2 != weakIdx && !strongPaths.contains(new SymmetricPair<Term>(idx2, weakIdx))) {
				continue;
			}
			return true;
		}
		return false;
	}

	private Term checkStoreIndex(final Term term1, final Term term2) {
		if (isApplication("store", term1)) {
			final Term[] storeArgs = ((ApplicationTerm) term1).getParameters();
			if (storeArgs[0] == term2) {
				return storeArgs[1];
			}
		}
		if (isApplication("store", term2)) {
			final Term[] storeArgs = ((ApplicationTerm) term2).getParameters();
			if (storeArgs[0] == term1) {
				return storeArgs[1];
			}
		}
		return null;
	}

	/**
	 * Check an LA lemma for correctness. If a problem is found, an error is reported.
	 *
	 * @param clause
	 *            the clause to check
	 * @param coefficients
	 *            the argument of the :LA annotation, which is the list of Farkas coefficients.
	 */
	private void checkLALemma(final Term[] clause, final Term[] coefficients) {
		if (clause.length != coefficients.length) {
			reportError("Clause and coefficients have different length");
			return;
		}

		boolean sumHasStrict = false;
		SMTAffineTerm sum = null;
		for (int i = 0; i < clause.length; i++) {
			final Rational coeff = convertAffineTerm(coefficients[i]).getConstant();
			if (coeff.equals(Rational.ZERO)) {
				reportWarning("Coefficient in LA lemma is zero.");
				continue;
			}
			Term literal = clause[i];
			boolean isNegated = false;
			if (isApplication("not", literal)) {
				literal = ((ApplicationTerm) literal).getParameters()[0];
				isNegated = true;
			}
			literal = unquote(literal);
			boolean isStrict;
			if (isNegated) {
				if (isApplication("<=", literal)) {
					isStrict = false;
					if (coeff.isNegative()) {
						reportError("Negative coefficient for <=");
					}
				} else if (isApplication("=", literal)) {
					isStrict = false;
				} else if (isApplication("<", literal)) {
					isStrict = true;
					if (coeff.isNegative()) {
						reportError("Negative coefficient for <");
					}
				} else {
					reportError("Unknown atom in LA lemma: " + literal);
					continue;
				}
			} else {
				if (isApplication("<=", literal)) {
					isStrict = true;
					if (!coeff.isNegative()) {
						reportError("Positive coefficient for negated <=");
					}
				} else if (isApplication("<", literal)) {
					isStrict = false;
					if (!coeff.isNegative()) {
						reportError("Positive coefficient for negated <");
					}
				} else {
					reportError("Unknown atom in LA lemma: " + literal);
					continue;
				}
			}
			final Term[] params = ((ApplicationTerm) literal).getParameters();
			if (params.length != 2) {
				reportError("not a binary comparison in LA lemma");
				continue;
			}
			if (!isZero(params[1])) {
				reportError("Right hand side is not zero");
			}
			SMTAffineTerm affine = convertAffineTerm(params[0]);
			if (isStrict && params[0].getSort().getName().equals("Int")) {
				/*
				 * make integer equalities non-strict by adding one. x < 0 iff x + 1 <= 0 x > 0 iff x - 1 >= 0
				 */
				affine = affine.add(isNegated ? Rational.ONE : Rational.MONE);
				isStrict = false;
			}
			affine = affine.mul(coeff);
			if (sum == null) {
				sum = affine;
			} else {
				if (sum.getSort() != affine.getSort()) {
					if (sum.getSort().getName() == "Real") {
						affine = affine.typecast(sum.getSort());
					} else {
						sum = sum.typecast(affine.getSort());
					}
				}
				sum = sum.add(affine);
			}
			sumHasStrict |= isStrict;
		}
		final Rational sumConstant = sum.getConstant();
		if (sum.isConstant()) {
			final int signum = sumConstant.signum();
			if (signum > 0 || (sumHasStrict && signum == 0)) {
				return;
			}
		}
		reportError("LA lemma sums up to " + sum + (sumHasStrict ? " < 0" : " <= 0"));
	}

	/**
	 * Check an trichotomy lemma for correctness. If a problem is found, an error is reported.
	 *
	 * @param clause
	 *            the clause to check.
	 */
	private void checkTrichotomy(final Term[] clause) {
		if (clause.length != 3) { // NOCHECKSTYLE
			reportError("Malformed Trichotomy clause: " + Arrays.toString(clause));
			return;
		}

		SMTAffineTerm trichotomyTerm = null;
		final int NEQ = 1;
		final int LEQ = 2;
		final int GEQ = 4;
		int foundlits = 0;
		for (Term lit : clause) {
			final boolean isNegated = isApplication("not", lit);
			if (isNegated) {
				lit = ((ApplicationTerm) lit).getParameters()[0];
			}
			lit = unquote(lit);

			Rational offset = Rational.ZERO;
			if (isApplication("=", lit)) {
				if (isNegated) {
					reportError("Equality in trichotomy has wrong polarity");
					return;
				}
				if ((foundlits & NEQ) != 0) {
					reportError("Two Disequalities in trichotomy");
					return;
				}
				foundlits |= NEQ;
			} else if (isApplication("<=", lit)) {
				if (isNegated) {
					if ((foundlits & GEQ) != 0) {
						reportError("Two > in trichotomy");
						return;
					}
					foundlits |= GEQ;
				} else {
					if ((foundlits & LEQ) != 0) {
						reportError("Two <= in trichotomy");
						return;
					}
					foundlits |= LEQ;
					offset = Rational.MONE; // x <= 0 iff x - 1 < 0
				}
			} else if (isApplication("<", lit)) {
				if (isNegated) {
					if ((foundlits & GEQ) != 0) {
						reportError("Two >= in trichotomy");
						return;
					}
					foundlits |= GEQ;
					offset = Rational.ONE; // x >= 0 iff x + 1 > 0
				} else {
					if ((foundlits & LEQ) != 0) {
						reportError("Two < in trichotomy");
						return;
					}
					foundlits |= LEQ;
				}
			} else {
				reportError("Unknown literal in trichotomy " + lit);
				return;
			}
			final Term[] params = ((ApplicationTerm) lit).getParameters();
			if (params.length != 2) {
				reportError("not a binary comparison in LA lemma");
				return;
			}
			if (!isZero(params[1])) {
				reportError("Right hand side is not zero");
			}
			if (offset != Rational.ZERO && !params[1].getSort().getName().equals("Int")) {
				reportError("<= or >= in non-integer trichotomy");
			}
			final SMTAffineTerm affine = convertAffineTerm(params[0]).add(offset);
			if (trichotomyTerm == null) {
				trichotomyTerm = affine;
			} else if (!trichotomyTerm.equals(affine)) {
				reportError("Invalid trichotomy");
			}
		}
		assert foundlits == (NEQ + LEQ + GEQ);
	}

	/**
	 * Check an EQ lemma for correctness. If a problem is found, an error is reported.
	 *
	 * @param clause
	 *            the clause to check
	 */
	private void checkEQLemma(final Term[] clause) {
		if (clause.length != 2) {
			reportError("Lemma :EQ must have two literals");
			return;
		}
		Term lit1 = clause[0];
		Term lit2 = clause[1];
		if (isApplication("not", lit1)) {
			lit1 = ((ApplicationTerm) lit1).getParameters()[0];
		} else if (isApplication("not", lit2)) {
			lit2 = ((ApplicationTerm) lit2).getParameters()[0];
		} else {
			reportError("Lemma :EQ must have one negated literal");
			return;
		}
		lit1 = unquote(lit1);
		lit2 = unquote(lit2);

		if (!isApplication("=", lit1) || ((ApplicationTerm) lit1).getParameters().length != 2
				|| !isApplication("=", lit2) || ((ApplicationTerm) lit2).getParameters().length != 2) {
			reportError("Lemma :EQ must have one equality and one disequality");
			return;
		}
		final Term[] lit1Args = ((ApplicationTerm) lit1).getParameters();
		final Term[] lit2Args = ((ApplicationTerm) lit1).getParameters();

		SMTAffineTerm diff1 = convertAffineTerm(lit1Args[0]).add(convertAffineTerm(lit1Args[1]).negate());
		diff1 = diff1.div(diff1.getGcd());
		SMTAffineTerm diff2 = convertAffineTerm(lit2Args[0]).add(convertAffineTerm(lit2Args[1]).negate());
		diff2 = diff2.div(diff2.getGcd());
		if (!diff1.equals(diff2) && !diff1.equals(diff2.negate())) {
			reportError("Error in lemma :EQ");
		}
	}

	/* === Tautologies === */

	public void walkTautology(final ApplicationTerm tautologyApp) {
		/*
		 * Tautologies are created to define the meaning of proxy literals like (! (or a b c) :quoted), or of proxy
		 * terms like (ite cond t1 t2) or (div x 5). They are of the form
		 *
		 * (@tautology (! (or ...) :type))
		 *
		 * The possible types are defined in ProofConstants.AUX_*
		 */
		final AnnotatedTerm annTerm = (AnnotatedTerm) tautologyApp.getParameters()[0];
		final String tautKind = annTerm.getAnnotations()[0].getKey();
		final Term tautology = annTerm.getSubterm();
		final Term[] clause = termToClause(tautology);

		boolean result;
		switch (tautKind) {
		case ":trueNotFalse":
			result = (clause.length == 1 && clause[0] == mSkript.term("not",
					mSkript.term("=", mSkript.term("true"), mSkript.term("false"))));
			break;
		case ":or+":
			result = checkTautOrPos(clause);
			break;
		case ":or-":
			result = checkTautOrNeg(clause);
			break;
		case ":ite+1":
		case ":ite+2":
		case ":ite+red":
		case ":ite-1":
		case ":ite-2":
		case ":ite-red":
			result = checkTautIte(tautKind, clause);
			break;
		case ":=+1":
		case ":=+2":
		case ":=-1":
		case ":=-2":
			result = checkTautEq(tautKind, clause);
			break;
		case ":termITE":
			result = checkTautTermIte(clause);
			break;
		case ":excludedMiddle1":
		case ":excludedMiddle2":
			result = checkTautExcludedMiddle(clause);
			break;
		case ":divHigh":
		case ":divLow":
		case ":toIntHigh":
		case ":toIntLow":
			result = checkTautLowHigh(tautKind, clause);
			break;
		case ":store":
			result = checkTautStore(clause);
			break;
		case ":diff":
			result = checkTautDiff(clause);
			break;
		default:
			result = false;
			break;
		}

		if (!result) {
			reportError("Malformed/unknown tautology rule " + tautologyApp);
		}

		/* push it and check later */
		stackPush(tautology, tautologyApp);
	}

	private boolean checkTautOrPos(final Term[] clause) {
		// Check for the form: (or (not (! (or p1 ... pn) :quoted)) p1 ... pn)
		final Term lit = unquote(negate(clause[0]));
		if (!isApplication("or", lit) || ((ApplicationTerm) lit).getParameters().length != clause.length - 1) {
			return false;
		}
		final Term[] params = ((ApplicationTerm) lit).getParameters();
		for (int i = 0; i < params.length; i++) {
			if (params[i] != clause[i + 1]) {
				return false;
			}
		}
		return true;
	}

	private boolean checkTautOrNeg(final Term[] clause) {
		// Check for the form: (or (! (or p1 ... pn) :quoted) (not pi))
		if (clause.length != 2) {
			return false;
		}
		final Term lit = unquote(clause[0]);
		if (!isApplication("or", lit)) {
			return false;
		}
		if (!isApplication("not", clause[1])) {
			return false;
		}
		final Term otherLit = ((ApplicationTerm) clause[1]).getParameters()[0];
		final Term[] params = ((ApplicationTerm) lit).getParameters();
		for (int i = 0; i < params.length; i++) {
			if (params[i] == otherLit) {
				/* found it; everything okay */
				return true;
			}
		}
		return false;
	}

	private boolean checkTautIte(final String tautKind, final Term[] clause) {
		if (clause.length != 3) {
			return false;
		}
		Term lit = clause[0];
		final boolean negated = isApplication("not", lit);
		if (negated) {
			lit = negate(lit);
		}
		lit = unquote(lit);
		if (!isApplication("ite", lit)) {
			return false;
		}
		final Term[] iteParams = ((ApplicationTerm) lit).getParameters();
		switch (tautKind) {
		case ":ite+1":
			// (or (not (! (ite cond then else)) :quoted)) (not cond) then)
			return negated && clause[1] == mSkript.term("not", iteParams[0]) && clause[2] == iteParams[1];
		case ":ite+2":
			// (or (not (! (ite cond then else)) :quoted)) cond else)
			return negated && clause[1] == iteParams[0] && clause[2] == iteParams[2];
		case ":ite+red":
			return negated && clause[1] == iteParams[1] && clause[2] == iteParams[2];
		case ":ite-1":
			// (or (! (ite cond then else) :quoted) (not cond) (not then))
			return !negated && clause[1] == mSkript.term("not", iteParams[0])
					&& clause[2] == mSkript.term("not", iteParams[1]);
		case ":ite-2":
			// (or (! (ite cond then else) :quoted) cond (not else))
			return !negated && clause[1] == iteParams[0] && clause[2] == mSkript.term("not", iteParams[2]);
		case ":ite-red":
			return !negated && clause[1] == mSkript.term("not", iteParams[1])
					&& clause[2] == mSkript.term("not", iteParams[2]);
		}
		return false;
	}

	private boolean checkTautEq(final String tautKind, final Term[] clause) {
		if (clause.length != 3) {
			return false;
		}
		Term lit = clause[0];
		final boolean negated = isApplication("not", lit);
		if (negated) {
			lit = negate(lit);
		}
		lit = unquote(lit);
		if (!isApplication("=", lit)) {
			return false;
		}
		final Term[] eqParams = ((ApplicationTerm) lit).getParameters();
		if (eqParams.length != 2) {
			return false;
		}
		switch (tautKind) {
		case ":=+1":
			// (or (not (! (or t1 t2) :quoted)) t1 (not t2))
			return negated && clause[1] == eqParams[0] && clause[2] == mSkript.term("not", eqParams[1]);
		case ":=+2":
			// (or (not (! (or t1 t2) :quoted)) (not t1) t2)
			return negated && clause[1] == mSkript.term("not", eqParams[0]) && clause[2] == eqParams[1];
		case ":=-1":
			// (or (! (or t1 t2) :quoted) t1 t2)
			return !negated && clause[1] == eqParams[0] && clause[2] == eqParams[1];
		case ":=-2":
			// (or (! (or t1 t2) :quoted) (not t1) (not t2))
			return !negated && clause[1] == mSkript.term("not", eqParams[0])
					&& clause[2] == mSkript.term("not", eqParams[1]);
		}
		return false;
	}

	private boolean checkTautTermIte(final Term[] clause) {
		if (clause.length < 2) {
			return false;
		}
		// Check for the form: (or (not c1) c2 (not c3) (= (ite c1 (ite c2 * (ite c3 x *)) *) x))
		final Term iteEq = clause[clause.length - 1];
		final Theory theory = iteEq.getTheory();
		if (!isApplication("=", iteEq)) {
			return false;
		}
		final Term[] eqParams = ((ApplicationTerm) iteEq).getParameters();
		if (eqParams.length != 2) {
			return false;
		}
		Term term = eqParams[0];
		for (int i = 0; i < clause.length - 1; i++) {
			if (!isApplication("ite", term)) {
				return false;
			}
			final Term[] iteParams = ((ApplicationTerm) term).getParameters();
			if (clause[i] == theory.term("not", iteParams[0])) {
				// descend into then branch
				term = iteParams[1];
			} else if (clause[i] == iteParams[0]) {
				// descend into else branch
				term = iteParams[2];
			} else {
				return false;
			}
		}
		// check right hand side of equality
		return term == eqParams[1];
	}

	private boolean checkTautLowHigh(final String ruleName, final Term[] clause) {
		if (clause.length != 1) {
			return false;
		}
		Term literal = clause[0];
		final boolean isToInt = ruleName.startsWith(":toInt");
		final boolean isHigh = ruleName.endsWith("High");
		// isLow: (<= (+ (- arg0) (* d candidate) ) 0)
		// aka. (>= (- arg0 (* d candidate)) 0)
		// isHigh: (not (<= (+ (- arg0) (* d candidate) |d|) 0)
		// aka. (< (- arg0 (* d candidate)) |d|)
		// where candidate is (div arg0 d) or (to_int arg0) and d is 1 for toInt.

		if (isHigh) {
			if (!isApplication("not", literal)) {
				return false;
			}
			literal = ((ApplicationTerm) literal).getParameters()[0];
		}
		if (!isApplication("<=", literal)) {
			return false;
		}
		final Term[] leArgs = ((ApplicationTerm) literal).getParameters();
		final SMTAffineTerm lhs = convertAffineTerm(leArgs[0]);
		if (!isZero(leArgs[1])) {
			return false;
		}
		if (lhs.getSort().getName() != (isToInt ? "Real" : "Int")) {
			return false;
		}

		final String func = isToInt ? "to_int" : "div";
		// search for the toInt or div term; note that there can be several div terms in case of a nested div.
		for (final Term candidate : lhs.getSummands().keySet()) {
			if (isApplication(func, candidate)) {
				final Term[] args = ((ApplicationTerm) candidate).getParameters();
				// compute d
				final Rational d;
				SMTAffineTerm summand;
				if (isToInt) {
					d = Rational.ONE;
					summand = SMTAffineTerm.create(candidate).typecast(lhs.getSort());
				} else {
					final SMTAffineTerm arg1 = convertAffineTerm(args[1]);
					if (!arg1.isConstant()) {
						return false;
					}
					d = arg1.getConstant();
					if (d.equals(Rational.ZERO)) {
						return false;
					}
					summand = SMTAffineTerm.create(d, candidate);
				}
				// compute expected term and check that lhs equals it.
				final SMTAffineTerm arg0 = convertAffineTerm(args[0]);
				if (isHigh) {
					final SMTAffineTerm expected = arg0.negate().add(summand).add(d.abs());
					if (lhs.equals(expected)) {
						return true;
					}
				} else {
					final SMTAffineTerm expected = arg0.negate().add(summand);
					if (lhs.equals(expected)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private boolean checkTautExcludedMiddle(final Term[] clause) {
		if (clause.length != 2) {
			return false;
		}
		// Check for the form: (or (not p) (= p true))
		// or (or p (= p false))

		final boolean negated = isApplication("not", clause[0]);
		final Term lit = negated ? negate(clause[0]) : clause[0];
		if (!isApplication("=", clause[1])) {
			return false;
		}
		final Theory theory = lit.getTheory();
		final Term[] eqArgs = ((ApplicationTerm) clause[1]).getParameters();
		if (eqArgs.length != 2 || eqArgs[0] != lit || eqArgs[1] != (negated ? theory.mTrue : theory.mFalse)) {
			return false;
		}
		return true;
	}

	/**
	 * Check an select over store lemma for correctness. If a problem is found, an error is reported.
	 *
	 * @param clause
	 *            the clause to check.
	 */
	private boolean checkTautStore(final Term[] clause) {
		// Store tautology have the form
		// (@tautology (! (= (select (store a i v) i) v) :store))
		if (clause.length == 1) {
			final Term eqlit = clause[0];
			if (isApplication("=", eqlit)) {
				final Term[] sides = ((ApplicationTerm) eqlit).getParameters();
				if (isApplication("select", sides[0])) {
					final ApplicationTerm select = (ApplicationTerm) sides[0];
					final Term store = select.getParameters()[0];
					if (isApplication("store", store)) {
						final Term[] storeArgs = ((ApplicationTerm) store).getParameters();
						if (storeArgs[1] == select.getParameters()[1] && storeArgs[2] == sides[1]) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	private boolean checkTautDiff(final Term[] clause) {
		if (clause.length != 2) {
			return false;
		}
		final Term arrEq = clause[0];
		final Term selectDisEq = clause[1];
		if (isApplication("not", selectDisEq)) {
			final Term selectEq = ((ApplicationTerm) selectDisEq).getParameters()[0];
			if (isApplication("=", arrEq) && isApplication("=", selectEq)) {
				final Term[] arrays = ((ApplicationTerm) arrEq).getParameters();
				final Term[] selects = ((ApplicationTerm) selectEq).getParameters();
				if (arrays.length == 2 && selects.length == 2 && isApplication("select", selects[0])
						&& isApplication("select", selects[1])) {
					boolean failure = false;
					for (int i = 0; i < 2; i++) {
						final Term[] selectArgs = ((ApplicationTerm) selects[i]).getParameters();
						if (selectArgs.length != 2 || selectArgs[0] != arrays[i]
								|| !isApplication("@diff", selectArgs[1])) {
							failure = true;
							break;
						}
						final Term[] diffArgs = ((ApplicationTerm) selectArgs[1]).getParameters();
						if (diffArgs.length != 2 || diffArgs[0] != arrays[0] || diffArgs[1] != arrays[1]) {
							failure = true;
							break;
						}
					}
					return !failure;
				}
			}
		}
		return false;
	}

	void walkAsserted(final ApplicationTerm assertedApp) {
		final Term assertedTerm = assertedApp.getParameters()[0];
		if (!mAssertions.contains(assertedTerm)) {
			reportError("Could not find asserted term " + assertedTerm);
		}
		/* Just return the part without @asserted */
		stackPush(assertedTerm, assertedApp);
	}

	void walkReflexivity(final ApplicationTerm reflexivityApp) {
		// sanity check (caller and typechecker should ensure this
		assert reflexivityApp.getFunction().getName() == "@refl";
		assert reflexivityApp.getParameters().length == 1;

		// reflexivity (@refl term) proves (= term term).
		final Theory theory = reflexivityApp.getTheory();
		final Term term = reflexivityApp.getParameters()[0];
		final Term newEquality = theory.term("=", term, term);
		stackPush(newEquality, reflexivityApp);
	}

	void walkTransitivity(final ApplicationTerm transitivityApp) {
		// sanity check (caller and typechecker should ensure this
		assert transitivityApp.getFunction().getName() == "@trans";
		final Term[] params = transitivityApp.getParameters();

		/*
		 * Get the equalities from the stack.
		 */
		final ApplicationTerm[] equalities = new ApplicationTerm[params.length];
		for (int i = params.length - 1; i >= 0; i--) {
			equalities[i] = (ApplicationTerm) stackPopCheck(params[i]);
			/* Check that it is an equality */
			if (equalities[i].getFunction().getName() != "=" || equalities[i].getParameters().length != 2) {
				throw new AssertionError("@trans on a proof of a non-equality: " + equalities[i]);
			}
		}
		for (int i = 0; i < equalities.length - 1; i++) {
			/* check that equalities chain correctly */
			if (equalities[i].getParameters()[1] != equalities[i + 1].getParameters()[0]) {
				throw new AssertionError("@trans doesn't chain: " + equalities[i] + " and " + equalities[i + 1]);
			}
		}
		final Theory theory = transitivityApp.getTheory();
		final Term newEquality = theory.term("=", equalities[0].getParameters()[0],
				equalities[equalities.length - 1].getParameters()[1]);
		stackPush(newEquality, transitivityApp);
	}

	void walkCongruence(final ApplicationTerm congruenceApp) {
		// sanity check (caller and typechecker should ensure this
		assert congruenceApp.getFunction().getName() == "@cong";
		final Term[] params = congruenceApp.getParameters();

		/*
		 * Get the proven equalities from the stack.
		 */
		final ApplicationTerm[] rewrites = new ApplicationTerm[params.length];
		for (int i = rewrites.length - 1; i >= 0; i--) {
			rewrites[i] = (ApplicationTerm) stackPopCheck(params[i]);
			/* Check that it is an equality */
			if (rewrites[i].getFunction().getName() != "=" || rewrites[i].getParameters().length != 2) {
				reportError("@cong on a proof of a non-equality: " + rewrites[i]);
			}
		}
		/* assume that the first equality is of the form (= x (f p1 ... pn)) */
		final ApplicationTerm funcTerm = (ApplicationTerm) rewrites[0].getParameters()[1];
		final Term[] funcParams = funcTerm.getParameters();
		final Term[] newFuncParams = funcParams.clone();
		/* check that the rewrites are of the form (= pi qi) where the i's are increasing */
		int offset = 0;
		for (int i = 1; i < rewrites.length; i++) {
			/* search the parameter that is rewritten */
			while (offset < funcParams.length && funcParams[offset] != rewrites[i].getParameters()[0]) {
				offset++;
			}
			if (offset == funcParams.length) {
				reportError("cannot find rewritten parameter in @cong: " + rewrites[i] + " in " + funcParams);
				break;
			}
			newFuncParams[offset] = rewrites[i].getParameters()[1];
			offset++;
		}
		/* compute the proven equality (= x (f q1 ... qn)) */
		final Theory theory = congruenceApp.getTheory();
		final Term newEquality =
				theory.term("=", rewrites[0].getParameters()[0], theory.term(funcTerm.getFunction(), newFuncParams));
		stackPush(newEquality, congruenceApp);
	}

	void walkRewrite(final ApplicationTerm rewriteApp) {
		/*
		 * A rewrite rule has the form (@rewrite (! (= lhs rhs) :rewriteRule)) The rewriteRule gives the name of the
		 * rewrite axiom. The equality (= lhs rhs) is then a simple rewrite axiom.
		 */
		assert rewriteApp.getFunction().getName() == "@rewrite";
		assert rewriteApp.getParameters().length == 1;
		final AnnotatedTerm annotatedRule = (AnnotatedTerm) rewriteApp.getParameters()[0];
		final String rewriteRule = annotatedRule.getAnnotations()[0].getKey();
		final ApplicationTerm rewriteEq = (ApplicationTerm) annotatedRule.getSubterm();
		final Term[] eqParams = rewriteEq.getParameters();
		if (!isApplication("=", rewriteEq) || eqParams.length != 2) {
			reportError("Rewrite rule is not a binary equality");
		}

		/*
		 * The result is simply the equality (without annotation). Compute it first and check later.
		 */
		stackPush(rewriteEq, rewriteApp);

		boolean okay;
		switch (rewriteRule) {
		case ":expand":
			okay = checkRewriteExpand(eqParams[0], eqParams[1]);
			break;
		case ":expandDef":
			okay = checkRewriteExpandDef(eqParams[0], eqParams[1]);
			break;
		case ":trueNotFalse":
			okay = checkRewriteTrueNotFalse(eqParams[0], eqParams[1]);
			break;
		case ":constDiff":
			okay = checkRewriteConstDiff(eqParams[0], eqParams[1]);
			break;
		case ":eqTrue":
		case ":eqFalse":
			okay = checkRewriteEqTrueFalse(rewriteRule, eqParams[0], eqParams[1]);
			break;
		case ":eqSimp":
		case ":eqSame":
			okay = checkRewriteEqSimp(rewriteRule, eqParams[0], eqParams[1]);
			break;
		case ":eqBinary":
			okay = checkRewriteEqBinary(eqParams[0], eqParams[1]);
			break;
		case ":distinctBool":
		case ":distinctSame":
		case ":distinctNeg":
		case ":distinctTrue":
		case ":distinctFalse":
		case ":distinctBoolEq":
		case ":distinctBinary":
			okay = checkRewriteDistinct(rewriteRule, eqParams[0], eqParams[1]);
			break;
		case ":notSimp":
			okay = checkRewriteNot(eqParams[0], eqParams[1]);
			break;
		case ":orSimp":
			okay = checkRewriteOrSimp(rewriteRule, eqParams[0], eqParams[1]);
			break;
		case ":orTaut":
			okay = checkRewriteOrTaut(eqParams[0], eqParams[1]);
			break;
		case ":iteTrue":
		case ":iteFalse":
		case ":iteSame":
		case ":iteBool1":
		case ":iteBool2":
		case ":iteBool3":
		case ":iteBool4":
		case ":iteBool5":
		case ":iteBool6":
			okay = checkRewriteIte(rewriteRule, eqParams[0], eqParams[1]);
			break;
		case ":andToOr":
			okay = checkRewriteAndToOr(eqParams[0], eqParams[1]);
			break;
		case ":xorToDistinct":
			okay = checkRewriteXorToDistinct(eqParams[0], eqParams[1]);
			break;
		case ":impToOr":
			okay = checkRewriteImpToOr(eqParams[0], eqParams[1]);
			break;
		case ":strip":
			okay = checkRewriteStrip(eqParams[0], eqParams[1]);
			break;
		case ":canonicalSum":
			okay = checkRewriteCanonicalSum(eqParams[0], eqParams[1]);
			break;
		case ":leqToLeq0":
		case ":ltToLeq0":
		case ":geqToLeq0":
		case ":gtToLeq0":
			okay = checkRewriteToLeq0(rewriteRule, eqParams[0], eqParams[1]);
			break;
		case ":leqTrue":
		case ":leqFalse":
			okay = checkRewriteLeq(rewriteRule, eqParams[0], eqParams[1]);
			break;
		case ":desugar":
			okay = checkRewriteDesugar(eqParams[0], eqParams[1]);
			break;
		case ":divisible":
			okay = checkRewriteDivisible(eqParams[0], eqParams[1]);
			break;
		case ":storeOverStore":
			okay = checkStoreOverStore(eqParams[0], eqParams[1]);
			break;
		case ":selectOverStore":
			okay = checkSelectOverStore(eqParams[0], eqParams[1]);
			break;
		case ":flatten":
			okay = checkRewriteFlatten(eqParams[0], eqParams[1]);
			break;
		case ":storeRewrite":
			okay = checkStoreRewrite(eqParams[0], eqParams[1]);
			break;
		default:
			okay = checkRewriteMisc(rewriteRule, rewriteEq);
			break;
		}

		if (!okay) {
			reportError("Malformed/unknown @rewrite rule " + rewriteApp);
		}
	}

	boolean checkRewriteAndToOr(final Term lhs, final Term rhs) {
		// expect lhs: (and ...), rhs: (not (or (not ...)))
		if (!isApplication("and", lhs) || !isApplication("not", rhs)) {
			return false;
		}
		final Term orTerm = ((ApplicationTerm) rhs).getParameters()[0];
		if (!isApplication("or", orTerm)) {
			return false;
		}
		final Term[] andParams = ((ApplicationTerm) lhs).getParameters();
		final Term[] orParams = ((ApplicationTerm) orTerm).getParameters();
		if (andParams.length != orParams.length) {
			return false;
		}
		for (int i = 0; i < andParams.length; i++) {
			if (orParams[i] != mSkript.term("not", andParams[i])) {
				return false;
			}
		}
		return true;
	}

	boolean checkRewriteImpToOr(final Term lhs, final Term rhs) {
		// expect lhs: (=> p1 ... pn), rhs: (or pn (not p1) .. (not pn-1))))
		if (!isApplication("=>", lhs) || !isApplication("or", rhs)) {
			return false;
		}
		final Term[] impParams = ((ApplicationTerm) lhs).getParameters();
		final Term[] orParams = ((ApplicationTerm) rhs).getParameters();
		if (impParams.length != orParams.length) {
			return false;
		}
		for (int i = 0; i < impParams.length - 1; i++) {
			if (orParams[i + 1] != mSkript.term("not", impParams[i])) {
				return false;
			}
		}
		return orParams[0] == impParams[impParams.length - 1];
	}

	boolean checkRewriteXorToDistinct(final Term lhs, final Term rhs) {
		// expect lhs: (xor a b), rhs: (distinct a b)
		if (!isApplication("xor", lhs) || !isApplication("distinct", rhs)) {
			return false;
		}
		final Term[] xorParams = ((ApplicationTerm) lhs).getParameters();
		final Term[] distinctParams = ((ApplicationTerm) rhs).getParameters();
		if (xorParams.length != 2 || distinctParams.length != 2) {
			return false;
		}
		return xorParams[0] == distinctParams[0] && xorParams[1] == distinctParams[1];
	}

	boolean checkRewriteStrip(final Term lhs, final Term rhs) {
		// expect lhs: (! (...) :...), rhs: ...
		return (lhs instanceof AnnotatedTerm) && rhs == ((AnnotatedTerm) lhs).getSubterm();
	}

	boolean checkRewriteTrueNotFalse(final Term lhs, final Term rhs) {
		// expect lhs: (= ... true ... false ...)), rhs: false
		if (!isApplication("=", lhs) || !isApplication("false", rhs)) {
			return false;
		}
		final Term[] lhsParams = ((ApplicationTerm) lhs).getParameters();
		boolean foundTrue = false, foundFalse = false;
		for (final Term t : lhsParams) {
			if (isApplication("true", t)) {
				foundTrue = true;
			}
			if (isApplication("false", t)) {
				foundFalse = true;
			}
		}
		return foundTrue && foundFalse;
	}

	boolean checkRewriteConstDiff(final Term lhs, final Term rhs) {
		// lhs: (= ... 5 ... 7 ...), rhs: false
		if (!isApplication("=", lhs) || !isApplication("false", rhs)) {
			return false;
		}
		final Term[] lhsParams = ((ApplicationTerm) lhs).getParameters();
		if (!lhsParams[0].getSort().isNumericSort()) {
			return false;
		}
		Rational lastConstant = null;
		for (final Term t : lhsParams) {
			final SMTAffineTerm value = convertAffineTerm(t);
			if (value.isConstant()) {
				if (lastConstant == null) {
					lastConstant = value.getConstant();
				} else if (!lastConstant.equals(value)) {
					return true;
				}
			}
		}
		return false;
	}

	boolean checkRewriteEqTrueFalse(final String rewriteRule, final Term lhs, Term rhs) {
		// lhs: (= l1 true ln), rhs: (not (or (not l1) ... (not ln)))
		// duplicated entries in lhs should be removed in rhs.
		final boolean trueCase = rewriteRule.equals(":eqTrue");
		if (!isApplication("=", lhs)) {
			return false;
		}
		boolean found = false;
		final LinkedHashSet<Term> args = new LinkedHashSet<Term>();
		for (final Term t : ((ApplicationTerm) lhs).getParameters()) {
			if (trueCase && isApplication("true", t)) {
				found = true;
			} else if (!trueCase && isApplication("false", t)) {
				found = true;
			} else {
				args.add(t);
			}
		}
		if (!found) {
			return false;
		}
		if (args.size() == 1) {
			// special case for only one argument:
			// (= true x) --> x
			// (= false x) --> (not x)
			final Term x = args.iterator().next();
			return trueCase ? rhs == x : rhs == mSkript.term("not", x);
		} else {
			if (!isApplication("not", rhs)) {
				return false;
			}
			rhs = negate(rhs);
			if (!isApplication("or", rhs)) {
				return false;
			}
			final Term[] rhsArgs = ((ApplicationTerm) rhs).getParameters();
			if (rhsArgs.length != args.size()) {
				return false;
			}
			int i = 0;
			for (final Term t : args) {
				if (rhsArgs[i] != (trueCase ? mSkript.term("not", t) : t)) {
					return false;
				}
				i++;
			}
			return true;
		}
	}

	boolean checkRewriteEqSimp(final String rewriteRule, final Term lhs, final Term rhs) {
		// lhs: (= ...), rhs: (= ...) or true, if all entries in rhs are the same.
		// duplicated entries in lhs should be removed in rhs.
		if (!isApplication("=", lhs)) {
			return false;
		}
		final LinkedHashSet<Term> args = new LinkedHashSet<Term>();
		for (final Term t : ((ApplicationTerm) lhs).getParameters()) {
			args.add(t);
		}
		if (args.size() == 1) {
			return rewriteRule.equals(":eqSame") && isApplication("true", rhs);
		} else {
			if (!rewriteRule.equals(":eqSimp")) {
				return false;
			}
			if (!isApplication("=", rhs)) {
				return false;
			}
			final Term[] rhsArgs = ((ApplicationTerm) rhs).getParameters();
			if (rhsArgs.length != args.size()) {
				return false;
			}
			int i = 0;
			for (final Term t : args) {
				if (rhsArgs[i] != t) {
					return false;
				}
				i++;
			}
			return true;
		}
	}

	boolean checkRewriteEqBinary(final Term lhs, Term rhs) {
		// eqBinary is like expand (chainable) combined with andToOr
		if (!isApplication("=", lhs)) {
			return false;
		}
		final Term[] lhsParams = ((ApplicationTerm) lhs).getParameters();
		if (lhsParams.length < 3) {
			return false;
		}
		if (!isApplication("not", rhs)) {
			return false;
		}
		rhs = ((ApplicationTerm) rhs).getParameters()[0];
		if (!isApplication("or", rhs)) {
			return false;
		}
		final Term[] rhsParams = ((ApplicationTerm) rhs).getParameters();
		if (lhsParams.length != rhsParams.length + 1) {
			return false;
		}
		for (int i = 0; i < rhsParams.length; i++) {
			if (rhsParams[i] != mSkript.term("not", mSkript.term("=", lhsParams[i], lhsParams[i + 1]))) {
				return false;
			}
		}
		return true;
	}

	boolean checkRewriteOrSimp(final String rewriteRule, final Term lhs, final Term rhs) {
		// lhs: (or ...), rhs: (or ...)
		// duplicated entries in lhs and false should be removed in rhs.
		// if only one entry remains, or is omitted, if no entry remains, false is returned.
		if (!isApplication("or", lhs)) {
			return false;
		}
		final LinkedHashSet<Term> args = new LinkedHashSet<Term>();
		for (final Term t : ((ApplicationTerm) lhs).getParameters()) {
			if (!isApplication("false", t)) {
				args.add(t);
			}
		}
		if (args.isEmpty()) {
			return isApplication("false", rhs);
		} else if (args.size() == 1) {
			return rhs == args.iterator().next();
		} else {
			if (!isApplication("or", rhs)) {
				return false;
			}
			final Term[] rhsArgs = ((ApplicationTerm) rhs).getParameters();
			if (rhsArgs.length != args.size()) {
				return false;
			}
			int i = 0;
			for (final Term t : args) {
				if (rhsArgs[i] != t) {
					return false;
				}
				i++;
			}
			return true;
		}
	}

	boolean checkRewriteOrTaut(final Term lhs, final Term rhs) {
		if (!isApplication("or", lhs) || !isApplication("true", rhs)) {
			return false;
		}
		// case 1
		// lhs: (or ... true ...), rhs: true
		// case 2
		// lhs: (or ... p ... (not p) ...), rhs: true
		final HashSet<Term> seen = new HashSet<>();
		for (final Term t : ((ApplicationTerm) lhs).getParameters()) {
			if (isApplication("true", t)) {
				return true;
			}
			if (seen.contains(negate(t))) {
				return true;
			}
			seen.add(t);
		}

		return false;
	}

	boolean checkRewriteIte(final String rewriteRule, final Term lhs, final Term rhs) {
		// lhs: (ite cond then else)
		if (!isApplication("ite", lhs)) {
			return false;
		}
		final Term[] args = ((ApplicationTerm) lhs).getParameters();
		final Term cond = args[0];
		final Term t1 = args[1];
		final Term t2 = args[2];
		switch (rewriteRule) {
		case ":iteTrue":
			// (= (ite true t1 t2) t1)
			return isApplication("true", cond) && rhs == t1;
		case ":iteFalse":
			// (= (ite false t1 t2) t2)
			return isApplication("false", cond) && rhs == t2;
		case ":iteSame":
			// (= (ite cond t1 t1) t1)
			return t1 == t2 && rhs == t1;
		case ":iteBool1":
			// (= (ite cond true false) cond)
			return isApplication("true", t1) && isApplication("false", t2) && rhs == cond;
		case ":iteBool2":
			// (= (ite cond false true) (not cond))
			return isApplication("false", t1) && isApplication("true", t2) && rhs == mSkript.term("not", cond);
		case ":iteBool3":
			// (= (ite cond true t2) (or cond t2))
			return isApplication("true", t1) && rhs == mSkript.term("or", cond, t2);
		case ":iteBool4":
			// (= (ite cond false t2) (not (or cond (not t2))))
			return isApplication("false", t1)
					&& rhs == mSkript.term("not", mSkript.term("or", cond, mSkript.term("not", t2)));
		case ":iteBool5":
			// (= (ite cond t1 true) (or (not cond) t1))
			return isApplication("true", t2) && rhs == mSkript.term("or", mSkript.term("not", cond), t1);
		case ":iteBool6":
			// (= (ite cond t1 false) (not (or (not cond) (not t1))))
			return isApplication("false", t2) && rhs == mSkript.term("not",
					mSkript.term("or", mSkript.term("not", cond), mSkript.term("not", t1)));
		}
		return false;
	}

	boolean checkRewriteDistinct(final String rewriteRule, final Term lhs, Term rhs) {
		// lhs: (ite cond then else)
		if (!isApplication("distinct", lhs)) {
			return false;
		}
		final Term[] args = ((ApplicationTerm) lhs).getParameters();
		switch (rewriteRule) {
		case ":distinctBool":
			return args.length > 2 && args[0].getSort().getName() == "Bool" && isApplication("false", rhs);
		case ":distinctSame": {
			// (distinct ... x ... x ...) = false
			final HashSet<Term> seen = new HashSet<Term>();
			for (final Term t : args) {
				// If seen already contains the term we found the duplicate
				if (!seen.add(t)) {
					return isApplication("false", rhs);
				}
			}
			return false;
		}
		case ":distinctNeg":
			if (args.length != 2) {
				return false;
			}
			return args[0] == negate(args[1]) && isApplication("true", rhs);
		case ":distinctTrue":
			if (args.length != 2) {
				return false;
			}
			return (isApplication("true", args[0]) && rhs == mSkript.term("not", args[1]))
					|| (isApplication("true", args[1]) && rhs == mSkript.term("not", args[0]));
		case ":distinctFalse":
			if (args.length != 2) {
				return false;
			}
			return (isApplication("false", args[0]) && rhs == args[1])
					|| (isApplication("false", args[1]) && rhs == args[0]);
		case ":distinctBoolEq":
			if (args.length != 2 || args[0].getSort().getName() != "Bool") {
				return false;
			}
			return rhs == mSkript.term("=", args[0], mSkript.term("not", args[1]))
					|| rhs == mSkript.term("=", mSkript.term("not", args[0]), args[1]);
		case ":distinctBinary": {
			rhs = negate(rhs);
			if (args.length == 2) {
				return rhs == mSkript.term("=", args[0], args[1]);
			}
			if (!isApplication("or", rhs)) {
				return false;
			}
			final Term[] rhsArgs = ((ApplicationTerm) rhs).getParameters();
			int offset = 0;
			for (int i = 0; i < args.length - 1; i++) {
				for (int j = i + 1; j < args.length; j++) {
					if (offset >= rhsArgs.length || rhsArgs[offset] != mSkript.term("=", args[i], args[j])) {
						return false;
					}
					offset++;
				}
			}
			return offset == rhsArgs.length;
		}
		}
		return false;
	}

	boolean checkRewriteNot(Term lhs, final Term rhs) {
		// lhs: (ite cond then else)
		if (!isApplication("not", lhs)) {
			return false;
		}
		lhs = ((ApplicationTerm) lhs).getParameters()[0];
		if (isApplication("false", lhs)) {
			return isApplication("true", rhs);
		}
		if (isApplication("true", lhs)) {
			return isApplication("false", rhs);
		}
		if (isApplication("not", lhs)) {
			return rhs == ((ApplicationTerm) lhs).getParameters()[0];
		}
		return false;
	}

	boolean checkRewriteCanonicalSum(final Term lhs, final Term rhs) {
		final SMTAffineTerm lhsAffine = convertAffineTerm(lhs);
		final SMTAffineTerm rhsAffine = convertAffineTerm(rhs);
		return lhsAffine.equals(rhsAffine);
	}

	boolean checkRewriteFlatten(final Term lhs, final Term rhs) {
		// lhs: (or ... (or ...) ... ), rhs: (or ... ... ...)
		if (!isApplication("or", lhs) || !isApplication("or", rhs)) {
			return false;
		}
		final Term[] rhsArgs = ((ApplicationTerm) rhs).getParameters();
		int rhsOffset = 0;
		final ArrayDeque<Term> lhsArgs = new ArrayDeque<Term>();
		for (final Term t : ((ApplicationTerm) lhs).getParameters()) {
			lhsArgs.add(t);
		}
		while (!lhsArgs.isEmpty()) {
			final Term first = lhsArgs.removeFirst();
			if (rhsOffset >= rhsArgs.length) {
				return false;
			}
			if (rhsArgs[rhsOffset] == first) {
				rhsOffset++;
			} else {
				if (!isApplication("or", first)) {
					return false;
				}
				final Term[] args = ((ApplicationTerm) first).getParameters();
				for (int i = args.length - 1; i >= 0; i--) {
					lhsArgs.addFirst(args[i]);
				}
			}
		}
		return rhsOffset == rhsArgs.length;
	}

	boolean checkRewriteDesugar(final Term lhs, final Term rhs) {
		// (* realparam intparam) --> (* realparam (to_real intparam))
		if (!(lhs instanceof ApplicationTerm) || !(rhs instanceof ApplicationTerm)) {
			return false;
		}

		final FunctionSymbol fsym = ((ApplicationTerm) lhs).getFunction();
		if (((ApplicationTerm) rhs).getFunction() != fsym) {
			return false;
		}
		final Sort[] sorts = fsym.getParameterSorts();
		if (sorts.length != 2 || !sorts[0].isNumericSort() || sorts[0].getName() != "Real" || !sorts[1].isNumericSort()
				|| sorts[1].getName() != "Real") {
			return false;
		}

		final Term[] lhsParams = ((ApplicationTerm) lhs).getParameters();
		final Term[] rhsParams = ((ApplicationTerm) rhs).getParameters();
		if (lhsParams.length != rhsParams.length) {
			return false;
		}
		for (int i = 0; i < lhsParams.length; i++) {
			final Term expected =
					(lhsParams[i].getSort().getName() == "Int" ? mSkript.term("to_real", lhsParams[i]) : lhsParams[i]);
			if (rhsParams[i] != expected) {
				return false;
			}
		}
		return true;
	}

	boolean checkRewriteDivisible(final Term lhs, final Term rhs) {
		// ((_ divisible n) x) --> (= x (* n (div x n)))
		if (!isApplication("divisible", lhs)) {
			return false;
		}
		final Rational num = Rational.valueOf(((ApplicationTerm) lhs).getFunction().getIndices()[0], BigInteger.ONE);
		if (num.equals(Rational.ONE)) {
			return isApplication("true", rhs);
		}
		final Term arg = ((ApplicationTerm) lhs).getParameters()[0];
		final SMTAffineTerm argAffine = convertAffineTerm(arg);
		if (argAffine.isConstant()) {
			assert argAffine.getConstant().denominator().equals(BigInteger.ONE);
			final boolean divisible = argAffine.getConstant().numerator().mod(num.numerator()).equals(BigInteger.ZERO);
			return isApplication(divisible ? "true" : "false", rhs);
		}
		final Theory theory = lhs.getTheory();
		final SMTAffineTerm expected =
				SMTAffineTerm.create(num, theory.term("div", arg, theory.rational(num, arg.getSort())));
		if (!isApplication("=", rhs)) {
			return false;
		}
		final Term[] rhsArgs = ((ApplicationTerm) rhs).getParameters();
		return rhsArgs[0] == arg &&
				convertAffineTerm(rhsArgs[1]).equals(expected);
	}

	boolean checkRewriteExpand(final Term lhs, final Term rhs) {
		if (!(lhs instanceof ApplicationTerm)) {
			return false;
		}
		final ApplicationTerm at = ((ApplicationTerm) lhs);
		final FunctionSymbol f = at.getFunction();
		if (f.isLeftAssoc()) {
			final Term[] lhsParams = at.getParameters();
			if (lhsParams.length < 3) {
				return false;
			}
			Term right = rhs;
			for (int i = lhsParams.length - 1; i >= 1; i--) {
				if (!(right instanceof ApplicationTerm)) {
					return false;
				}
				final ApplicationTerm rightApp = (ApplicationTerm) right;
				if (rightApp.getFunction() != f || rightApp.getParameters().length != 2
						|| rightApp.getParameters()[1] != lhsParams[i]) {
					return false;
				}
				right = rightApp.getParameters()[0];
			}
			return right == lhsParams[0];
		} else if (f.isRightAssoc()) {
			final Term[] lhsParams = at.getParameters();
			if (lhsParams.length < 3) {
				return false;
			}
			Term right = rhs;
			for (int i = 0; i < lhsParams.length - 1; i++) {
				if (!(right instanceof ApplicationTerm)) {
					return false;
				}
				final ApplicationTerm rightApp = (ApplicationTerm) right;
				if (rightApp.getFunction() != f || rightApp.getParameters().length != 2
						|| rightApp.getParameters()[0] != lhsParams[i]) {
					return false;
				}
				right = rightApp.getParameters()[1];
			}
			return right == lhsParams[lhsParams.length - 1];
		} else if (f.isChainable()) {
			final Term[] lhsParams = at.getParameters();
			if (lhsParams.length < 3) {
				return false;
			}
			if (!isApplication("and", rhs)) {
				return false;
			}
			final Term[] rhsParams = ((ApplicationTerm) rhs).getParameters();
			if (lhsParams.length != rhsParams.length + 1) {
				return false;
			}
			for (int i = 0; i < rhsParams.length; i++) {
				if (!(rhsParams[i] instanceof ApplicationTerm)) {
					return false;
				}
				final ApplicationTerm rightApp = (ApplicationTerm) rhsParams[i];
				if (rightApp.getFunction() != f || rightApp.getParameters().length != 2
						|| rightApp.getParameters()[0] != lhsParams[i]
						|| rightApp.getParameters()[1] != lhsParams[i + 1]) {
					return false;
				}
			}
			return true;
		}
		return false;
	}

	boolean checkRewriteExpandDef(final Term lhs, final Term rhs) {
		// (= f arg) is expanded to (let ((var arg)) body), if f has definition body.
		if (!(lhs instanceof ApplicationTerm)) {
			return false;
		}
		final ApplicationTerm at = ((ApplicationTerm) lhs);
		final Term def = at.getFunction().getDefinition();
		if (def == null) {
			return false;
		}
		final TermVariable[] defVars = at.getFunction().getDefinitionVars();
		final Term[] params = at.getParameters();
		final Term expected = mSkript.let(defVars, params, def);
		return rhs == new FormulaUnLet().unlet(expected);
	}

	boolean checkStoreOverStore(final Term lhs, final Term rhs) {
		// lhs: (store (store a i v) i w)
		// rhs: (store a i w)
		if (!isApplication("store", lhs)) {
			return false;
		}
		final Term[] outerArgs = ((ApplicationTerm) lhs).getParameters();
		if (!isApplication("store", outerArgs[0])) {
			return false;
		}
		final Term[] innerArgs = ((ApplicationTerm) outerArgs[0]).getParameters();
		final SMTAffineTerm indexDiff = convertAffineTerm(innerArgs[1]).add(convertAffineTerm(outerArgs[1]).negate());
		if (!indexDiff.isConstant() || !indexDiff.getConstant().equals(Rational.ZERO)) {
			return false;
		}
		return rhs == mSkript.term("store", innerArgs[0], outerArgs[1], outerArgs[2]);
	}

	boolean checkSelectOverStore(final Term lhs, final Term rhs) {
		// lhs: (select (store a i v) j) i-j is a constant
		// rhs: (select a j) if i-j !=0. v if i-j = 0
		if (!isApplication("select", lhs)) {
			return false;
		}
		final Term[] selectArgs = ((ApplicationTerm) lhs).getParameters();
		if (!isApplication("store", selectArgs[0])) {
			return false;
		}
		final Term[] storeArgs = ((ApplicationTerm) selectArgs[0]).getParameters();
		final SMTAffineTerm indexDiff = convertAffineTerm(storeArgs[1]).add(convertAffineTerm(selectArgs[1]).negate());
		if (!indexDiff.isConstant()) {
			return false;
		}
		if (indexDiff.getConstant().equals(Rational.ZERO)) {
			return rhs == storeArgs[2];
		} else {
			return rhs == mSkript.term("select", storeArgs[0], selectArgs[1]);
		}
	}

	boolean checkStoreRewrite(final Term lhs, final Term rhs) {
		// lhs: (= (store a i v) a) (or symmetric)
		// rhs: (= (select a i) v)
		if (!isApplication("=", lhs)) {
			return false;
		}
		final Term[] eqArgs = ((ApplicationTerm) lhs).getParameters();
		Term[] storeArgs;
		if (isApplication("store", eqArgs[0]) && ((ApplicationTerm) eqArgs[0]).getParameters()[0] == eqArgs[1]) {
			storeArgs = ((ApplicationTerm) eqArgs[0]).getParameters();
		} else if (isApplication("store", eqArgs[1]) && ((ApplicationTerm) eqArgs[1]).getParameters()[0] == eqArgs[0]) {
			storeArgs = ((ApplicationTerm) eqArgs[1]).getParameters();
		} else {
			return false;
		}
		return rhs == mSkript.term("=", mSkript.term("select", storeArgs[0], storeArgs[1]), storeArgs[2]);
	}

	boolean checkRewriteToLeq0(final String rewriteRule, final Term lhs, Term rhs) {
		String func;
		boolean isNegated;
		int firstArg;
		switch (rewriteRule) {
		case ":leqToLeq0":
			func = "<=";
			isNegated = false;
			firstArg = 0;
			break;
		case ":ltToLeq0":
			func = "<";
			isNegated = true;
			firstArg = 1;
			break;
		case ":geqToLeq0":
			func = ">=";
			isNegated = false;
			firstArg = 1;
			break;
		case ":gtToLeq0":
			func = ">";
			isNegated = true;
			firstArg = 0;
			break;
		default:
			return false;
		}
		if (!isApplication(func, lhs)) {
			return false;
		}
		if (isNegated) {
			rhs = negate(rhs);
		}
		if (!isApplication("<=", rhs)) {
			return false;
		}
		final Term[] params = ((ApplicationTerm) lhs).getParameters();
		final SMTAffineTerm expected =
				convertAffineTerm(params[firstArg]).add(convertAffineTerm(params[1 - firstArg]).negate());
		final Term[] rhsParams = ((ApplicationTerm) rhs).getParameters();
		return convertAffineTerm(rhsParams[0]).equals(expected) && isZero(rhsParams[1]);
	}

	boolean checkRewriteLeq(final String rewriteRule, final Term lhs, final Term rhs) {
		// (<= c 0) --> true/false if c is constant.
		if (!isApplication("<=", lhs)) {
			return false;
		}
		final Term[] params = ((ApplicationTerm) lhs).getParameters();
		if (!isZero(params[1])) {
			return false;
		}
		final SMTAffineTerm param0 = convertAffineTerm(params[0]);
		if (!param0.isConstant()) {
			return false;
		}

		switch (rewriteRule) {
		case ":leqTrue":
			return param0.getConstant().signum() <= 0 && isApplication("true", rhs);
		case ":leqFalse":
			return param0.getConstant().signum() > 0 && isApplication("false", rhs);
		default:
			return false;
		}
	}

	boolean checkRewriteMisc(final String rewriteRule, final ApplicationTerm termEqApp) {
		if (rewriteRule == ":div1") {
			final ApplicationTerm termOldApp = convertApp(termEqApp.getParameters()[0]);

			pm_func(termOldApp, "div");

			checkNumber(termOldApp, 2);

			final SMTAffineTerm constant = convertAffineTerm(convertConst_Neg(termOldApp.getParameters()[1]));

			// Rule-Execution was wrong if c != 1
			if (!constant.isConstant()) {
				throw new AssertionError("Error 1 at " + rewriteRule);
			}
			if (!(constant.getConstant().equals(Rational.ONE))) {
				throw new AssertionError("Error 2 at " + rewriteRule);
			}

			if (termEqApp.getParameters()[1] != termOldApp.getParameters()[0]) {
				throw new AssertionError("Error 3 at " + rewriteRule);
			}

		} else if (rewriteRule == ":div-1") {
			final ApplicationTerm termOldApp = convertApp(termEqApp.getParameters()[0]);

			pm_func(termOldApp, "div");

			checkNumber(termOldApp, 2);

			convertConst_Neg(termOldApp.getParameters()[1]);

			final SMTAffineTerm constant = convertAffineTerm(termOldApp.getParameters()[1]);

			// Rule-Execution was wrong if c != 1
			if (!constant.negate().isConstant()) {
				throw new AssertionError("Error 1 at " + rewriteRule);
			}
			if (!(constant.negate().getConstant().equals(Rational.ONE))) {
				throw new AssertionError("Error 2 at " + rewriteRule);
			}

			if (!convertAffineTerm(termEqApp.getParameters()[1]).negate()
					.equals(convertAffineTerm(termOldApp.getParameters()[0]))) {
				throw new AssertionError("Error 3 at " + rewriteRule);
			}
		} else if (rewriteRule == ":divConst") {
			final ApplicationTerm termOldApp = convertApp(termEqApp.getParameters()[0]);

			pm_func(termOldApp, "div");

			checkNumber(termOldApp, 2);

			convertConst_Neg(termOldApp.getParameters()[0]);
			convertConst_Neg(termOldApp.getParameters()[1]);

			final SMTAffineTerm c1 = convertAffineTerm(termOldApp.getParameters()[0]);

			if (!c1.isConstant()) {
				throw new AssertionError("Error 1 at " + rewriteRule);
			}

			final SMTAffineTerm c2 = convertAffineTerm(termOldApp.getParameters()[1]);

			if (!c2.isConstant()) {
				throw new AssertionError("Error 2 at " + rewriteRule);
			}

			if (c2.getConstant().equals(Rational.ZERO)) {
				throw new AssertionError("Error 3 at " + rewriteRule);
			}

			final SMTAffineTerm d = convertAffineTerm(termEqApp.getParameters()[1]);

			if (!c1.isIntegral() || !c2.isIntegral() || !d.isIntegral()) {
				throw new AssertionError("Error 4 at " + rewriteRule);
			}

			if (c2.getConstant().isNegative()
					&& !d.getConstant().equals(c1.getConstant().div(c2.getConstant()).ceil())) {
				throw new AssertionError("Error 5 at " + rewriteRule);
			}

			if (!c2.getConstant().isNegative()
					&& !d.getConstant().equals(c1.getConstant().div(c2.getConstant()).floor())) {
				throw new AssertionError("Error 6 at " + rewriteRule);
			}

		} else if (rewriteRule == ":modulo1") {
			final ApplicationTerm termOldApp = convertApp(termEqApp.getParameters()[0]);

			pm_func(termOldApp, "mod");

			checkNumber(termOldApp, 2);

			// Check syntactical correctness
			if (!(termOldApp.getParameters()[0] instanceof ConstantTerm)
					&& !checkInt_weak(termOldApp.getParameters()[0])) {
				throw new AssertionError("Error 1 at " + rewriteRule);
			}

			convertConst(termOldApp.getParameters()[1]);
			convertConst(termEqApp.getParameters()[1]);

			final SMTAffineTerm constant1 = convertAffineTerm(termOldApp.getParameters()[1]);

			if (!(constant1.getConstant().equals(Rational.ONE))) {
				throw new AssertionError("Error 2 at " + rewriteRule);
			}

			final SMTAffineTerm constant0 = convertAffineTerm(termEqApp.getParameters()[1]);

			if (!(constant0.getConstant().equals(Rational.ZERO))) {
				throw new AssertionError("Error 3 at " + rewriteRule);
			}

		} else if (rewriteRule == ":modulo-1") {
			final ApplicationTerm termOldApp = convertApp(termEqApp.getParameters()[0]);

			pm_func(termOldApp, "mod");

			checkNumber(termOldApp, 2);

			// Check syntactical correctness
			if (!(termOldApp.getParameters()[0] instanceof ConstantTerm)
					&& !checkInt_weak(termOldApp.getParameters()[0])) {
				throw new AssertionError("Error 1 at " + rewriteRule);
			}

			convertConst_Neg(termOldApp.getParameters()[1]);
			convertConst(termEqApp.getParameters()[1]);

			final SMTAffineTerm constantm1 = convertAffineTerm(termOldApp.getParameters()[1]);

			if (!(constantm1.getConstant().negate().equals(Rational.ONE))) {
				throw new AssertionError("Error 2 at " + rewriteRule);
			}

			final SMTAffineTerm constant0 = convertAffineTerm(termEqApp.getParameters()[1]);
			if (!(constant0.getConstant().equals(Rational.ZERO))) {
				throw new AssertionError("Error 3 at " + rewriteRule);
			}

		} else if (rewriteRule == ":moduloConst") {
			final ApplicationTerm termOldApp = convertApp(termEqApp.getParameters()[0]);

			pm_func(termOldApp, "mod");

			checkNumber(termOldApp, 2);

			// Check syntactical correctness
			if (!(termOldApp.getParameters()[0] instanceof ConstantTerm)
					&& !checkInt_weak(termOldApp.getParameters()[0])) {
				throw new AssertionError("Error 1a at " + rewriteRule);
			}
			if (!(termOldApp.getParameters()[1] instanceof ConstantTerm)
					&& !checkInt_weak(termOldApp.getParameters()[1])) {
				throw new AssertionError("Error 1b at " + rewriteRule);
			}
			if (!(termEqApp.getParameters()[1] instanceof ConstantTerm)
					&& !checkInt_weak(termEqApp.getParameters()[1])) {
				throw new AssertionError("Error 1c at " + rewriteRule);
			}

			final SMTAffineTerm c2 = convertAffineTerm(termOldApp.getParameters()[1]);

			if (c2.getConstant().equals(Rational.ZERO)) {
				throw new AssertionError("Error 2 at " + rewriteRule);
			}

			final SMTAffineTerm c1 = convertAffineTerm(termOldApp.getParameters()[0]);
			final SMTAffineTerm d = convertAffineTerm(termEqApp.getParameters()[1]);
			if (!c1.isIntegral() || !c2.isIntegral() || !d.isIntegral()) {
				throw new AssertionError("Error 3 at " + rewriteRule);
			}

			if (c2.getConstant().isNegative()) {
				// d = c1 + c2 * ceil(c1/c2)
				if (!d.equals(c1.add(c2.mul(c1.div(c2.getConstant()).getConstant().ceil()).negate()))) {
					throw new AssertionError("Error 4 at " + rewriteRule);
				}
			} else {
				if (!d.equals(c1.add(c2.mul(c1.div(c2.getConstant()).getConstant().floor()).negate()))) {
					throw new AssertionError("Error 5 at " + rewriteRule);
				}
			}
		} else if (rewriteRule == ":modulo") {

			final ApplicationTerm termOldMod = convertApp(termEqApp.getParameters()[0]);
			final ApplicationTerm termNewSum = convertApp(termEqApp.getParameters()[1]);

			checkNumber(termOldMod, 2);
			checkNumber(termNewSum, 2);

			ApplicationTerm termNewProd;
			Term termNewNotProd;
			if (termNewSum.getParameters()[0] instanceof ApplicationTerm) {
				if (pm_func_weak(termNewSum.getParameters()[0], "*")) {
					termNewProd = convertApp(termNewSum.getParameters()[0]);
					termNewNotProd = termNewSum.getParameters()[1];
				} else {
					termNewProd = convertApp(termNewSum.getParameters()[1]);
					termNewNotProd = termNewSum.getParameters()[0];
				}
			} else {
				termNewProd = convertApp(termNewSum.getParameters()[1]);
				termNewNotProd = termNewSum.getParameters()[0];
			}

			checkNumber(termNewProd, 2);

			ApplicationTerm termNewDiv;
			Term termNewNotDiv;
			if (termNewProd.getParameters()[0] instanceof ApplicationTerm) {
				if (pm_func_weak(termNewProd.getParameters()[0], "/")
						|| pm_func_weak(termNewProd.getParameters()[0], "div")) {
					termNewDiv = convertApp(termNewProd.getParameters()[0]);
					termNewNotDiv = termNewProd.getParameters()[1];
				} else {
					termNewDiv = convertApp(termNewProd.getParameters()[1]);
					termNewNotDiv = termNewProd.getParameters()[0];
				}
			} else {
				termNewDiv = convertApp(termNewProd.getParameters()[1]);
				termNewNotDiv = termNewProd.getParameters()[0];
			}

			checkNumber(termNewDiv, 2);

			// ApplicationTerm termNewDiv = convertApp(termNewProd.getParameters()[1]);

			pm_func(termOldMod, "mod");
			pm_func(termNewSum, "+");
			pm_func(termNewProd, "*");
			if (!pm_func_weak(termNewDiv, "div") && !pm_func_weak(termNewDiv, "/")) {
				throw new AssertionError("Error 1 at " + rewriteRule);
			}

			final Term termOldX = termOldMod.getParameters()[0];
			final Term termOldY = termOldMod.getParameters()[1];
			if (termNewNotProd != termOldX
					|| !convertAffineTerm(termNewNotDiv).equals(convertAffineTerm(termOldY).negate())
					|| termNewDiv.getParameters()[0] != termOldX || termNewDiv.getParameters()[1] != termOldY) {
				throw new AssertionError("Error 2 at " + rewriteRule);
			}

		} else if (rewriteRule == ":toInt") {
			final ApplicationTerm termOldApp = convertApp(termEqApp.getParameters()[0]);

			pm_func(termOldApp, "to_int");

			// r and v as in the documentation proof.pdf
			final Term termV = convertConst_Neg(termEqApp.getParameters()[1]);
			final Term termR = termOldApp.getParameters()[0];
			// r can be a positive/negative fraction
			// Case A: Positive Integer, Case B: Negative Integer
			// Case C: Positive Fraction, Case D: Negative Fraction

			if (termR instanceof ApplicationTerm) {
				// Case B, C, D:
				final ApplicationTerm termRApp = convertApp(termR);
				ApplicationTerm termRInnerApp;
				if (pm_func_weak(termRApp, "-") && termRApp.getParameters()[0] instanceof ApplicationTerm) {
					// Case D:
					termRInnerApp = convertApp(termRApp.getParameters()[0]);
					pm_func(termRInnerApp, "/");
					checkNumber(termRInnerApp, 2);

					convertConst_Neg(termRInnerApp.getParameters()[0]); // Presumably the neg isn't needed
					convertConst_Neg(termRInnerApp.getParameters()[1]); // Presumably the neg isn't needed
				} else if (pm_func_weak(termRApp, "/")) {
					// Case C:
					pm_func(termRApp, "/");
					checkNumber(termRApp, 2);

					convertConst_Neg(termRApp.getParameters()[0]); // Presumably the neg isn't needed
					convertConst_Neg(termRApp.getParameters()[1]); // Presumably the neg isn't needed
				} else {
					// Case B:
					pm_func(termRApp, "-");

					convertConst(termRApp.getParameters()[0]);
				}
			} else {
				// Case A:
				convertConst(termR);
			}

			if (!convertAffineTerm(termR).getConstant().floor().equals(convertAffineTerm(termV).getConstant())) {
				throw new AssertionError("Error 2 at " + rewriteRule);
			}

			/*
			 * Not nice: Not checked, if v is an integer and r a real, but it is still correct.
			 */
		} else {
			return false;
		}
		return true;
	}

	public void walkIntern(final ApplicationTerm internApp) {
		final Term equality = internApp.getParameters()[0];

		/*
		 * The result is simply the equality.
		 */
		stackPush(equality, internApp);

		if (!isApplication("=", equality)) {
			reportError("Expected equality: " + equality);
			return;
		}
		final Term[] args = ((ApplicationTerm) equality).getParameters();
		if (args.length != 2 || args[0].getSort().getName() != "Bool" || !checkIntern(args[0], args[1])) {
			reportError("Malformed intern application: " + internApp);
		}
	}

	boolean checkIntern(final Term lhs, Term rhs) {
		if (!(lhs instanceof ApplicationTerm)) {
			return false;
		}
		final ApplicationTerm at = (ApplicationTerm) lhs;
		if (!at.getFunction().isIntern() || at.getFunction().getName() == "select") {
			/* boolean literals are not quoted */
			if (at.getParameters().length == 0) {
				return rhs == at;
			}
			/* second case: boolean functions are created as equalities */
			rhs = unquote(rhs);
			if (!isApplication("=", rhs)) {
				return false;
			}
			final Term[] rhsArgs = ((ApplicationTerm) rhs).getParameters();
			return rhsArgs.length == 2 && rhsArgs[0] == lhs && isApplication("true", rhsArgs[1]);
		}

		if (isApplication("<=", lhs)) {
			final Term[] lhsParams = ((ApplicationTerm) lhs).getParameters();
			final boolean isInt = lhsParams[0].getSort().getName() == "Int";
			SMTAffineTerm lhsAffine = convertAffineTerm(lhsParams[0]);
			if (!isZero(lhsParams[1])) {
				return false;
			}

			/* (<= a b) can be translated to (not (< b a)) */
			final boolean isNegated = isApplication("not", rhs);
			boolean isStrict = false;
			if (isNegated) {
				rhs = negate(rhs);
				/* <= (a-b) 0 --> (not (< (b-a) 0)) resp. (not (<= (b-a+1) 0)) for integer */
				lhsAffine = lhsAffine.negate();
				if (isInt) {
					lhsAffine = lhsAffine.add(Rational.ONE);
				} else {
					isStrict = true;
				}
			}
			rhs = unquote(rhs);

			if (!isApplication(isStrict ? "<" : "<=", rhs)) {
				return false;
			}

			// Normalize coefficients
			lhsAffine = lhsAffine.div(lhsAffine.getGcd());
			// Round constant up for integers: (<= (x + 1.25) 0) --> (<= x + 2)
			if (isInt) {
				final Rational constant = lhsAffine.getConstant();
				final Rational frac = constant.add(constant.negate().floor());
				lhsAffine = lhsAffine.add(frac.negate());
			}
			final Term[] rhsArgs = ((ApplicationTerm) rhs).getParameters();
			return convertAffineTerm(rhsArgs[0]).equals(lhsAffine) && isZero(rhsArgs[1]);
		}

		if (isApplication("=", lhs) && ((ApplicationTerm) lhs).getParameters()[0].getSort().getName() != "Bool") {
			rhs = unquote(rhs);
			if (!isApplication("=", rhs)) {
				return false;
			}
			final Term[] lhsParams = ((ApplicationTerm) lhs).getParameters();
			final Term[] rhsParams = ((ApplicationTerm) rhs).getParameters();
			if (lhsParams.length != 2 || rhsParams.length != 2) {
				return false;
			}

			/* first check if rhs and lhs are the same or only swapped parameters */
			if (lhs == rhs || (lhsParams[1] == rhsParams[0] && lhsParams[0] == rhsParams[1])) {
				return true;
			}

			if (!lhsParams[0].getSort().isNumericSort()) {
				return false;
			}

			/* check that they represent the same equality */
			final SMTAffineTerm lhsAffine = convertAffineTerm(lhsParams[0]).add(convertAffineTerm(lhsParams[1]).negate());
			final SMTAffineTerm rhsAffine = convertAffineTerm(rhsParams[0]).add(convertAffineTerm(rhsParams[1]).negate());
			lhsAffine.div(lhsAffine.getGcd());
			rhsAffine.div(rhsAffine.getGcd());
			return lhsAffine.equals(rhsAffine) || lhsAffine.equals(rhsAffine.negate());
		}

		/* Check for auxiliary literals */
		if (isApplication("ite", lhs) || isApplication("or", lhs) || isApplication("=", lhs)) {
			rhs = unquote(rhs);
			return lhs == rhs;
		}
		return false;
	}

	/**
	 * Convert a clause term into an Array of terms, one entry for each disjunct. This also handles singleton and empty
	 * clause correctly.
	 *
	 * @param clauseTerm
	 *            The term representing a clause.
	 * @return The disjuncts of the clause.
	 */
	private Term[] termToClause(final Term clauseTerm) {
		assert clauseTerm != null && clauseTerm.getSort().getName() == "Bool";
		if (isApplication("or", clauseTerm)) {
			return ((ApplicationTerm) clauseTerm).getParameters();
		} else if (isApplication("false", clauseTerm)) {
			return new Term[0];
		} else {
			/* in all other cases, this is a singleton clause. */
			return new Term[] { clauseTerm };
		}
	}

	/**
	 * Convert a collection of terms into a clause term. This also handles singleton and empty clause correctly.
	 *
	 * @param disjuncts
	 *            the disjuncts of the clause.
	 * @return a term representing the clause.
	 */
	private Term clauseToTerm(final Collection<Term> disjuncts) {
		if (disjuncts.size() <= 1) {
			if (disjuncts.isEmpty()) {
				return mSkript.term("false");
			} else {
				return disjuncts.iterator().next();
			}
		} else {
			final Term[] args = disjuncts.toArray(new Term[disjuncts.size()]);
			return mSkript.term("or", args);
		}
	}

	/**
	 * Handle the resolution rule. The stack should contain the converted input clauses.
	 *
	 * @param resApp
	 *            The <code>{@literal @}res</code> application from the original proof.
	 */
	public void walkResolution(final ApplicationTerm resApp) {
		final Term[] termArgs = resApp.getParameters();

		/*
		 * Get the pivot literals (pivots[0] is always null) and retrieve the calculations for the proofs from the
		 * stack.
		 */
		final Term[] pivots = new Term[termArgs.length];
		final Term[] clauseTerms = new Term[termArgs.length];
		for (int i = termArgs.length - 1; i >= 1; i--) {
			final AnnotatedTerm pivotPlusProof = (AnnotatedTerm) termArgs[i];

			/* Check if it is a pivot-annotation */
			if (pivotPlusProof.getAnnotations().length != 1
					|| pivotPlusProof.getAnnotations()[0].getKey() != ":pivot") {
				throw new IllegalArgumentException("Annotation :pivot expected");
			}

			/*
			 * Just take the first annotation, because it should have exactly one - otherwise the proof-checker throws
			 * an error
			 */
			pivots[i] = (Term) pivotPlusProof.getAnnotations()[0].getValue();
			clauseTerms[i] = stackPopCheck(pivotPlusProof.getSubterm());
		}
		// The 0th argument is the main clause an has no pivot.
		clauseTerms[0] = stackPopCheck(termArgs[0]);

		/*
		 * allDisjuncts is the currently computed resolution result.
		 */
		final HashSet<Term> allDisjuncts = new HashSet<Term>();

		/* Now get the disjuncts of the first argument. */
		allDisjuncts.addAll(Arrays.asList(termToClause(clauseTerms[0])));
		if (mDebug.contains("resolution")) {
			mLogger.warn("main clause: " + clauseTerms[0]);
		}

		/* Resolve the other clauses */
		for (int i = 1; i < termArgs.length; i++) {
			if (mDebug.contains("resolution")) {
				mLogger.warn("  with pivot " + pivots[i] + " and clause " + clauseTerms[i]);
			}
			/* Remove the negated pivot from allDisjuncts */
			if (!allDisjuncts.remove(negate(pivots[i]))) {
				reportWarning("Could not find negated pivot in main clause");
			}

			/*
			 * For each clause check for the pivot and add all other literals.
			 */
			final Term[] clause = termToClause(clauseTerms[i]);
			boolean pivotFound = false;
			for (final Term t : clause) {
				if (t == pivots[i]) {
					pivotFound = true;
				} else {
					allDisjuncts.add(t);
				}
			}

			if (!pivotFound) {
				reportWarning("Could not find pivot in secondary clause");
			}
			if (mDebug.contains("resolution")) {
				mLogger.warn("  results in " + allDisjuncts);
			}
		}

		stackPush(clauseToTerm(allDisjuncts), resApp);
	}

	/**
	 * Checks that an {@literal @}eq application is okay. The two parameter of the application should already be
	 * converted and their proved formula on the result stack. This puts the resulting formula proved by the
	 * {@literal @}eq application on the result stack.
	 *
	 * @param eqApp
	 *            The {@literal @}eq application.
	 */
	public void walkEquality(final ApplicationTerm eqApp) {
		final Term[] eqParams = eqApp.getParameters();
		assert eqApp.getFunction().getName().equals("@eq");
		assert eqParams.length == 2;

		/*
		 * Expected: The first argument is a boolean formula f the second argument a binary equality (= f g).
		 *
		 * The second argument is a proves that g is equivalent to f and the result is a proof for g.
		 */

		final Term rewrite = stackPopCheck(eqParams[1]);
		final Term origFormula = stackPopCheck(eqParams[0]);

		boolean okay = false;
		Term result = origFormula;
		if (isApplication("=", rewrite)) {
			final Term[] eqSides = ((ApplicationTerm) rewrite).getParameters();
			if (eqSides.length == 2) {
				result = eqSides[1];
				okay = (origFormula == eqSides[0]);
			}
		}
		if (!okay) {
			reportError("Malformed @eq application: " + origFormula + " and " + rewrite);
		}
		stackPush(result, eqApp);
	}

	public void walkClause(final ApplicationTerm clauseApp) {
		/* Check if the parameters of clause are two disjunctions (which they should be) */
		final Term provedClause = stackPopCheck(clauseApp.getParameters()[0]);
		Term expectedClause = clauseApp.getParameters()[1];
		if (expectedClause instanceof AnnotatedTerm) {
			final Annotation[] annot = ((AnnotatedTerm) expectedClause).getAnnotations();
			if (annot.length == 1 && annot[0].getKey().equals(":input")) {
				/* newer version of proof producer adds :input annotation to @clause for interpolator */
				expectedClause = ((AnnotatedTerm) expectedClause).getSubterm();
			}
		}

		// The disjuncts of each parameter
		final Term[] provedLits = termToClause(provedClause);
		final Term[] expectedLits = termToClause(expectedClause);
		if (provedLits.length != expectedLits.length) {
			reportError("Clause has different number of literals: " + provedClause + " versus " + expectedClause);
		}
		final HashSet<Term> param1Disjuncts = new HashSet<Term>(Arrays.asList(provedLits));
		final HashSet<Term> param2Disjuncts = new HashSet<Term>(Arrays.asList(expectedLits));

		/*
		 * Check if the clause operation was correct. Each later disjunct has to be in the first disjunction and reverse
		 * and there should be no double literal.
		 */
		if (!param1Disjuncts.equals(param2Disjuncts) || param1Disjuncts.size() != provedLits.length) {
			reportError("The clause-operation didn't permute correctly!");
		}

		stackPush(expectedClause, clauseApp);
	}

	/* === Split rules === */

	public void walkSplit(final ApplicationTerm splitApp) {
		// term is just the first term

		// The first term casted to an ApplicationTerm
		final AnnotatedTerm annotSplit = (AnnotatedTerm) splitApp.getParameters()[0];
		final Term splitTerm = splitApp.getParameters()[1];
		final Term origTerm = stackPopCheck(annotSplit.getSubterm());

		final String splitRule = annotSplit.getAnnotations()[0].getKey();

		if (mDebug.contains("currently")) {
			System.out.println("Split-Rule: " + splitRule);
		}
		if (mDebug.contains("hardTerm")) {
			System.out.println("Term: " + splitApp.toStringDirect());
		}

		boolean result;
		switch (splitRule) {
		case ":notOr":
			result = checkSplitNotOr(origTerm, splitTerm);
			break;
		case ":=+1":
		case ":=+2":
		case ":=-1":
		case ":=-2":
			result = checkSplitEq(splitRule, origTerm, splitTerm);
			break;
		case ":ite+1":
		case ":ite+2":
		case ":ite-1":
		case ":ite-2":
			result = checkSplitIte(splitRule, origTerm, splitTerm);
			break;
		default:
			result = false;
		}

		if (!result) {
			reportError("Malformed/unknown split rule " + splitApp);
		}
		stackPush(splitTerm, splitApp);
	}

	public boolean checkSplitNotOr(final Term origTerm, final Term splitTerm) {
		final Term orTerm = negate(origTerm);
		if (!isApplication("or", orTerm)) {
			return false;
		}
		final Term[] lits = ((ApplicationTerm) orTerm).getParameters();
		if (!isApplication("not", splitTerm)) {
			return false;
		}
		final Term disjunct = negate(splitTerm);
		for (final Term t : lits) {
			if (t == disjunct) {
				return true;
			}
		}
		return false;
	}

	public boolean checkSplitEq(final String splitRule, Term origTerm, final Term splitTerm) {
		// rule is =+ iff origTerm is an equality.
		final boolean positive = !isApplication("not", origTerm);
		if (!positive) {
			origTerm = ((ApplicationTerm) origTerm).getParameters()[0];
		}
		if (!isApplication("=", origTerm)) {
			return false;
		}
		final Term[] eqParams = ((ApplicationTerm) origTerm).getParameters();
		if (eqParams.length != 2) {
			return false;
		}
		if (!isApplication("or", splitTerm)) {
			return false;
		}
		final Term[] clause = ((ApplicationTerm) splitTerm).getParameters();
		if (clause.length != 2) {
			return false;
		}
		switch (splitRule) {
		case ":=+1":
			return positive && clause[0] == eqParams[0] && clause[1] == mSkript.term("not", eqParams[1]);
		case ":=+2":
			return positive && clause[0] == mSkript.term("not", eqParams[0]) && clause[1] == eqParams[1];
		case ":=-1":
			return !positive && clause[0] == eqParams[0] && clause[1] == eqParams[1];
		case ":=-2":
			return !positive && clause[0] == mSkript.term("not", eqParams[0])
					&& clause[1] == mSkript.term("not", eqParams[1]);
		}
		return false;
	}

	public boolean checkSplitIte(final String splitRule, Term origTerm, final Term splitTerm) {
		final boolean positive = !isApplication("not", origTerm);
		if (!positive) {
			origTerm = ((ApplicationTerm) origTerm).getParameters()[0];
		}
		if (!isApplication("ite", origTerm)) {
			return false;
		}
		final Term[] iteParams = ((ApplicationTerm) origTerm).getParameters();
		if (iteParams.length != 3) {
			return false;
		}
		if (!isApplication("or", splitTerm)) {
			return false;
		}
		final Term[] clause = ((ApplicationTerm) splitTerm).getParameters();
		if (clause.length != 2) {
			return false;
		}
		switch (splitRule) {
		case ":ite+1":
			return positive && clause[0] == mSkript.term("not", iteParams[0]) && clause[1] == iteParams[1];
		case ":ite+2":
			return positive && clause[0] == iteParams[0] && clause[1] == iteParams[2];
		case ":ite-1":
			return !positive && clause[0] == mSkript.term("not", iteParams[0])
					&& clause[1] == mSkript.term("not", iteParams[1]);
		case ":ite-2":
			return !positive && clause[0] == iteParams[0] && clause[1] == mSkript.term("not", iteParams[2]);
		}
		return false;
	}

	/* === Auxiliary functions === */

	public void stackPush(final Term pushTerm, final ApplicationTerm keyTerm) {
		mCacheConv.put(keyTerm, pushTerm);
		mStackResults.push(pushTerm);
		mStackResultsDebug.push(keyTerm);
	}

	public Term stackPopCheck(final Term expected) {
		if (mStackResults.size() != mStackResultsDebug.size()) {
			throw new AssertionError("The debug-stack and the result-stack have different size");
		}
		final Term returnTerm = mStackResults.pop();
		final Term debugTerm = mStackResultsDebug.pop();

		if (mCacheConv.get(debugTerm) != returnTerm) {
			throw new AssertionError("The debugger couldn't associate " + returnTerm.toStringDirect() + " with "
					+ debugTerm.toStringDirect());
		}
		if (expected != null && debugTerm != expected) {
			throw new AssertionError("Unexpected Term on proofchecker stack.");
		}

		return returnTerm;
	}

	/*
	 * Convert a term to an SMTAffineTerm
	 *
	 * @param term The term to convert.
	 *
	 * @return The converted term.
	 */
	SMTAffineTerm convertAffineTerm(final Term term) {
		return (SMTAffineTerm) mAffineConverter.transform(term);
	}

	ApplicationTerm convertApp(final Term term) {
		if (mDebug.contains("convertApp")) {
			System.out.println("Aufruf");
		}

		if (!(term instanceof ApplicationTerm)) {
			throw new AssertionError("Error: The following term should be an ApplicationTerm, " + "but is of the class "
					+ term.getClass().getSimpleName() + ".\n" + "The term was: " + term.toString());
		}

		return (ApplicationTerm) term;
	}

	ConstantTerm convertConst(final Term term) {
		if (!(term instanceof ConstantTerm)) {
			throw new AssertionError("Error: The following term should be a ConstantTerm, " + "but is of the class "
					+ term.getClass().getSimpleName() + ".\n" + "The term was: " + term.toString());
		}

		return (ConstantTerm) term;
	}

	Term convertConst_Neg(final Term term) {
		if (term instanceof ConstantTerm) {
			return term;
		}

		// Then it must be a negative number
		final ApplicationTerm termApp = convertApp(term);
		pm_func(termApp, "-");

		if (termApp.getParameters()[0] instanceof ConstantTerm) {
			return termApp;
		}

		throw new AssertionError("Error: The following term should be a ConstantTerm, " + "but is of the class "
				+ term.getClass().getSimpleName() + ".\n" + "The term was: " + term.toString());
	}

	boolean checkInt_weak(final Term term) {
		if (term.getSort() == mSkript.sort("Int")) {
			return true;
		}

		// Then it must be a negative Integer

		final ApplicationTerm termApp = convertApp(term);
		pm_func(termApp, "-");

		if (termApp.getParameters()[0].getSort() == mSkript.sort("Int")) {
			return true;
		}

		return false;
		// throw new AssertionError("Error: The following term should be an Integer, "
		// + "but is of the sort " + term.getSort().getName() + ".\n"
		// + "The term was: " + term.toString());
	}

	// Now some pattern-match-functions.

	// Throws an error if the pattern doesn't match
	void pm_func(final ApplicationTerm termApp, final String pattern) {
		if (!termApp.getFunction().getName().equals(pattern)) {
			reportError("Error: The pattern \"" + pattern + "\" was supposed to be the function symbol of "
					+ termApp.toStringDirect() + "\n" + "Instead it was " + termApp.getFunction().getName());
		}
	}

	boolean pm_func_weak(final ApplicationTerm termApp, final String pattern) {
		return termApp.getFunction().getName().equals(pattern);
	}

	// Does this function make any sense?
	boolean pm_func_weak(final Term term, final String pattern) {
		if (term instanceof ApplicationTerm) {
			return pm_func_weak((ApplicationTerm) term, pattern);
		}

		throw new AssertionError("Expected an ApplicationTerm in func_weak!");
	}

	void checkNumber(final ApplicationTerm termApp, final int n) {
		if (termApp.getParameters().length < n) {
			throw new AssertionError("Error: " + "The parameter-array of " + termApp.toStringDirect() + " is to short!"
					+ "\n It should have length " + n);
		}
	}

	void isConstant(final SMTAffineTerm term, final Rational constant) {
		if (!isConstant_weak(term, constant)) {
			throw new AssertionError("The following term should be the " + "constant " + constant.toString()
					+ " but isn't: " + term.toStringDirect());
		}
	}

	boolean isConstant_weak(final SMTAffineTerm term, final Rational constant) {
		if (!term.isConstant() || term.getConstant() != constant) {
			return false;
		}
		return true;
	}

	public Term unquote(final Term quotedTerm) {
		if (quotedTerm instanceof AnnotatedTerm) {
			final AnnotatedTerm annTerm = (AnnotatedTerm) quotedTerm;
			final Annotation[] annots = annTerm.getAnnotations();
			if (annots.length == 1 && annots[0].getKey() == ":quoted") {
				final Term result = annTerm.getSubterm();
				return result;
			}
		}
		reportError("Expected quoted literal, but got " + quotedTerm);
		return quotedTerm;
	}

	/**
	 * Negate a term, avoiding double negation. If formula is (not x) it returns x, otherwise it returns (not formula).
	 *
	 * @param formula
	 *            the formula to negate.
	 * @return the negated formula.
	 */
	public Term negate(final Term formula) {
		if (isApplication("not", formula)) {
			return ((ApplicationTerm) formula).getParameters()[0];
		}
		return formula.getTheory().term("not", formula);
	}

	/**
	 * Checks if a term is an application of an internal function symbol.
	 *
	 * @param funcSym
	 *            the expected function symbol.
	 * @param term
	 *            the term to check.
	 * @return true if term is an application of funcSym.
	 */
	public boolean isApplication(final String funcSym, final Term term) {
		if (term instanceof ApplicationTerm) {
			final ApplicationTerm appTerm = (ApplicationTerm) term;
			final FunctionSymbol func = appTerm.getFunction();
			if (func.isIntern() && func.getName().equals(funcSym)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Checks if a term is zero, either Int or Real.
	 *
	 * @param zero
	 *            the term to check.
	 * @return true if zero is 0.
	 */
	public boolean isZero(final Term zero) {
		return zero == zero.getTheory().rational(Rational.ZERO, zero.getSort())
				|| zero == Rational.ZERO.toTerm(zero.getSort());
	}
}