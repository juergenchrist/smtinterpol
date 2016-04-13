/*
 * Copyright (C) 2012 University of Freiburg
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

import java.util.HashMap;
import java.util.Map;

import de.uni_freiburg.informatik.ultimate.logic.ApplicationTerm;
import de.uni_freiburg.informatik.ultimate.logic.NonRecursive;
import de.uni_freiburg.informatik.ultimate.logic.Term;
import de.uni_freiburg.informatik.ultimate.logic.TermVariable;
import de.uni_freiburg.informatik.ultimate.logic.Theory;
import de.uni_freiburg.informatik.ultimate.smtinterpol.proof.NoopProofTracker;
import de.uni_freiburg.informatik.ultimate.smtinterpol.proof.NoopRuleApplicator;

/**
 * Destructs equalities over quantified variables.  This class assumes that the
 * body of the quantifier is already in or-not-form.  Furthermore, it can only
 * be used to destruct equalities for existentially quantified variables.  It
 * folds equalities into the formula, i.e., it transforms
 * (exists ((x)(Y)) (not (or (not (= x c)) (not F[x,Y]))) into
 * (exists ((Y)) F[Y]).
 * @author Juergen Christ
 */
public class EqualityDestructor extends NonRecursive {
	
	private static final class SearchEqualities implements NonRecursive.Walker {
		private Term mTerm;
		private boolean mPositive;
		public SearchEqualities(Term term) {
			this(term, true);
		}
		public SearchEqualities(Term term, boolean positive) {
			mTerm = term;
			mPositive = positive;
		}
		@Override
		public void walk(NonRecursive engine) {
			if (mTerm instanceof ApplicationTerm) {
				ApplicationTerm at = (ApplicationTerm) mTerm;
				if (at.getFunction() == at.getTheory().mNot)
					engine.enqueueWalker(new SearchEqualities(
							at.getParameters()[0], !mPositive));
				else if (!mPositive && at.getFunction() == at.getTheory().mOr)
					for (Term t : at.getParameters())
						engine.enqueueWalker(new SearchEqualities(t, false));
				else if (at.getFunction().getName().equals("=")) {
					// An interesting equality?
					Term[] args = at.getParameters();
					assert args.length == 2;
					EqualityDestructor ed =
						(EqualityDestructor) engine;
					if (args[0] instanceof TermVariable) {
						TermVariable v0 = (TermVariable) args[0];
						if (args[1] instanceof TermVariable) {
							TermVariable v1 = (TermVariable) args[1];
							// This cannot happen due to simplification
//									if (v0 == v1)
//										return;
							if (ed.mEqs.containsKey(v0)) {
								if (ed.mEqs.containsKey(v1))
									// We are already rewriting v0, v1
									// Skip this equality.
									return;
								// Rewrite v1 to the same value we are
								// rewriting v0
								ed.mEqs.put(v1, ed.mEqs.get(v0));
							} else if (ed.mEqs.containsKey(v1))
								// See above
								ed.mEqs.put(v0, ed.mEqs.get(v1));
							else
								// Use one variable from now on
								ed.mEqs.put(v1, v0);
						} else { // (= x c)
							checkVariable(ed, v0, args[1]);
						}
					} else if (args[1] instanceof TermVariable) {
						// (= c x)
						TermVariable v1 = (TermVariable) args[1];
						checkVariable(ed, v1, args[0]);						
					}
				}
				// Not interesting...
			}
		}
		
		private void checkVariable(EqualityDestructor ed,
				TermVariable var, Term val) {
			// rewrite loop check
			// FIXME: This is ugly
			TermVariable[] freeVars =
					SMTAffineTerm.cleanup(val).getFreeVars();
			for (TermVariable v : freeVars)
				if (v == var)
					return;
			if (!ed.mEqs.containsKey(var))
				ed.mEqs.put(var, val);
		}
	}
	
	private final Map<TermVariable, Term> mEqs =
		new HashMap<TermVariable, Term>();
	
	public Term destruct(Term qbody) {
		run(new SearchEqualities(qbody));
		return new InternTermTransformer() {
			
			Utils mUtils = new Utils(new NoopRuleApplicator());

			@Override
			protected void convert(Term term) {
				if (term instanceof TermVariable) {
					Term replacement = mEqs.get(term);
					if (replacement != null) {
						setResult(replacement);
						return;
					}
				}
				super.convert(term);
			}

			@Override
			public void convertApplicationTerm(ApplicationTerm appTerm,
					Term[] newArgs) {
				if (newArgs == appTerm.getParameters())
					setResult(appTerm);
				else {
					Theory t = appTerm.getTheory();
					if (appTerm.getFunction() == t.mNot)
						setResult(mUtils.convertNot(newArgs[0]));
					else if (appTerm.getFunction() == t.mOr)
						setResult(mUtils.createOr(newArgs));
					else if (appTerm.getFunction().getName().equals("="))
						setResult(mUtils.createEq(newArgs));
					else if (appTerm.getFunction().getName().equals("<="))
						setResult(mUtils.createLeq0((SMTAffineTerm) newArgs[0]));
					else if (appTerm.getFunction().getName().equals("ite"))
						setResult(mUtils.createIte(
								newArgs[0], newArgs[1], newArgs[2]));
					else {
						assert !appTerm.getFunction().isIntern();
						setResult(t.term(appTerm.getFunction(), newArgs));
					}
				}
			}
			
		}.transform(qbody);
	}
}
