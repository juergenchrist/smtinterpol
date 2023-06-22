(set-option :produce-models true)
(set-option :produce-unsat-cores true)
(set-option :produce-interpolants true)
(set-option :interpolant-check-mode true)
(set-option :proof-transformation LU)
(set-logic ALL)
(set-info :source |SMT script generated on 2022-09-16T15:38:07+02:00 by Ultimate (https://ultimate.informatik.uni-freiburg.de/)|)
(set-info :smt-lib-version 2.5)
(set-info :category "industrial")
(set-info :ultimate-id TraceCheck_Iteration_ArrayInitialization02.bplJordan_AllErrorsAtOnce_Iteration3)
(declare-fun c_main_a () (Array Int Int))
(declare-fun c_main_a_primed () (Array Int Int))
(declare-fun c_main_n () Int)
(declare-fun c_main_n_primed () Int)
(declare-fun c_main_i () Int)
(declare-fun c_main_i_primed () Int)
(declare-fun c_main_j () Int)
(declare-fun c_main_j_primed () Int)
(declare-fun c_aux_v_skolemized_qk_2 () Int)
(declare-fun c_aux_v_skolemized_qk_3 () Int)
(declare-fun c_aux_v_skolemized_qk_4 () Int)
(declare-fun c_aux_v_ArrVal_1 () Int)
(declare-fun c_aux_v_skolemized_qk_5 () Int)
(declare-fun c_aux_v_ArrVal_2 () Int)
(declare-fun c_aux_v_skolemized_qk_6 () Int)
(declare-fun c_aux_v_ArrVal_3 () Int)
(declare-fun c_aux_v_skolemized_qk_7 () Int)
(echo "starting trace check")
(push 1)
(declare-fun main_n_-1 () Int)
(declare-fun main_i_0 () Int)
(declare-fun main_a_-1 () (Array Int Int))
(declare-fun main_a_2 () (Array Int Int))
(declare-fun main_i_2 () Int)
(declare-fun v_ArrVal_4_fresh_1 () Int)
(declare-fun main_j_-1 () Int)
(assert (! true :named ssa_precond))
(assert (! (not false) :named ssa_postcond))
(assert (! (and (= main_i_0 0) (<= 0 main_n_-1)) :named ssa_0))
(assert (! (forall ((qk Int)) (and (<= 0 main_i_0) (or (= 23 (select main_a_-1 qk)) (not (<= (+ qk 1) main_i_0)) (not (<= 0 qk))) (<= main_i_0 (+ main_n_-1 1)) (<= 0 main_n_-1))) :named ssa_1))
(assert (! (and (= main_a_2 (store main_a_-1 main_i_0 v_ArrVal_4_fresh_1)) (= main_i_2 (+ main_i_0 1)) (<= main_i_0 main_n_-1) (= 23 v_ArrVal_4_fresh_1)) :named ssa_2))
(assert (! (forall ((qk Int)) (and (<= 0 main_i_2) (or (= 23 (select main_a_2 qk)) (not (<= (+ qk 1) main_i_2)) (not (<= 0 qk))) (<= main_i_2 (+ main_n_-1 1)) (<= 0 main_n_-1))) :named ssa_3))
(assert (! (not (<= main_i_2 main_n_-1)) :named ssa_4))
(assert (! (and (<= 0 main_j_-1) (not (= 23 (select main_a_2 main_j_-1))) (<= main_j_-1 main_n_-1)) :named ssa_5))
(check-sat)
(get-interpolants (and ssa_0 ssa_precond) ssa_1 ssa_2 ssa_3 ssa_4 (and ssa_5 ssa_postcond))


(get-interpolants (and ssa_5 ssa_postcond) ssa_4 ssa_3 ssa_2 ssa_1 (and ssa_0 ssa_precond))
(exit)