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
package de.uni_freiburg.informatik.ultimate.smtinterpol.theory.linar;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.log4j.Logger;

import de.uni_freiburg.informatik.ultimate.logic.ApplicationTerm;
import de.uni_freiburg.informatik.ultimate.logic.FunctionSymbol;
import de.uni_freiburg.informatik.ultimate.logic.MutableRational;
import de.uni_freiburg.informatik.ultimate.logic.Rational;
import de.uni_freiburg.informatik.ultimate.logic.Term;
import de.uni_freiburg.informatik.ultimate.logic.Theory;
import de.uni_freiburg.informatik.ultimate.smtinterpol.Config;
import de.uni_freiburg.informatik.ultimate.smtinterpol.convert.EqualityProxy;
import de.uni_freiburg.informatik.ultimate.smtinterpol.convert.SharedTerm;
import de.uni_freiburg.informatik.ultimate.smtinterpol.dpll.Clause;
import de.uni_freiburg.informatik.ultimate.smtinterpol.dpll.DPLLAtom;
import de.uni_freiburg.informatik.ultimate.smtinterpol.dpll.DPLLEngine;
import de.uni_freiburg.informatik.ultimate.smtinterpol.dpll.ITheory;
import de.uni_freiburg.informatik.ultimate.smtinterpol.dpll.Literal;
import de.uni_freiburg.informatik.ultimate.smtinterpol.model.Model;
import de.uni_freiburg.informatik.ultimate.smtinterpol.model.SharedTermEvaluator;
import de.uni_freiburg.informatik.ultimate.smtinterpol.proof.LeafNode;
import de.uni_freiburg.informatik.ultimate.smtinterpol.theory.cclosure.CCEquality;
import de.uni_freiburg.informatik.ultimate.smtinterpol.util.ArrayMap;
import de.uni_freiburg.informatik.ultimate.util.DebugMessage;
import de.uni_freiburg.informatik.ultimate.util.ScopedArrayList;
import de.uni_freiburg.informatik.ultimate.util.ScopedHashMap;

/**
 * Class implementing a decision procedure for linear arithmetic over 
 * rationals and integers. An algorithm proposed by Dutertre and de Moura 
 * is used. It provides tableau simplification, theory propagation, conflict 
 * set generation and interpolation.
 * 
 * To build a tableau, we create slack variables for every linear combination
 * of non-basic variables. To be able to recognize recurring linear
 * combinations, we canonicalize every linear combination and remember them the
 * first time we see them. Canonicalization takes advantage of the ordering of
 * non-basic variables. We transform every linear combination to an equivalent
 * one where the greatest common divisor of the coefficient is equal to one 
 * and the coefficient of the first non-basic variable is positive.
 * 
 * Theory propagation comes in two flavors: bound propagation and bound
 * refinement propagation. 
 * 
 * With bound propagation, we propagate every bound <code>x <= c'</code> after
 * setting bound <code>x <= c</code> where <code>c<=c'</code>. Lower bounds are
 * handled the same way.
 * 
 * With bound refinement propagation, we propagate bounds from the column
 * (non-basic) variables to a row (basic) variable.  The bound for the row
 * variable is a simple linear combination of the bounds for the column
 * variables. For the derived bound we create a composite reason to remember 
 * the bound.  Then we can use this composite reason as a source for bound 
 * propagation and propagate all bounds that are weaker than the composite.
 * 
 * @author Juergen Christ, Jochen Hoenicke
 */
public class LinArSolve implements ITheory {
	private static class UnsimpData {
		LinVar mVar;
		Rational mFac;
		public UnsimpData(LinVar v, Rational f) {
			mVar = v;
			mFac = f;
		}
	}
	/** The DPLL engine. */
	final DPLLEngine mEngine;
	/** The list of all variables (basic and nonbasic, integer and reals). */
	final ArrayList<LinVar> mLinvars;
	/** The list of all non-basic integer variables. */
	final ArrayList<LinVar> mIntVars;
	/** The literals that will be propagated. */
	final Queue<Literal> mProplist;
	/** The basic variables hashed by their linear combinations. */
	final ScopedHashMap<Map<LinVar,Rational>,LinVar> mTerms;
	/** List of all variables outside their bounds.
	 * I prefer a tree set here since it provides ordering, retrieval of the 
	 * first element, addition of elements and uniqueness of elements!
	 */
	final TreeSet<LinVar> mOob;
	/** Map of all variables removed by simplifying operations. Their chains are
	 * converted into maps to provide faster lookup.
	 */
	HashMap<LinVar,TreeMap<LinVar,Rational>> mSimps;
	/// --- Statistics variables ---
	/** Pivot counter. */
	int mNumPivots;
	/** Pivot counter. */
	int mNumPivotsBland;
	/** Counter for switches to Bland's Rule. */
	int mNumSwitchToBland;
	/** Time needed for pivoting operations. */
	long mPivotTime;
	/** Number of literals created due to composites. */
	int mCompositeCreateLit;

	// Statistics
	int mNumCuts;
	int mNumBranches;
	long mCutGenTime;
	final ScopedArrayList<SharedTerm> mSharedVars =
		new ScopedArrayList<SharedTerm>();
	
	/** The next suggested literals */
	final ArrayDeque<Literal> mSuggestions;
	
	private long mPropBoundTime;
	private long mPropBoundSetTime;
	private long mBacktrackPropTime;
	/**
	 * The variables for which we need to recompute the composite bounds.
	 */
	private final TreeSet<LinVar> mPropBounds;
	private LinVar mConflictVar;
	private Rational mEps;
	
	/** Are we in a check-sat? */
	private boolean mInCheck = false;
	/**
	 * The number of the next variable when created.
	 */
	private int mVarNum = 0;
	/**
	 * Cache for the lower bound of the freedom interval.  This cache is used to
	 * safe frequent memory allocations. 
	 */
	private ExactInfinitNumber mLower;
	/**
	 * Cache for the upper bound of the freedom interval.  This cache is used to
	 * safe frequent memory allocations. 
	 */
	private ExactInfinitNumber mUpper;
	/**
	 * Basic initialization.
	 * @param engine DPLLEngine this theory is used in.
	 */
	public LinArSolve(DPLLEngine engine) {
		mEngine = engine;
		mLinvars = new ArrayList<LinVar>();
		mIntVars = new ArrayList<LinVar>();
		mPropBounds = new TreeSet<LinVar>();
		mProplist = new ArrayDeque<Literal>();
		mSuggestions = new ArrayDeque<Literal>();
		mTerms = new ScopedHashMap<Map<LinVar,Rational>,LinVar>();
		mOob = new TreeSet<LinVar>();
		mSimps = new HashMap<LinVar, TreeMap<LinVar,Rational>>();
		mNumPivots = 0;
		mNumSwitchToBland = 0;
		mPivotTime = 0;
		mCompositeCreateLit = 0;
		mNumCuts = 0;
		mNumBranches = 0;
		mCutGenTime = 0;
//		m_compositeWatchers = new HashMap<LAReason, Set<CompositeReason>>();
	}

	/// --- Assertion check routines ---
	private boolean checkClean() {
		if (Config.EXPENSIVE_ASSERTS) {
			/* check if there are unprocessed bounds */
			for (LinVar v : mLinvars) {
				if (!v.mBasic)
					continue;
				assert v.checkBrpCounters();
				assert v.getUpperBound().lesseq(v.getUpperComposite());
				assert v.getLowerComposite().lesseq(v.getLowerBound());
				assert v.getLowerBound().mEps == 0 
						|| v.getDiseq(v.getLowerBound().mA) == null;
				assert v.getUpperBound().mEps == 0 
						|| v.getDiseq(v.getUpperBound().mA) == null;
			}
		}
		return true;
	}

	@SuppressWarnings("unused")
	private boolean checkColumn(MatrixEntry mentry) {
		LinVar c = mentry.mColumn;
		assert !c.mBasic;
		assert c.mHeadEntry.mColumn == c;
		assert c.mHeadEntry.mRow == LinVar.DUMMY_LINVAR;
		assert c.mHeadEntry.mNextInRow == null && c.mHeadEntry.mPrevInRow == null;
		boolean seen = false;
		for (MatrixEntry entry = c.mHeadEntry.mNextInCol;
			entry != c.mHeadEntry; entry = entry.mNextInCol) {
			assert entry.mNextInCol.mPrevInCol == entry; 
			assert entry.mPrevInCol.mNextInCol == entry; 
			assert entry.mColumn == c;
			if (mentry == entry)
				seen = true;
		}
		assert c.mHeadEntry.mNextInCol.mPrevInCol == c.mHeadEntry; 
		assert c.mHeadEntry.mPrevInCol.mNextInCol == c.mHeadEntry; 
		assert seen;
		return true;
	}

	private boolean checkPostSimplify() {
		if (mSimps == null)
			return true;
		for (LinVar b : mLinvars) {
			if (b.mBasic) {
				for (MatrixEntry entry = b.mHeadEntry.mNextInRow;
					entry != b.mHeadEntry; entry = entry.mNextInRow) {
					LinVar nb = entry.mColumn;
					assert !mSimps.containsKey(nb)
						: ("Variable " + b
								+ (b.mBasic ? " [basic]" : " [nonbasic]")
								+ " contains simplified variable!");
				}
			} else {
				for (MatrixEntry entry = b.mHeadEntry.mNextInCol;
					entry != b.mHeadEntry; entry = entry.mNextInCol) {
					LinVar nb = entry.mRow;
					assert !mSimps.containsKey(nb)
						: ("Variable " + b
								+ (b.mBasic ? " [basic]" : " [nonbasic]")
								+ " contains simplified variable!");
				}
			}
		}
		return true;
	}
	
	private boolean checkoobcontent() {
		for (LinVar v : mLinvars) {
			assert !v.outOfBounds() || mOob.contains(v)
				: "Variable " + v + " is out of bounds: "
				+ v.getLowerBound() + "<=" + v.getValue() + "<="
				+ v.getUpperBound();
		}
		return true;
	}

	/// --- Introduction of variables ---
	/**
	 * Add a new non-basic variable.
	 * @param name  Name of the variable
	 * @param isint Is the variable integer valued
	 * @param level The assertion stack level for this variable
	 * @return Newly created variable
	 */
	public LinVar addVar(SharedTerm name, boolean isint, int level) {
		if (mEngine.getLogger().isDebugEnabled())
			mEngine.getLogger().debug("Creating var " + name);
		LinVar var = new LinVar(name, isint, level, mVarNum++);
		mLinvars.add(var);
		if (isint)
			mIntVars.add(var);
		return var;
	}

	/**
	 * Add a new basic variable that is defined as linear combination.
	 * @param factors the linear combination as a map from LinVar to its factor.
	 *        The map must be normalized, i.e. divided by its gcd.
	 * @return Newly created variable
	 */
	public LinVar generateLinVar(TreeMap<LinVar, Rational> factors, int level) {
		if (factors.size() == 1) {
			Map.Entry<LinVar,Rational> me = factors.entrySet().iterator().next();
			assert me.getValue().equals(Rational.ONE);
			LinVar var = me.getKey();
			ensureUnsimplified(var);
			return var;
		}
		LinVar var = mTerms.get(factors);
		if (var == null) {
			// Linear combination not known yet
			LinVar[] vars = new LinVar[factors.size()];
			BigInteger[] coeffs = new BigInteger[factors.size()];
			int i = 0;
			TreeMap<LinVar,Rational> curcoeffs = new TreeMap<LinVar,Rational>();
			boolean isInt = true;
			for (Map.Entry<LinVar, Rational> entry : factors.entrySet()) {
				vars[i] = entry.getKey();
				assert entry.getValue().denominator().equals(BigInteger.ONE);
				coeffs[i] = entry.getValue().numerator();
				unsimplifyAndAdd(entry.getKey(), entry.getValue(), curcoeffs);
				isInt &= vars[i].mIsInt;
				i++;
			}
			ArrayMap<LinVar, BigInteger> intSum = 
				new ArrayMap<LinVar, BigInteger>(vars, coeffs);
			var = new LinVar(new LinTerm(intSum), isInt, level, mVarNum++);
			insertVar(var, curcoeffs);
			mTerms.put(factors, var);
			mLinvars.add(var);
			assert var.checkBrpCounters();
		}
		return var;
	}
	
	/**
	 * Generate a bound constraint for <code>at <(=) 0</code>.
	 * @param at     Affine input term (may be unit term c_i*x_i+c or 
	 * 				 sum_i c_i*x_i+c)
	 * @param strict   Strict bound (< instead of <=)
	 * @param level		Assertion stack level for the constraint.
	 * @param remember Should the variable remember the generated constraint?
	 * @return
	 */
	public Literal generateConstraint(MutableAffinTerm at,boolean strict,int level) {
		Rational normFactor = at.getGCD().inverse();
		at.mul(normFactor);
		LinVar var = generateLinVar(getSummandMap(at), level);
		return generateConstraint(var, at.mConstant.mA.negate(),
				normFactor.isNegative(), strict);
	}
	
	private final TreeMap<LinVar, Rational> getSummandMap(MutableAffinTerm at) {
		return at.getSummands();
	}

	
	/**
	 * Update values of all basic variables depending on some non-basic variable.
	 * @param updateVar the non-basic variable.
	 * @param newValue  Value to set for this variable.
	 */
	private void updateVariableValue(LinVar updateVar, InfinitNumber newValue) {
		assert(!updateVar.mBasic);
		InfinitNumber diff = newValue.sub(updateVar.getValue());
		updateVar.setValue(newValue);
		for (MatrixEntry entry = updateVar.mHeadEntry.mNextInCol;
			entry != updateVar.mHeadEntry; entry = entry.mNextInCol) {
			LinVar var = entry.mRow;
			assert var.mBasic;
			Rational coeff = Rational.valueOf(entry.mCoeff,
					var.mHeadEntry.mCoeff.negate());
			var.setValue(var.getValue().addmul(diff,coeff));
			assert var.checkBrpCounters();
			assert !var.getValue().mA.denominator().equals(BigInteger.ZERO);
			if (var.outOfBounds())
				mOob.add(var);
		}
	}

	/**
	 * Update bound of a non-basic variable and generate CompositeReasons for
	 * all its dependent basic variables.
	 * @param updateVar the non-basic variable.
	 * @param isUpper   whether upper or lower bound is assigned.
	 * @param oldBound  the previous bound.
	 * @param newBound  the new bound.
	 * @return Conflict clause detected during bound refinement propagations
	 */
	private Clause updateVariable(LinVar updateVar, boolean isUpper, 
			InfinitNumber oldBound, InfinitNumber newBound) {
		assert(!updateVar.mBasic);
		InfinitNumber diff = newBound.sub(updateVar.getValue());
		if ((diff.signum() > 0) == isUpper)
			diff = InfinitNumber.ZERO;
		else
			updateVar.setValue(newBound);
		
		assert !(updateVar.getValue().mA.denominator().equals(BigInteger.ZERO));
		Clause conflict = null;
		for (MatrixEntry entry = updateVar.mHeadEntry.mNextInCol;
			entry != updateVar.mHeadEntry; entry = entry.mNextInCol) {
			LinVar var = entry.mRow;
			assert var.mBasic;
			Rational coeff = Rational.valueOf(entry.mCoeff,
					var.mHeadEntry.mCoeff.negate());
			if (diff != InfinitNumber.ZERO)
				var.setValue(var.getValue().addmul(diff,coeff));
			assert !var.getValue().mA.denominator().equals(BigInteger.ZERO);
			if (var.outOfBounds())
				mOob.add(var);
			if (isUpper == entry.mCoeff.signum() < 0) {
				var.updateLower(entry.mCoeff, oldBound, newBound);
				assert var.checkBrpCounters();
				
				if (var.mNumLowerInf == 0) {
					if (conflict == null)
						conflict = propagateBound(var, false);
					else
						mPropBounds.add(var);
				}
				
			} else {
				var.updateUpper(entry.mCoeff, oldBound, newBound);
				assert var.checkBrpCounters();
				
				if (var.mNumUpperInf == 0) {
					if (conflict == null)
						conflict = propagateBound(var, true);
					else
						mPropBounds.add(var);
				}
			}
			assert(!var.mBasic || var.checkBrpCounters());
		}
		return conflict;
	}

	private void updatePropagationCountersOnBacktrack(LinVar var,
			InfinitNumber oldBound, InfinitNumber newBound,
			boolean upper) {
		for (MatrixEntry entry = var.mHeadEntry.mNextInCol;
			entry != var.mHeadEntry; entry = entry.mNextInCol) {
			if (upper == entry.mCoeff.signum() < 0) {
				entry.mRow.updateLower(entry.mCoeff, oldBound, newBound);
			} else {
				entry.mRow.updateUpper(entry.mCoeff, oldBound, newBound);
			}
			assert entry.mRow.checkBrpCounters();
		}
	}
	
	public void removeReason(LAReason reason) {
		LinVar var = reason.getVar();
		if (var.mBasic && var.mHeadEntry != null)
			mPropBounds.add(var);
		LAReason chain;
		if (reason.isUpper()) {
			if (var.mUpper == reason) {
				var.mUpper = reason.getOldReason();
				if (var.mDead) {
					/* do nothing for simplified variables */
				} else if (!var.mBasic) { // NOPMD
					updatePropagationCountersOnBacktrack(
							var, reason.getBound(), var.getUpperBound(), true);
					if (var.getValue().less(var.getLowerBound()))
						updateVariableValue(var, var.getLowerBound());
				} else if (var.outOfBounds()) {
					mOob.add(var);
				}
				return;
			}
			chain = var.mUpper;
		} else {
			if (var.mLower == reason) {
				var.mLower = reason.getOldReason();
				if (var.mDead) {
					/* do nothing for simplified variables */
				} else if (!var.mBasic) { // NOPMD
					updatePropagationCountersOnBacktrack(
							var, reason.getBound(), var.getLowerBound(), false);
					if (var.getUpperBound().less(var.getValue()))
						updateVariableValue(var, var.getUpperBound());
				} else if (var.outOfBounds()) {
					mOob.add(var);
				}
				return;
			}
			chain = var.mLower;
		}
		while (chain.getOldReason() != reason)
			chain = chain.getOldReason();
		chain.setOldReason(reason.getOldReason());
	}
	
	public void removeLiteralReason(LiteralReason reason) {
		for (LAReason depReason : reason.getDependents()) {
			removeReason(depReason);
		}
		removeReason(reason);
	}
	
	@Override
	public void backtrackLiteral(Literal literal) {
		DPLLAtom atom = literal.getAtom();
		LinVar var;
		InfinitNumber bound;
		if (atom instanceof LAEquality) {
			LAEquality laeq = (LAEquality) atom;
			var = laeq.getVar();
			bound = new InfinitNumber(laeq.getBound(), 0);
			if (laeq == literal.negate()) {
				// disequality: remove from diseq map
				var.removeDiseq(laeq);
			}
		} else if (atom instanceof BoundConstraint) {
			BoundConstraint bc = (BoundConstraint) atom;
			var = bc.getVar();
			bound = bc.getBound();
		} else
			return;

		LAReason reason = var.mUpper;
		while (reason != null && reason.getBound().lesseq(bound)) {
			if ((reason instanceof LiteralReason)
					&& ((LiteralReason)reason).getLiteral() == literal
					&& reason.getLastLiteral() == reason) {
				removeLiteralReason((LiteralReason)reason);
				break;
			}
			reason = reason.getOldReason();
		}
		reason = var.mLower;
		while (reason != null && bound.lesseq(reason.getBound())) {
			if ((reason instanceof LiteralReason)
					&& ((LiteralReason)reason).getLiteral() == literal
					&& reason.getLastLiteral() == reason) {
				removeLiteralReason((LiteralReason)reason);
				break;
			}
			reason = reason.getOldReason();
		}
	}
	
	/** Check if there is still a pending conflict that must be reported.
	 * @return the corresponding conflict clause or null, if no conflict pending.
	 */
	public Clause checkPendingConflict() {
		LinVar var = mConflictVar;
		if (var != null && var.getUpperBound().less(var.getLowerBound())) {
			// we still have a conflict
			Explainer explainer = new Explainer(
					this, mEngine.isProofGenerationEnabled(), null);
			InfinitNumber slack = var.getLowerBound().sub(var.getUpperBound());
			slack = var.mUpper.explain(explainer, slack, Rational.ONE);
			slack = var.mLower.explain(explainer, slack, Rational.MONE);
			return explainer.createClause(mEngine);
		}
		mConflictVar = null;
		return null;
	}

	@Override
	public Clause backtrackComplete() {
		mProplist.clear();
		mSuggestions.clear();

		Clause conflict = checkPendingConflict();
		if (conflict != null)
			return conflict;
		conflict = checkPendingBoundPropagations();
		if (conflict != null)
			return conflict;
		
		assert checkClean();
		return fixOobs();
	}

	private Clause checkPendingBoundPropagations() {
		/* check if there are unprocessed bounds */
		while (!mPropBounds.isEmpty()) {
			LinVar b = mPropBounds.pollFirst();
			if (b.mDead || !b.mBasic)
				continue;
			assert b.checkBrpCounters();
			long time;
			if (Config.PROFILE_TIME)
				time = System.nanoTime();
			Clause conflict = null;
			if (b.mNumUpperInf == 0)
				conflict = propagateBound(b, true);
			if (b.mNumLowerInf == 0) {
				if (conflict == null)
					conflict = propagateBound(b, false);
				else
					mPropBounds.add(b);
			}
			if (Config.PROFILE_TIME)
				mBacktrackPropTime += System.nanoTime() - time;
			if (conflict != null)
				return conflict;
		}
		return null;
	}

	@Override
	public Clause computeConflictClause() {
		mSuggestions.clear();
		mEngine.getLogger().debug("Final Check LA");
		Clause c = fixOobs();
		if (c != null)
			return c;

		c = ensureIntegrals();
		if (c != null || !mSuggestions.isEmpty() || !mProplist.isEmpty())
			return c;
		assert mOob.isEmpty();
		mutate();
		assert mOob.isEmpty();
		Map<ExactInfinitNumber, List<SharedTerm>> cong = getSharedCongruences();
		assert checkClean();
		mEngine.getLogger().debug(new DebugMessage("cong: {0}", cong));
		for (LinVar v : mLinvars) {
			LAEquality ea = v.getDiseq(v.getValue().mA);
			if (ea == null)
				continue;
			// if disequality equals bound, the bound should have 
			// already been refined.
			//assert !v.getUpperBound().equals(ea.getBound());
			//assert !v.getLowerBound().equals(ea.getBound());
			/*
			 * FIX: Since we only recompute the epsilons in ensureDisequality,
			 *      we might try to satisfy an already satisfied disequality. In
			 *      this case, we return null and have nothing to do.
			 */
			Literal lit = ensureDisequality(ea);
			if (lit != null) {
				assert lit.getAtom().getDecideStatus() == null;
				mSuggestions.add(lit);
				mEngine.getLogger().debug(new DebugMessage(
					"Using {0} to ensure disequality {1}", lit,
					ea.negate()));
			}
		}
		if (mSuggestions.isEmpty() && mProplist.isEmpty())
			return mbtc(cong);
		assert compositesSatisfied();
		return null;
	}

	private boolean compositesSatisfied() {
		for (LinVar v : mLinvars) {
			if (v.mBasic)
				v.fixEpsilon();
			assert v.getValue().lesseq(v.getUpperBound());
			assert v.getLowerBound().lesseq(v.getValue());
		}
		return true;
	}

	@Override
	public Literal getPropagatedLiteral() {
		while (!mProplist.isEmpty()) {
			Literal lit = mProplist.remove();
			if (lit.getAtom().getDecideStatus() == null) {
				return lit;
			}
		}
		return null;
	}
	
	private Clause createUnitClause(Literal literal, boolean isUpper,
			InfinitNumber bound, LinVar var) {
		Explainer explainer = new Explainer(
				this, mEngine.isProofGenerationEnabled(), literal);
		if (isUpper) {
			assert var.getUpperBound().less(bound);
			explainer.addLiteral(literal, Rational.MONE);
			LAReason reason = var.mUpper;
			// Find the first oldest reason that explains the bound
			while (reason.getOldReason() != null
					&& reason.getOldReason().getBound().less(bound))
				reason = reason.getOldReason();
			reason.explain(explainer, 
					bound.sub(reason.getBound()), 
					Rational.ONE);
		} else {
			assert bound.less(var.getLowerBound());
			explainer.addLiteral(literal, Rational.ONE);
			LAReason reason = var.mLower;
			// Find the first oldest reason that explains the bound
			while (reason.getOldReason() != null
					&& bound.less(reason.getOldReason().getBound()))
				reason = reason.getOldReason();
			reason.explain(explainer, 
					reason.getBound().sub(bound), 
					Rational.MONE);
		}
		return explainer.createClause(mEngine);
	}

	@Override
	public Clause getUnitClause(Literal literal) {
		DPLLAtom atom = literal.getAtom();
		if (atom instanceof LAEquality) {
			LAEquality laeq = (LAEquality)atom;
			LinVar var = laeq.getVar();
			if (literal == laeq) {
				InfinitNumber bound = new InfinitNumber(laeq.getBound(), 0);
				LAReason upperReason = var.mUpper;
				while (upperReason.getBound().less(bound))
					upperReason = upperReason.getOldReason();
				LAReason lowerReason = var.mLower;
				while (bound.less(lowerReason.getBound()))
					lowerReason = lowerReason.getOldReason();
				assert upperReason.getBound().equals(bound)
						&& lowerReason.getBound().equals(bound)
						: "Bounds on variable do not match propagated equality bound";
				Explainer explainer = new Explainer(
						this, mEngine.isProofGenerationEnabled(), literal);
				LiteralReason uppereq = new LiteralReason(
						var, var.getUpperBound().sub(var.getEpsilon()), 
						true, laeq.negate());
				uppereq.setOldReason(upperReason);
				lowerReason.explain(explainer, var.getEpsilon(), Rational.MONE);
				explainer.addEQAnnotation(uppereq, Rational.ONE);

				return explainer.createClause(mEngine);
			} else  {
				InfinitNumber bound = new InfinitNumber(laeq.getBound(), 0);
				// Check if this was propagated due to an upper bound.
				// We also need to make sure that the upper bound does not
				// depend on the inequality literal.
				LAReason upper = var.mUpper;
				while (laeq.getStackPosition() >= 0
						&& upper != null 
						&& upper.getLastLiteral().getStackPosition() >= laeq.getStackPosition())
					upper = upper.getOldReason();
				return createUnitClause(literal, upper != null
						&& upper.getBound().less(bound), bound, var);
			}
		} else if (atom instanceof CCEquality) {
			return generateEqualityClause(literal);
		} else {
			BoundConstraint bc = (BoundConstraint)atom;
			LinVar var = bc.getVar();
			boolean isUpper = literal.getSign() > 0;
			return createUnitClause(literal, isUpper,
					isUpper ? bc.getInverseBound() : bc.getBound(), var);
		}
	}
	
	private Clause generateEqualityClause(Literal cclit) {
		CCEquality cceq = (CCEquality) cclit.getAtom();
		Literal ea = cceq.getLASharedData();
		if (cceq == cclit)
			ea = ea.negate();
		return new Clause(new Literal[] { cclit, ea }, 
				new LeafNode(LeafNode.EQ, EQAnnotation.EQ));
	}

	/**
	 * Create a new LiteralReason for a newly created and back-propagated 
	 * literal and add the reason to the right position in the reason chain.
	 * 
	 * @param var The variable that got a new literal
	 * @param lit The newly created literal that was inserted as propagated literal.
	 */
	private void insertReasonOfNewComposite(LinVar var, Literal lit) {
		BoundConstraint bc = (BoundConstraint) lit.getAtom();
		boolean isUpper = lit == bc;
		
		if (isUpper) {
			InfinitNumber bound = bc.getBound();
			LiteralReason reason = new LiteralReason(var, bound, true, lit);
			// insert reason into the reason chain
			if (bound.less(var.getExactUpperBound())) {
				reason.setOldReason(var.mUpper);
				var.mUpper = reason;
			} else {
				LAReason thereason = var.mUpper;
				while (thereason.getOldReason().getExactBound().less(bound)) {
					thereason = thereason.getOldReason();
				}
				assert (thereason.getExactBound().less(bound)
						&& bound.less(thereason.getOldReason().getExactBound()));
				reason.setOldReason(thereason.getOldReason());
				thereason.setOldReason(reason);
			}
			if (var.mBasic && var.outOfBounds())
				mOob.add(var);
		} else {
			InfinitNumber bound = bc.getInverseBound();
			LiteralReason reason = new LiteralReason(var, bound, false, lit);
			// insert reason into the reason chain
			if (var.getExactLowerBound().less(bound)) {
				reason.setOldReason(var.mLower);
				var.mLower = reason;
			} else {
				LAReason thereason = var.mLower;
				while (bound.less(thereason.getOldReason().getExactBound())) {
					thereason = thereason.getOldReason();
				}
				assert (thereason.getOldReason().getExactBound().less(bound)
						&& bound.less(thereason.getExactBound()));
				reason.setOldReason(thereason.getOldReason());
				thereason.setOldReason(reason);
			}
			if (var.mBasic && var.outOfBounds())
				mOob.add(var);
		}
	}
	
	private Clause setBound(LAReason reason) {
		LinVar var = reason.getVar();
		InfinitNumber bound = reason.getBound();
		InfinitNumber epsilon = var.getEpsilon();
		LiteralReason lastLiteral = reason.getLastLiteral();
		if (reason.isUpper()) {
			// check if bound is stronger
			InfinitNumber oldBound = var.getUpperBound();
			assert reason.getExactBound().less(var.getExactUpperBound());
			reason.setOldReason(var.mUpper);
			var.mUpper = reason;

			// Propagate Disequalities
			LAEquality ea;
			while (bound.mEps == 0 && (ea = var.getDiseq(bound.mA)) != null) {
				bound = bound.sub(epsilon);
				if (ea.getStackPosition() > lastLiteral.getStackPosition()) {
					lastLiteral = new LiteralReason(var, bound, 
							true, ea.negate());
					var.mUpper = lastLiteral;
				} else  {
					var.mUpper = new LiteralReason(var, bound, 
							true, ea.negate(), 
							lastLiteral);
					lastLiteral.addDependent(var.mUpper);
				}
				var.mUpper.setOldReason(reason);
				reason = var.mUpper;
			}
			
			if (!var.mBasic) { // NOPMD
				Clause conflict = updateVariable(var, true, oldBound, bound);
				if (conflict != null)
					return conflict;
			} else if (var.outOfBounds())
				mOob.add(var);

			for (BoundConstraint bc
					: var.mConstraints.subMap(bound, oldBound).values()) {
				assert var.getUpperBound().lesseq(bc.getBound());
				mProplist.add(bc);
			}
			for (LAEquality laeq
					: var.mEqualities.subMap(bound.add(var.getEpsilon()),
							oldBound.add(var.getEpsilon())).values()) {
				mProplist.add(laeq.negate());
			}
		} else {
			// lower
			// check if bound is stronger
			InfinitNumber oldBound = var.getLowerBound();
			assert var.getExactLowerBound().less(reason.getExactBound());
			reason.setOldReason(var.mLower);
			var.mLower = reason;

			// Propagate Disequalities
			LAEquality ea;
			while (bound.mEps == 0 && (ea = var.getDiseq(bound.mA)) != null) {
				bound = bound.add(epsilon);
				if (ea.getStackPosition() > lastLiteral.getStackPosition()) {
					lastLiteral = new LiteralReason(var, bound, 
							false, ea.negate());
					var.mLower = lastLiteral;
				} else  {
					var.mLower = new LiteralReason(var, bound, 
							false, ea.negate(), 
							lastLiteral);
					lastLiteral.addDependent(var.mLower);
				}
				var.mLower.setOldReason(reason);
				reason = var.mLower;
			}
			
			if (!var.mBasic) { // NOPMD
				Clause conflict = updateVariable(var, false, oldBound, bound);
				if (conflict != null)
					return conflict;
			} else if (var.outOfBounds())
				mOob.add(var);

			for (BoundConstraint bc
					: var.mConstraints.subMap(oldBound, bound).values()) {
				assert bc.getInverseBound().lesseq(var.getLowerBound());
				mProplist.add(bc.negate());
			}
			for (LAEquality laeq
					: var.mEqualities.subMap(oldBound, bound).values()) {
				mProplist.add(laeq.negate());
			}
		}
		InfinitNumber ubound = var.getUpperBound();
		InfinitNumber lbound = var.getLowerBound();
		if (lbound.equals(ubound)) {
			LAEquality lasd = var.mEqualities.get(lbound);
			if (lasd != null && lasd.getDecideStatus() == null)
				mProplist.add(lasd);
		} else if (ubound.less(lbound)) {
			// we have a conflict
			mConflictVar = var;
			return checkPendingConflict();
		}
		assert (var.mBasic || !var.outOfBounds());
		return null;
	}
	
	@Override
	public Clause setLiteral(Literal literal) {
		Clause conflict = checkPendingBoundPropagations();
		if (conflict != null)
			return conflict;
		assert checkClean();
		if (mProplist.contains(literal.negate()))
			return getUnitClause(literal.negate());
		DPLLAtom atom = literal.getAtom();
		if (atom instanceof LAEquality) {
			LAEquality lasd = (LAEquality) atom;
			/* Propagate dependent atoms */
			for (CCEquality eq : lasd.getDependentEqualities()) {
				if (eq.getDecideStatus() == null) {
					mProplist.add(literal == atom ? eq : eq.negate());
				} else if (eq.getDecideStatus().getSign() != literal.getSign()) {
					/* generate conflict */
					return generateEqualityClause(eq.getDecideStatus().negate());
				}
			}
			
			LinVar var = lasd.getVar();
			InfinitNumber bound = new InfinitNumber(lasd.getBound(), 0);
			if (literal.getSign() == 1) {
				// need to assert ea as upper and lower bound
				/* we need to take care of propagations:
				 * x = c ==> x <= c && x >= c should not propagate
				 * x <= c - k1 or x >= c + k2, but
				 * x <= c and x >= c
				 */
				if (mEngine.getLogger().isDebugEnabled())
					mEngine.getLogger().debug("Setting "
							+ lasd.getVar() + " to " + lasd.getBound());
				if (bound.less(var.getUpperBound()))
					conflict = setBound(new LiteralReason(var, bound, 
							true, literal));
				if (conflict != null)
					return conflict;
				if (var.getLowerBound().less(bound))
					conflict = setBound(new LiteralReason(var, bound, 
							false, literal));
			} else {
				// Disequality constraint
				var.addDiseq(lasd);
				if (var.getUpperBound().equals(bound)) {
					conflict = setBound(new LiteralReason(var, 
							bound.sub(var.getEpsilon()), 
							true, literal));
				} else if (var.getLowerBound().equals(bound)) {
					conflict = setBound(new LiteralReason(var, 
							bound.add(var.getEpsilon()), 
							false, literal));
				}
			}
		} else if (atom instanceof BoundConstraint) {
			BoundConstraint bc = (BoundConstraint) atom;
			LinVar var = bc.getVar();
			// Check if the *exact* bound is refined and add this 
			// literal as reason.  This is even done, if we propagated the
			// literal.  If there is already a composite with the
			// same bound, we still may use it later to explain the literal,
			// see LiteralReason.explain.
			if (literal == bc) {
				if (bc.getBound().less(var.getExactUpperBound()))
					conflict = setBound(new LiteralReason(var, bc.getBound(), 
							true, literal));
			} else {
				if (var.getExactLowerBound().less(bc.getInverseBound()))
					conflict = setBound(new LiteralReason(var, bc.getInverseBound(), 
							false, literal));
			}
		}
		assert (conflict != null || checkClean());
		return conflict;
	}

	@Override
	public Clause checkpoint() {
		Clause conflict = checkPendingBoundPropagations();
		if (conflict != null)
			return conflict;
		// Prevent pivoting before tableau simplification
		if (!mInCheck)
			return null;
		return fixOobs();
	}

	public Rational realValue(LinVar var) {
		if (mEps == null)
			prepareModel();
		if (var.mDead) {
			Rational val = Rational.ZERO;
			for (Entry<LinVar,Rational> chain : mSimps.get(var).entrySet())
				val = val.add(realValue(chain.getKey()).mul(chain.getValue()));
			return val;
		} else {
			Rational cureps = var.computeEpsilon();
			return var.getValue().mA.addmul(mEps, cureps);
		}
	}
	
	public void dumpTableaux(Logger logger) {
		for (LinVar var : mLinvars) {
			if (var.mBasic) {
				StringBuilder sb = new StringBuilder();
				sb.append(var.mHeadEntry.mCoeff).append('*').append(var).
				append(" ; ");
				for (MatrixEntry entry = var.mHeadEntry.mNextInRow;
						entry != var.mHeadEntry; entry = entry.mNextInRow) {
					sb.append(" ; ").append(entry.mCoeff)
						.append('*').append(entry.mColumn);
				}
				logger.debug(sb.toString());
			}
		}
	}
	
	public void dumpConstraints(Logger logger) {
		for (LinVar var : mLinvars) {
			StringBuilder sb = new StringBuilder();
			sb.append(var).append(var.mIsInt ? "[int]" : "[real]").append(": ");
			InfinitNumber lower = var.getLowerBound();
			if (lower != InfinitNumber.NEGATIVE_INFINITY)
				sb.append("lower: ").append(var.getLowerBound()).append(" <= ");
			sb.append(var.getValue());
			InfinitNumber upper = var.getUpperBound();
			if (upper != InfinitNumber.POSITIVE_INFINITY)
				sb.append(" <= ").append(upper).append(" : upper");
			logger.debug(sb);
		}
	}

	private void prepareModel() {
		/* Shortcut: If info log level is enabled we prepare the model to dump
		 * it as info message and later on when we have to produce a model.
		 * This work can be avoided.
		 */
		if (mEps != null)
			return;
		TreeSet<Rational> prohibitions = new TreeSet<Rational>();
		InfinitNumber maxeps = computeMaxEpsilon(prohibitions);
		if (maxeps == InfinitNumber.POSITIVE_INFINITY)
			mEps = Rational.ONE;
		else
			mEps = maxeps.inverse().ceil().mA.inverse();
		Map<Rational,Set<ExactInfinitNumber>> sharedPoints = 
				new TreeMap<Rational, Set<ExactInfinitNumber>>();
		// Do not merge two shared variables that are not yet merged.
		Map<ExactInfinitNumber, List<SharedTerm>> cong = getSharedCongruences();
		for (ExactInfinitNumber value : cong.keySet()) {
			Rational eps = value.getEpsilon();
			Set<ExactInfinitNumber> confl = sharedPoints.get(eps);
			if (confl == null) {
				confl = new TreeSet<ExactInfinitNumber>();
				sharedPoints.put(eps, confl);
			}
			confl.add(value);
		}
		// If we cannot choose the current value since we would violate a
		// disequality, choose a different number.
		while (prohibitions.contains(mEps)
				|| hasSharing(sharedPoints, new ExactInfinitNumber(mEps)))
			mEps = mEps.inverse().add(Rational.ONE).inverse();
	}
	
	@Override
	public void dumpModel(Logger logger) {
		dumpTableaux(logger);
		dumpConstraints(logger);
		prepareModel();
		logger.info("Assignments:");
		for (LinVar var : mLinvars) {
			if (!var.isInitiallyBasic())
				logger.info(var + " = " + realValue(var));
		}
		if (mSimps != null) {
			for (LinVar var : mSimps.keySet())
				logger.info(var + " = " + realValue(var));
		}
	}

	@Override
	public void printStatistics(Logger logger) {
		logger.info("Number of Bland pivoting-Operations: "
				+ mNumPivotsBland + "/" + mNumPivots);
		logger.info("Number of switches to Bland's Rule: " + mNumSwitchToBland);
		int basicVars = 0;
		for (LinVar var : mLinvars) {
			if (!var.isInitiallyBasic())
				basicVars++;
		}
		logger.info("Number of variables: " + mLinvars.size()
				+ " nonbasic: " + basicVars + " shared: " + mSharedVars.size());			
		logger.info("Time for pivoting         : " + mPivotTime / 1000000);// NOCHECKSTYLE
		logger.info("Time for bound computation: " + mPropBoundTime / 1000000);// NOCHECKSTYLE
		logger.info("Time for bound setting    : " + mPropBoundSetTime / 1000000);// NOCHECKSTYLE
		logger.info("Time for bound comp(back) : " + mBacktrackPropTime/1000000);// NOCHECKSTYLE
		logger.info("No. of simplified variables: " + (mSimps == null ? 0 : mSimps.size()));
		logger.info("Composite::createLit: " + mCompositeCreateLit);
		logger.info("Number of cuts: " + mNumCuts);
		logger.info("Time for cut-generation: " + mCutGenTime / 1000000);// NOCHECKSTYLE
		logger.info("Number of branchings: " + mNumBranches);
	}
	
	/**
	 * Pivot nonbasic versus basic variables along a coefficient.
	 * @param pivotEntry Coefficient specifying basic and nonbasic variable.
	 * @return Conflict clause detected during propagations
	 */
	private final Clause pivot(MatrixEntry pivotEntry) {
		if (mEngine.getLogger().isDebugEnabled())
			mEngine.getLogger().debug("pivot " + pivotEntry);
		Clause conflict = null;
		++mNumPivots;
		long starttime;
		if (Config.PROFILE_TIME)
			starttime = System.nanoTime();
		LinVar basic = pivotEntry.mRow;
		LinVar nonbasic = pivotEntry.mColumn;
		assert basic.checkChainlength();
		assert nonbasic.checkChainlength();
		assert basic.checkBrpCounters();
		assert basic.mBasic;
		assert !nonbasic.mBasic;
		basic.mBasic = false;
		nonbasic.mBasic = true;
		
		// Adjust brp numbers
		BigInteger nbcoeff = pivotEntry.mCoeff;
		BigInteger bcoeff = basic.mHeadEntry.mCoeff;
		basic.updateUpperLowerClear(nbcoeff, nonbasic);
		nonbasic.moveBounds(basic);
		nonbasic.updateUpperLowerSet(bcoeff, basic);
		assert basic.checkCoeffChain();
		pivotEntry.pivot();
		basic.mCachedRowVars = null;
		basic.mCachedRowCoeffs = null;

		assert nonbasic.checkChainlength();
		assert basic.checkChainlength();
		assert nonbasic.mCachedRowCoeffs == null;
		assert nonbasic.checkCoeffChain();
		assert nonbasic.checkBrpCounters();

		// Eliminate nonbasic from all equations
		MatrixEntry columnHead = nonbasic.mHeadEntry;
		while (columnHead.mNextInCol != columnHead) {
			LinVar row = columnHead.mNextInCol.mRow;
			assert row.checkBrpCounters();
			assert row.checkChainlength();
			columnHead.mNextInCol.add(columnHead);
			assert row.checkChainlength();
			row.mCachedRowVars = null;
			row.mCachedRowCoeffs = null;
			assert row.checkCoeffChain();
			assert row.checkBrpCounters();
			if (row.mNumUpperInf == 0) {
				if (conflict == null)
					conflict = propagateBound(row, true);
				else
					mPropBounds.add(row);
			}
			if (row.mNumLowerInf == 0) {
				if (conflict == null)
					conflict = propagateBound(row, false);
				else
					mPropBounds.add(row);
			}
		}
		
		assert nonbasic.checkChainlength();
		
		if (nonbasic.mNumUpperInf == 0) {
			if (conflict == null)
				conflict = propagateBound(nonbasic, true);
			else
				mPropBounds.add(nonbasic);
		}
		if (nonbasic.mNumLowerInf == 0) {
			if (conflict == null)
				conflict = propagateBound(nonbasic, false);
			else
				mPropBounds.add(nonbasic);
		}
		if (Config.PROFILE_TIME)
			mPivotTime += System.nanoTime() - starttime;
//		mengine.getLogger().debug("Pivoting took " + (System.nanoTime() - starttime));
		return conflict;
	}
		
	/**
	 * Ensure that all integer variables have integral values. 
	 * @return Conflict clause or <code>null</code> if formula is satisfiable.
	 */
	private Clause ensureIntegrals() {
		boolean isIntegral = true;
		for (LinVar lv : mIntVars) {
			lv.fixEpsilon();
			if (!lv.getValue().isIntegral())
				isIntegral = false;
		}
		if (isIntegral)
			return null;

		Logger logger = mEngine.getLogger();
		if (logger.isDebugEnabled()) {
			dumpTableaux(logger);
			dumpConstraints(logger);
		}

		// Satisfiable in the reals
		assert mOob.isEmpty();
		long start;
		if (Config.PROFILE_TIME)
			start = System.nanoTime();
		CutCreator cutter = new CutCreator(this);
		cutter.generateCuts();
		if (Config.PROFILE_TIME)
			mCutGenTime += System.nanoTime() - start;
		Clause c = checkPendingConflict();
		if (c != null)
			return c;
		c = checkpoint();
		if (c != null)
			return c;
		return null;
	}
	
	/**
	 * Check whether all constraints can be satisfied. Here, we use the set of
	 * all variables outside their bounds. Rest of this algorithm is copied 
	 * from original paper.
	 * 
	 * If the formula is satisfiable, we additionally search for bound 
	 * refinement propagations and calculate the values of all variables
	 * simplified out.
	 * @return Conflict clause or <code>null</code> if formula is satisfiable.
	 */
	@SuppressWarnings("unused")
	private Clause fixOobs() {
//		
//		mengine.getLogger().debug("===Start Of Dump===");
//		dumpTableaux(mengine.getLogger());
//		dumpConstraints(mengine.getLogger());
//		System.exit(0);
//		
		// The number of pivoting operations with our own strategy
		final int switchtobland = Config.BLAND_USE_FACTOR * mLinvars.size();
		assert checkClean();
		assert !Config.EXPENSIVE_ASSERTS || checkoobcontent();
		int curnumpivots = 0;
		boolean useBland = false;
	poll_loop:
		while (!mOob.isEmpty()) {
			LinVar oob = useBland ? mOob.pollFirst() : findBestVar();
			if (oob == null)
				return null;
			assert oob.mBasic;
			assert oob.getLowerBound().lesseq(oob.getUpperBound());
			assert oob.checkBrpCounters();
			assert oob.checkCoeffChain();
			InfinitNumber bound;
			InfinitNumber diff;
			BigInteger denom;
			boolean isLower;
			// BUGFIX: Use exact bounds here...
			oob.fixEpsilon();
			if (oob.getValue().compareTo(oob.getExactLowerBound()) < 0) {
				bound = oob.getLowerBound();
				isLower = oob.mHeadEntry.mCoeff.signum() < 0;
				diff = oob.getValue().sub(bound).negate();
				denom = oob.mHeadEntry.mCoeff;
			} else if (oob.getValue().compareTo(oob.getExactUpperBound()) > 0) {
				bound = oob.getUpperBound();
				isLower = oob.mHeadEntry.mCoeff.signum() > 0;
				diff = oob.getValue().sub(bound);
				denom = oob.mHeadEntry.mCoeff.negate();
			} else {
				/* no longer out of bounds */
				continue;
			}
			assert InfinitNumber.ZERO.less(diff);
			
			//TODO: This code looks ugly!
			MatrixEntry entry;
			if (useBland) {
				entry = oob.mHeadEntry;
				/* Find the lowest element in the row chain */
				while (entry.mColumn.mMatrixpos > entry.mPrevInRow.mColumn.mMatrixpos)
					entry = entry.mNextInRow;
			} else
				entry = findNonBasic(oob, isLower);
			MatrixEntry start = entry;
			/* Iterate elements in the row chain */
			do {
				assert (entry == oob.mHeadEntry || !entry.mColumn.mBasic);
				assert (entry == oob.mHeadEntry || entry.mColumn.getValue().compareTo(entry.mColumn.getUpperBound()) <= 0);
				assert (entry == oob.mHeadEntry || entry.mColumn.getValue().compareTo(entry.mColumn.getLowerBound()) >= 0);
				if (entry != oob.mHeadEntry) {
					boolean checkLower = (entry.mCoeff.signum() < 0) == isLower;
					InfinitNumber colBound = checkLower 
						? entry.mColumn.getLowerBound() 
						: entry.mColumn.getUpperBound();
					InfinitNumber slack = entry.mColumn.getValue().sub(colBound);
					slack = slack.mul(Rational.valueOf(entry.mCoeff,denom));
					if (!useBland && slack.less(diff)) {
						if (slack.signum() > 0) {
							updateVariableValue(entry.mColumn, colBound);
							// UpdateVariableValue will put us back into the
							// oob list.  So we remove us.
							mOob.remove(oob);
							oob.fixEpsilon();
							/* JC: These assertions are not valid anymore.
							 * Changing the value of a non-basic variable might
							 * actually fix the problem with the basic variable.
							 * However, we still need to pivot once to prevent
							 * trivial non-termination.
							 * 
							 * Alternative: Don't change the value of this
							 * non-basic variable again, but this has some other
							 * side-effects (e.g. in the selection of a new
							 * pivot variable).
							 */
//							assert !oob.m_curval.equals(bound);
							diff = oob.getValue().sub(bound);
							if (diff.signum() < 0)
								diff = diff.negate();
//							assert diff.signum() != 0;
						}
					} else if (slack.signum() > 0) {
						assert(!mOob.contains(oob));
						Clause conflict = pivot(entry);
						boolean oldBland = useBland;
						if (oldBland)
							++mNumPivotsBland;
						useBland = ++curnumpivots > switchtobland;
						if (useBland && !oldBland) { 
							mEngine.getLogger().debug("Using Blands Rule");
							mNumSwitchToBland++;
						}
						updateVariableValue(oob, bound);
						if (conflict != null)
							return conflict;
						continue poll_loop;
					}
				}
				entry = useBland ? entry.mNextInRow : findNonBasic(oob, isLower);
			} while (!useBland || entry != start);
			assert oob.checkBrpCounters();
			throw new AssertionError("Bound was not propagated????");
		} // end of queue polling
		assert checkClean();
		assert !Config.EXPENSIVE_ASSERTS || checkoobcontent();
		return null;
	}
	
	private Clause propagateBound(LinVar basic, boolean isUpper) {
		long start;
		if (Config.PROFILE_TIME)
			start = System.nanoTime();
		BigInteger denom = basic.mHeadEntry.mCoeff.negate();
		isUpper ^= denom.signum() < 0;
		InfinitNumber bound = isUpper ? basic.getUpperComposite() 
				: basic.getLowerComposite();
		if (isUpper ? bound.less(basic.getUpperBound())
				    : basic.getLowerBound().less(bound)) {

			LAReason[] reasons;
			Rational[] coeffs;
			LiteralReason lastLiteral = null;
			if (basic.mCachedRowCoeffs == null) {
				int rowLength = 0;
				for (MatrixEntry entry = basic.mHeadEntry.mNextInRow;
				     entry != basic.mHeadEntry; entry = entry.mNextInRow)
					rowLength++;
				
				LinVar[] rowVars = new LinVar[rowLength];
				reasons = new LAReason[rowLength];
				coeffs = new Rational[rowLength];
				int i = 0;
				for (MatrixEntry entry = basic.mHeadEntry.mNextInRow;
				 	 entry != basic.mHeadEntry; entry = entry.mNextInRow) {
					LinVar nb = entry.mColumn;
					Rational coeff = Rational.valueOf(entry.mCoeff, denom);
					rowVars[i] = nb;
					reasons[i] = coeff.isNegative() == isUpper ? nb.mLower : nb.mUpper;
					coeffs[i] = coeff;
					LiteralReason lastOfThis = reasons[i].getLastLiteral();
					if (lastLiteral == null 
						|| lastOfThis.getStackPosition() > lastLiteral.getStackPosition()) {
						lastLiteral = lastOfThis;
					}
					i++;
				}
				basic.mCachedRowCoeffs = coeffs;
				basic.mCachedRowVars = rowVars;
			} else {
				LinVar[] rowVars = basic.mCachedRowVars;
				coeffs = basic.mCachedRowCoeffs;
				reasons = new LAReason[rowVars.length];
				for (int i = 0; i < rowVars.length; i++) {
					reasons[i] = coeffs[i].isNegative() == isUpper 
						? rowVars[i].mLower : rowVars[i].mUpper;
					LiteralReason lastOfThis = reasons[i].getLastLiteral();
					if (lastLiteral == null 
						|| lastOfThis.getStackPosition() > lastLiteral.getStackPosition()) {
						lastLiteral = lastOfThis;
					}
				}
			}
			CompositeReason newComposite =
				new CompositeReason(basic, bound, isUpper,
						            reasons, coeffs, lastLiteral);
			lastLiteral.addDependent(newComposite);
			long mid;
			if (Config.PROFILE_TIME) {
				mid = System.nanoTime();
				mPropBoundTime += mid - start;
			}
			Clause conflict = setBound(newComposite);
			if (Config.PROFILE_TIME)
				mPropBoundSetTime += System.nanoTime() - mid;
			return conflict;
		}
		if (Config.PROFILE_TIME)
			mPropBoundTime += System.nanoTime() - start;
		return null;
	}
	
	/**
	 * Dump the equations of all variables simplified out of the tableau.
	 */
	@SuppressWarnings("unused")
	private void dumpSimps() {
		if (mSimps == null)
			return;
		for (Entry<LinVar, TreeMap<LinVar,Rational>> me : mSimps.entrySet()) {
			mEngine.getLogger().debug(me.getKey() + " = " + me.getValue());
		}
	}

	/**
	 * Generate a bound constraint for a given variable. We use
	 * {@link BoundConstraint}s to represent bounds for variables
	 * and linear combination thereof. Those constraints are optimized to
	 * prevent strict bounds by manipulating the bounds.
	 * @param var      Variable to set bound on.
	 * @param bound    Bound to set on <code>var</code>.
	 * @param isLowerBound Is the bound a lower bound?
	 * @param strict   Is the bound strict?
	 * @return Constraint representing this bound.
	 */
	private Literal generateConstraint(LinVar var, Rational bound,
			boolean isLowerBound, boolean strict) {
		InfinitNumber rbound = new InfinitNumber(bound, 
				(strict ^ isLowerBound) ? -1 : 0);
		if (var.isInt())
			rbound = rbound.floor();
		return generateConstraint(var,rbound,isLowerBound);
	}
	
	private Literal generateConstraint(LinVar var, InfinitNumber rbound,
			boolean isLowerBound) {
		if (var.mDead)
			ensureUnsimplified(var);
		BoundConstraint bc = var.mConstraints.get(rbound);
		if (bc == null) {
			bc = new BoundConstraint(rbound, var, mEngine.getAssertionStackLevel());
			assert bc.mVar.checkCoeffChain();
			mEngine.addAtom(bc);
			if (var.getUpperBound().lesseq(rbound))
				mProplist.add(bc);
			if (rbound.less(var.getLowerBound()))
				mProplist.add(bc.negate());
		}
		return isLowerBound ? bc.negate() : bc;
	}
	
	/**
	 * Insert a new basic variable into the tableau.
	 * @param v      Variable to insert.
	 * @param coeffs Coefficients for this variable wrt mbasics and msimps.
	 */
	private void insertVar(LinVar v, TreeMap<LinVar,Rational> coeffs) {
		v.mBasic = true;
		v.mHeadEntry = new MatrixEntry();
		v.mHeadEntry.mColumn = v;
		v.mHeadEntry.mRow = v;
		v.mHeadEntry.mNextInRow = v.mHeadEntry.mPrevInRow = v.mHeadEntry;
		v.mHeadEntry.mNextInCol = v.mHeadEntry.mPrevInCol = v.mHeadEntry;
		v.resetComposites();

		MutableInfinitNumber val = new MutableInfinitNumber();
		Rational gcd = Rational.ONE;
		for (Rational c : coeffs.values()) {
			gcd = gcd.gcd(c);
		}
		assert gcd.numerator().equals(BigInteger.ONE);
		v.mHeadEntry.mCoeff = gcd.denominator().negate();
		for (Map.Entry<LinVar,Rational> me : coeffs.entrySet()) {
			assert(!me.getKey().mBasic);
			LinVar nb = me.getKey();
			Rational value = me.getValue().div(gcd);
			assert value.denominator().equals(BigInteger.ONE);
			BigInteger coeff = value.numerator();
			v.mHeadEntry.insertRow(nb, coeff);
			val.addmul(nb.getValue(), value);
			v.updateUpperLowerSet(coeff, nb);
		}
		val = val.mul(gcd);
		v.setValue(val.toInfinitNumber());
		assert v.checkBrpCounters();
		if (v.mNumUpperInf == 0 || v.mNumLowerInf == 0)
			mPropBounds.add(v);
		assert !v.getValue().mA.denominator().equals(BigInteger.ZERO);
	}
	
	/**
	 * Remove a basic variable from the tableau. Note that <code>v</code> has
	 * to be a basic variable. So you might need to call pivot before removing
	 * a variable.
	 * @param v Basic variable to remove from the tableau.
	 */
	private TreeMap<LinVar, Rational> removeVar(LinVar v) {
		assert v.mBasic;
//		mcurbasics.remove(v);
		mLinvars.remove(v);
		TreeMap<LinVar,Rational> res = new TreeMap<LinVar, Rational>();
		BigInteger denom = v.mHeadEntry.mCoeff.negate();
		for (MatrixEntry entry = v.mHeadEntry.mNextInRow;
			entry != v.mHeadEntry; entry = entry.mNextInRow) {
			assert(!entry.mColumn.mBasic);
			res.put(entry.mColumn, Rational.valueOf(entry.mCoeff, denom));
			entry.removeFromMatrix();
		}
		v.mHeadEntry = null;
		return res;
	}
	
	public void removeLinVar(LinVar v) {
		assert mOob.isEmpty();
		if (!v.mBasic) {
			// We might have nonbasic variables that do not contribute to a basic variable. 
			if (v.mHeadEntry.mNextInCol == v.mHeadEntry) {
				mLinvars.remove(v);
				return;
			}
			Clause conflict = pivot(v.mHeadEntry.mNextInCol);
			assert(conflict == null) : "Removing a variable produced a conflict!";
		}
		TreeMap<LinVar, Rational> coeffs = removeVar(v);
		updateSimps(v, coeffs);
	}
	
	/**
	 * This function simplifies the tableau by removing all trivially 
	 * satisfiable equations. In case of rationals, an equation of the
	 * for x_b = sum_i c_i*x_i where there is at least one x_i without
	 * any bound constraint is trivially satisfiable.
	 */
	private Clause simplifyTableau() {
		// We cannot remove more vars than we have basic variables
		List<LinVar> removeVars = new ArrayList<LinVar>(mLinvars.size());
		for (LinVar v : mLinvars) {
			// Skip integer and already simplified variables
			if (v.isInt() || v.mDead)
				continue;
			if (v.unconstrained()) {
				if (v.mBasic) {
					removeVars.add(v);
					v.mDead = true;
				} else {
					for (MatrixEntry entry = v.mHeadEntry.mNextInCol;
						entry != v.mHeadEntry; entry = entry.mNextInCol) {
						LinVar basic = entry.mRow;
						if (!basic.unconstrained() && !basic.mDead
								&& !removeVars.contains(basic)) {
							if (mEngine.getLogger().isDebugEnabled())
								mEngine.getLogger().debug("simplify pivot " + entry);
							Clause conflict = pivot(entry);
							InfinitNumber bound = basic.getLowerBound();
							if (bound.isInfinity())
								bound = basic.getUpperBound();
							if (!bound.isInfinity())
								updateVariableValue(basic, bound);
							// Just in case it was out of bounds before...
							mOob.remove(basic);
							v.mDead = true;
							removeVars.add(v);
							if (conflict != null) {
								for (LinVar var : removeVars)
									// We marked them dead, but don't simplify
									// them right now.  Remove the mark!!!
									var.mDead = false;
								return conflict;
							}
							break;
						}
					}
				}
			}
		}
		/*
		 * Here, we have a tableau containing some trivially satisfiable
		 * rows. We now remove one var after the other and check against
		 * all simplified variables. This way, all simplified variables
		 * only contain variables relative to the current set of nonbasics.
		 */
		HashMap<LinVar,TreeMap<LinVar,Rational>> newsimps = 
			new HashMap<LinVar, TreeMap<LinVar,Rational>>();
		for (LinVar v : removeVars) {
			assert v.mBasic;
			mEngine.getLogger().debug(new DebugMessage("Simplifying {0}",v));
			TreeMap<LinVar,Rational> coeffs = removeVar(v);
			updateSimps(v,coeffs);
			newsimps.put(v,coeffs);
			mOob.remove(v);
			mPropBounds.remove(v);
		}
		mSimps.putAll(newsimps);
		assert checkPostSimplify();
		return null;
	}
	
	/**
	 * Represents a linvar in terms of the current column (non-basic) variables
	 * and adds it to the map facs.
	 * @param lv    The variable to add.
	 * @param fac   The factor of the variable to add.
	 * @param facs  The map, where to add it.
	 */
	private void unsimplifyAndAdd(LinVar lv,Rational fac, Map<LinVar,Rational> facs) {
		if (lv.mDead) {
			// simplified variable
			ArrayDeque<UnsimpData> unsimpStack = new ArrayDeque<UnsimpData>();
			unsimpStack.add(new UnsimpData(lv, fac));
			while (!unsimpStack.isEmpty()) {
				UnsimpData data = unsimpStack.removeFirst();
				if (data.mVar.mDead) {
					for (Entry<LinVar,Rational> simp
						: mSimps.get(data.mVar).entrySet()) {
						unsimpStack.add(new UnsimpData(simp.getKey(), 
								data.mFac.mul(simp.getValue())));
					}
				} else
					unsimplifyAndAdd(data.mVar, data.mFac, facs);
			}
		} else if (lv.mBasic) {
			// currently basic variable
			BigInteger denom = lv.mHeadEntry.mCoeff.negate();
			for (MatrixEntry entry = lv.mHeadEntry.mNextInRow;
				entry != lv.mHeadEntry;
				entry = entry.mNextInRow) {
				Rational coeff = Rational.valueOf(entry.mCoeff, denom);
				unsimplifyAndAdd(entry.mColumn, fac.mul(coeff), facs);
			}
		} else {
			// Just add it.
			Rational oldval = facs.get(lv);
			if (oldval == null)
				facs.put(lv, fac);
			else {
				Rational newval = oldval.add(fac);
				if (newval.equals(Rational.ZERO))
					facs.remove(lv);
				else
					facs.put(lv, newval);
			}
		}
	}
	
	private void updateSimps(LinVar v, Map<LinVar, Rational> coeffs) {
		for (Map.Entry<LinVar, TreeMap<LinVar,Rational>> me : mSimps.entrySet()) {
			TreeMap<LinVar,Rational> cmap = me.getValue();
			Rational cc = cmap.get(v);
			if (cc != null) {
				cmap.remove(v);
				for (Map.Entry<LinVar, Rational> cme : coeffs.entrySet()) {
					Rational c = cmap.get(cme.getKey());
					if (c == null)
						cmap.put(cme.getKey(), cme.getValue().mul(cc));
					else {
						Rational newcoeff = cme.getValue().mul(cc).add(c);
						if (newcoeff.equals(Rational.ZERO))
							cmap.remove(cme.getKey());
						else
							cmap.put(cme.getKey(), newcoeff);
					}
				}
				me.setValue(cmap);
			}
		}
	}
	
	private void ensureUnsimplified(LinVar var) {
		ensureUnsimplified(var, true);
	}
	
	private void ensureUnsimplified(LinVar var, boolean remove) {
		if (var.mDead) {
			assert mSimps.containsKey(var);
			mEngine.getLogger().debug(
					new DebugMessage("Ensuring {0} is unsimplified",var));
			// Variable is actually simplified. Reintroduce it
			TreeMap<LinVar,Rational> curcoeffs = new TreeMap<LinVar,Rational>();
			unsimplifyAndAdd(var, Rational.ONE, curcoeffs);
			insertVar(var, curcoeffs);
			var.mDead = false;
			if (remove)
				mSimps.remove(var);
			mLinvars.add(var);
		}
		assert (!var.mDead);
	}

	/**
	 * Calculates the values of the simplified variables.
	 * @return The exact values of the simplified variables.
	 */
	Map<LinVar, ExactInfinitNumber> calculateSimpVals() {
		HashMap<LinVar, ExactInfinitNumber> simps =
			new HashMap<LinVar, ExactInfinitNumber>();
		for (Entry<LinVar,TreeMap<LinVar,Rational>> me : mSimps.entrySet()) {
			MutableRational real = new MutableRational(0, 1);
			MutableRational eps = new MutableRational(0, 1);
			for (Entry<LinVar,Rational> chain : me.getValue().entrySet()) {
				real.addmul(chain.getKey().getValue().mA, chain.getValue());
				if (chain.getKey().mBasic)
					eps.addmul(
							chain.getKey().computeEpsilon(), chain.getValue());
				else if (chain.getKey().getValue().mEps < 0)
					eps.sub(chain.getValue());
				else if (chain.getKey().getValue().mEps > 0)
					eps.add(chain.getValue());
					
			}
			Rational rreal = real.toRational();
			me.getKey().setValue(new InfinitNumber(rreal, eps.signum()));
			simps.put(me.getKey(),
					new ExactInfinitNumber(rreal, eps.toRational()));
		}
		return simps;
	}
	/**
     * Compute freedom interval for a nonbasic variable.  This function fills
     * the private member variables {@link #mLower} and {@link #mUpper}.
     * @param var   Nonbasic variable to compute freedom interval for.
     */
	private void freedom(LinVar var) {
		mLower = new ExactInfinitNumber(var.getExactLowerBound());
		mUpper = new ExactInfinitNumber(var.getExactUpperBound());
		// fast path: Fixed variable
		if (mLower.equals(mUpper))
			return;
		ExactInfinitNumber maxBelow = ExactInfinitNumber.NEGATIVE_INFINITY;
		ExactInfinitNumber minAbove = ExactInfinitNumber.POSITIVE_INFINITY;
		for (MatrixEntry me = var.mHeadEntry.mNextInCol; me != var.mHeadEntry;
			me = me.mNextInCol) {
			Rational coeff = Rational.valueOf(
					me.mRow.mHeadEntry.mCoeff.negate(), me.mCoeff);
			ExactInfinitNumber below = me.mRow.getExactLowerBound().sub(
					me.mRow.getExactValue()).mul(coeff);
			ExactInfinitNumber above = me.mRow.getExactUpperBound().sub(
					me.mRow.getExactValue()).mul(coeff);
			if (coeff.isNegative()) {
				// reverse orders
				ExactInfinitNumber t = below;
				below = above;
				above = t;
			}
			if (below.signum() > 0) {
				// We can only violate a bound by a number of epsilons
				assert below.getRealValue() == Rational.ZERO;
				below = new ExactInfinitNumber();
			}
			if (above.signum() < 0) {
				// We can only violate a bound by a number of epsilons
				assert above.getRealValue() == Rational.ZERO;
				above = new ExactInfinitNumber();
			}
			if (below.compareTo(maxBelow) > 0)
				maxBelow = below;
			if (above.compareTo(minAbove) < 0)
				minAbove = above;
		}
		maxBelow = maxBelow.add(var.getValue());
		minAbove = minAbove.add(var.getValue());
		if (maxBelow.compareTo(mLower) > 0)
			mLower = maxBelow;
		if (minAbove.compareTo(mUpper) < 0)
			mUpper = minAbove;
	}
	/**
	 * Mutate a model such that less variables have the same value.
	 * 
	 * TODO This method is still very inefficient. Even if all variables have
	 * distinct values, we still compute a lot of stuff.
	 */
	private void mutate() {
		Map<Rational,Set<ExactInfinitNumber>> sharedPoints = 
				new TreeMap<Rational, Set<ExactInfinitNumber>>();
		Set<InfinitNumber> prohib = new TreeSet<InfinitNumber>();
		for (LinVar lv : mLinvars) {
			if (lv.mBasic
				|| lv.getUpperBound().equals(lv.getLowerBound()))
				// variable is basic or is fixed by its own constraints
				continue;
			freedom(lv);
			if (mLower.equals(mUpper))
				// variable is fixed by its own constraints and basic variables
				continue;
			Rational gcd = lv.isInt() ? Rational.ONE : Rational.ZERO;
			ExactInfinitNumber exactval = lv.getExactValue();

			sharedPoints.clear();
			prohib.clear();
			// prevent violating disequalities
			if (lv.mDisequalities != null) {
				for (Rational diseq : lv.mDisequalities.keySet()) {
					prohib.add(new InfinitNumber(diseq, 0));
				}
			}

			// Iterate over basic variables
			HashMap<LinVar, Rational> basicFactors = new HashMap<LinVar, Rational>();
			for (MatrixEntry it1 = lv.mHeadEntry.mNextInCol; it1 != lv.mHeadEntry;
				it1 = it1.mNextInCol) {
				LinVar basic = it1.mRow;
				Rational coeff = Rational.valueOf(
						it1.mCoeff.negate(), it1.mRow.mHeadEntry.mCoeff);
				basicFactors.put(basic, coeff);
				if (basic.isInt())
					gcd = gcd.gcd(coeff.abs());
				if (basic.mDisequalities != null) {
					for (Rational diseq : basic.mDisequalities.keySet()) {
						ExactInfinitNumber basicval = basic.getExactValue();
						ExactInfinitNumber bad = 
								new ExactInfinitNumber(diseq, Rational.ZERO)
									.sub(basicval).div(coeff).add(exactval);
						InfinitNumber badapprox = bad.toInfinitNumber();
						if (badapprox != null)
							prohib.add(badapprox);
					}
				}
			}

			Map<LinVar, ExactInfinitNumber> simps = calculateSimpVals();
			// Do not merge two shared variables
			for (int i = 0; i < mSharedVars.size(); i++) {
				SharedTerm sharedVar = mSharedVars.get(i);
				LinVar sharedLV = sharedVar.getLinVar();
				Rational sharedCoeff = basicFactors.get(sharedLV);
				if (sharedCoeff == null)
					sharedCoeff = Rational.ZERO;
				else
					sharedCoeff = sharedCoeff.mul(sharedVar.getFactor());
				Set<ExactInfinitNumber> set = sharedPoints.get(sharedCoeff);
				if (set == null) {
					set = new TreeSet<ExactInfinitNumber>();
					sharedPoints.put(sharedCoeff, set);
				}
				ExactInfinitNumber sharedCurVal = new ExactInfinitNumber(
						sharedVar.getOffset(), Rational.ZERO);
				if (sharedLV != null)
					sharedCurVal = sharedCurVal.add(
							(sharedLV.isAlive() ? sharedLV.getExactValue() : simps.get(sharedLV))
							.mul(sharedVar.getFactor()));
				set.add(sharedCurVal);
			}
			// If there is no integer constraint for the non-basic manipulate
			// it by eps, otherwise incrementing by a multiple of gcd.inverse()
			// will preserve integrity of all depending variables. 
			Rational lcm = gcd.inverse();
			InfinitNumber chosen = 
					choose(prohib,sharedPoints,lcm,lv.getValue());
			assert (new ExactInfinitNumber(chosen).compareTo(mLower) >= 0
					&& new ExactInfinitNumber(chosen).compareTo(mUpper) <= 0);
			if (!chosen.equals(lv.getValue()))
				updateVariableValue(lv, chosen);
		}
	}
	
	/**
	 * Compute the value of each shared variable as exact infinite number.
	 * @return A map from the value to the list of shared variables that
	 * have this value.
	 */
	Map<ExactInfinitNumber, List<SharedTerm>> getSharedCongruences() {
		mEngine.getLogger().debug("Shared Vars:");
		Map<ExactInfinitNumber, List<SharedTerm>> result = 
			new HashMap<ExactInfinitNumber, List<SharedTerm>>();
		Map<LinVar, ExactInfinitNumber> simps = calculateSimpVals();
		for (SharedTerm shared : mSharedVars) {
			MutableRational real = new MutableRational(shared.getOffset());
			MutableRational eps = new MutableRational(0, 1);
			if (shared.getLinVar() != null) {
				if (shared.getLinVar().mDead) {
					ExactInfinitNumber simpval = simps.get(shared.getLinVar());
					real.addmul(simpval.getRealValue(), shared.getFactor());
					eps.addmul(simpval.getEpsilon(), shared.getFactor());
				} else {
					if (shared.getLinVar().mBasic)
						eps.addmul(shared.getLinVar().computeEpsilon(),
								shared.getFactor());
					else if (shared.getLinVar().getValue().mEps > 0)
						eps.add(shared.getFactor());
					else if (shared.getLinVar().getValue().mEps < 0)
						eps.sub(shared.getFactor());
					real.addmul(shared.getLinVar().getValue().mA,
							shared.getFactor());
				}
			}
			ExactInfinitNumber curval = new ExactInfinitNumber(
					real.toRational(), eps.toRational());
			if (mEngine.getLogger().isDebugEnabled())
				mEngine.getLogger().debug(shared + " = " + curval);
			List<SharedTerm> slot = result.get(curval);
			if (slot == null) {
				slot = new LinkedList<SharedTerm>();
				result.put(curval, slot);
			}
			slot.add(shared);
		}
		return result;
	}
	private Literal ensureDisequality(LAEquality eq) {
		LinVar v = eq.getVar();
		assert (eq.getDecideStatus().getSign() == -1);
		// Disequality already satisfied...
		if (!v.getValue().mA.equals(eq.getBound()))
			return null;
		if (v.mBasic)
			v.fixEpsilon();
		if (v.getValue().mEps != 0)
			return null;

		// Find already existing literal
		InfinitNumber bound = new InfinitNumber(eq.getBound(), 0);
		BoundConstraint gbc = eq.getVar().mConstraints.get(bound);
		boolean usegt = gbc == null;
		if (!usegt && gbc.getDecideStatus() == null)
			return gbc.negate();
		InfinitNumber strictbound = bound.sub(eq.getVar().getEpsilon());
		BoundConstraint lbc = eq.getVar().mConstraints.get(strictbound);
		if (lbc != null && lbc.getDecideStatus() == null)
				return lbc;
		// Here, we have neither inequality. Create one...	
		return usegt
				? generateConstraint(eq.getVar(),eq.getBound(), false, false).negate()
				: generateConstraint(eq.getVar(),eq.getBound(),false,true);
	}
    /**
     * Choose a value from a given interval respecting certain prohibitions.
     * The interval is given symbolically by a lower and an upper bound. All
     * values prohibited are given in a set. Note that this set might also
     * contain values outside the interval. For integer solutions, we also give
     * the lcm such that we can generate an integer solution from an integer
     * solution.
     *
     * If the interval is empty or no value can be found, the current value
     * should be returned.
     * @param prohibitions Prohibited values.
     * @param lcm          Least common multiple of denominators (integer only)
     * @param currentValue Value currently assigned to a variable.
     * @return
     */
	private InfinitNumber choose(Set<InfinitNumber> prohibitions,
			Map<Rational,Set<ExactInfinitNumber>> sharedPoints,
			Rational lcm, InfinitNumber currentValue) {
		// Check if variable is fixed or allowed.
		if (mUpper.equals(mLower)
			|| (!prohibitions.contains(currentValue)
				&& !hasSharing(sharedPoints, new ExactInfinitNumber())))
			return currentValue;

		ExactInfinitNumber exactval = new ExactInfinitNumber(currentValue);
		if (lcm == Rational.POSITIVE_INFINITY) {
			if (mUpper.isInfinite()) {
				// We search linear upwards from starting from the current value
				InfinitNumber cur = currentValue;
				do {
					cur = cur.add(InfinitNumber.ONE);
				} while (prohibitions.contains(cur)
						&& hasSharing(sharedPoints, exactval.isub(cur)));
				return cur;
			}
			if (mLower.isInfinite()) {
				// We search linear downwards
				InfinitNumber cur = currentValue;
				do {
					cur = cur.sub(InfinitNumber.ONE);
				} while (prohibitions.contains(cur)
						&& hasSharing(sharedPoints, exactval.isub(cur)));
				return cur;
			}
			if (mLower.getRealValue().equals(mUpper.getRealValue())) {
				// We can only change epsilons
				switch (currentValue.mEps) {
				case -1:
					if (mUpper.getEpsilon().compareTo(Rational.ZERO) >= 0) {
						InfinitNumber test = new InfinitNumber(currentValue.mA, 0);
						if (!prohibitions.contains(test)
								&& !hasSharing(sharedPoints, exactval.isub(test)))
							return test;
					}
					if (mUpper.getEpsilon().compareTo(Rational.ONE) >= 0) {
						InfinitNumber test = new InfinitNumber(currentValue.mA, 1);
						if (!prohibitions.contains(test)
								&& !hasSharing(sharedPoints, exactval.isub(test)))
							return test;
					}
					break;
				case 0:
					if (mUpper.getEpsilon().compareTo(Rational.ONE) >= 0) {
						InfinitNumber test = new InfinitNumber(currentValue.mA, 1);
						if (!prohibitions.contains(test)
								&& !hasSharing(sharedPoints, exactval.isub(test)))
							return test;
					}
					if (mLower.getEpsilon().compareTo(Rational.MONE) <= 0) {
						InfinitNumber test = new InfinitNumber(currentValue.mA, -1);
						if (!prohibitions.contains(test)
								&& !hasSharing(sharedPoints, exactval.isub(test)))
							return test;
					}
					break;
				case 1:
					if (mLower.getEpsilon().compareTo(Rational.ZERO) <= 0) {
						InfinitNumber test = new InfinitNumber(currentValue.mA, 0);
						if (!prohibitions.contains(test)
								&& !hasSharing(sharedPoints, exactval.isub(test)))
							return test;
					}
					if (mLower.getEpsilon().compareTo(Rational.MONE) <= 0) {
						InfinitNumber test = new InfinitNumber(currentValue.mA, -1);
						if (!prohibitions.contains(test)
								&& !hasSharing(sharedPoints, exactval.isub(test)))
							return test;
					}
					break;
				default:
					throw new InternalError("Unnormalised epsilons");
				}
				// We could not change the value
				return currentValue;
			}
			/* use binary search to find the candidate */
			Rational lowreal = mLower.getRealValue();
			Rational upreal = mUpper.getRealValue();
			// Should be handled in previous cases.
			assert lowreal.isRational();
			assert upreal.isRational();
			assert (!lowreal.equals(upreal));
			Rational mid = lowreal;
			do {
				mid = mid.add(upreal).div(Rational.TWO);
				// Test all three cases
				InfinitNumber test = new InfinitNumber(mid, 0);
				if (!prohibitions.contains(test)
						&& !hasSharing(sharedPoints, exactval.isub(test)))
					return test;
				test = new InfinitNumber(mid, 1);
				if (!prohibitions.contains(test)
						&& !hasSharing(sharedPoints, exactval.isub(test)))
					return test;
				test = new InfinitNumber(mid, -1);
				if (!prohibitions.contains(test)
						&& !hasSharing(sharedPoints, exactval.isub(test)))
					return test;
			} while (true); // TODO Termination???
		} else {
			/* We should change it.  We search upwards and downwards by
			 * incrementing and decrementing currentValue by lcm. 
			 */
			InfinitNumber lower = mLower.toInfinitNumber();
			if (lower == null)
				lower = mLower.toInfinitNumberCeil();
			InfinitNumber upper = mUpper.toInfinitNumber();
			if (upper == null)
				upper = mUpper.toInfinitNumberFloor();
			if (lower.equals(upper))
				return lower;
			MutableInfinitNumber up = new MutableInfinitNumber(currentValue);
			MutableInfinitNumber down = new MutableInfinitNumber(currentValue);
			InfinitNumber ilcm = new InfinitNumber(lcm, 0);
			while (true) {
				up.add(ilcm);
				if (up.compareTo(upper) > 0)
					break;
				InfinitNumber cur = up.toInfinitNumber();
				if (!prohibitions.contains(cur)
					&& !hasSharing(sharedPoints,
							exactval.isub(cur)))
					return cur;

				down.sub(ilcm);
				if (down.compareTo(lower) < 0)
					break;
				cur = down.toInfinitNumber();
				if (!prohibitions.contains(cur)
					&& !hasSharing(sharedPoints, exactval.isub(cur)))
					return cur;
			}
			up.add(ilcm);
			while (up.compareTo(upper) <= 0) {
				InfinitNumber cur = up.toInfinitNumber();
				if (!prohibitions.contains(cur)
					&& !hasSharing(sharedPoints, exactval.isub(cur)))
					return cur;
				up.add(ilcm);
			}
			down.sub(ilcm);
			while (down.compareTo(lower) >= 0) {
				InfinitNumber cur = down.toInfinitNumber();
				if (!prohibitions.contains(cur)
					&& !hasSharing(sharedPoints, exactval.isub(cur)))
					return cur;
				down.sub(ilcm);
			}
			// this can only be reached in the integer case.
			return currentValue;
		}
	}
	/**
	 * Test for merging of at least two shared terms.  Shared terms in our
	 * setting have form <code>c*x+o</code> for variable <code>x</code>.  Given
	 * a map of <code>c</code> and the current value of <code>c*x+o</code>, and
	 * the desired difference, we check if two shared terms will be merged by
	 * this update.
	 * @param sharedPoints Map from slope to current value. 
	 * @param diff         Expected difference.
	 * @return Did this difference merge two shared terms.
	 */
	private boolean hasSharing(Map<Rational, Set<ExactInfinitNumber>> sharedPoints,
			ExactInfinitNumber diff) {
		TreeSet<ExactInfinitNumber> used = new TreeSet<ExactInfinitNumber>();
		for (Entry<Rational, Set<ExactInfinitNumber>> entry : sharedPoints.entrySet()) {
			ExactInfinitNumber sharedDiff = diff.mul(entry.getKey());
			for (ExactInfinitNumber r : entry.getValue()) {
				if (!used.add(r.add(sharedDiff))) {
//					System.err.println("found sharing");
					return true;
				}
			}
		}
		return false;
	}

	private Clause mbtc(Map<ExactInfinitNumber,List<SharedTerm>> cong) {
		for (Map.Entry<ExactInfinitNumber,List<SharedTerm>> congclass : cong.entrySet()) {
			List<SharedTerm> lcongclass = congclass.getValue();
			if (lcongclass.size() <= 1) // NOPMD
				continue;
			mEngine.getLogger().debug(new DebugMessage("propagating MBTC: {0}",
					lcongclass));
			Iterator<SharedTerm> it = lcongclass.iterator();
			SharedTerm shared1 = it.next();
			while (it.hasNext()) {
				SharedTerm shared2 = it.next();
				EqualityProxy eq = shared1.createEquality(shared2);
				assert eq != EqualityProxy.getTrueProxy();
				assert eq != EqualityProxy.getFalseProxy();
				CCEquality cceq = eq.createCCEquality(shared1, shared2);
				if (cceq.getLASharedData().getDecideStatus() != null) { // NOPMD
					if (cceq.getDecideStatus() == cceq.negate())
						return generateEqualityClause(cceq);
					else if (cceq.getDecideStatus() == null)
						mProplist.add(cceq);
					else
						mEngine.getLogger().debug(
								new DebugMessage("already set: {0}", 
										cceq.getAtom().getDecideStatus()));
				} else {
					mEngine.getLogger().debug(new DebugMessage(
							"MBTC: Suggesting literal {0}",cceq));
					mSuggestions.add(cceq.getLASharedData());
				}
			}
		}
		return null;
	}
	
	@Override
	public Literal getSuggestion() {
		Literal res;
		do {
			if (mSuggestions.isEmpty())
				return null;
			res = mSuggestions.removeFirst();
		} while (res.getAtom().getDecideStatus() != null);
		return res;
	}
	
	private InfinitNumber computeMaxEpsilon(Set<Rational> prohibitions) {
		InfinitNumber maxeps = InfinitNumber.POSITIVE_INFINITY;
		for (LinVar v : mLinvars) {
			if (v.mBasic) {
				Rational epsilons = v.computeEpsilon();
				if (epsilons.signum() > 0) {
					InfinitNumber diff = v.getUpperBound().sub(
							new InfinitNumber(v.getValue().mA, 0)).div(epsilons);
					if (diff.compareTo(maxeps) < 0)
						maxeps = diff;
				} else if (epsilons.signum() < 0) {
					InfinitNumber diff = v.getLowerBound().sub(
							new InfinitNumber(v.getValue().mA, 0)).div(epsilons);
					if (diff.compareTo(maxeps) < 0)
						maxeps = diff;
				}
				if (epsilons.signum() != 0 && v.mDisequalities != null) {
					for (Rational prohib : v.mDisequalities.keySet())
						// solve v.mcurval = prohib to eps
						// a+b*eps = p ==> eps = (p-a)/b given b != 0
						prohibitions.add(prohib.sub(v.getValue().mA).div(epsilons));
				}
			} else {
				if (v.getValue().mEps > 0) {
					InfinitNumber diff = v.getUpperBound().sub(
							new InfinitNumber(v.getValue().mA, 0));
					if (diff.compareTo(maxeps) < 0)
						maxeps = diff;
					assert (v.getValue().mEps == 1);
					if (v.mDisequalities != null) {
						for (Rational prohib : v.mDisequalities.keySet())
							// solve a+eps = p ==> eps = p-a
							prohibitions.add(prohib.sub(v.getValue().mA));
					}
				} else if (v.getValue().mEps < 0) {
					InfinitNumber diff = new InfinitNumber(v.getValue().mA, 0).
							sub(v.getLowerBound());
					if (diff.compareTo(maxeps) < 0)
						maxeps = diff;
					assert (v.getValue().mEps == -1);
					if (v.mDisequalities != null) {
						for (Rational prohib : v.mDisequalities.keySet())
							// solve a-eps = p ==> eps = a-p
							prohibitions.add(v.getValue().mA.sub(prohib));
					}
				}
			}
		}
		return maxeps;
	}

	@Override
	public void decreasedDecideLevel(int currentDecideLevel) {
		// Nothing to do
	}

	@Override
	public void increasedDecideLevel(int currentDecideLevel) {
		// Nothing to do
	}

	/**
	 * We reset the bounds and bound asserting members but not the current value
	 * since this might be a good start for the next iteration.
	 */
	@Override
	public void restart(int iteration) {
		// Nothing to do
	}

	public LAEquality createEquality(int stackLevel, MutableAffinTerm at) {
		Rational normFactor = at.getGCD().inverse();
		at.mul(normFactor);
		LinVar var = generateLinVar(getSummandMap(at), stackLevel);
		InfinitNumber bound;
		if (at.mSummands.size() == 1) {
			Rational fac = at.mSummands.values().iterator().next();
			bound = at.mConstant.negate().div(fac);
		} else
			bound = at.mConstant.negate();
		assert bound.mEps == 0;
		LAEquality sharedData = var.getEquality(bound);
		if (sharedData == null) {
			sharedData = 
				new LAEquality(stackLevel, var, bound.mA);
			mEngine.addAtom(sharedData);
			var.addEquality(sharedData);
		}
		ensureUnsimplified(var);
		return sharedData;
	}

	@Override
	public Clause startCheck() {
		mEps = null;
		mInCheck = true; 
		return simplifyTableau();
	}
	
	@Override
	public void endCheck() {
		mInCheck = false;
	}

	public Literal createCompositeLiteral(LAReason comp, Literal beforeLit) {
		mCompositeCreateLit++;
		int depth = comp.getLastLiteral().getDecideLevel();
		if (mEngine.getLogger().isDebugEnabled())
			mEngine.getLogger().debug("Create Propagated Literal for "
					+ comp + " @ level " + depth);
		LinVar var = comp.getVar();
		InfinitNumber bound = comp.getBound();
		if (!comp.isUpper())
			bound = bound.sub(var.getEpsilon());
		BoundConstraint bc = new BoundConstraint(
				bound, var, mEngine.getAssertionStackLevel());
		Literal lit = comp.isUpper() ? bc : bc.negate();
		int decideLevel = comp.getLastLiteral().getDecideLevel();
		if (beforeLit != null 
			&& beforeLit.getAtom().getDecideLevel() == decideLevel)
			mEngine.insertPropagatedLiteralBefore(this, lit, beforeLit);
		else
			mEngine.insertPropagatedLiteral(this, lit, decideLevel);
		InfinitNumber litBound =
				comp.isUpper() ? bc.getBound() : bc.getInverseBound();
		if (!comp.getExactBound().equals(litBound))
			insertReasonOfNewComposite(var, lit);
		
		return lit;
	}

	public void generateSharedVar(SharedTerm shared, MutableAffinTerm mat, int level) {
		assert mat.getConstant().mEps == 0;
		if (mat.isConstant()) {
			shared.setLinVar(Rational.ZERO, null, mat.getConstant().mA);
		} else {
			Rational normFactor = mat.getGCD().inverse();
			Rational offset = mat.getConstant().mA;
			mat.mul(normFactor);
			LinVar linVar = generateLinVar(getSummandMap(mat), level);
			shared.setLinVar(normFactor.inverse(), linVar, offset);
		}
	}

	public void share(SharedTerm shared) {
		mSharedVars.add(shared);
	}
	
	public void unshare(SharedTerm shared) {
		mSharedVars.remove(shared);
	}

	@Override
	public void removeAtom(DPLLAtom atom) {
		if (atom instanceof BoundConstraint) {
			BoundConstraint bc = (BoundConstraint) atom;
			LinVar v = bc.getVar();
			v.mConstraints.remove(bc.getBound());
//			if (v.unconstrained()) {
//				if (v.isInitiallyBasic()) {
//					System.err.println("Removing initially basic variable");
//					removeLinVar(bc.getVar());
//				} else if (v.mbasic) {
//					System.err.println("Moving unconstraint variable away");
//					// Move it out of the way and let simplifier handle this
//					for (MatrixEntry entry = v.headEntry.nextInRow; 
//						entry != v.headEntry; entry = entry.nextInRow) {
//						if (entry.column.isInitiallyBasic()) {
//							System.err.println("Using variable " + entry.column);
//							pivot(entry);
//							return;
//						}
//					}
//					throw new IllegalStateException("We should never reach here!!!");
//				}
//			}
		} else if (atom instanceof LAEquality) {
			LAEquality laeq = (LAEquality) atom;
			InfinitNumber num = new InfinitNumber(laeq.getBound(), 0);
			laeq.getVar().mEqualities.remove(num);
			for (CCEquality eq : laeq.getDependentEqualities())
				eq.removeLASharedData();
		}
	}

	@Override
	public void pop(Object object, int targetlevel) {
		ArrayList<LinVar> removeVars = new ArrayList<LinVar>();
		for (LinVar v : mLinvars) {
			if (v.mAssertionstacklevel > targetlevel)
				removeVars.add(v);
		}
		for (LinVar v : mSimps.keySet()) {
			if (v.mAssertionstacklevel > targetlevel)
				removeVars.add(v);
		}
		for (LinVar v : removeVars) {
			mOob.remove(v);
			mPropBounds.remove(v);
			if (v.mDead)
				mSimps.remove(v);
			else
				removeLinVar(v);
			if (v == mConflictVar)
				mConflictVar = null;
//			v.mbasic = v.isInitiallyBasic();
			/// Mark variable as dead
			v.mAssertionstacklevel = -1;
			if (v.isInt())
				mIntVars.remove(v);
		}
		mSharedVars.endScope();
		mTerms.endScope();
		// TODO This is a bit too much but should work
		mSuggestions.clear();
		mProplist.clear();
		assert popPost();
	}

	private final boolean popPost() {
		for (Map.Entry<LinVar, TreeMap<LinVar, Rational>> me : mSimps.entrySet()) {
			assert me.getKey().mDead;
			for (Map.Entry<LinVar, Rational> me2 : me.getValue().entrySet()) {
				assert !me2.getKey().mDead;
				assert !mSimps.containsKey(me2.getKey());
				assert !me2.getValue().equals(Rational.ZERO);
			}
		}
		return true;
	}
	
	@Override
	public Object push() {
		mTerms.beginScope();
		mSharedVars.beginScope();
		return null;
	}

	@Override
	public Object[] getStatistics() {
		return new Object[] {
			":LA", new Object[][] {
				{"Pivot", mNumPivots},
				{"PivotBland", mNumPivotsBland},
				{"SwitchToBland", mNumSwitchToBland},
				{"Vars", mLinvars.size()},
				{"SimpVars", (mSimps == null ? 0 : mSimps.size())},
				{"CompLits", mCompositeCreateLit},
				{"Cuts", mNumCuts},
				{"Branches", mNumBranches},
				{"Times", new Object[][]{
					{"Pivot", mPivotTime / 1000000},// NOCHECKSTYLE
					{"BoundComp", mPropBoundTime / 1000000},// NOCHECKSTYLE
					{"BoundSet", mPropBoundSetTime / 1000000},// NOCHECKSTYLE
					{"BoundBack", mBacktrackPropTime / 1000000},// NOCHECKSTYLE
					{"CutGen", mCutGenTime / 1000000}}// NOCHECKSTYLE
				}
			}};
	}

	// utilities for the new pivoting strategy
	private LinVar findBestVar() {
		LinVar best = null;
		for (LinVar v : mOob) {
			if (best == null || best.mChainlength > v.mChainlength)
				best = v;
		}
		if (best != null)
			mOob.remove(best);
		return best;
	}
	private MatrixEntry findNonBasic(LinVar basic, boolean isLower) {
		assert basic.mBasic;
		MatrixEntry best = null;
		// Is the correct side unbounded?
		boolean unboundedSide = false;
		for (MatrixEntry entry = basic.mHeadEntry.mNextInRow;
				entry != basic.mHeadEntry; entry = entry.mNextInRow) {
			LinVar col = entry.mColumn;
			if (col.mUpper == null && col.mLower == null)
				// Unbounded at every side => trivially satisfied row
				return entry;
			boolean checkLower = isLower == (entry.mCoeff.signum() < 0);
			if (checkLower) {
				if (col.mLower == null) {
					if (unboundedSide
							&& best.mColumn.mChainlength > col.mChainlength)
						best = entry;
					else {
						best = entry;
						unboundedSide = true;
					}
				} else if (!unboundedSide && col.isDecrementPossible()
						&& (best == null
								|| best.mColumn.mChainlength > col.mChainlength))
					best = entry;
			} else {
				if (col.mUpper == null) {
					if (unboundedSide
							&& best.mColumn.mChainlength > col.mChainlength)
						best = entry;
					else {
						best = entry;
						unboundedSide = true;
					}
				} else if (!unboundedSide && col.isIncrementPossible()
						&& (best == null
								|| best.mColumn.mChainlength > col.mChainlength))
					best = entry;
			}
		}
		return best;
	}
	
	/**
	 * Check whether the LA solver should fill in the value for this term.  This
	 * is the case, if it is an ApplicationTerm corresponding to a 0-ary
	 * function.
	 * @param term Term to check.
	 * @return Symbol that gets a value from the LA solver.
	 */
	private FunctionSymbol getsValueFromLA(Term term) {
		if (term instanceof ApplicationTerm) {
			ApplicationTerm at = (ApplicationTerm) term;
			if (at.getParameters().length == 0)
				return at.getFunction();
		}
		return null;
	}

	@Override
	public void fillInModel(Model model, Theory t, SharedTermEvaluator ste) {
		prepareModel();
		for (LinVar var : mLinvars) {
			if (!var.isInitiallyBasic()) {
				Term term = var.getSharedTerm().getTerm();
				FunctionSymbol fsym = getsValueFromLA(term);
				if (fsym != null) {
					Rational val = realValue(var);
					model.extendNumeric(fsym, val);
				}
			}
		}
		if (mSimps != null) {
			for (Entry<LinVar,TreeMap<LinVar,Rational>> me : mSimps.entrySet()) {
				LinVar var = me.getKey();
				if (!var.isInitiallyBasic()) {
					Term term = var.getSharedTerm().getTerm();
					FunctionSymbol fsym = getsValueFromLA(term);
					if (fsym != null) {
						Rational val = Rational.ZERO;
						for (Entry<LinVar,Rational> chain : me.getValue().entrySet())
							val = val.add(realValue(chain.getKey()).mul(
									chain.getValue()));
						model.extendNumeric(fsym, val);
					}
				}
			}
		}
	}
	
}
