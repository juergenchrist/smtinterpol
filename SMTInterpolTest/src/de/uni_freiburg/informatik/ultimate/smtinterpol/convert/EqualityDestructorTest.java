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
package de.uni_freiburg.informatik.ultimate.smtinterpol.convert;

import org.apache.log4j.Logger;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import de.uni_freiburg.informatik.ultimate.logic.Logics;
import de.uni_freiburg.informatik.ultimate.logic.Script;
import de.uni_freiburg.informatik.ultimate.logic.Sort;
import de.uni_freiburg.informatik.ultimate.logic.Term;
import de.uni_freiburg.informatik.ultimate.smtinterpol.proof.NoopProofTracker;
import de.uni_freiburg.informatik.ultimate.smtinterpol.proof.NoopRuleApplicator;
import de.uni_freiburg.informatik.ultimate.smtinterpol.smtlib2.SMTInterpol;
import de.uni_freiburg.informatik.ultimate.smtinterpol.util.TestCaseWithLogger;

@RunWith(JUnit4.class)
public class EqualityDestructorTest extends TestCaseWithLogger {
	private final Script mScript;
	private final TermCompiler mCompiler = new TermCompiler();
	private final Sort mInt, mU;
	private final Term mIC1, mIC2;
	private final Term mUC1, mUC2;
	public EqualityDestructorTest() {
		mScript = new SMTInterpol(Logger.getRootLogger(), false);
		mScript.setLogic(Logics.QF_UFLIA);
		mInt = mScript.sort("Int");
		mScript.declareSort("U", 0);
		mU = mScript.sort("U");
		mScript.declareFun("ic1", new Sort[0], mInt);
		mScript.declareFun("ic2", new Sort[0], mInt);
		mIC1 = mScript.term("ic1");
		mIC2 = mScript.term("ic2");
		mScript.declareFun("f", new Sort[] {mU}, mU);
		mScript.declareFun("uc1", new Sort[0], mU);
		mScript.declareFun("uc2", new Sort[0], mU);
		mUC1 = mScript.term("uc1");
		mUC2 = mScript.term("uc2");
		mCompiler.setIRuleApplicator(new NoopRuleApplicator());
	}
	
	/**
	 * Test case for the destruction of
	 * (exists ((x::Int)) (and (= x 3) (< x 2))) to false. 
	 */
	@Test
	public void testDestructToFalse() {
		Term body = mScript.term("and",
				mScript.term("=",
						mScript.variable("x", mInt), mScript.numeral("3")),
				mScript.term("<",
						mScript.variable("x", mInt), mScript.numeral("2")));
		Term ibody = mCompiler.transform(body);
		EqualityDestructor ed = new EqualityDestructor();
		Term dbody = ed.destruct(ibody);
		Assert.assertSame(mScript.term("false"), dbody);
	}
	
	@Test
	public void testDestructInAffineTerm() {
		Term body = mScript.term("and",
				mScript.term("=",
						mScript.variable("x", mInt), mScript.numeral("3")),
				mScript.term("<=",
						mScript.term("+", 
								mScript.variable("x", mInt), mIC1, mIC2),
						mScript.numeral("0")));
		Term ibody = mCompiler.transform(body);
		EqualityDestructor ed = new EqualityDestructor();
		Term dbody = SMTAffineTerm.cleanup(ed.destruct(ibody));
		Term expected = mScript.term("<=",
				mScript.term("+", mIC1, mIC2, mScript.numeral("3")),
				mScript.numeral("0"));
		Assert.assertSame(expected, dbody);
	}
	
	@Test
	public void testDestructInApplicationTerm() {
		Term body = mScript.term("and",
				mScript.term("=", mScript.variable("x", mU), mUC1),
				mScript.term("=",mScript.term("f", 
						mScript.variable("x", mU)),
					mUC2));
		Term ibody = mCompiler.transform(body);
		EqualityDestructor ed = new EqualityDestructor();
		Term dbody = ed.destruct(ibody);
		Term expected = mScript.term("=", mScript.term("f", mUC1), mUC2);
		Assert.assertSame(expected, dbody);
	}
	
	@Test
	public void testDestructNested() {
		Term body = mScript.term("and",
				mScript.term("=",mScript.term("f", 
						mScript.variable("x", mU)),
					mUC2),
				mScript.term("and",
						mScript.term("=", mScript.term("f", mUC2), mUC2),
						mScript.term("=", mScript.variable("x", mU), mUC1)));
		Term ibody = mCompiler.transform(body);
		EqualityDestructor ed = new EqualityDestructor();
		Term dbody = ed.destruct(ibody);
		Term expected = mScript.term("not",
				mScript.term("or",
						mScript.term("not",
								mScript.term("=", 
										mScript.term("f", mUC1), mUC2)),
						mScript.term("not",
								mScript.term("=",
										mScript.term("f", mUC2), mUC2))));
		Assert.assertSame(expected, dbody);
	}
}
